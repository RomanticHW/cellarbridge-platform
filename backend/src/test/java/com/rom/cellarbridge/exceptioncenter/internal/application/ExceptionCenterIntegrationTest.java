package com.rom.cellarbridge.exceptioncenter.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.exceptioncenter.ExceptionStatus;
import com.rom.cellarbridge.exceptioncenter.RecoveryAction;
import com.rom.cellarbridge.fulfillment.FulfillmentRecoveryOperations;
import com.rom.cellarbridge.fulfillment.FulfillmentStatus;
import com.rom.cellarbridge.fulfillment.FulfillmentStepFailedV1;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStatus;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(classes = ExceptionCenterIntegrationTest.RecoveryConfiguration.class)
class ExceptionCenterIntegrationTest extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final UUID TRADE_ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000005");
  private static final UUID SYSTEM_ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000007");

  @Autowired private FulfillmentStepFailedExceptionHandler failedHandler;
  @Autowired private FailedDeliveryExceptionScanner failedDeliveryScanner;
  @Autowired private ExceptionCaseService service;
  @Autowired private TenantContextHolder contexts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;
  @Autowired private ReliableEventPublisher publisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private StubFulfillmentRecovery recovery;

  @BeforeEach
  void resetRecovery() {
    recovery.reset();
  }

  @Test
  void duplicateSourceEventsCreateOneActiveCaseAndAppendDistinctEvidenceOnce() {
    UUID stepId = UUID.randomUUID();
    failedHandler.handle(failedDelivery(UUID.randomUUID(), stepId));
    failedHandler.handle(failedDelivery(UUID.randomUUID(), stepId));
    EventDelivery exactReplay = failedDelivery(UUID.randomUUID(), stepId);
    failedHandler.handle(exactReplay);
    failedHandler.handle(exactReplay);

    UUID caseId = caseId(TENANT, stepId);
    assertThat(count("exception_center.exception_case", "source_id", stepId)).isEqualTo(1);
    assertThat(count("exception_center.case_occurrence", "case_id", caseId)).isEqualTo(3);
    assertThat(count("exception_center.work_item", "case_id", caseId)).isEqualTo(1);
    assertThat(count("exception_center.notification_fact", "case_id", caseId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform_event.event_publication WHERE tenant_id = ? AND subject_id = ? AND event_type = 'cellarbridge.exception.opened.v1'",
                Integer.class,
                TENANT.value(),
                caseId))
        .isEqualTo(1);
  }

  @Test
  void lifecycleEnforcesTenantVersionAndTechnicalFieldBoundaries() {
    UUID businessStep = UUID.randomUUID();
    failedHandler.handle(failedDelivery(UUID.randomUUID(), businessStep));
    UUID businessCase = caseId(TENANT, businessStep);

    try (TenantContextHolder.Scope ignored = contexts.open(systemOperator(TENANT))) {
      assertThat(service.list(Set.of(), null, null, null, null, 100, null).items())
          .allMatch(item -> item.category().name().equals("EVENT_DELIVERY_FAILED"))
          .noneMatch(item -> item.id().equals(businessCase));
      assertThatThrownBy(() -> service.get(businessCase))
          .isInstanceOfSatisfying(
              ExceptionProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));
      assertThatThrownBy(
              () -> service.acknowledge(businessCase, 0, "Review the commercial failure"))
          .isInstanceOfSatisfying(
              ExceptionProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(OTHER_TENANT))) {
      assertThatThrownBy(() -> service.get(businessCase))
          .isInstanceOfSatisfying(
              ExceptionProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
      assertThatThrownBy(
              () -> service.assign(businessCase, 0, null, "Reject an empty assignee identifier"))
          .isInstanceOfSatisfying(
              ExceptionProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("VALIDATION_FAILED"));
      var assigned = service.assign(businessCase, 0, TRADE_ACTOR, "Trade desk accepted ownership");
      assertThat(assigned.summary().status()).isEqualTo(ExceptionStatus.ASSIGNED);
      assertThatThrownBy(() -> service.acknowledge(businessCase, 0, "Use a stale browser version"))
          .isInstanceOfSatisfying(
              ExceptionProblem.class,
              problem -> {
                assertThat(problem.code()).isEqualTo("RESOURCE_VERSION_CONFLICT");
                assertThat(problem.currentVersion()).isEqualTo(1);
              });
      var acknowledged =
          service.acknowledge(
              businessCase, assigned.summary().version(), "Evidence has been reviewed");
      var investigating =
          service.begin(
              businessCase,
              acknowledged.summary().version(),
              "Recovery prerequisites are confirmed");
      assertThat(investigating.summary().status()).isEqualTo(ExceptionStatus.IN_PROGRESS);
    }
  }

  @Test
  void quickClosurePersistsPrimaryCaseAndTenantConstraintsRejectMismatchedFacts() {
    UUID primaryStep = UUID.randomUUID();
    UUID duplicateStep = UUID.randomUUID();
    UUID falsePositiveStep = UUID.randomUUID();
    failedHandler.handle(failedDelivery(UUID.randomUUID(), primaryStep));
    failedHandler.handle(failedDelivery(UUID.randomUUID(), duplicateStep));
    failedHandler.handle(failedDelivery(UUID.randomUUID(), falsePositiveStep));
    UUID primaryCase = caseId(TENANT, primaryStep);
    UUID duplicateCase = caseId(TENANT, duplicateStep);
    UUID falsePositiveCase = caseId(TENANT, falsePositiveStep);

    try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
      assertThatThrownBy(
              () ->
                  service.close(
                      duplicateCase,
                      0,
                      "UNREVIEWED",
                      "Reject an unsupported closure outcome",
                      null))
          .isInstanceOfSatisfying(
              ExceptionProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("VALIDATION_FAILED"));
      assertThatThrownBy(
              () ->
                  service.close(
                      duplicateCase,
                      0,
                      "DUPLICATE",
                      "Reject a case linked to itself",
                      duplicateCase))
          .isInstanceOfSatisfying(
              ExceptionProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("VALIDATION_FAILED"));

      var duplicate =
          service.close(
              duplicateCase,
              0,
              "DUPLICATE",
              "The retained case contains the authoritative source evidence",
              primaryCase);
      assertThat(duplicate.summary().status()).isEqualTo(ExceptionStatus.CLOSED);
      assertThat(duplicate.summary().primaryCaseId()).isEqualTo(primaryCase);
      assertThat(
              jdbc.queryForObject(
                  "SELECT primary_case_id FROM exception_center.exception_case WHERE tenant_id = ? AND id = ?",
                  UUID.class,
                  TENANT.value(),
                  duplicateCase))
          .isEqualTo(primaryCase);

      var falsePositive =
          service.close(
              falsePositiveCase,
              0,
              "FALSE_POSITIVE",
              "The source evidence does not represent an operational failure",
              null);
      assertThat(falsePositive.summary().status()).isEqualTo(ExceptionStatus.CLOSED);
      assertThat(falsePositive.summary().primaryCaseId()).isNull();
    }

    Instant at = Instant.parse("2026-07-21T22:00:00Z");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO exception_center.work_item
                      (id, tenant_id, case_id, status, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'OPEN', ?, ?, 0)
                    """,
                    UUID.randomUUID(),
                    OTHER_TENANT.value(),
                    primaryCase,
                    Timestamp.from(at),
                    Timestamp.from(at)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void verifiedRecoveryIsIdempotentAndFailureFactsSurviveSourceRollback() {
    UUID successfulStep = UUID.randomUUID();
    failedHandler.handle(failedDelivery(UUID.randomUUID(), successfulStep));
    UUID successfulCase = caseId(TENANT, successfulStep);

    try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
      long recoveryVersion = begin(successfulCase).summary().version();
      String key = "retry-fulfillment-step-success-0001";
      var recovered =
          service.recover(
              successfulCase,
              recoveryVersion,
              key,
              RecoveryAction.RETRY_FULFILLMENT_STEP,
              "Retry after the route dependency recovered",
              Map.of());
      assertThat(recovered.status()).isEqualTo("SUCCEEDED");
      assertThat(recovered.caseStatus()).isEqualTo(ExceptionStatus.RESOLVED);
      assertThat(recovered.resultCode()).isEqualTo("SOURCE_STATE_VERIFIED");

      var replay =
          service.recover(
              successfulCase,
              recoveryVersion,
              key,
              RecoveryAction.RETRY_FULFILLMENT_STEP,
              "Retry after the route dependency recovered",
              Map.of());
      assertThat(replay.replayed()).isTrue();
      assertThat(replay.attemptId()).isEqualTo(recovered.attemptId());
      assertThat(recovery.calls()).isEqualTo(1);
      assertThat(count("exception_center.recovery_attempt", "case_id", successfulCase))
          .isEqualTo(1);
      assertThat(count("exception_center.recovery_outcome", "attempt_id", recovered.attemptId()))
          .isEqualTo(1);

      var closed =
          service.close(
              successfulCase,
              replay.version(),
              "RECOVERY_VERIFIED",
              "Source state and recovery evidence were reviewed",
              null);
      assertThat(closed.summary().status()).isEqualTo(ExceptionStatus.CLOSED);
    }

    UUID failedStep = UUID.randomUUID();
    failedHandler.handle(failedDelivery(UUID.randomUUID(), failedStep));
    UUID failedCase = caseId(TENANT, failedStep);
    recovery.failWith(new IllegalStateException("simulated source rollback"));
    try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
      long recoveryVersion = begin(failedCase).summary().version();
      var failed =
          service.recover(
              failedCase,
              recoveryVersion,
              "retry-fulfillment-step-failed-0001",
              RecoveryAction.RETRY_FULFILLMENT_STEP,
              "Retry the failed source transaction once",
              Map.of());
      assertThat(failed.status()).isEqualTo("FAILED");
      assertThat(failed.caseStatus()).isEqualTo(ExceptionStatus.IN_PROGRESS);
      assertThat(failed.resultCode()).isEqualTo("RECOVERY_EXECUTION_FAILED");
      assertThat(count("exception_center.recovery_attempt", "case_id", failedCase)).isEqualTo(1);
      assertThat(count("exception_center.recovery_outcome", "attempt_id", failed.attemptId()))
          .isEqualTo(1);
    }
  }

  @Test
  void failedRecoveriesEscalateAtTheBoundAndRejectAnUnboundedFourthAttempt() {
    UUID stepId = UUID.randomUUID();
    failedHandler.handle(failedDelivery(UUID.randomUUID(), stepId));
    UUID exceptionCase = caseId(TENANT, stepId);
    recovery.failWith(new IllegalStateException("simulated persistent source failure"));

    try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
      var detail = begin(exceptionCase);
      for (int attempt = 1; attempt <= 3; attempt++) {
        var failed =
            service.recover(
                exceptionCase,
                detail.summary().version(),
                "bounded-fulfillment-retry-000" + attempt,
                RecoveryAction.RETRY_FULFILLMENT_STEP,
                "Record bounded source retry attempt " + attempt,
                Map.of());
        assertThat(failed.status()).isEqualTo("FAILED");
        detail = service.get(exceptionCase);
      }

      assertThat(detail.summary().severity().name()).isEqualTo("CRITICAL");
      assertThat(detail.summary().status()).isEqualTo(ExceptionStatus.IN_PROGRESS);
      assertThat(count("exception_center.recovery_attempt", "case_id", exceptionCase)).isEqualTo(3);
      assertThat(count("exception_center.notification_fact", "case_id", exceptionCase))
          .isEqualTo(2);
      long currentVersion = detail.summary().version();
      assertThatThrownBy(
              () ->
                  service.recover(
                      exceptionCase,
                      currentVersion,
                      "bounded-fulfillment-retry-0004",
                      RecoveryAction.RETRY_FULFILLMENT_STEP,
                      "Reject the unbounded fourth retry attempt",
                      Map.of()))
          .isInstanceOfSatisfying(
              ExceptionProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("EXCEPTION_RECOVERY_NOT_ALLOWED"));
      assertThat(count("exception_center.recovery_attempt", "case_id", exceptionCase)).isEqualTo(3);
    }
  }

  @Test
  void concurrentIdempotentRecoveryHasOneSourceExecutor() throws Exception {
    UUID stepId = UUID.randomUUID();
    failedHandler.handle(failedDelivery(UUID.randomUUID(), stepId));
    UUID exceptionCase = caseId(TENANT, stepId);
    long version;
    try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
      version = begin(exceptionCase).summary().version();
    }
    recovery.blockSource();
    String key = "concurrent-recovery-idempotency-0001";
    String reason = "Execute the source recovery once under concurrency";

    try (var executor = Executors.newSingleThreadExecutor()) {
      var first =
          executor.submit(
              () -> {
                try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
                  return service.recover(
                      exceptionCase,
                      version,
                      key,
                      RecoveryAction.RETRY_FULFILLMENT_STEP,
                      reason,
                      Map.of());
                }
              });
      recovery.awaitSourceCall();
      try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
        assertThatThrownBy(
                () ->
                    service.recover(
                        exceptionCase,
                        version,
                        key,
                        RecoveryAction.RETRY_FULFILLMENT_STEP,
                        reason,
                        Map.of()))
            .isInstanceOfSatisfying(
                ExceptionProblem.class,
                problem -> assertThat(problem.code()).isEqualTo("EXCEPTION_RECOVERY_IN_PROGRESS"));
      }
      recovery.releaseSource();
      assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
    }
    assertThat(recovery.calls()).isEqualTo(1);
    assertThat(count("exception_center.recovery_attempt", "case_id", exceptionCase)).isEqualTo(1);
  }

  @Test
  void finalDeliveryScanningAndReplayPreserveTheInboxAndMaskedPayload() {
    UUID eventId = publishFailedDelivery();
    String originalPayload =
        jdbc.queryForObject(
            "SELECT payload::text FROM platform_event.event_publication WHERE event_id = ?",
            String.class,
            eventId);

    failedDeliveryScanner.scan();
    failedDeliveryScanner.scan();
    UUID technicalCase =
        jdbc.queryForObject(
            "SELECT id FROM exception_center.exception_case WHERE tenant_id = ? AND source_id = ?",
            UUID.class,
            TENANT.value(),
            eventId);

    try (TenantContextHolder.Scope ignored = contexts.open(systemOperator(TENANT))) {
      var page = service.failedDeliveries(100, null);
      assertThat(page.items()).hasSize(1);
      assertThat(page.items().getFirst().eventId()).isEqualTo(eventId);
      assertThat(json.writeValueAsString(page))
          .doesNotContain("commercial-secret", "subjectNumber", "producer", "tenantId");
      assertThat(service.list(Set.of(), null, null, null, null, 100, null).items())
          .extracting("id")
          .containsExactly(technicalCase);

      long recoveryVersion = begin(technicalCase).summary().version();
      var replayed =
          service.recover(
              technicalCase,
              recoveryVersion,
              "replay-final-publication-0001",
              RecoveryAction.REPLAY_PUBLICATION,
              "Replay the immutable delivery after operator review",
              Map.of());
      assertThat(replayed.status()).isEqualTo("SUCCEEDED");
      assertThat(replayed.resultCode()).isEqualTo("REPLAY_SCHEDULED");
    } catch (JacksonException exception) {
      throw new AssertionError(exception);
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM platform_event.event_inbox WHERE event_id = ? AND consumer_name = 'exception-test-consumer'",
                String.class,
                eventId))
        .isEqualTo("FAILED_RETRYABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform_event.event_inbox WHERE event_id = ?",
                Integer.class,
                eventId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM platform_event.event_publication WHERE event_id = ?",
                String.class,
                eventId))
        .isEqualTo(originalPayload);
    assertThat(count("exception_center.case_occurrence", "case_id", technicalCase)).isEqualTo(1);
    assertThat(count("exception_center.notification_fact", "case_id", technicalCase)).isEqualTo(1);
  }

  private ExceptionCaseService.DetailView begin(UUID caseId) {
    var acknowledged = service.acknowledge(caseId, 0, "Evidence has been reviewed");
    return service.begin(
        caseId, acknowledged.summary().version(), "Recovery prerequisites are confirmed");
  }

  private EventDelivery failedDelivery(UUID eventId, UUID stepId) {
    Instant failedAt = Instant.parse("2026-07-21T20:00:00Z");
    UUID planId = UUID.nameUUIDFromBytes(("plan-" + stepId).getBytes(StandardCharsets.UTF_8));
    FulfillmentStepFailedV1.Payload payload =
        new FulfillmentStepFailedV1.Payload(
            planId,
            "FP-TEST-001",
            UUID.randomUUID(),
            "ORD-TEST-001",
            stepId,
            "CUSTOMS_CLEARANCE",
            "ADAPTER_TIMEOUT",
            "The route adapter did not confirm completion.",
            failedAt,
            2,
            true);
    try {
      return new EventDelivery(
          eventId,
          TENANT.value(),
          FulfillmentStepFailedV1.TYPE,
          1,
          failedAt,
          "fulfillment",
          new EventDelivery.Subject("FULFILLMENT_PLAN", planId, payload.planNumber()),
          UUID.randomUUID(),
          UUID.randomUUID(),
          json.writeValueAsString(payload));
    } catch (JacksonException exception) {
      throw new AssertionError(exception);
    }
  }

  private UUID publishFailedDelivery() {
    UUID eventId = UUID.randomUUID();
    Instant at = Instant.parse("2026-07-21T21:00:00Z");
    PendingEvent event =
        new PendingEvent(
            eventId,
            TENANT.value(),
            "cellarbridge.test.delivery.v1",
            1,
            at,
            "exception-test",
            new PendingEvent.Subject("TRADE_ORDER", UUID.randomUUID(), "ORD-COMMERCIAL-SECRET"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Map.of("commercialNote", "commercial-secret"),
            Map.of());
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(ignored -> publisher.publish(event));
    String payloadHash = sha256("failed-delivery-test");
    jdbc.update(
        """
        INSERT INTO platform_event.event_inbox
          (tenant_id, consumer_name, event_id, event_type, payload_hash, status,
           attempts, duplicate_count, last_error_code, first_received_at, last_attempt_at,
           created_at, updated_at, version)
        VALUES (?, 'exception-test-consumer', ?, ?, ?, 'FAILED_FINAL',
                3, 0, 'HANDLER_REJECTED', ?, ?, ?, ?, 0)
        """,
        TENANT.value(),
        eventId,
        event.eventType(),
        payloadHash,
        Timestamp.from(at),
        Timestamp.from(at),
        Timestamp.from(at),
        Timestamp.from(at));
    return eventId;
  }

  private UUID caseId(TenantId tenantId, UUID sourceId) {
    return jdbc.queryForObject(
        "SELECT id FROM exception_center.exception_case WHERE tenant_id = ? AND source_id = ?",
        UUID.class,
        tenantId.value(),
        sourceId);
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static TenantContext tradeOperator(TenantId tenantId) {
    return context(
        TRADE_ACTOR,
        tenantId,
        Set.of("trade-operator"),
        Set.of(
            PermissionCode.EXCEPTION_READ,
            PermissionCode.EXCEPTION_ASSIGN,
            PermissionCode.EXCEPTION_RECOVER));
  }

  private static TenantContext systemOperator(TenantId tenantId) {
    return context(
        SYSTEM_ACTOR,
        tenantId,
        Set.of("system-operator"),
        Set.of(
            PermissionCode.EXCEPTION_READ,
            PermissionCode.EXCEPTION_RECOVER,
            PermissionCode.EVENT_PUBLICATION_READ,
            PermissionCode.EVENT_PUBLICATION_REPLAY));
  }

  private static TenantContext context(
      UUID userId, TenantId tenantId, Set<String> roleCodes, Set<PermissionCode> permissions) {
    return new TenantContext(
        userId,
        "Synthetic Operator",
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        null,
        roleCodes,
        roleCodes,
        permissions,
        "subject-exception-test",
        "tenant-exception-test");
  }

  @TestConfiguration
  static class RecoveryConfiguration {
    @Bean
    @Primary
    StubFulfillmentRecovery fulfillmentRecoveryOperations() {
      return new StubFulfillmentRecovery();
    }
  }

  static final class StubFulfillmentRecovery implements FulfillmentRecoveryOperations {
    private final AtomicInteger calls = new AtomicInteger();
    private RuntimeException failure;
    private CountDownLatch sourceEntered;
    private CountDownLatch sourceRelease;

    @Override
    public RecoveryResult retryFailedStep(
        UUID planId, UUID stepId, String idempotencyKey, String reason) {
      calls.incrementAndGet();
      awaitRelease();
      if (failure != null) throw failure;
      return new RecoveryResult(
          planId, stepId, FulfillmentStepStatus.READY, FulfillmentStatus.IN_PROGRESS, 13, false);
    }

    @Override
    public RecoveryResult resumeOverdueStep(
        UUID planId, UUID stepId, String idempotencyKey, String reason) {
      calls.incrementAndGet();
      awaitRelease();
      if (failure != null) throw failure;
      return new RecoveryResult(
          planId, stepId, FulfillmentStepStatus.READY, FulfillmentStatus.IN_PROGRESS, 13, false);
    }

    int calls() {
      return calls.get();
    }

    void failWith(RuntimeException value) {
      failure = value;
    }

    void blockSource() {
      sourceEntered = new CountDownLatch(1);
      sourceRelease = new CountDownLatch(1);
    }

    void awaitSourceCall() throws InterruptedException {
      if (sourceEntered == null || !sourceEntered.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Source recovery was not called");
      }
    }

    void releaseSource() {
      if (sourceRelease != null) sourceRelease.countDown();
    }

    private void awaitRelease() {
      if (sourceEntered == null || sourceRelease == null) return;
      sourceEntered.countDown();
      try {
        if (!sourceRelease.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to release source recovery");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Source recovery was interrupted", exception);
      }
    }

    void reset() {
      calls.set(0);
      failure = null;
      sourceEntered = null;
      sourceRelease = null;
    }
  }
}
