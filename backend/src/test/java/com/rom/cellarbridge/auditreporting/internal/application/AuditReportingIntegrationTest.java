package com.rom.cellarbridge.auditreporting.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.AuditPage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.AuditQuery;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.PaginatedTimelinePage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.PaginatedWorkItemPage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.DashboardRecord;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.ProjectionFreshness;
import com.rom.cellarbridge.auditreporting.internal.mcp.ReportingMcpProvider;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.mcp.McpWarning;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class AuditReportingIntegrationTest extends PostgresIntegrationTestSupport {
  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final Instant BASE = Instant.parse("2026-07-22T12:00:00Z");
  private static final String DRAFT_ACTION = "QUOTATION_DRAFT_CREATED";

  @Autowired private AuditReportingService service;
  @Autowired private TenantContextHolder contexts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  @Test
  void deduplicatesEventsAndPreventsOlderStateFromReplacingNewerState() throws Exception {
    UUID quotationId = UUID.randomUUID();
    EventDelivery approved =
        event(
            TENANT,
            UUID.randomUUID(),
            "cellarbridge.quotation.approved.v1",
            BASE.plusSeconds(20),
            "QUOTATION",
            quotationId,
            "QUO-REPORT-001",
            Map.of(
                "quotationId", quotationId, "status", "APPROVED", "revision", 3, "actorId", ACTOR));
    EventDelivery requested =
        event(
            TENANT,
            UUID.randomUUID(),
            "cellarbridge.quotation.approval-requested.v1",
            BASE,
            "QUOTATION",
            quotationId,
            "QUO-REPORT-001",
            Map.of(
                "quotationId",
                quotationId,
                "status",
                "PENDING_APPROVAL",
                "revision",
                3,
                "actorId",
                ACTOR));

    service.project(approved);
    service.project(requested);
    service.project(requested);

    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM audit_reporting.subject_state_projection WHERE tenant_id = ? AND subject_id = ?",
                String.class,
                TENANT.value(),
                quotationId))
        .isEqualTo("APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM audit_reporting.work_item_projection WHERE tenant_id = ? AND subject_id = ?",
                String.class,
                TENANT.value(),
                quotationId))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT duplicate_count FROM audit_reporting.projector_checkpoint WHERE tenant_id = ?",
                Long.class,
                TENANT.value()))
        .isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_reporting.audit_entry WHERE tenant_id = ? AND subject_id = ?",
                Long.class,
                TENANT.value(),
                quotationId))
        .isEqualTo(2);
  }

  @Test
  void resolvesAClosingEventThatArrivesBeforeItsWorkItem() throws Exception {
    UUID partnerId = UUID.randomUUID();
    EventDelivery activated =
        event(
            TENANT,
            UUID.randomUUID(),
            "cellarbridge.partner.activated.v1",
            BASE.plusSeconds(10),
            "PARTNER",
            partnerId,
            "PAR-REPORT-001",
            Map.of("partnerId", partnerId, "status", "ACTIVE", "actorId", ACTOR));
    EventDelivery submitted =
        event(
            TENANT,
            UUID.randomUUID(),
            "cellarbridge.partner.submitted-for-review.v1",
            BASE,
            "PARTNER",
            partnerId,
            "PAR-REPORT-001",
            Map.of("partnerId", partnerId, "status", "PENDING_REVIEW", "submittedBy", ACTOR));

    service.project(activated);
    assertThat(inboxStatus(activated.eventId())).isEqualTo("PENDING");
    service.project(submitted);

    assertThat(inboxStatus(activated.eventId())).isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM audit_reporting.work_item_projection WHERE tenant_id = ? AND dedup_key = ?",
                String.class,
                TENANT.value(),
                "partner-review:" + partnerId))
        .isEqualTo("COMPLETED");
  }

  @Test
  void rejectsAuditMutationAndEnforcesRoleScopedReads() throws Exception {
    UUID quotationId = UUID.randomUUID();
    UUID otherActor = UUID.randomUUID();
    service.project(
        event(
            TENANT,
            UUID.randomUUID(),
            "cellarbridge.quotation.draft-created.v1",
            BASE,
            "QUOTATION",
            quotationId,
            "QUO-REPORT-SCOPED",
            Map.of("status", "DRAFT", "revision", 1, "actorId", ACTOR)));
    service.project(
        event(
            TENANT,
            UUID.randomUUID(),
            "cellarbridge.quotation.draft-created.v1",
            BASE.plusSeconds(1),
            "QUOTATION",
            UUID.randomUUID(),
            "QUO-REPORT-OTHER",
            Map.of("status", "DRAFT", "revision", 1, "actorId", otherActor)));

    UUID auditId =
        jdbc.queryForObject(
            "SELECT id FROM audit_reporting.audit_entry WHERE tenant_id = ? AND subject_id = ?",
            UUID.class,
            TENANT.value(),
            quotationId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE audit_reporting.audit_entry SET new_state = 'CHANGED' WHERE id = ?",
                    auditId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("audit entries are immutable");
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM audit_reporting.audit_entry WHERE id = ?", auditId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("audit entries are immutable");

    try (TenantContextHolder.Scope ignored = contexts.open(sales(TENANT))) {
      DashboardRecord dashboard =
          service.dashboard(LocalDate.parse("2026-07-22"), LocalDate.parse("2026-07-22"));
      Long expectedOwnQuotations =
          jdbc.queryForObject(
              "SELECT count(*) FROM audit_reporting.metric_fact_projection metric JOIN audit_reporting.projection_generation generation ON generation.tenant_id = metric.tenant_id AND generation.generation = metric.generation AND generation.status = 'ACTIVE' WHERE metric.tenant_id = ? AND metric.metric_type = 'QUOTATION_CREATED' AND metric.owner_user_id = ?",
              Long.class,
              TENANT.value(),
              ACTOR);
      assertThat(dashboard.metrics().get("quotationCount")).isEqualTo(expectedOwnQuotations);
      assertThat(expectedOwnQuotations).isPositive();
      assertThat(service.audit(null, null, null, null, null, null, null, 100, null).items())
          .allSatisfy(item -> assertThat(item.actorId()).isEqualTo(ACTOR));
      assertThatThrownBy(
              () -> service.workItems(Set.of(), Set.of(), Set.of(), null, null, null, "team", 25))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.timeline("QUOTATION", quotationId, 25))
          .isInstanceOf(AccessDeniedException.class);
    }

    try (TenantContextHolder.Scope ignored = contexts.open(quotationReader(TENANT))) {
      assertThat(service.timeline("QUOTATION", quotationId, 25).items())
          .isNotEmpty()
          .allSatisfy(item -> assertThat(item.subjectNumber()).isEqualTo("QUO-REPORT-SCOPED"));
    }
  }

  @Test
  void isolatesTenantDashboardAndAuditAndHonorsUtcDateBoundaries() throws Exception {
    UUID tenantQuotationId = UUID.randomUUID();
    projectAndArchive(
        event(
            TENANT,
            UUID.randomUUID(),
            "cellarbridge.quotation.draft-created.v1",
            Instant.parse("2026-07-22T23:59:59Z"),
            "QUOTATION",
            tenantQuotationId,
            "QUO-REPORT-UTC",
            Map.of("status", "DRAFT", "revision", 1, "actorId", ACTOR)));
    projectAndArchive(
        event(
            OTHER,
            UUID.randomUUID(),
            "cellarbridge.quotation.draft-created.v1",
            Instant.parse("2026-07-22T12:00:00Z"),
            "QUOTATION",
            UUID.randomUUID(),
            "QUO-OTHER-UTC",
            Map.of("status", "DRAFT", "revision", 1, "actorId", ACTOR)));

    var metricRows =
        jdbc.queryForList(
            "SELECT tenant_id, metric_type, occurred_date, subject_id FROM audit_reporting.metric_fact_projection ORDER BY tenant_id, source_event_id");
    assertThat(metricRows)
        .anySatisfy(
            row -> {
              assertThat(row.get("tenant_id")).isEqualTo(OTHER.value());
              assertThat(row.get("metric_type")).isEqualTo("QUOTATION_CREATED");
              assertThat(row.get("subject_id")).isNotNull();
            });
    assertThat(metricRows)
        .anySatisfy(
            row -> {
              assertThat(row.get("tenant_id")).isEqualTo(TENANT.value());
              assertThat(row.get("occurred_date")).isEqualTo(java.sql.Date.valueOf("2026-07-22"));
            });

    try (TenantContextHolder.Scope ignored = contexts.open(admin(TENANT))) {
      DashboardRecord dashboard =
          service.dashboard(LocalDate.parse("2026-07-22"), LocalDate.parse("2026-07-22"));
      assertThat(dashboard.metrics().get("quotationCount")).isEqualTo(1L);
      AuditPage audit =
          service.audit("QUOTATION", tenantQuotationId, null, null, null, null, null, 25, null);
      assertThat(audit.items())
          .singleElement()
          .satisfies(item -> assertThat(item.subjectNumber()).isEqualTo("QUO-REPORT-UTC"));
    }
    try (TenantContextHolder.Scope ignored = contexts.open(admin(OTHER))) {
      DashboardRecord dashboard =
          service.dashboard(LocalDate.parse("2026-07-22"), LocalDate.parse("2026-07-22"));
      assertThat(dashboard.metrics().get("quotationCount")).isEqualTo(1L);
    }
  }

  @Test
  @Transactional
  void bindsAuditCursorToNormalizedQueryAndEffectiveAuthorizationScope() throws Exception {
    UUID quotationId = UUID.randomUUID();
    Instant occurredAt = BASE.plusSeconds(100);
    for (int index = 0; index < 3; index++) {
      service.project(draftEvent(TENANT, quotationId, occurredAt, "QUO-CURSOR-BOUND", index + 1));
    }

    AuditFilter filter =
        new AuditFilter("QUOTATION", quotationId, null, null, DRAFT_ACTION, null, null);
    String firstCursor;
    try (TenantContextHolder.Scope ignored = contexts.open(admin(TENANT))) {
      List<UUID> ids = new ArrayList<>();
      AuditPage page =
          auditPage(
              new AuditFilter(
                  " QUOTATION ", quotationId, null, null, " " + DRAFT_ACTION + " ", null, null),
              null);
      firstCursor = page.pageInfo().nextCursor();
      while (true) {
        ids.addAll(page.items().stream().map(item -> item.id()).toList());
        String cursor = page.pageInfo().nextCursor();
        if (cursor == null) break;
        page = auditPage(filter, cursor);
      }
      assertThat(ids).hasSize(3).doesNotHaveDuplicates();
      List.of(
              new AuditFilter("TRADE_ORDER", quotationId, null, null, DRAFT_ACTION, null, null),
              new AuditFilter("QUOTATION", UUID.randomUUID(), null, null, DRAFT_ACTION, null, null),
              new AuditFilter(
                  "QUOTATION", quotationId, UUID.randomUUID(), null, DRAFT_ACTION, null, null),
              new AuditFilter("QUOTATION", quotationId, null, ACTOR, DRAFT_ACTION, null, null),
              new AuditFilter(
                  "QUOTATION", quotationId, null, null, "QUOTATION_APPROVED", null, null),
              new AuditFilter("QUOTATION", quotationId, null, null, DRAFT_ACTION, BASE, null),
              new AuditFilter(
                  "QUOTATION",
                  quotationId,
                  null,
                  null,
                  DRAFT_ACTION,
                  null,
                  BASE.plusSeconds(1_000)))
          .forEach(rejected -> assertAuditCursorRejected(firstCursor, rejected));
    }

    try (TenantContextHolder.Scope ignored = contexts.open(sales(TENANT))) {
      assertAuditCursorRejected(firstCursor, filter);
    }
    try (TenantContextHolder.Scope ignored = contexts.open(systemOperator(TENANT))) {
      assertAuditCursorRejected(firstCursor, filter);
    }
    try (TenantContextHolder.Scope ignored = contexts.open(admin(OTHER))) {
      assertAuditCursorRejected(firstCursor, filter);
    }
  }

  @Test
  @Transactional
  void paginatesTimelineWithoutGapsAndBindsCursorToTenantAndQuery() throws Exception {
    UUID quotationId = UUID.randomUUID();
    Instant occurredAt = BASE.plusSeconds(500);
    for (int index = 0; index < 3; index++) {
      service.project(
          draftEvent(TENANT, quotationId, occurredAt, "QUO-TIMELINE-CURSOR", index + 1));
    }

    String firstCursor;
    try (TenantContextHolder.Scope ignored = contexts.open(timelineReader(TENANT))) {
      PaginatedTimelinePage first = service.timeline("QUOTATION", quotationId, 1, null).data();
      assertThat(first.items()).hasSize(1);
      assertThat(first.pageInfo())
          .returns(1, AuditReportingService.PageInfo::pageSize)
          .returns(true, AuditReportingService.PageInfo::hasNext);
      firstCursor = first.pageInfo().nextCursor();

      List<UUID> sourceEventIds = new ArrayList<>();
      sourceEventIds.add(first.items().getFirst().sourceEventId());
      for (String cursor = firstCursor; cursor != null; ) {
        PaginatedTimelinePage page = service.timeline(" quotation ", quotationId, 1, cursor).data();
        page.items().forEach(item -> sourceEventIds.add(item.sourceEventId()));
        cursor = page.pageInfo().nextCursor();
      }

      assertThat(sourceEventIds).hasSize(3).doesNotHaveDuplicates();
      assertCursorRejected(() -> service.timeline("QUOTATION", UUID.randomUUID(), 1, firstCursor));
      assertCursorRejected(() -> service.timeline("PARTNER", quotationId, 1, firstCursor));
    }

    try (TenantContextHolder.Scope ignored = contexts.open(timelineReader(OTHER))) {
      assertCursorRejected(() -> service.timeline("QUOTATION", quotationId, 1, firstCursor));
    }
  }

  @Test
  @Transactional
  void paginatesDatedAndUndatedWorkItemsAndBindsEffectiveScope() throws Exception {
    Instant occurredAt = BASE.plusSeconds(600);
    for (int index = 0; index < 3; index++) {
      service.project(approvalEvent(occurredAt, "QUO-WORK-CURSOR-" + index));
    }
    for (int index = 0; index < 2; index++) {
      service.project(
          exceptionEvent(occurredAt.plusSeconds(index), "EXC-WORK-CURSOR-" + index, "HIGH"));
    }

    Set<String> types = Set.of("QUOTATION_APPROVAL", "EXCEPTION_ACTION");
    Set<PermissionCode> permissions =
        Set.of(
            PermissionCode.REPORTING_READ,
            PermissionCode.QUOTATION_APPROVE,
            PermissionCode.EXCEPTION_ASSIGN);
    String firstCursor;
    try (TenantContextHolder.Scope ignored = contexts.open(manager(TENANT, ACTOR, permissions))) {
      List<UUID> workItemIds = new ArrayList<>();
      PaginatedWorkItemPage first = workPage(Set.of("HIGH"), types, "WORK-CURSOR", "team", 2, null);
      assertThat(first.items()).hasSize(2).allSatisfy(item -> assertThat(item.dueAt()).isNotNull());
      assertThat(first.pageInfo())
          .returns(2, AuditReportingService.PageInfo::pageSize)
          .returns(true, AuditReportingService.PageInfo::hasNext);
      firstCursor = first.pageInfo().nextCursor();
      first.items().forEach(item -> workItemIds.add(item.id()));

      for (String cursor = firstCursor; cursor != null; ) {
        PaginatedWorkItemPage page =
            workPage(Set.of("HIGH"), types, "WORK-CURSOR", "team", 2, cursor);
        page.items().forEach(item -> workItemIds.add(item.id()));
        cursor = page.pageInfo().nextCursor();
      }

      assertThat(workItemIds).hasSize(5).doesNotHaveDuplicates();
      assertCursorRejected(
          () -> workPage(Set.of("HIGH"), types, "OTHER-SUBJECT", "team", 2, firstCursor));
    }

    try (TenantContextHolder.Scope ignored =
        contexts.open(
            manager(
                TENANT,
                ACTOR,
                Set.of(PermissionCode.REPORTING_READ, PermissionCode.QUOTATION_APPROVE)))) {
      assertCursorRejected(
          () -> workPage(Set.of("HIGH"), types, "WORK-CURSOR", "team", 2, firstCursor));
    }
    try (TenantContextHolder.Scope ignored = contexts.open(manager(OTHER, ACTOR, permissions))) {
      assertCursorRejected(
          () -> workPage(Set.of("HIGH"), types, "WORK-CURSOR", "team", 2, firstCursor));
    }
  }

  @Test
  void derivesFreshnessFromSourceAndCheckpointEvidenceInsteadOfEventAge() throws Exception {
    TenantId currentTenant = TenantId.of(UUID.randomUUID());
    EventDelivery current = draftEvent(currentTenant, BASE, "QUO-FRESH-CURRENT");
    projectAndArchive(current);
    ProjectionFreshness caughtUp = freshness(currentTenant);
    assertFreshness(caughtUp, "CURRENT", 0L);
    assertThat(caughtUp.sourceWatermark()).isEqualTo(caughtUp.processedThrough());
    assertThat(caughtUp.sourceWatermark().eventId()).isEqualTo(current.eventId());
    assertThat(caughtUp.lastSuccessfulRefreshAt()).isNotNull();

    TenantId sourceAheadTenant = TenantId.of(UUID.randomUUID());
    EventDelivery sourceAhead =
        draftEvent(sourceAheadTenant, BASE.plusSeconds(1), "QUO-FRESH-AHEAD");
    archive(sourceAhead);
    ProjectionFreshness stale = freshness(sourceAheadTenant);
    assertFreshness(stale, "STALE", null, "PROJECTION_STALE");
    assertThat(stale.sourceWatermark().eventId()).isEqualTo(sourceAhead.eventId());
    assertThat(stale.processedThrough()).isNull();

    TenantId gapTenant = TenantId.of(UUID.randomUUID());
    archive(draftEvent(gapTenant, BASE.plusSeconds(1), "QUO-FRESH-GAP"));
    EventDelivery latest = draftEvent(gapTenant, BASE.plusSeconds(2), "QUO-FRESH-LATEST");
    projectAndArchive(latest);
    ProjectionFreshness gap = freshness(gapTenant);
    assertFreshness(gap, "STALE", 0L, "PROJECTION_STALE");
    assertThat(gap.sourceWatermark()).isEqualTo(gap.processedThrough());

    TenantId pendingTenant = TenantId.of(UUID.randomUUID());
    EventDelivery pending =
        event(
            pendingTenant,
            UUID.randomUUID(),
            "cellarbridge.partner.activated.v1",
            BASE.plusSeconds(2),
            "PARTNER",
            UUID.randomUUID(),
            "PAR-FRESH-PENDING",
            Map.of("status", "ACTIVE", "actorId", ACTOR));
    service.project(pending);
    archive(pending);
    ProjectionFreshness pendingFreshness = freshness(pendingTenant);
    assertFreshness(pendingFreshness, "STALE", 0L, "PROJECTION_STALE", "PROJECTION_PENDING");
    assertThat(pendingFreshness.pendingCount()).isEqualTo(1);

    TenantId deadTenant = TenantId.of(UUID.randomUUID());
    EventDelivery deadSource = draftEvent(deadTenant, BASE.plusSeconds(3), "QUO-FRESH-DEAD");
    archive(deadSource);
    service.project(
        new EventDelivery(
            deadSource.eventId(),
            deadSource.tenantId(),
            deadSource.eventType(),
            deadSource.eventVersion(),
            deadSource.occurredAt(),
            deadSource.producer(),
            deadSource.subject(),
            deadSource.correlationId(),
            deadSource.causationId(),
            "{"));
    ProjectionFreshness deadFreshness = freshness(deadTenant);
    assertFreshness(deadFreshness, "STALE", 0L, "PROJECTION_STALE", "PROJECTION_DEAD_LETTER");
    assertThat(deadFreshness.deadLetterCount()).isEqualTo(1);
    assertThat(deadFreshness.lastSuccessfulRefreshAt()).isNull();

    TenantId unknownTenant = TenantId.of(UUID.randomUUID());
    service.project(draftEvent(unknownTenant, BASE.plusSeconds(4), "QUO-FRESH-UNKNOWN"));
    assertFreshness(freshness(unknownTenant), "UNKNOWN", null, "PROJECTION_FRESHNESS_UNKNOWN");

    TenantId emptyTenant = TenantId.of(UUID.randomUUID());
    assertFreshness(freshness(emptyTenant), "EMPTY", null);

    jdbc.update(
        """
        INSERT INTO audit_reporting.projection_generation
          (tenant_id,generation,status,schema_version,source_event_count,created_at)
        VALUES (?,2,'STAGING',1,0,CURRENT_TIMESTAMP)
        """,
        currentTenant.value());
    assertFreshness(freshness(currentTenant), "REBUILDING", 0L, "PROJECTION_REBUILDING");
    try (TenantContextHolder.Scope ignored = contexts.open(admin(currentTenant))) {
      assertThat(
              service
                  .dashboard(LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 22))
                  .projectionStatus())
          .isEqualTo("REBUILDING");
      assertThat(service.timeline("QUOTATION", current.subject().id(), 25).projectionStatus())
          .isEqualTo("STALE");
    }
  }

  @Test
  void rebuildsIntoStagingAndAtomicallySwitchesEquivalentResults() throws Exception {
    UUID orderId = UUID.randomUUID();
    EventDelivery order =
        event(
            TENANT,
            UUID.randomUUID(),
            "cellarbridge.order.created.v1",
            BASE,
            "TRADE_ORDER",
            orderId,
            "ORD-REPORT-001",
            Map.of("orderId", orderId, "orderNumber", "ORD-REPORT-001", "actorId", ACTOR));
    projectAndArchive(order);

    long before = activeMetricCount(TENANT, orderId);
    try (TenantContextHolder.Scope ignored = contexts.open(admin(TENANT))) {
      AuditReportingService.RebuildResult rebuilt = service.rebuildCurrentTenant();
      assertThat(rebuilt.projectedEvents()).isGreaterThanOrEqualTo(1);
    }

    assertThat(activeMetricCount(TENANT, orderId)).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_reporting.projection_generation WHERE tenant_id = ? AND status = 'ACTIVE'",
                Long.class,
                TENANT.value()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_reporting.projection_generation WHERE tenant_id = ? AND status = 'RETIRED'",
                Long.class,
                TENANT.value()))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void usesTenantFirstIndexesForRepresentativeDashboardAndTimelineQueries() {
    jdbc.execute("SET enable_seqscan = off");
    try {
      String metricPlan =
          String.join(
              "\n",
              jdbc.queryForList(
                  "EXPLAIN SELECT count(*) FROM audit_reporting.metric_fact_projection WHERE tenant_id = ? AND generation = ? AND occurred_date >= ? AND metric_type = ?",
                  String.class,
                  TENANT.value(),
                  1L,
                  LocalDate.parse("2026-07-01"),
                  "QUOTATION_CREATED"));
      String timelinePlan =
          String.join(
              "\n",
              jdbc.queryForList(
                  "EXPLAIN SELECT * FROM audit_reporting.timeline_projection WHERE tenant_id = ? AND generation = ? AND subject_type = ? AND subject_id = ? ORDER BY occurred_at DESC LIMIT 25",
                  String.class,
                  TENANT.value(),
                  1L,
                  "QUOTATION",
                  UUID.randomUUID()));
      assertThat(metricPlan).contains("ix_audit_reporting_metric_range");
      assertThat(timelinePlan).contains("ix_audit_reporting_timeline_subject");
    } finally {
      jdbc.execute("RESET enable_seqscan");
    }
  }

  private void projectAndArchive(EventDelivery event) throws Exception {
    service.project(event);
    archive(event);
  }

  private void archive(EventDelivery event) throws Exception {
    String envelope =
        json.writeValueAsString(
            Map.ofEntries(
                Map.entry("id", event.eventId()),
                Map.entry("type", event.eventType()),
                Map.entry("specVersion", "1.0"),
                Map.entry("occurredAt", event.occurredAt()),
                Map.entry("tenantId", event.tenantId()),
                Map.entry("producer", event.producer()),
                Map.entry(
                    "subject",
                    Map.of(
                        "type",
                        event.subject().type(),
                        "id",
                        event.subject().id(),
                        "number",
                        event.subject().number())),
                Map.entry("correlationId", event.correlationId()),
                Map.entry("causationId", event.causationId()),
                Map.entry("payload", json.readTree(event.payloadJson())),
                Map.entry("metadata", Map.of())));
    jdbc.update(
        """
        INSERT INTO platform_event.event_publication
          (event_id,tenant_id,event_type,event_version,spec_version,producer,
           subject_type,subject_id,subject_number,payload,status,attempts,next_attempt_at,
           occurred_at,correlation_id,causation_id,created_at,updated_at,version)
        VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),'PENDING',0,?,?,?,?,?,?,0)
        ON CONFLICT DO NOTHING
        """,
        event.eventId(),
        event.tenantId(),
        event.eventType(),
        1,
        "1.0",
        event.producer(),
        event.subject().type(),
        event.subject().id(),
        event.subject().number(),
        envelope,
        Timestamp.from(event.occurredAt()),
        Timestamp.from(event.occurredAt()),
        event.correlationId(),
        event.causationId(),
        Timestamp.from(event.occurredAt()),
        Timestamp.from(event.occurredAt()));
  }

  private ProjectionFreshness freshness(TenantId tenantId) {
    try (TenantContextHolder.Scope ignored = contexts.open(admin(tenantId))) {
      return service.reportingProjectionFreshness();
    }
  }

  private static void assertWarningCodes(ProjectionFreshness freshness, String... codes) {
    assertThat(ReportingMcpProvider.projectionWarnings(freshness, freshness.projectionStatus()))
        .extracting(McpWarning::code)
        .containsExactly(codes);
  }

  private static void assertFreshness(
      ProjectionFreshness freshness, String status, Long lagSeconds, String... warningCodes) {
    assertThat(freshness.projectionStatus()).isEqualTo(status);
    assertThat(freshness.projectionLagSeconds()).isEqualTo(lagSeconds);
    assertWarningCodes(freshness, warningCodes);
  }

  private PaginatedWorkItemPage workPage(
      Set<String> priorities,
      Set<String> types,
      String subjectNumber,
      String scope,
      int pageSize,
      String cursor) {
    return service.workItems(
        Set.of("OPEN"), priorities, types, null, null, subjectNumber, scope, pageSize, cursor);
  }

  private EventDelivery approvalEvent(Instant occurredAt, String subjectNumber) throws Exception {
    UUID quotationId = UUID.randomUUID();
    return event(
        TENANT,
        UUID.randomUUID(),
        "cellarbridge.quotation.approval-requested.v1",
        occurredAt,
        "QUOTATION",
        quotationId,
        subjectNumber,
        Map.of(
            "quotationId",
            quotationId,
            "status",
            "PENDING_APPROVAL",
            "revision",
            1,
            "actorId",
            ACTOR));
  }

  private EventDelivery exceptionEvent(Instant occurredAt, String subjectNumber, String severity)
      throws Exception {
    UUID exceptionId = UUID.randomUUID();
    return event(
        TENANT,
        UUID.randomUUID(),
        "cellarbridge.exception.opened.v1",
        occurredAt,
        "EXCEPTION",
        exceptionId,
        subjectNumber,
        Map.of("status", "OPEN", "severity", severity, "actorId", ACTOR));
  }

  private EventDelivery draftEvent(TenantId tenant, Instant occurredAt, String subjectNumber)
      throws Exception {
    return draftEvent(tenant, UUID.randomUUID(), occurredAt, subjectNumber, 1);
  }

  private EventDelivery draftEvent(
      TenantId tenant, UUID quotationId, Instant occurredAt, String subjectNumber, int revision)
      throws Exception {
    return event(
        tenant,
        UUID.randomUUID(),
        "cellarbridge.quotation.draft-created.v1",
        occurredAt,
        "QUOTATION",
        quotationId,
        subjectNumber,
        Map.of("status", "DRAFT", "revision", revision, "actorId", ACTOR));
  }

  private EventDelivery event(
      TenantId tenant,
      UUID eventId,
      String type,
      Instant occurredAt,
      String subjectType,
      UUID subjectId,
      String number,
      Map<String, Object> payload)
      throws Exception {
    return new EventDelivery(
        eventId,
        tenant.value(),
        type,
        1,
        occurredAt,
        type.substring("cellarbridge.".length(), type.indexOf('.', "cellarbridge.".length())),
        new EventDelivery.Subject(subjectType, subjectId, number),
        UUID.nameUUIDFromBytes(("correlation|" + subjectId).getBytes(StandardCharsets.UTF_8)),
        ACTOR,
        json.writeValueAsString(payload));
  }

  private String inboxStatus(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT status FROM audit_reporting.projector_inbox WHERE event_id = ?",
        String.class,
        eventId);
  }

  private long activeMetricCount(TenantId tenantId, UUID subjectId) {
    Long value =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM audit_reporting.metric_fact_projection metric
            JOIN audit_reporting.projection_generation generation
              ON generation.tenant_id = metric.tenant_id
             AND generation.generation = metric.generation
             AND generation.status = 'ACTIVE'
            WHERE metric.tenant_id = ? AND metric.subject_id = ?
            """,
            Long.class,
            tenantId.value(),
            subjectId);
    return value == null ? 0 : value;
  }

  private AuditPage auditPage(AuditFilter filter, String cursor) {
    return service.boundedAudit(
        new AuditQuery(
            filter.subjectType(),
            filter.subjectId(),
            filter.correlationId(),
            filter.actorId(),
            filter.action(),
            filter.from(),
            filter.to(),
            1,
            cursor));
  }

  private void assertAuditCursorRejected(String cursor, AuditFilter filter) {
    assertCursorRejected(() -> auditPage(filter, cursor));
  }

  private record AuditFilter(
      String subjectType,
      UUID subjectId,
      UUID correlationId,
      UUID actorId,
      String action,
      Instant from,
      Instant to) {}

  private static void assertCursorRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query");
  }

  private static TenantContext admin(TenantId tenantId) {
    return context(
        tenantId,
        ACTOR,
        "Tenant Administrator",
        "tenant-administrator",
        Set.of(
            PermissionCode.REPORTING_READ,
            PermissionCode.AUDIT_READ,
            PermissionCode.QUOTATION_READ,
            PermissionCode.ADMINISTRATION_MANAGE_ACCESS),
        "reporting-subject");
  }

  private static TenantContext sales(TenantId tenantId) {
    return context(
        tenantId,
        ACTOR,
        "Sales Representative",
        "sales-representative",
        Set.of(PermissionCode.REPORTING_READ, PermissionCode.AUDIT_READ),
        "reporting-sales-subject");
  }

  private static TenantContext quotationReader(TenantId tenantId) {
    return context(
        tenantId,
        ACTOR,
        "Sales Representative",
        "sales-representative",
        Set.of(PermissionCode.QUOTATION_READ),
        "reporting-sales-subject");
  }

  private static TenantContext timelineReader(TenantId tenantId) {
    return context(
        tenantId,
        ACTOR,
        "Timeline Reader",
        "timeline-reader",
        Set.of(PermissionCode.PARTNER_READ, PermissionCode.QUOTATION_READ),
        "reporting-timeline-subject");
  }

  private static TenantContext systemOperator(TenantId tenantId) {
    return context(
        tenantId,
        ACTOR,
        "System Operator",
        "system-operator",
        Set.of(PermissionCode.AUDIT_READ),
        "reporting-operator-subject");
  }

  private static TenantContext manager(
      TenantId tenantId, UUID userId, Set<PermissionCode> permissions) {
    return context(
        tenantId,
        userId,
        "Sales Manager",
        "sales-manager",
        permissions,
        "reporting-manager-subject");
  }

  private static TenantContext context(
      TenantId tenantId,
      UUID userId,
      String roleName,
      String roleCode,
      Set<PermissionCode> permissions,
      String subject) {
    return new TenantContext(
        userId,
        roleName,
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        null,
        Set.of(roleName),
        Set.of(roleCode),
        permissions,
        subject,
        "reporting-tenant");
  }
}
