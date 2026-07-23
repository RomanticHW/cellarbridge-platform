package com.rom.cellarbridge.auditreporting.internal.mcp;

import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.AuditPage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.TimelinePage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.WorkItemPage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.DashboardRecord;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.ProjectionFreshness;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.platform.mcp.McpCallSupport;
import com.rom.cellarbridge.platform.mcp.McpCapabilitySupport;
import com.rom.cellarbridge.platform.mcp.McpCapabilitySupport.Arguments;
import com.rom.cellarbridge.platform.mcp.McpReadPayload;
import com.rom.cellarbridge.platform.mcp.McpSafeException;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public final class ReportingMcpProvider {

  private static final Set<String> WORK_STATUSES =
      Set.of("OPEN", "CLAIMED", "COMPLETED", "CANCELLED");
  private static final Set<String> WORK_PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
  private static final Set<String> WORK_TYPES =
      Set.of(
          "PARTNER_REVIEW",
          "QUOTATION_APPROVAL",
          "FULFILLMENT_STEP",
          "EXCEPTION_ACTION",
          "RECEIVABLE_FOLLOW_UP");
  private static final Set<String> TIMELINE_TYPES =
      Set.of("PARTNER", "QUOTATION", "TRADE_ORDER", "ORDER");
  private static final Set<String> SALES_OWNED_TIMELINE_TYPES =
      Set.of("QUOTATION", "TRADE_ORDER", "ORDER");
  private static final Map<String, Object> WORK_ITEM_PROPERTIES = workItemProperties();
  private static final Map<String, Object> DASHBOARD_PROPERTIES = dashboardProperties();
  private static final Map<String, Object> TIMELINE_PROPERTIES = timelineProperties();
  private static final Map<String, Object> AUDIT_PROPERTIES = auditProperties();

  private final AuditReportingService service;
  private final TenantContextHolder contexts;
  private final McpCallSupport calls;
  private final Clock clock;

  public ReportingMcpProvider(
      AuditReportingService service,
      TenantContextHolder contexts,
      McpCallSupport calls,
      Clock clock) {
    this.service = service;
    this.contexts = contexts;
    this.calls = calls;
    this.clock = clock;
  }

  @Bean
  SyncToolSpecification listWorkItemsMcpTool() {
    return McpCapabilitySupport.readOnlyTool(
        calls,
        "WORK_ITEM_PROJECTION",
        "cellarbridge_list_work_items",
        "List CellarBridge work items",
        "Lists personal work or, for an authorized manager, team work from the reporting projection.",
        WORK_ITEM_PROPERTIES,
        List.of(),
        this::listWorkItems);
  }

  private McpReadPayload listWorkItems(Arguments arguments) {
    Instant from = instant(arguments.text("dueFrom"));
    Instant to = instant(arguments.text("dueTo"));
    requireOrdered(from, to);
    WorkItemPage page =
        service.workItems(
            allowedSet(arguments.textList("statuses"), WORK_STATUSES),
            allowedSet(arguments.textList("priorities"), WORK_PRIORITIES),
            allowedSet(arguments.textList("types"), WORK_TYPES),
            from,
            to,
            optionalText(arguments.text("subjectNumber"), 1, 80),
            scope(arguments.text("scope")),
            pageSize(arguments.integer("pageSize")));
    ProjectionFreshness freshness = service.reportingProjectionFreshness();
    return projection(freshness, page);
  }

  @Bean
  SyncToolSpecification getDashboardMcpTool() {
    return McpCapabilitySupport.readOnlyTool(
        calls,
        "DASHBOARD_PROJECTION",
        "cellarbridge_get_dashboard",
        "Get CellarBridge dashboard",
        "Returns permission-filtered reporting metrics for an inclusive UTC date range.",
        DASHBOARD_PROPERTIES,
        List.of("from", "to"),
        this::getDashboard);
  }

  private McpReadPayload getDashboard(Arguments arguments) {
    DashboardRecord dashboard =
        service.dashboard(
            localDate(arguments.requiredText("from")), localDate(arguments.requiredText("to")));
    return McpReadPayload.projection(
        dashboard.dataAsOf(),
        McpCallSupport.normalizedProjectionStatus(dashboard.projectionStatus()),
        McpCallSupport.projectionWarnings(dashboard.projectionStatus()),
        dashboard);
  }

  @Bean
  SyncToolSpecification getTimelineMcpTool() {
    return McpCapabilitySupport.readOnlyTool(
        calls,
        "TIMELINE_PROJECTION",
        "cellarbridge_get_timeline",
        "Get a CellarBridge business timeline",
        "Returns a permission-filtered timeline for one whitelisted business subject.",
        TIMELINE_PROPERTIES,
        List.of("subjectType", "subjectId"),
        arguments ->
            timelinePayload(
                arguments.requiredText("subjectType"),
                arguments.requiredText("subjectId"),
                arguments.integer("pageSize")));
  }

  @McpResource(
      name = "cellarbridge_business_timeline",
      title = "CellarBridge business timeline",
      uri = "cellarbridge://timeline/{subjectType}/{subjectId}",
      description =
          "Permission-filtered timeline for a PARTNER, QUOTATION, TRADE_ORDER, or ORDER UUID.",
      mimeType = "application/json")
  public String timelineResource(String subjectType, String subjectId) {
    return calls.json(
        calls.read("TIMELINE_PROJECTION", () -> timelinePayload(subjectType, subjectId, null)));
  }

  @Bean
  SyncToolSpecification searchAuditMcpTool() {
    return McpCapabilitySupport.readOnlyTool(
        calls,
        "AUDIT_PROJECTION",
        "cellarbridge_search_audit",
        "Search CellarBridge audit evidence",
        "Searches immutable, tenant-scoped audit evidence using a bounded time window.",
        AUDIT_PROPERTIES,
        List.of(),
        this::searchAudit);
  }

  private McpReadPayload searchAudit(Arguments arguments) {
    String to = arguments.text("to");
    String from = arguments.text("from");
    Instant upper = to == null ? clock.instant() : instant(to);
    Instant lower = from == null ? upper.minus(Duration.ofDays(30)) : instant(from);
    requireOrdered(lower, upper);
    if (Duration.between(lower, upper).compareTo(Duration.ofDays(367)) > 0) {
      throw McpSafeException.invalidRequest();
    }
    AuditPage page =
        service.audit(
            optionalText(arguments.text("subjectType"), 1, 50),
            optionalUuid(arguments.text("subjectId")),
            optionalUuid(arguments.text("correlationId")),
            null,
            optionalText(arguments.text("action"), 1, 120),
            lower,
            upper,
            pageSize(arguments.integer("pageSize")),
            optionalText(arguments.text("cursor"), 1, 2048));
    ProjectionFreshness freshness = service.auditProjectionFreshness();
    return projection(freshness, page);
  }

  @McpPrompt(
      name = "cellarbridge_daily_operations_brief",
      title = "Daily operations brief",
      description =
          "Plan a permission-aware daily operations brief from current work and reporting data.")
  public GetPromptResult dailyOperationsBrief(GetPromptRequest request) {
    McpCapabilitySupport.requireNoPromptArguments(request);
    return prompt(
        "Permission-aware daily operations brief",
        """
        Prepare a concise CellarBridge daily operations brief.
        1. Call cellarbridge_current_user to establish the effective role and permissions.
        2. Call cellarbridge_list_work_items with personal scope; use team scope only when the
           returned permissions and role permit it.
        3. Call cellarbridge_get_dashboard for the requested UTC date range.
        4. Label projection freshness exactly as returned and surface every warning.
        5. Do not propose or execute approvals, inventory changes, fulfillment actions, payments,
           or any other write. Ask the user to use the product workflow for those actions.
        """);
  }

  @McpPrompt(
      name = "cellarbridge_trace_business_history",
      title = "Trace business history",
      description =
          "Guide a safe timeline and immutable-audit investigation for an authorized object.")
  public GetPromptResult traceBusinessHistory(GetPromptRequest request) {
    McpCapabilitySupport.requireNoPromptArguments(request);
    return prompt(
        "Authorized business history trace",
        """
        Trace an authorized CellarBridge business object's history.
        1. Confirm the subject type and UUID with the user.
        2. Call cellarbridge_get_timeline or read
           cellarbridge://timeline/{subjectType}/{subjectId}.
        3. If the current user has audit permission, call cellarbridge_search_audit with a bounded
           time range and the narrowest useful filters.
        4. Keep correlation and causation identifiers distinct, and state projection freshness.
        5. Do not treat absent or denied records as proof that an event never occurred, and do not
           request broader privileges.
        """);
  }

  private McpReadPayload timelinePayload(String subjectType, String subjectId, Integer pageSize) {
    String normalizedType = requiredTimelineType(subjectType);
    TenantContext context = contexts.requireCurrent();
    boolean unprivilegedSales =
        context.hasRoleCode("sales-representative")
            && !context.hasRoleCode("sales-manager")
            && !context.hasRoleCode("tenant-administrator");
    if (unprivilegedSales && SALES_OWNED_TIMELINE_TYPES.contains(normalizedType)) {
      throw new AccessDeniedException("Access denied");
    }
    if (context.partnerId() != null
        && !"ORDER".equals(normalizedType)
        && !"TRADE_ORDER".equals(normalizedType)) {
      throw new AccessDeniedException("Access denied");
    }
    TimelinePage page = service.timeline(normalizedType, uuid(subjectId), pageSize(pageSize));
    return McpReadPayload.projection(
        page.dataAsOf(),
        McpCallSupport.normalizedProjectionStatus(page.projectionStatus()),
        McpCallSupport.projectionWarnings(page.projectionStatus()),
        page);
  }

  private static McpReadPayload projection(ProjectionFreshness freshness, Object data) {
    return McpReadPayload.projection(
        freshness.dataAsOf(),
        McpCallSupport.normalizedProjectionStatus(freshness.projectionStatus()),
        McpCallSupport.projectionWarnings(freshness.projectionStatus()),
        data);
  }

  private static Set<String> allowedSet(List<String> values, Set<String> allowed) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    if (values.size() > allowed.size()) {
      throw McpSafeException.invalidRequest();
    }
    Set<String> normalized =
        values.stream()
            .map(value -> value.strip().toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    if (!allowed.containsAll(normalized)) {
      throw McpSafeException.invalidRequest();
    }
    return normalized;
  }

  private static String requiredTimelineType(String value) {
    String normalized = uppercase(optionalText(value, 1, 40));
    if (normalized == null || !TIMELINE_TYPES.contains(normalized)) {
      throw McpSafeException.invalidRequest();
    }
    return normalized;
  }

  private static String scope(String value) {
    String normalized = value == null ? "personal" : value.strip().toLowerCase(Locale.ROOT);
    if (!Set.of("personal", "team").contains(normalized)) {
      throw McpSafeException.invalidRequest();
    }
    return normalized;
  }

  private static Integer pageSize(Integer value) {
    if (value != null && (value < 1 || value > 100)) {
      throw McpSafeException.invalidRequest();
    }
    return value;
  }

  private static String optionalText(String value, int min, int max) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    if (normalized.length() < min || normalized.length() > max) {
      throw McpSafeException.invalidRequest();
    }
    return normalized;
  }

  private static String uppercase(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  private static LocalDate localDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException exception) {
      throw McpSafeException.invalidRequest();
    }
  }

  private static Instant instant(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException exception) {
      throw McpSafeException.invalidRequest();
    }
  }

  private static UUID optionalUuid(String value) {
    return value == null ? null : uuid(value);
  }

  private static UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw McpSafeException.invalidRequest();
      }
      return parsed;
    } catch (RuntimeException exception) {
      throw McpSafeException.invalidRequest();
    }
  }

  private static void requireOrdered(Instant from, Instant to) {
    if (from != null && to != null && !from.isBefore(to)) {
      throw McpSafeException.invalidRequest();
    }
  }

  private static Map<String, Object> workItemProperties() {
    return Map.ofEntries(
        Map.entry(
            "statuses",
            McpCapabilitySupport.textArray(
                "Work item statuses", WORK_STATUSES.stream().sorted().toList(), 4)),
        Map.entry(
            "priorities",
            McpCapabilitySupport.textArray(
                "Work item priorities", WORK_PRIORITIES.stream().sorted().toList(), 4)),
        Map.entry(
            "types",
            McpCapabilitySupport.textArray(
                "Known work item types", WORK_TYPES.stream().sorted().toList(), 5)),
        Map.entry(
            "dueFrom",
            McpCapabilitySupport.formattedText(
                "Inclusive ISO-8601 due-time start", "date-time", 64)),
        Map.entry(
            "dueTo",
            McpCapabilitySupport.formattedText("Exclusive ISO-8601 due-time end", "date-time", 64)),
        Map.entry(
            "subjectNumber",
            McpCapabilitySupport.text("Subject number fragment; at most 80 characters", 1, 80)),
        Map.entry(
            "scope",
            McpCapabilitySupport.enumeratedText(
                "Personal or authorized team scope", List.of("personal", "team"), false)),
        Map.entry("pageSize", McpCapabilitySupport.integer("Page size from 1 to 100", 1, 100)));
  }

  private static Map<String, Object> dashboardProperties() {
    return Map.of(
        "from",
        McpCapabilitySupport.formattedText("Required inclusive UTC start date", "date", 10),
        "to",
        McpCapabilitySupport.formattedText("Required inclusive UTC end date", "date", 10));
  }

  private static Map<String, Object> timelineProperties() {
    return Map.of(
        "subjectType",
        McpCapabilitySupport.enumeratedText(
            "Business subject type", TIMELINE_TYPES.stream().sorted().toList(), false),
        "subjectId",
        McpCapabilitySupport.formattedText("Required subject UUID", "uuid", 36),
        "pageSize",
        McpCapabilitySupport.integer("Page size from 1 to 100", 1, 100));
  }

  private static Map<String, Object> auditProperties() {
    return Map.ofEntries(
        Map.entry(
            "subjectType", McpCapabilitySupport.text("Subject type; at most 50 characters", 1, 50)),
        Map.entry("subjectId", McpCapabilitySupport.formattedText("Subject UUID", "uuid", 36)),
        Map.entry(
            "correlationId", McpCapabilitySupport.formattedText("Correlation UUID", "uuid", 36)),
        Map.entry("action", McpCapabilitySupport.text("Action; at most 120 characters", 1, 120)),
        Map.entry(
            "from",
            McpCapabilitySupport.formattedText(
                "Inclusive ISO-8601 start; defaults to 30 days before to", "date-time", 64)),
        Map.entry(
            "to",
            McpCapabilitySupport.formattedText(
                "Exclusive ISO-8601 end; defaults to the current time", "date-time", 64)),
        Map.entry("pageSize", McpCapabilitySupport.integer("Page size from 1 to 100", 1, 100)),
        Map.entry(
            "cursor", McpCapabilitySupport.text("Opaque cursor returned by this tool", 1, 2048)));
  }

  private static GetPromptResult prompt(String description, String text) {
    return new GetPromptResult(
        description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
  }
}
