package com.rom.cellarbridge.auditreporting.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.AuditPage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.DashboardRecord;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

  private static TenantContext admin(TenantId tenantId) {
    return new TenantContext(
        ACTOR,
        "Tenant Administrator",
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        null,
        Set.of("Tenant Administrator"),
        Set.of("tenant-administrator"),
        Set.of(
            PermissionCode.REPORTING_READ,
            PermissionCode.AUDIT_READ,
            PermissionCode.ADMINISTRATION_MANAGE_ACCESS),
        "reporting-subject",
        "reporting-tenant");
  }

  private static TenantContext sales(TenantId tenantId) {
    return new TenantContext(
        ACTOR,
        "Sales Representative",
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        null,
        Set.of("Sales Representative"),
        Set.of("sales-representative"),
        Set.of(PermissionCode.REPORTING_READ, PermissionCode.AUDIT_READ),
        "reporting-sales-subject",
        "reporting-tenant");
  }

  private static TenantContext quotationReader(TenantId tenantId) {
    return new TenantContext(
        ACTOR,
        "Sales Representative",
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        null,
        Set.of("Sales Representative"),
        Set.of("sales-representative"),
        Set.of(PermissionCode.QUOTATION_READ),
        "reporting-sales-subject",
        "reporting-tenant");
  }
}
