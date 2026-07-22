package com.rom.cellarbridge.settlement.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.fulfillment.FulfillmentCompletedV1;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.settlement.PaymentMethod;
import com.rom.cellarbridge.settlement.PaymentRecordedV1;
import com.rom.cellarbridge.settlement.PaymentReversedV1;
import com.rom.cellarbridge.settlement.ReceivableCreatedV1;
import com.rom.cellarbridge.settlement.ReceivableOverdueV1;
import com.rom.cellarbridge.settlement.ReceivablePaidV1;
import com.rom.cellarbridge.settlement.ReceivableStatus;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import com.rom.cellarbridge.tradeorder.TradeOrderCreatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class SettlementIntegrationTest extends PostgresIntegrationTestSupport {
  private static final Instant BASE = Instant.parse("2026-07-22T12:00:00Z");
  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final UUID PARTNER = UUID.fromString("30000000-0000-4000-8000-000000000001");

  @Autowired private TradeOrderCreatedSettlementHandler orderHandler;
  @Autowired private FulfillmentCompletedSettlementHandler fulfillmentHandler;
  @Autowired private SettlementService service;
  @Autowired private TenantContextHolder contexts;
  @Autowired private MutableClock clock;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  @BeforeEach
  void resetClock() {
    clock.set(BASE);
  }

  @Test
  void createsOneReceivableFromPublicFactsAndPreservesReplayEvidence() {
    Created created = createReceivable("125.5000", 30, BASE.minusSeconds(86_400));

    fulfillmentHandler.handle(created.fulfillmentDelivery());

    assertThat(receivableCount(created.orderId())).isEqualTo(1);
    assertThat(eventCount(created.receivableId(), ReceivableCreatedV1.TYPE)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT trigger_policy_code FROM settlement.receivable WHERE id = ?",
                String.class,
                created.receivableId()))
        .isEqualTo("DEMO-FULFILLMENT-COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT trigger_policy_version FROM settlement.receivable WHERE id = ?",
                Integer.class,
                created.receivableId()))
        .isEqualTo(1);
    try {
      jdbc.update("UPDATE settlement.receivable_trigger_policy SET active = false WHERE active");
      jdbc.update(
          """
          INSERT INTO settlement.receivable_trigger_policy
            (id, code, version, trigger_type, active, created_at, created_by)
          VALUES (?, 'NEXT-FULFILLMENT-COMPLETED', 2, 'FULFILLMENT_COMPLETED', true, ?, 'test')
          """,
          UUID.randomUUID(),
          java.sql.Timestamp.from(BASE));

      orderHandler.handle(created.orderDelivery());

      assertThat(
              jdbc.queryForObject(
                  "SELECT trigger_policy_version FROM settlement.order_snapshot WHERE order_id = ?",
                  Integer.class,
                  created.orderId()))
          .isEqualTo(1);
    } finally {
      jdbc.update("DELETE FROM settlement.receivable_trigger_policy WHERE version = 2");
      jdbc.update(
          "UPDATE settlement.receivable_trigger_policy SET active = true WHERE version = 1");
    }
  }

  @Test
  void supportsPartialAndFullPaymentsMultiplePartialReversalsAndImmutableFacts() {
    Created created = createReceivable("100.0000", 30, BASE.minusSeconds(86_400));

    try (TenantContextHolder.Scope ignored = contexts.open(finance(TENANT))) {
      var partial =
          service.recordPayment(
              created.receivableId(),
              0,
              key("partial"),
              new BigDecimal("40.0000"),
              "USD",
              LocalDate.ofInstant(BASE, ZoneOffset.UTC),
              PaymentMethod.BANK_TRANSFER,
              "BANK-SETTLEMENT-001",
              "First remittance");
      assertThat(partial.replayed()).isFalse();
      assertThat(partial.detail().summary().status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
      assertThat(partial.detail().summary().outstandingAmount().amount()).isEqualTo("60.0000");

      var replay =
          service.recordPayment(
              created.receivableId(),
              0,
              key("partial"),
              new BigDecimal("40.0000"),
              "USD",
              LocalDate.ofInstant(BASE, ZoneOffset.UTC),
              PaymentMethod.BANK_TRANSFER,
              "BANK-SETTLEMENT-001",
              "First remittance");
      assertThat(replay.replayed()).isTrue();

      var paid =
          service.recordPayment(
              created.receivableId(),
              1,
              key("full"),
              new BigDecimal("60.0000"),
              "USD",
              LocalDate.ofInstant(BASE, ZoneOffset.UTC),
              PaymentMethod.BANK_TRANSFER,
              "BANK-SETTLEMENT-002",
              null);
      assertThat(paid.detail().summary().status()).isEqualTo(ReceivableStatus.PAID);
      UUID secondPayment =
          paid.detail().payments().stream()
              .filter(payment -> "BANK-SETTLEMENT-002".equals(payment.externalReference()))
              .findFirst()
              .orElseThrow()
              .id();

      var firstReversal =
          service.reversePayment(
              created.receivableId(),
              secondPayment,
              2,
              key("reverse-10"),
              new BigDecimal("10.0000"),
              "USD",
              "Bank reconciliation correction");
      assertThat(firstReversal.detail().summary().status())
          .isEqualTo(ReceivableStatus.PARTIALLY_PAID);
      assertThat(firstReversal.detail().summary().outstandingAmount().amount())
          .isEqualTo("10.0000");

      var secondReversal =
          service.reversePayment(
              created.receivableId(),
              secondPayment,
              3,
              key("reverse-50"),
              new BigDecimal("50.0000"),
              "USD",
              "Remaining transfer was returned");
      assertThat(secondReversal.detail().summary().outstandingAmount().amount())
          .isEqualTo("60.0000");

      assertThatThrownBy(
              () ->
                  service.reversePayment(
                      created.receivableId(),
                      secondPayment,
                      4,
                      key("reverse-over"),
                      new BigDecimal("0.0001"),
                      "USD",
                      "No reversible balance remains"))
          .isInstanceOfSatisfying(
              SettlementProblem.class,
              problem ->
                  assertThat(problem.code()).isEqualTo("PAYMENT_REVERSAL_EXCEEDS_AVAILABLE"));
    }

    assertThat(eventCount(created.receivableId(), PaymentRecordedV1.TYPE)).isEqualTo(2);
    assertThat(eventCount(created.receivableId(), ReceivablePaidV1.TYPE)).isEqualTo(1);
    assertThat(eventCount(created.receivableId(), PaymentReversedV1.TYPE)).isEqualTo(2);
    UUID paymentId =
        jdbc.queryForObject(
            "SELECT id FROM settlement.payment_record WHERE receivable_id = ? LIMIT 1",
            UUID.class,
            created.receivableId());
    assertThat(
            jdbc.update(
                "UPDATE settlement.payment_record SET note = 'changed' WHERE id = ?", paymentId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT note FROM settlement.payment_record WHERE id = ?", String.class, paymentId))
        .isEqualTo("First remittance");
    assertThat(jdbc.update("DELETE FROM settlement.payment_record WHERE id = ?", paymentId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.payment_record WHERE id = ?",
                Integer.class,
                paymentId))
        .isOne();
  }

  @Test
  void serializesConcurrentReplayByIdempotencyKeyAndExternalReference() throws Exception {
    Created created = createReceivable("80.0000", 30, BASE.minusSeconds(86_400));
    CyclicBarrier start = new CyclicBarrier(2);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> concurrentPayment(created.receivableId(), start));
      var second = executor.submit(() -> concurrentPayment(created.receivableId(), start));
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(false, true);
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM settlement.payment_record WHERE receivable_id = ?",
                Integer.class,
                created.receivableId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM settlement.receivable WHERE id = ?",
                Long.class,
                created.receivableId()))
        .isEqualTo(1L);
  }

  @Test
  void marksOverdueOnlyAfterTheUtcBoundaryAndPaymentWinsTheFinalState() {
    Created created = createReceivable("45.0000", 0, BASE);

    assertThat(service.markOverdue(20)).isZero();
    clock.set(BASE.plusSeconds(86_400));
    assertThat(service.markOverdue(20)).isEqualTo(1);
    assertThat(service.markOverdue(20)).isZero();

    try (TenantContextHolder.Scope ignored = contexts.open(finance(TENANT))) {
      var paid =
          service.recordPayment(
              created.receivableId(),
              1,
              key("overdue-paid"),
              new BigDecimal("45.0000"),
              "USD",
              LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC),
              PaymentMethod.BANK_TRANSFER,
              "BANK-OVERDUE-001",
              null);
      assertThat(paid.detail().summary().status()).isEqualTo(ReceivableStatus.PAID);
    }
    assertThat(service.markOverdue(20)).isZero();
    assertThat(eventCount(created.receivableId(), ReceivableOverdueV1.TYPE)).isEqualTo(1);
  }

  @Test
  void enforcesTenantPartnerAndCommercialVisibilityBoundaries() {
    Created created = createReceivable("75.0000", 30, BASE.minusSeconds(86_400));

    try (TenantContextHolder.Scope ignored = contexts.open(finance(TENANT))) {
      assertThat(service.get(created.receivableId()).commercialAmountVisible()).isTrue();
    }
    try (TenantContextHolder.Scope ignored = contexts.open(buyer(TENANT, PARTNER))) {
      var detail = service.get(created.receivableId());
      assertThat(detail.commercialAmountVisible()).isTrue();
      assertThat(detail.payments()).isEmpty();
      assertThat(detail.allowedActions()).isEmpty();
    }
    try (TenantContextHolder.Scope ignored = contexts.open(buyer(TENANT, UUID.randomUUID()))) {
      assertThatThrownBy(() -> service.get(created.receivableId()))
          .isInstanceOfSatisfying(
              SettlementProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }
    try (TenantContextHolder.Scope ignored = contexts.open(auditor(TENANT))) {
      var detail = service.get(created.receivableId());
      assertThat(detail.commercialAmountVisible()).isFalse();
      assertThat(detail.summary().originalAmount()).isNull();
      assertThat(detail.summary().outstandingAmount()).isNull();
    }
    try (TenantContextHolder.Scope ignored = contexts.open(systemOperator(TENANT))) {
      assertThatThrownBy(() -> service.get(created.receivableId()))
          .isInstanceOf(AccessDeniedException.class);
    }
    try (TenantContextHolder.Scope ignored = contexts.open(finance(OTHER_TENANT))) {
      assertThatThrownBy(() -> service.get(created.receivableId()))
          .isInstanceOfSatisfying(
              SettlementProblem.class,
              problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }
  }

  private boolean concurrentPayment(UUID receivableId, CyclicBarrier start) throws Exception {
    try (TenantContextHolder.Scope ignored = contexts.open(finance(TENANT))) {
      start.await(10, TimeUnit.SECONDS);
      return service
          .recordPayment(
              receivableId,
              0,
              key("concurrent"),
              new BigDecimal("20.0000"),
              "USD",
              LocalDate.ofInstant(BASE, ZoneOffset.UTC),
              PaymentMethod.BANK_TRANSFER,
              "BANK-CONCURRENT-001",
              null)
          .replayed();
    }
  }

  private Created createReceivable(String amount, int paymentTermDays, Instant completedAt) {
    UUID orderId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    EventDelivery order = orderCreated(orderId, amount, paymentTermDays);
    EventDelivery fulfillment = fulfillmentCompleted(planId, orderId, completedAt);
    orderHandler.handle(order);
    fulfillmentHandler.handle(fulfillment);
    UUID receivableId =
        jdbc.queryForObject(
            "SELECT id FROM settlement.receivable WHERE tenant_id = ? AND order_id = ?",
            UUID.class,
            TENANT.value(),
            orderId);
    return new Created(orderId, planId, receivableId, order, fulfillment);
  }

  private EventDelivery orderCreated(UUID orderId, String amount, int paymentTermDays) {
    UUID eventId = UUID.randomUUID();
    String number = "ORD-" + orderId;
    var payload =
        new TradeOrderCreatedV1.Payload(
            orderId,
            number,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "QUO-" + orderId,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            BASE.minusSeconds(172_800),
            ACTOR,
            new TradeOrderCreatedV1.Customer(PARTNER, "BUYER-001", "Harbor Imports", 3),
            "USD",
            amount,
            paymentTermDays,
            null,
            "terms-2026.1",
            null,
            null,
            "a".repeat(64),
            null,
            List.of(),
            BASE.minusSeconds(172_800));
    return delivery(
        eventId, TradeOrderCreatedV1.TYPE, "trade-order", "TRADE_ORDER", orderId, number, payload);
  }

  private EventDelivery fulfillmentCompleted(UUID planId, UUID orderId, Instant completedAt) {
    String planNumber = "FUL-" + planId;
    var payload =
        new FulfillmentCompletedV1.Payload(
            planId,
            planNumber,
            orderId,
            "ORD-" + orderId,
            "SH_GENERAL_TRADE",
            completedAt,
            List.of());
    return delivery(
        UUID.randomUUID(),
        FulfillmentCompletedV1.TYPE,
        "fulfillment",
        "FULFILLMENT_PLAN",
        planId,
        planNumber,
        payload);
  }

  private EventDelivery delivery(
      UUID eventId,
      String type,
      String producer,
      String subjectType,
      UUID subjectId,
      String subjectNumber,
      Object payload) {
    try {
      return new EventDelivery(
          eventId,
          TENANT.value(),
          type,
          1,
          BASE,
          producer,
          new EventDelivery.Subject(subjectType, subjectId, subjectNumber),
          UUID.randomUUID(),
          UUID.randomUUID(),
          json.writeValueAsString(payload));
    } catch (JacksonException exception) {
      throw new AssertionError(exception);
    }
  }

  private int receivableCount(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM settlement.receivable WHERE tenant_id = ? AND order_id = ?",
        Integer.class,
        TENANT.value(),
        orderId);
  }

  private int eventCount(UUID receivableId, String eventType) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM platform_event.event_publication WHERE tenant_id = ? AND subject_id = ? AND event_type = ?",
        Integer.class,
        TENANT.value(),
        receivableId,
        eventType);
  }

  private static String key(String suffix) {
    return "settlement-integration-" + suffix;
  }

  private static TenantContext finance(TenantId tenantId) {
    return context(
        tenantId,
        null,
        "Finance Specialist",
        Set.of(
            PermissionCode.SETTLEMENT_READ,
            PermissionCode.SETTLEMENT_READ_COMMERCIAL_SENSITIVE,
            PermissionCode.SETTLEMENT_RECORD_PAYMENT,
            PermissionCode.SETTLEMENT_REVERSE_PAYMENT));
  }

  private static TenantContext buyer(TenantId tenantId, UUID partnerId) {
    return context(tenantId, partnerId, "Buyer", Set.of(PermissionCode.SETTLEMENT_READ));
  }

  private static TenantContext auditor(TenantId tenantId) {
    return context(tenantId, null, "Auditor", Set.of(PermissionCode.SETTLEMENT_READ));
  }

  private static TenantContext systemOperator(TenantId tenantId) {
    return context(tenantId, null, "System Operator", Set.of(PermissionCode.EXCEPTION_READ));
  }

  private static TenantContext context(
      TenantId tenantId, UUID partnerId, String role, Set<PermissionCode> permissions) {
    return new TenantContext(
        ACTOR,
        role,
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        partnerId,
        Set.of(role),
        Set.of(role.toLowerCase().replace(' ', '-')),
        permissions,
        "settlement-subject",
        "settlement-tenant");
  }

  private record Created(
      UUID orderId,
      UUID planId,
      UUID receivableId,
      EventDelivery orderDelivery,
      EventDelivery fulfillmentDelivery) {}

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    MutableClock(Instant initial, ZoneId zone) {
      this(new AtomicReference<>(initial), zone);
    }

    private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
      this.current = current;
      this.zone = zone;
    }

    void set(Instant instant) {
      current.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
      return zone.equals(requestedZone) ? this : new MutableClock(current, requestedZone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SettlementTestConfiguration {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(BASE, ZoneOffset.UTC);
    }
  }
}
