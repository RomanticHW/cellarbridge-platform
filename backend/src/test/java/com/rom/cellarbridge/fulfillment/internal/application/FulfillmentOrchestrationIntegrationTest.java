package com.rom.cellarbridge.fulfillment.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.fulfillment.FulfillmentCompletedV1;
import com.rom.cellarbridge.fulfillment.FulfillmentStatus;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStatus;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.Action;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.ActionResult;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.DetailView;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventoryReservationConfirmedV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FulfillmentOrchestrationIntegrationTest extends PostgresIntegrationTestSupport {
  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final String NORTH_ADMIN = "fulfillment-north-admin-token";
  private static final String NORTH_BUYER = "fulfillment-north-buyer-token";

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private InventoryReservationConfirmedFulfillmentHandler handler;
  @Autowired private FulfillmentPlanService service;
  @Autowired private FulfillmentSlaService sla;
  @Autowired private TenantContextHolder contexts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  @Test
  void exposesFulfillmentRoutesOnlyToAuthenticatedInternalReaders() throws Exception {
    UUID orderId = UUID.randomUUID();
    handler.handle(confirmed(orderId, "SH_GENERAL_TRADE"));

    assertThat(get("/api/v1/fulfillment/plans?pageSize=100", NORTH_ADMIN).statusCode())
        .isEqualTo(200);
    assertThat(get("/api/v1/fulfillment/plans", NORTH_BUYER).statusCode()).isEqualTo(403);
    assertThat(get("/api/v1/fulfillment/plans", null).statusCode()).isEqualTo(401);
  }

  @Test
  void snapshotsTheRouteTemplateAndCompletesAllRequiredStepsWithReplaySafety() {
    UUID orderId = UUID.randomUUID();
    EventDelivery delivery = confirmed(orderId, "NB_BONDED_B2B");

    handler.handle(delivery);
    handler.handle(delivery);

    UUID planId = planId(orderId);
    assertThat(countPlans(orderId)).isEqualTo(1);
    assertThat(templateVersion(planId)).isEqualTo("2026.1");
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      DetailView detail = service.get(planId);
      assertThat(detail.steps()).hasSize(5);
      assertThat(detail.steps().getFirst().status()).isEqualTo(FulfillmentStepStatus.READY);
      assertThat(detail.steps().subList(1, 5))
          .allMatch(step -> step.status() == FulfillmentStepStatus.BLOCKED);
      assertThat(detail.steps().getFirst().plannedStartAt()).isEqualTo(createdAt(planId));
      assertThat(
              detail.steps().stream()
                  .map(step -> Duration.between(step.plannedStartAt(), step.dueAt()).toMinutes())
                  .toList())
          .containsExactly(60L, 480L, 240L, 240L, 1440L);
      for (int index = 1; index < detail.steps().size(); index++) {
        assertThat(detail.steps().get(index).plannedStartAt())
            .isEqualTo(detail.steps().get(index - 1).dueAt());
      }
      assertThat(templateSnapshot(planId))
          .contains("\"routeCode\": \"NB_BONDED_B2B\"")
          .contains("\"version\": \"2026.1\"");
      var blocked = detail.steps().get(1);
      assertThatThrownBy(
              () ->
                  service.act(
                      planId,
                      blocked.id(),
                      detail.summary().version(),
                      key("blocked", "start"),
                      Action.START,
                      null,
                      null))
          .isInstanceOfSatisfying(
              FulfillmentProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("INVALID_STATE_TRANSITION"));

      assertThat(
              service
                  .list(Set.of(), null, "WAREHOUSE_OPERATOR", orderId, 100, null)
                  .items()
                  .stream()
                  .map(item -> item.id()))
          .contains(planId);

      for (var original : detail.steps()) {
        DetailView beforeStart = service.get(planId);
        var step =
            beforeStart.steps().stream()
                .filter(item -> item.id().equals(original.id()))
                .findFirst()
                .orElseThrow();
        ActionResult started =
            service.act(
                planId,
                step.id(),
                beforeStart.summary().version(),
                key(step.code(), "start"),
                Action.START,
                null,
                null);
        ActionResult replay =
            service.act(
                planId,
                step.id(),
                beforeStart.summary().version(),
                key(step.code(), "start"),
                Action.START,
                null,
                null);
        assertThat(started.stepStatus()).isEqualTo(FulfillmentStepStatus.IN_PROGRESS);
        assertThat(replay.replayed()).isTrue();
        DetailView beforeComplete = service.get(planId);
        String completeKey = key(step.code(), "complete");
        ActionResult completedStep =
            service.act(
                planId,
                step.id(),
                beforeComplete.summary().version(),
                completeKey,
                Action.COMPLETE,
                null,
                "SUCCESS");
        ActionResult completedReplay =
            service.act(
                planId,
                step.id(),
                beforeComplete.summary().version(),
                completeKey,
                Action.COMPLETE,
                null,
                "SUCCESS");
        assertThat(completedStep.replayed()).isFalse();
        assertThat(completedReplay.replayed()).isTrue();
      }

      DetailView completed = service.get(planId);
      assertThat(completed.summary().status()).isEqualTo(FulfillmentStatus.COMPLETED);
      assertThat(completed.steps())
          .allMatch(step -> step.status() == FulfillmentStepStatus.COMPLETED);
      assertThat(completed.milestones())
          .extracting("code")
          .containsExactly("ORDER_CONFIRMATION", "DISPATCH", "SIGNED_DELIVERY");
      assertThatThrownBy(
              () ->
                  service.act(
                      planId,
                      completed.steps().getFirst().id(),
                      completed.summary().version() - 1,
                      key("stale", "version"),
                      Action.START,
                      null,
                      null))
          .isInstanceOfSatisfying(
              FulfillmentProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("OPTIMISTIC_VERSION_CONFLICT"));
    }
    assertThat(publicationCount(planId, FulfillmentCompletedV1.TYPE)).isEqualTo(1);
    assertThat(adapterAttempts(planId)).isEqualTo(5);
  }

  @Test
  void acknowledgesPreRouteV1ConfirmationWithoutInventingARetroactivePlan() {
    UUID orderId = UUID.randomUUID();

    assertThat(handler.handle(confirmed(orderId, null))).isEqualTo(EventHandlingResult.processed());
    assertThat(countPlans(orderId)).isZero();
  }

  @Test
  void recordsDeterministicDelayAndFailureAndMarksSlaOnlyOnce() {
    UUID orderId = UUID.randomUUID();
    handler.handle(confirmed(orderId, "SH_GENERAL_TRADE"));
    UUID planId = planId(orderId);

    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      DetailView plan = service.get(planId);
      var step = plan.steps().getFirst();
      service.act(
          planId,
          step.id(),
          plan.summary().version(),
          key("adapter", "start"),
          Action.START,
          null,
          null);
      plan = service.get(planId);
      ActionResult delayed =
          service.act(
              planId,
              step.id(),
              plan.summary().version(),
              key("adapter", "delay"),
              Action.COMPLETE,
              null,
              "DELAY");
      assertThat(delayed.stepStatus()).isEqualTo(FulfillmentStepStatus.IN_PROGRESS);
      plan = service.get(planId);
      ActionResult failed =
          service.act(
              planId,
              step.id(),
              plan.summary().version(),
              key("adapter", "failure"),
              Action.COMPLETE,
              null,
              "FAILURE");
      assertThat(failed.stepStatus()).isEqualTo(FulfillmentStepStatus.FAILED);
      assertThat(service.get(planId).steps().getFirst().latestAdapterAttempt().outcome())
          .isEqualTo("FAILED");
    }

    UUID overdueOrder = UUID.randomUUID();
    handler.handle(confirmed(overdueOrder, "HK_FREE_TRADE"));
    UUID overduePlan = planId(overdueOrder);
    UUID overdueStep = firstStep(overduePlan);
    Instant past = Instant.now().minusSeconds(7200);
    jdbc.update(
        "UPDATE fulfillment.fulfillment_step SET planned_start_at = ?, due_at = ? WHERE tenant_id = ? AND id = ?",
        Timestamp.from(past.minusSeconds(60)),
        Timestamp.from(past),
        TENANT.value(),
        overdueStep);
    assertThat(sla.markOverdue(20)).isEqualTo(1);
    assertThat(sla.markOverdue(20)).isZero();
    assertThat(stepStatus(overdueStep)).isEqualTo("OVERDUE");
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      ActionResult resumed =
          service.resumeOverdue(
              overduePlan,
              overdueStep,
              key("overdue", "resume"),
              "Rebase the step window after operator review");
      ActionResult replayed =
          service.resumeOverdue(
              overduePlan,
              overdueStep,
              key("overdue", "resume"),
              "Rebase the step window after operator review");
      assertThat(resumed.stepStatus()).isEqualTo(FulfillmentStepStatus.READY);
      assertThat(replayed.replayed()).isTrue();
    }
    assertThat(stepStatus(overdueStep)).isEqualTo("READY");
    assertThat(sla.markOverdue(20)).isZero();
  }

  @Test
  void isolatesTenantReadsAndRejectsIdempotencyKeyReuse() {
    UUID orderId = UUID.randomUUID();
    handler.handle(confirmed(orderId, "SH_GENERAL_TRADE"));
    UUID planId = planId(orderId);
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      DetailView plan = service.get(planId);
      var step = plan.steps().getFirst();
      String key = key("reuse", "same");
      service.act(planId, step.id(), plan.summary().version(), key, Action.START, null, null);
      assertThatThrownBy(
              () ->
                  service.act(
                      planId,
                      step.id(),
                      plan.summary().version(),
                      key,
                      Action.START,
                      "changed",
                      null))
          .isInstanceOfSatisfying(
              FulfillmentProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }
    try (TenantContextHolder.Scope ignored = contexts.open(operator(OTHER_TENANT))) {
      assertThatThrownBy(() -> service.get(planId))
          .isInstanceOfSatisfying(
              FulfillmentProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }
  }

  @Test
  void exposesActionsOnlyOnStepsOwnedByTheCurrentRole() {
    UUID orderId = UUID.randomUUID();
    handler.handle(confirmed(orderId, "SH_GENERAL_TRADE"));
    UUID planId = planId(orderId);

    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      DetailView plan = service.get(planId);
      var first = plan.steps().getFirst();
      service.act(
          planId,
          first.id(),
          plan.summary().version(),
          key("role", "start"),
          Action.START,
          null,
          null);
      plan = service.get(planId);
      service.act(
          planId,
          first.id(),
          plan.summary().version(),
          key("role", "complete"),
          Action.COMPLETE,
          null,
          "SUCCESS");
    }

    try (TenantContextHolder.Scope ignored = contexts.open(tradeOperator(TENANT))) {
      DetailView plan = service.get(planId);
      var warehouseStep = plan.steps().get(1);
      assertThat(warehouseStep.status()).isEqualTo(FulfillmentStepStatus.READY);
      assertThat(warehouseStep.allowedActions()).isEmpty();
      assertThat(plan.allowedActions()).isEmpty();
      assertThatThrownBy(
              () ->
                  service.act(
                      planId,
                      warehouseStep.id(),
                      plan.summary().version(),
                      key("role", "warehouse"),
                      Action.START,
                      null,
                      null))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
  }

  @Test
  void serializesConcurrentActionsAgainstThePlanVersion() throws Exception {
    UUID orderId = UUID.randomUUID();
    handler.handle(confirmed(orderId, "SH_GENERAL_TRADE"));
    UUID planId = planId(orderId);
    DetailView plan;
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      plan = service.get(planId);
    }
    UUID stepId = plan.steps().getFirst().id();
    long version = plan.summary().version();
    CyclicBarrier start = new CyclicBarrier(2);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> concurrentStart(planId, stepId, version, key("concurrent", "first"), start));
      var second =
          executor.submit(
              () -> concurrentStart(planId, stepId, version, key("concurrent", "second"), start));

      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("SUCCESS", "OPTIMISTIC_VERSION_CONFLICT");
    }
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      DetailView current = service.get(planId);
      assertThat(current.summary().version()).isEqualTo(version + 1);
      assertThat(current.steps().getFirst().status()).isEqualTo(FulfillmentStepStatus.IN_PROGRESS);
    }
  }

  private String concurrentStart(
      UUID planId, UUID stepId, long version, String operationKey, CyclicBarrier start)
      throws Exception {
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      start.await(10, TimeUnit.SECONDS);
      try {
        service.act(planId, stepId, version, operationKey, Action.START, null, null);
        return "SUCCESS";
      } catch (FulfillmentProblem problem) {
        return problem.code();
      }
    }
  }

  private EventDelivery confirmed(UUID orderId, String routeCode) {
    UUID eventId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    Instant now = Instant.now();
    var payload =
        new InventoryReservationConfirmedV1.Payload(
            reservationId,
            "RES-" + reservationId,
            orderId,
            "ORD-" + orderId,
            "a".repeat(64),
            "b".repeat(64),
            routeCode,
            now,
            List.of());
    try {
      return new EventDelivery(
          eventId,
          TENANT.value(),
          InventoryReservationConfirmedV1.TYPE,
          1,
          now,
          "inventory",
          new EventDelivery.Subject(
              "INVENTORY_RESERVATION", reservationId, payload.reservationNumber()),
          UUID.randomUUID(),
          UUID.randomUUID(),
          json.writeValueAsString(payload));
    } catch (JacksonException exception) {
      throw new AssertionError(exception);
    }
  }

  private static TenantContext operator(TenantId tenantId) {
    return new TenantContext(
        ACTOR,
        "Fulfillment Operator",
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        null,
        Set.of("Trade Operator", "Warehouse Operator"),
        Set.of("trade-operator", "warehouse-operator"),
        Set.of(PermissionCode.FULFILLMENT_READ, PermissionCode.FULFILLMENT_OPERATE),
        "subject-fulfillment",
        "tenant-fulfillment");
  }

  private static TenantContext tradeOperator(TenantId tenantId) {
    return new TenantContext(
        ACTOR,
        "Trade Operator",
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        null,
        Set.of("Trade Operator"),
        Set.of("trade-operator"),
        Set.of(PermissionCode.FULFILLMENT_READ, PermissionCode.FULFILLMENT_OPERATE),
        "subject-trade-operator",
        "tenant-fulfillment");
  }

  private UUID planId(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT id FROM fulfillment.fulfillment_plan WHERE tenant_id = ? AND order_id = ?",
        UUID.class,
        TENANT.value(),
        orderId);
  }

  private UUID firstStep(UUID planId) {
    return jdbc.queryForObject(
        "SELECT id FROM fulfillment.fulfillment_step WHERE tenant_id = ? AND plan_id = ? ORDER BY sequence_number LIMIT 1",
        UUID.class,
        TENANT.value(),
        planId);
  }

  private int countPlans(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM fulfillment.fulfillment_plan WHERE tenant_id = ? AND order_id = ?",
        Integer.class,
        TENANT.value(),
        orderId);
  }

  private String templateVersion(UUID planId) {
    return jdbc.queryForObject(
        "SELECT template_version FROM fulfillment.fulfillment_plan WHERE tenant_id = ? AND id = ?",
        String.class,
        TENANT.value(),
        planId);
  }

  private Instant createdAt(UUID planId) {
    return jdbc.queryForObject(
            "SELECT created_at FROM fulfillment.fulfillment_plan WHERE tenant_id = ? AND id = ?",
            Timestamp.class,
            TENANT.value(),
            planId)
        .toInstant();
  }

  private String templateSnapshot(UUID planId) {
    return jdbc.queryForObject(
        "SELECT template_snapshot::text FROM fulfillment.fulfillment_plan WHERE tenant_id = ? AND id = ?",
        String.class,
        TENANT.value(),
        planId);
  }

  private int publicationCount(UUID planId, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM platform_event.event_publication WHERE tenant_id = ? AND subject_id = ? AND event_type = ?",
        Integer.class,
        TENANT.value(),
        planId,
        type);
  }

  private int adapterAttempts(UUID planId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM fulfillment.simulated_adapter_attempt WHERE tenant_id = ? AND plan_id = ?",
        Integer.class,
        TENANT.value(),
        planId);
  }

  private String stepStatus(UUID stepId) {
    return jdbc.queryForObject(
        "SELECT status FROM fulfillment.fulfillment_step WHERE tenant_id = ? AND id = ?",
        String.class,
        TENANT.value(),
        stepId);
  }

  private static String key(String first, String second) {
    return "fulfillment-operation-" + first + "-" + second;
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Accept", "application/json")
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TokenConfiguration {
    @Bean
    @Primary
    JwtDecoder fulfillmentJwtDecoder() {
      return token ->
          switch (token) {
            case NORTH_ADMIN -> jwt(token, "11000000-0000-4000-8000-000000000004", "north-cellars");
            case NORTH_BUYER -> jwt(token, "11000000-0000-4000-8000-000000000002", "north-cellars");
            default -> throw new BadJwtException("Token is invalid");
          };
    }

    private static Jwt jwt(String token, String subject, String tenantCode) {
      Instant now = Instant.now();
      return Jwt.withTokenValue(token)
          .header("alg", "RS256")
          .issuer("http://localhost:8081/realms/cellarbridge")
          .subject(subject)
          .audience(List.of("cellarbridge-api"))
          .issuedAt(now.minusSeconds(30))
          .expiresAt(now.plusSeconds(300))
          .claim("tenant_code", tenantCode)
          .build();
    }
  }
}
