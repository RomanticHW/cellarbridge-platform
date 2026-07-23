package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.AuditFilter;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.AuditRecord;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.DashboardRecord;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.InboxDecision;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.Projection;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.ProjectionFreshness;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.ProjectionStatus;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.TimelineRecord;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.WorkFilter;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.WorkItemRecord;
import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditReportingService {
  private static final int DEFAULT_PAGE_SIZE = 25;
  private static final int MAX_PAGE_SIZE = 100;

  private final AuditReportingStore store;
  private final ProjectionEventSource eventSource;
  private final AuditCursorCodec cursors;
  private final TenantContextHolder contexts;
  private final AuthorizationService authorization;
  private final JsonMapper json;
  private final Clock clock;

  AuditReportingService(
      AuditReportingStore store,
      ProjectionEventSource eventSource,
      AuditCursorCodec cursors,
      TenantContextHolder contexts,
      AuthorizationService authorization,
      JsonMapper json,
      Clock clock) {
    this.store = store;
    this.eventSource = eventSource;
    this.cursors = cursors;
    this.contexts = contexts;
    this.authorization = authorization;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public EventHandlingResult project(EventDelivery delivery) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String payloadHash = ProjectionDefinition.sha256(delivery.payloadJson());
    JsonNode payload;
    try {
      payload = json.readTree(delivery.payloadJson());
      if (payload == null || !payload.isObject()) throw new IllegalArgumentException("payload");
    } catch (JacksonException | IllegalArgumentException exception) {
      store.deadLetter(
          delivery, ProjectionDefinition.PROJECTOR, payloadHash, "PROJECTION_PAYLOAD_INVALID", now);
      return EventHandlingResult.processed(delivery.eventId().toString(), payloadHash);
    }
    String dependencyKey = ProjectionDefinition.dependencyKey(delivery, payload);
    String resolutionAction = ProjectionDefinition.resolutionAction(delivery, payload);
    InboxDecision decision =
        store.begin(
            delivery,
            ProjectionDefinition.PROJECTOR,
            payloadHash,
            dependencyKey,
            resolutionAction,
            now);
    if (decision != InboxDecision.PROCESS && decision != InboxDecision.PENDING_RETRY) {
      return EventHandlingResult.processed(delivery.eventId().toString(), payloadHash);
    }
    Projection projection = ProjectionDefinition.from(delivery, payload);
    long generation = store.activeGeneration(new TenantId(delivery.tenantId()), now);
    ProjectionStatus status = store.project(generation, projection, true, now);
    store.complete(
        delivery,
        ProjectionDefinition.PROJECTOR,
        status,
        status == ProjectionStatus.PENDING ? "WORK_ITEM_OPEN_NOT_SEEN" : null,
        now);
    return EventHandlingResult.processed(delivery.eventId().toString(), projection.entryHash());
  }

  @Transactional(readOnly = true)
  public DashboardRecord dashboard(LocalDate from, LocalDate to) {
    TenantContext context = context();
    authorization.require(PermissionCode.REPORTING_READ, context.tenantId());
    requireRange(from, to);
    UUID ownerScope =
        context.hasRoleCode("sales-representative")
                && !context.hasRoleCode("sales-manager")
                && !context.hasRoleCode("tenant-administrator")
            ? context.userId()
            : null;
    Set<String> metrics =
        context.hasRoleCode("finance-specialist") ? Set.of("RECEIVABLE_STATUS") : Set.of();
    return store.dashboard(context.tenantId(), from, to, ownerScope, metrics, clock.instant());
  }

  @Transactional(readOnly = true)
  public AuditPage audit(
      String subjectType,
      UUID subjectId,
      UUID correlationId,
      UUID actorId,
      String action,
      Instant from,
      Instant to,
      Integer requestedPageSize,
      String cursor) {
    TenantContext context = context();
    authorization.require(PermissionCode.AUDIT_READ, context.tenantId());
    int pageSize = pageSize(requestedPageSize);
    String normalizedSubjectType = blank(subjectType);
    String normalizedAction = blank(action);
    UUID actorScope = context.hasRoleCode("sales-representative") ? context.userId() : actorId;
    Set<String> classifications =
        context.hasRoleCode("system-operator")
            ? Set.of("TECHNICAL_SENSITIVE")
            : Set.of("INTERNAL", "COMMERCIAL_SENSITIVE");
    String queryFingerprint =
        auditQueryFingerprint(
            normalizedSubjectType,
            subjectId,
            correlationId,
            actorScope,
            normalizedAction,
            from,
            to,
            classifications);
    AuditCursorCodec.Position position =
        cursors.decode(context.tenantId(), queryFingerprint, cursor);
    List<AuditRecord> values =
        store.audit(
            context.tenantId(),
            new AuditFilter(
                normalizedSubjectType,
                subjectId,
                correlationId,
                actorScope,
                normalizedAction,
                from,
                to,
                position.occurredAt(),
                position.id()),
            actorScope,
            classifications,
            pageSize + 1);
    boolean hasNext = values.size() > pageSize;
    List<AuditRecord> items = hasNext ? values.subList(0, pageSize) : values;
    String next =
        hasNext
            ? cursors.encode(
                context.tenantId(),
                queryFingerprint,
                items.getLast().occurredAt(),
                items.getLast().id())
            : null;
    return new AuditPage(items, new PageInfo(next, hasNext, pageSize));
  }

  @Transactional(readOnly = true)
  public TimelinePage timeline(String subjectType, UUID subjectId, Integer requestedPageSize) {
    TenantContext context = context();
    PermissionCode permission =
        switch (subjectType == null ? "" : subjectType.toUpperCase()) {
          case "PARTNER" -> PermissionCode.PARTNER_READ;
          case "QUOTATION" -> PermissionCode.QUOTATION_READ;
          case "TRADE_ORDER", "ORDER" -> PermissionCode.ORDER_READ;
          default -> PermissionCode.AUDIT_READ;
        };
    authorization.require(permission, context.tenantId());
    int pageSize = pageSize(requestedPageSize);
    List<TimelineRecord> items =
        store.timeline(
            context.tenantId(),
            requiredText(subjectType, "subjectType"),
            java.util.Objects.requireNonNull(subjectId, "subjectId is required"),
            context.partnerId(),
            pageSize);
    Instant dataAsOf = items.isEmpty() ? null : items.getFirst().dataAsOf();
    return new TimelinePage(items, dataAsOf, projectionStatus(dataAsOf));
  }

  @Transactional(readOnly = true)
  public WorkItemPage workItems(
      Set<String> statuses,
      Set<String> priorities,
      Set<String> types,
      Instant dueFrom,
      Instant dueTo,
      String subjectNumber,
      String scope,
      Integer requestedPageSize) {
    TenantContext context = context();
    authorization.require(PermissionCode.REPORTING_READ, context.tenantId());
    boolean teamScope = "team".equalsIgnoreCase(scope);
    if (teamScope
        && !context.hasRoleCode("sales-manager")
        && !context.hasRoleCode("tenant-administrator")) {
      throw new org.springframework.security.access.AccessDeniedException("Access denied");
    }
    Set<String> permissionValues =
        context.permissions().stream()
            .map(PermissionCode::value)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    List<WorkItemRecord> items =
        store.workItems(
            context.tenantId(),
            new WorkFilter(
                safe(statuses),
                safe(priorities),
                safe(types),
                dueFrom,
                dueTo,
                blank(subjectNumber)),
            context.userId(),
            permissionValues,
            teamScope,
            pageSize(requestedPageSize));
    return new WorkItemPage(items, teamScope ? "TEAM" : "PERSONAL");
  }

  @Transactional(readOnly = true)
  public ProjectionFreshness reportingProjectionFreshness() {
    TenantContext context = context();
    authorization.require(PermissionCode.REPORTING_READ, context.tenantId());
    return store.projectionFreshness(context.tenantId(), clock.instant());
  }

  @Transactional(readOnly = true)
  public ProjectionFreshness auditProjectionFreshness() {
    TenantContext context = context();
    authorization.require(PermissionCode.AUDIT_READ, context.tenantId());
    return store.projectionFreshness(context.tenantId(), clock.instant());
  }

  @Transactional
  public RebuildResult rebuildCurrentTenant() {
    TenantContext context = context();
    authorization.require(PermissionCode.ADMINISTRATION_MANAGE_ACCESS, context.tenantId());
    Instant started = clock.instant().truncatedTo(ChronoUnit.MICROS);
    long generation = store.beginRebuild(context.tenantId(), started);
    List<EventDelivery> events = eventSource.all(context.tenantId());
    long projected = 0;
    Instant dataAsOf = null;
    for (EventDelivery delivery : events) {
      if (!ProjectionDefinition.EVENT_TYPES.contains(delivery.eventType())) continue;
      try {
        JsonNode payload = json.readTree(delivery.payloadJson());
        store.project(generation, ProjectionDefinition.from(delivery, payload), false, started);
        projected++;
        if (dataAsOf == null || delivery.occurredAt().isAfter(dataAsOf))
          dataAsOf = delivery.occurredAt();
      } catch (JacksonException | IllegalArgumentException exception) {
        // Invalid source events remain visible in the active inbox/dead-letter view and are skipped
        // in staging.
      }
    }
    Instant completed = clock.instant().truncatedTo(ChronoUnit.MICROS);
    store.activateRebuild(context.tenantId(), generation, projected, dataAsOf, completed);
    return new RebuildResult(generation, projected, dataAsOf, started, completed);
  }

  private TenantContext context() {
    return contexts.requireCurrent();
  }

  private static void requireRange(LocalDate from, LocalDate to) {
    if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusDays(366))) {
      throw new IllegalArgumentException("Dashboard date range must contain 1 to 367 UTC dates");
    }
  }

  private static int pageSize(Integer requested) {
    int value = requested == null ? DEFAULT_PAGE_SIZE : requested;
    if (value < 1 || value > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("pageSize must be between 1 and 100");
    }
    return value;
  }

  private static Set<String> safe(Set<String> values) {
    return values == null ? Set.of() : Set.copyOf(values);
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String requiredText(String value, String name) {
    String result = blank(value);
    if (result == null) throw new IllegalArgumentException(name + " is required");
    return result;
  }

  private static String auditQueryFingerprint(
      String subjectType,
      UUID subjectId,
      UUID correlationId,
      UUID actorScope,
      String action,
      Instant from,
      Instant to,
      Set<String> classifications) {
    StringBuilder canonical = new StringBuilder();
    appendCanonical(canonical, "subjectType", subjectType);
    appendCanonical(canonical, "subjectId", subjectId);
    appendCanonical(canonical, "correlationId", correlationId);
    appendCanonical(canonical, "actorScope", actorScope);
    appendCanonical(canonical, "action", action);
    appendCanonical(canonical, "from", from);
    appendCanonical(canonical, "to", to);
    appendCanonical(
        canonical,
        "classifications",
        classifications.stream()
            .sorted()
            .map(value -> value.length() + ":" + value)
            .collect(java.util.stream.Collectors.joining(",")));
    return ProjectionDefinition.sha256(canonical.toString());
  }

  private static void appendCanonical(StringBuilder canonical, String name, Object value) {
    canonical.append(name).append('=');
    if (value == null) {
      canonical.append('-');
    } else {
      String text = value.toString();
      canonical.append(text.length()).append(':').append(text);
    }
    canonical.append(';');
  }

  private String projectionStatus(Instant dataAsOf) {
    if (dataAsOf == null) return "EMPTY";
    return dataAsOf.isBefore(clock.instant().minusSeconds(10)) ? "STALE" : "CURRENT";
  }

  public record PageInfo(String nextCursor, boolean hasNext, int pageSize) {}

  public record AuditPage(List<AuditRecord> items, PageInfo pageInfo) {
    public AuditPage {
      items = List.copyOf(items);
    }
  }

  public record TimelinePage(
      List<TimelineRecord> items, Instant dataAsOf, String projectionStatus) {
    public TimelinePage {
      items = List.copyOf(items);
    }
  }

  public record WorkItemPage(List<WorkItemRecord> items, String scope) {
    public WorkItemPage {
      items = List.copyOf(items);
    }
  }

  public record RebuildResult(
      long generation,
      long projectedEvents,
      Instant dataAsOf,
      Instant startedAt,
      Instant completedAt) {}
}
