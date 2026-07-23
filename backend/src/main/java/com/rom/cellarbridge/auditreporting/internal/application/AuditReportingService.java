package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.AuditFilter;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.AuditRecord;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.DashboardRecord;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.InboxDecision;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.Projection;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.ProjectionCheckpoint;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.ProjectionFreshness;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.ProjectionStatus;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.TimelineRecord;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.WorkFilter;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.WorkItemRecord;
import com.rom.cellarbridge.auditreporting.internal.application.ProjectionEventSource.SourceState;
import com.rom.cellarbridge.auditreporting.internal.application.ProjectionEventSource.Watermark;
import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
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
    ProjectionRead<DashboardRecord> read = dashboardRead(from, to, Integer.MAX_VALUE);
    DashboardRecord dashboard = read.data();
    ProjectionFreshness freshness = read.freshness();
    return new DashboardRecord(
        dashboard.from(),
        dashboard.to(),
        dashboard.generatedAt(),
        freshness.dataAsOf(),
        freshness.projectionLagSeconds() == null ? 0 : freshness.projectionLagSeconds(),
        "UNKNOWN".equals(freshness.projectionStatus()) ? "STALE" : freshness.projectionStatus(),
        dashboard.metrics(),
        dashboard.charts());
  }

  @Transactional(readOnly = true)
  public ProjectionRead<DashboardRecord> dashboardRead(
      LocalDate from, LocalDate to, int maxCollectionItems) {
    if (maxCollectionItems < 1)
      throw new IllegalArgumentException("Result budget must be positive");
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
    Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    ProjectionFreshness freshness = projectionFreshness(context.tenantId(), observedAt);
    DashboardRecord dashboard =
        store.dashboard(
            context.tenantId(),
            from,
            to,
            ownerScope,
            metrics,
            observedAt,
            maxCollectionItems == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxCollectionItems + 1);
    return new ProjectionRead<>(
        new DashboardRecord(
            dashboard.from(),
            dashboard.to(),
            dashboard.generatedAt(),
            freshness.dataAsOf(),
            freshness.projectionLagSeconds(),
            freshness.projectionStatus(),
            dashboard.metrics(),
            dashboard.charts()),
        freshness);
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
    return auditPage(
        new AuditQuery(
            subjectType,
            subjectId,
            correlationId,
            actorId,
            action,
            from,
            to,
            requestedPageSize,
            cursor),
        false);
  }

  @Transactional(readOnly = true)
  public AuditPage boundedAudit(AuditQuery query) {
    return auditPage(query, true);
  }

  private AuditPage auditPage(AuditQuery query, boolean boundedWindow) {
    TenantContext context = context();
    authorization.require(PermissionCode.AUDIT_READ, context.tenantId());
    int pageSize = pageSize(query.pageSize());
    String normalizedSubjectType = blank(query.subjectType());
    String normalizedAction = blank(query.action());
    UUID actorScope =
        context.hasRoleCode("sales-representative") ? context.userId() : query.actorId();
    Set<String> classifications =
        context.hasRoleCode("system-operator")
            ? Set.of("TECHNICAL_SENSITIVE")
            : Set.of("INTERNAL", "COMMERCIAL_SENSITIVE");
    String queryFingerprint =
        auditQueryFingerprint(
            normalizedSubjectType,
            query.subjectId(),
            query.correlationId(),
            actorScope,
            normalizedAction,
            query.from(),
            query.to(),
            classifications);
    AuditCursorCodec.Position position =
        decodeCursor(context.tenantId(), queryFingerprint, query.cursor());
    Instant effectiveFrom = query.from();
    Instant effectiveTo = query.to();
    boolean continuation = query.cursor() != null && !query.cursor().isBlank();
    if (continuation) {
      effectiveFrom = position.windowFrom();
      effectiveTo = position.windowTo();
    } else if (boundedWindow) {
      effectiveTo = query.to() == null ? clock.instant() : query.to();
      effectiveFrom = query.from() == null ? effectiveTo.minus(Duration.ofDays(30)) : query.from();
    }
    if (boundedWindow
        && (effectiveFrom == null
            || effectiveTo == null
            || !effectiveFrom.isBefore(effectiveTo)
            || Duration.between(effectiveFrom, effectiveTo).compareTo(Duration.ofDays(367)) > 0)) {
      if (continuation) throw new InvalidCursorException(new IllegalArgumentException("window"));
      throw new IllegalArgumentException("Audit time window is invalid");
    }
    List<AuditRecord> values =
        store.audit(
            context.tenantId(),
            new AuditFilter(
                normalizedSubjectType,
                query.subjectId(),
                query.correlationId(),
                actorScope,
                normalizedAction,
                effectiveFrom,
                effectiveTo,
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
                effectiveFrom,
                effectiveTo,
                items.getLast().occurredAt(),
                items.getLast().id())
            : null;
    return new AuditPage(items, new PageInfo(next, hasNext, pageSize));
  }

  @Transactional(readOnly = true)
  public TimelinePage timeline(String subjectType, UUID subjectId, Integer requestedPageSize) {
    PaginatedTimelinePage page = timeline(subjectType, subjectId, requestedPageSize, null).data();
    return new TimelinePage(
        page.items(), page.dataAsOf(), legacyTimelineProjectionStatus(page.projectionStatus()));
  }

  @Transactional(readOnly = true)
  public ProjectionRead<PaginatedTimelinePage> timeline(
      String subjectType, UUID subjectId, Integer requestedPageSize, String cursor) {
    TenantContext context = context();
    String normalizedSubjectType =
        requiredText(subjectType, "subjectType").toUpperCase(Locale.ROOT);
    PermissionCode permission = timelinePermission(normalizedSubjectType);
    authorization.require(permission, context.tenantId());
    int pageSize = pageSize(requestedPageSize);
    UUID normalizedSubjectId = java.util.Objects.requireNonNull(subjectId, "subjectId is required");
    String queryFingerprint =
        timelineQueryFingerprint(
            normalizedSubjectType, normalizedSubjectId, permission, context.partnerId());
    AuditCursorCodec.Position position = decodeCursor(context.tenantId(), queryFingerprint, cursor);
    ProjectionFreshness freshness =
        projectionFreshness(context.tenantId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
    List<TimelineRecord> values =
        store.timeline(
            context.tenantId(),
            normalizedSubjectType,
            normalizedSubjectId,
            context.partnerId(),
            position.occurredAt(),
            position.id(),
            pageSize + 1);
    boolean hasNext = values.size() > pageSize;
    List<TimelineRecord> items = hasNext ? values.subList(0, pageSize) : values;
    String next =
        hasNext
            ? cursors.encode(
                context.tenantId(),
                queryFingerprint,
                items.getLast().occurredAt(),
                items.getLast().sourceEventId())
            : null;
    return new ProjectionRead<>(
        new PaginatedTimelinePage(
            items,
            freshness.dataAsOf(),
            freshness.projectionStatus(),
            new PageInfo(next, hasNext, pageSize)),
        freshness);
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
    PaginatedWorkItemPage page =
        workItems(
            statuses,
            priorities,
            types,
            dueFrom,
            dueTo,
            subjectNumber,
            scope,
            requestedPageSize,
            null);
    return new WorkItemPage(page.items(), page.scope());
  }

  @Transactional(readOnly = true)
  public PaginatedWorkItemPage workItems(
      Set<String> statuses,
      Set<String> priorities,
      Set<String> types,
      Instant dueFrom,
      Instant dueTo,
      String subjectNumber,
      String scope,
      Integer requestedPageSize,
      String cursor) {
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
    WorkFilter filter =
        new WorkFilter(
            safe(statuses), safe(priorities), safe(types), dueFrom, dueTo, blank(subjectNumber));
    int pageSize = pageSize(requestedPageSize);
    String queryFingerprint =
        workItemQueryFingerprint(
            filter, teamScope ? null : context.userId(), permissionValues, teamScope);
    AuditCursorCodec.WorkItemPosition position =
        decodeWorkItemCursor(context.tenantId(), queryFingerprint, cursor);
    List<WorkItemRecord> values =
        store.workItems(
            context.tenantId(),
            filter,
            context.userId(),
            permissionValues,
            teamScope,
            position.dueAt(),
            position.priority(),
            position.id(),
            pageSize + 1);
    boolean hasNext = values.size() > pageSize;
    List<WorkItemRecord> items = hasNext ? values.subList(0, pageSize) : values;
    String next =
        hasNext
            ? cursors.encodeWorkItem(
                context.tenantId(),
                queryFingerprint,
                items.getLast().dueAt(),
                items.getLast().priority(),
                items.getLast().id())
            : null;
    return new PaginatedWorkItemPage(
        items, teamScope ? "TEAM" : "PERSONAL", new PageInfo(next, hasNext, pageSize));
  }

  @Transactional(readOnly = true)
  public ProjectionFreshness reportingProjectionFreshness() {
    TenantContext context = context();
    authorization.require(PermissionCode.REPORTING_READ, context.tenantId());
    return projectionFreshness(context.tenantId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
  }

  @Transactional(readOnly = true)
  public ProjectionFreshness auditProjectionFreshness() {
    TenantContext context = context();
    authorization.require(PermissionCode.AUDIT_READ, context.tenantId());
    return projectionFreshness(context.tenantId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
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

  private AuditCursorCodec.Position decodeCursor(
      TenantId tenantId, String queryFingerprint, String cursor) {
    try {
      return cursors.decode(tenantId, queryFingerprint, cursor);
    } catch (IllegalArgumentException exception) {
      throw new InvalidCursorException(exception);
    }
  }

  private AuditCursorCodec.WorkItemPosition decodeWorkItemCursor(
      TenantId tenantId, String queryFingerprint, String cursor) {
    try {
      return cursors.decodeWorkItem(tenantId, queryFingerprint, cursor);
    } catch (IllegalArgumentException exception) {
      throw new InvalidCursorException(exception);
    }
  }

  private static PermissionCode timelinePermission(String subjectType) {
    String normalized = requiredText(subjectType, "subjectType").toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "PARTNER" -> PermissionCode.PARTNER_READ;
      case "QUOTATION" -> PermissionCode.QUOTATION_READ;
      case "TRADE_ORDER", "ORDER" -> PermissionCode.ORDER_READ;
      default -> PermissionCode.AUDIT_READ;
    };
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
    appendCanonical(canonical, "capability", "audit");
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

  private static String timelineQueryFingerprint(
      String subjectType, UUID subjectId, PermissionCode permission, UUID partnerScope) {
    StringBuilder canonical = new StringBuilder();
    appendCanonical(canonical, "capability", "timeline");
    appendCanonical(canonical, "subjectType", subjectType);
    appendCanonical(canonical, "subjectId", subjectId);
    appendCanonical(canonical, "permission", permission.value());
    appendCanonical(canonical, "partnerScope", partnerScope);
    return ProjectionDefinition.sha256(canonical.toString());
  }

  private static String workItemQueryFingerprint(
      WorkFilter filter, UUID userScope, Set<String> permissionValues, boolean teamScope) {
    StringBuilder canonical = new StringBuilder();
    appendCanonical(canonical, "capability", "work-items");
    appendCanonical(canonical, "statuses", canonicalSet(filter.statuses()));
    appendCanonical(canonical, "priorities", canonicalSet(filter.priorities()));
    appendCanonical(canonical, "types", canonicalSet(filter.types()));
    appendCanonical(canonical, "dueFrom", filter.dueFrom());
    appendCanonical(canonical, "dueTo", filter.dueTo());
    appendCanonical(canonical, "subjectNumber", filter.subjectNumber());
    appendCanonical(canonical, "teamScope", teamScope);
    appendCanonical(canonical, "userScope", userScope);
    appendCanonical(canonical, "permissions", canonicalSet(permissionValues));
    return ProjectionDefinition.sha256(canonical.toString());
  }

  private static String canonicalSet(Set<String> values) {
    return values.stream()
        .sorted()
        .map(value -> value.length() + ":" + value)
        .collect(java.util.stream.Collectors.joining(","));
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

  private ProjectionFreshness projectionFreshness(TenantId tenantId, Instant observedAt) {
    SourceState source = eventSource.sourceState(tenantId, ProjectionDefinition.EVENT_TYPES);
    ProjectionCheckpoint checkpoint = store.projectionCheckpoint(tenantId);
    Watermark sourceWatermark = source.watermark();
    Watermark processedThrough = checkpoint.processedThrough();
    String status;
    Long lagSeconds = lagSeconds(sourceWatermark, processedThrough);

    if (checkpoint.rebuilding()) {
      status = "REBUILDING";
    } else if (isEmpty(source, checkpoint)) {
      status = "EMPTY";
    } else if (checkpoint.pendingCount() > 0 || checkpoint.deadLetterCount() > 0) {
      status = "STALE";
    } else if (source.eventCount() > 0 && !checkpoint.activeGeneration()) {
      status = "STALE";
    } else if (isInconsistent(source, checkpoint)) {
      status = "UNKNOWN";
      lagSeconds = null;
    } else if (sourceAhead(source, checkpoint)) {
      status = "STALE";
    } else if (source.eventCount() == checkpoint.handledEventCount()
        && sourceWatermark.equals(processedThrough)
        && checkpoint.activeGeneration()) {
      status = "CURRENT";
      lagSeconds = 0L;
    } else {
      status = "UNKNOWN";
      lagSeconds = null;
    }
    return new ProjectionFreshness(
        checkpoint.dataAsOf(),
        lagSeconds,
        status,
        observedAt,
        sourceWatermark,
        processedThrough,
        checkpoint.lastSuccessfulRefreshAt(),
        checkpoint.pendingCount(),
        checkpoint.deadLetterCount());
  }

  private static boolean isEmpty(SourceState source, ProjectionCheckpoint checkpoint) {
    return source.eventCount() == 0
        && !checkpoint.checkpointPresent()
        && checkpoint.handledEventCount() == 0
        && checkpoint.dataAsOf() == null;
  }

  private static boolean isInconsistent(SourceState source, ProjectionCheckpoint checkpoint) {
    Watermark sourceWatermark = source.watermark();
    Watermark processedThrough = checkpoint.processedThrough();
    if (checkpoint.checkpointPresent() != (processedThrough != null)
        || checkpoint.handledEventCount() > source.eventCount()) {
      return true;
    }
    if (sourceWatermark == null)
      return processedThrough != null || checkpoint.handledEventCount() > 0;
    if (processedThrough == null) return checkpoint.handledEventCount() > 0;
    return processedThrough.occurredAt().isAfter(sourceWatermark.occurredAt())
        || (source.eventCount() == checkpoint.handledEventCount()
            && !sourceWatermark.equals(processedThrough));
  }

  private static boolean sourceAhead(SourceState source, ProjectionCheckpoint checkpoint) {
    return source.watermark() != null && source.eventCount() > checkpoint.handledEventCount();
  }

  private static Long lagSeconds(Watermark source, Watermark processed) {
    if (source == null
        || processed == null
        || processed.occurredAt().isAfter(source.occurredAt())) {
      return null;
    }
    return Math.max(0, ChronoUnit.SECONDS.between(processed.occurredAt(), source.occurredAt()));
  }

  private static String legacyTimelineProjectionStatus(String status) {
    return Set.of("UNKNOWN", "REBUILDING").contains(status) ? "STALE" : status;
  }

  public record PageInfo(String nextCursor, boolean hasNext, int pageSize) {}

  public record AuditQuery(
      String subjectType,
      UUID subjectId,
      UUID correlationId,
      UUID actorId,
      String action,
      Instant from,
      Instant to,
      Integer pageSize,
      String cursor) {}

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

  public record PaginatedTimelinePage(
      List<TimelineRecord> items, Instant dataAsOf, String projectionStatus, PageInfo pageInfo) {
    public PaginatedTimelinePage {
      items = List.copyOf(items);
    }
  }

  public record WorkItemPage(List<WorkItemRecord> items, String scope) {
    public WorkItemPage {
      items = List.copyOf(items);
    }
  }

  public record PaginatedWorkItemPage(List<WorkItemRecord> items, String scope, PageInfo pageInfo) {
    public PaginatedWorkItemPage {
      items = List.copyOf(items);
    }
  }

  public record ProjectionRead<T>(T data, ProjectionFreshness freshness) {}

  public record RebuildResult(
      long generation,
      long projectedEvents,
      Instant dataAsOf,
      Instant startedAt,
      Instant completedAt) {}

  public static final class InvalidCursorException extends IllegalArgumentException {
    private InvalidCursorException(IllegalArgumentException cause) {
      super(cause.getMessage(), cause);
    }
  }
}
