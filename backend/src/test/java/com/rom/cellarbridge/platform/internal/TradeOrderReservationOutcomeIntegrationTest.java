package com.rom.cellarbridge.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventoryReservationConfirmedV1;
import com.rom.cellarbridge.inventory.InventoryReservationFailedV1;
import com.rom.cellarbridge.inventory.InventoryReservationRequestHashV1;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import com.rom.cellarbridge.tradeorder.internal.application.InventoryReservationConfirmedEventHandler;
import com.rom.cellarbridge.tradeorder.internal.application.InventoryReservationFailedEventHandler;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.CommercialSnapshot;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Customer;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.DeliveryAddress;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Line;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Route;
import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class TradeOrderReservationOutcomeIntegrationTest extends PostgresIntegrationTestSupport {

  private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID OTHER_TENANT = UUID.fromString("10000000-0000-4000-8000-000000000002");
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final Instant CREATED_AT = Instant.parse("2026-07-17T08:00:00Z");
  private static final Instant OUTCOME_AT = Instant.parse("2026-07-17T08:00:01Z");

  @Autowired private LocalEventDeliveryService deliveryService;
  @Autowired private JdbcEventFailureRecorder failureRecorder;
  @Autowired private InventoryReservationConfirmedEventHandler confirmedHandler;
  @Autowired private InventoryReservationFailedEventHandler failedHandler;
  @Autowired private TradeOrderRepository orders;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  @Test
  void appliesOneConfirmedOutcomeAcrossConcurrentBusinessDuplicates() throws Exception {
    Fixture fixture = currentOrder();
    UUID reservationId = UUID.randomUUID();
    InventoryReservationConfirmedV1.Payload payload = confirmed(fixture, reservationId);
    int consumers = 8;
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(consumers)) {
      var attempts =
          java.util.stream.IntStream.range(0, consumers)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () -> {
                            start.await();
                            return deliveryService.deliver(
                                confirmedHandler,
                                delivery(
                                    UUID.randomUUID(),
                                    TENANT,
                                    InventoryReservationConfirmedV1.TYPE,
                                    fixture,
                                    reservationId,
                                    payload));
                          }))
              .toList();
      start.countDown();
      for (var attempt : attempts) {
        assertThat(attempt.get()).isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
      }
    }

    assertThat(status(fixture.order().id())).isEqualTo("RESERVED");
    assertThat(outcomeTimelineCount(fixture.order().id())).isEqualTo(1);
    assertThat(orders.timeline(new TenantId(TENANT), fixture.order().id(), true))
        .allMatch(entry -> entry.visibility().equals("CUSTOMER"))
        .noneMatch(entry -> entry.safeReason().contains("evidence"));

    EventDelivery sameEvent =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationConfirmedV1.TYPE,
            fixture,
            reservationId,
            payload);
    assertThat(deliveryService.deliver(confirmedHandler, sameEvent))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertThat(deliveryService.deliver(confirmedHandler, sameEvent))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.ALREADY_PROCESSED);

    UUID conflictingReservationId = UUID.randomUUID();
    EventDelivery conflictingBusinessKey =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationConfirmedV1.TYPE,
            fixture,
            conflictingReservationId,
            confirmed(fixture, conflictingReservationId));
    assertFinal(confirmedHandler, conflictingBusinessKey, "ORDER_RESERVATION_OUTCOME_CONFLICT");
    assertThat(outcomeTimelineCount(fixture.order().id())).isEqualTo(1);
  }

  @Test
  void acceptsPostgresMicrosecondPrecisionForConfirmedOutcomeTime() {
    Fixture fixture = currentOrder();
    UUID reservationId = UUID.randomUUID();
    Instant payloadTime = Instant.parse("2026-07-17T08:00:01.123456789Z");
    InventoryReservationConfirmedV1.Payload baseline = confirmed(fixture, reservationId);
    InventoryReservationConfirmedV1.Payload payload =
        new InventoryReservationConfirmedV1.Payload(
            baseline.reservationId(),
            baseline.reservationNumber(),
            baseline.orderId(),
            baseline.orderNumber(),
            baseline.requestHash(),
            baseline.supplyDecisionHash(),
            fixture.order().commercialSnapshot().route().code(),
            payloadTime,
            baseline.allocations());

    EventDelivery delivery =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationConfirmedV1.TYPE,
            fixture,
            reservationId,
            payload,
            Instant.parse("2026-07-17T08:00:01.123457Z"));

    assertThat(deliveryService.deliver(confirmedHandler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertThat(status(fixture.order().id())).isEqualTo("RESERVED");
  }

  @Test
  void preservesTheFirstTerminalOutcomeAndRejectsTheOppositeOutcome() {
    Fixture fixture = currentOrder();
    UUID reservationId = UUID.randomUUID();
    EventDelivery failed =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationFailedV1.TYPE,
            fixture,
            reservationId,
            failed(fixture, reservationId, "INVENTORY_FIXED_POOL_INELIGIBLE"));
    assertThat(deliveryService.deliver(failedHandler, failed))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);

    EventDelivery confirmed =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationConfirmedV1.TYPE,
            fixture,
            reservationId,
            confirmed(fixture, reservationId));
    assertFinal(confirmedHandler, confirmed, "ORDER_RESERVATION_OUTCOME_CONFLICT");

    assertThat(status(fixture.order().id())).isEqualTo("RESERVATION_FAILED");
    assertThat(outcomeTimelineCount(fixture.order().id())).isEqualTo(1);
  }

  @Test
  void rejectsConflictingEvidenceBeforeAcceptingTheCanonicalOutcome() {
    Fixture fixture = currentOrder();
    UUID reservationId = UUID.randomUUID();
    InventoryReservationFailedV1.Payload conflict =
        failed(fixture, reservationId, "INVENTORY_FIXED_POOL_INELIGIBLE");
    conflict =
        new InventoryReservationFailedV1.Payload(
            conflict.reservationId(),
            conflict.reservationNumber(),
            conflict.orderId(),
            conflict.orderNumber(),
            "f".repeat(64),
            conflict.supplyDecisionHash(),
            conflict.failedAt(),
            conflict.reasonCode(),
            conflict.shortages(),
            conflict.lineFailures(),
            conflict.retryable());
    EventDelivery conflicting =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationFailedV1.TYPE,
            fixture,
            reservationId,
            conflict);
    assertFinal(failedHandler, conflicting, "ORDER_RESERVATION_REQUEST_HASH_MISMATCH");
    assertThat(status(fixture.order().id())).isEqualTo("PENDING_RESERVATION");
    assertThat(outcomeTimelineCount(fixture.order().id())).isZero();

    EventDelivery canonical =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationConfirmedV1.TYPE,
            fixture,
            reservationId,
            confirmed(fixture, reservationId));
    assertThat(deliveryService.deliver(confirmedHandler, canonical))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertThat(status(fixture.order().id())).isEqualTo("RESERVED");
  }

  @Test
  void rejectsUnknownTenantCausationAndDecisionEvidenceWithoutMutation() {
    Fixture fixture = currentOrder();
    UUID reservationId = UUID.randomUUID();
    InventoryReservationConfirmedV1.Payload payload = confirmed(fixture, reservationId);

    EventDelivery crossTenant =
        delivery(
            UUID.randomUUID(),
            OTHER_TENANT,
            InventoryReservationConfirmedV1.TYPE,
            fixture,
            reservationId,
            payload);
    assertFinal(confirmedHandler, crossTenant, "ORDER_RESERVATION_ORDER_NOT_FOUND");

    UUID unknownOrderId = UUID.randomUUID();
    InventoryReservationConfirmedV1.Payload unknownPayload =
        new InventoryReservationConfirmedV1.Payload(
            payload.reservationId(),
            payload.reservationNumber(),
            unknownOrderId,
            "ORD-UNKNOWN",
            payload.requestHash(),
            payload.supplyDecisionHash(),
            payload.confirmedAt(),
            payload.allocations());
    EventDelivery unknownOrder =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationConfirmedV1.TYPE,
            fixture,
            reservationId,
            unknownPayload);
    assertFinal(confirmedHandler, unknownOrder, "ORDER_RESERVATION_ORDER_NOT_FOUND");

    EventDelivery wrongCausation =
        new EventDelivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationConfirmedV1.TYPE,
            1,
            OUTCOME_AT,
            "inventory",
            new EventDelivery.Subject(
                "INVENTORY_RESERVATION", reservationId, "RES-" + reservationId),
            fixture.order().correlationId(),
            UUID.randomUUID(),
            json(payload));
    assertFinal(confirmedHandler, wrongCausation, "INVENTORY_RESERVATION_OUTCOME_INVALID");

    InventoryReservationConfirmedV1.Payload wrongDecision =
        new InventoryReservationConfirmedV1.Payload(
            payload.reservationId(),
            payload.reservationNumber(),
            payload.orderId(),
            payload.orderNumber(),
            payload.requestHash(),
            "e".repeat(64),
            payload.confirmedAt(),
            payload.allocations());
    EventDelivery decisionMismatch =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationConfirmedV1.TYPE,
            fixture,
            reservationId,
            wrongDecision);
    assertFinal(confirmedHandler, decisionMismatch, "ORDER_RESERVATION_SUPPLY_DECISION_MISMATCH");

    assertThat(status(fixture.order().id())).isEqualTo("PENDING_RESERVATION");
    assertThat(outcomeTimelineCount(fixture.order().id())).isZero();
  }

  @Test
  void acceptsTheExplicitLegacySupplyDecisionFailureOnly() {
    Fixture fixture = legacyOrder();
    UUID reservationId = UUID.randomUUID();
    EventDelivery failed =
        delivery(
            UUID.randomUUID(),
            TENANT,
            InventoryReservationFailedV1.TYPE,
            fixture,
            reservationId,
            legacyFailed(fixture, reservationId));

    assertThat(deliveryService.deliver(failedHandler, failed))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertThat(status(fixture.order().id())).isEqualTo("RESERVATION_FAILED");
    assertThat(timelineCode(fixture.order().id())).isEqualTo("SUPPLY_DECISION_MISSING");
  }

  private Fixture currentOrder() {
    UUID sourceLineId = UUID.randomUUID();
    UUID skuId = UUID.randomUUID();
    UUID poolId = UUID.randomUUID();
    SupplyDecisionSnapshot decision =
        SupplyDecisionSnapshot.create(
            SupplyDecisionSnapshot.POLICY_VERSION,
            CREATED_AT.minusSeconds(2),
            UUID.randomUUID(),
            "a".repeat(64),
            TradeRouteCode.SH_GENERAL_TRADE,
            CREATED_AT.minusSeconds(3),
            List.of(
                new SupplyDecisionSnapshot.LineDecision(
                    sourceLineId,
                    skuId,
                    new BigDecimal("2.000000"),
                    TradePlanningQuantityUnit.CASE,
                    SupplyAllocationMode.FIXED_POOL,
                    poolId,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND)));
    Line line =
        new Line(
            UUID.randomUUID(),
            sourceLineId,
            skuId,
            "SKU-" + skuId.toString().substring(0, 8),
            "Outcome integration item",
            new BigDecimal("2.000000"),
            "CASE",
            new BigDecimal("5.0000"),
            new BigDecimal("10.0000"),
            poolId,
            SupplyAllocationMode.FIXED_POOL,
            "DOMESTIC_ON_HAND");
    TradeOrder order = createCurrent(decision, line);
    insert(order);
    return new Fixture(order, line);
  }

  private Fixture legacyOrder() {
    UUID skuId = UUID.randomUUID();
    Line line =
        new Line(
            UUID.randomUUID(),
            UUID.randomUUID(),
            skuId,
            "SKU-" + skuId.toString().substring(0, 8),
            "Legacy outcome item",
            new BigDecimal("2.000000"),
            "CASE",
            new BigDecimal("5.0000"),
            new BigDecimal("10.0000"),
            null,
            null,
            "DOMESTIC_ON_HAND");
    TradeOrder order =
        TradeOrder.createLegacy(
            UUID.randomUUID(),
            new TenantId(TENANT),
            "ORD-LEGACY-" + UUID.randomUUID().toString().substring(0, 8),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "QT-LEGACY",
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            CREATED_AT.minusSeconds(1),
            ACTOR,
            snapshot(1, line),
            "b".repeat(64),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            CREATED_AT);
    insert(order);
    return new Fixture(order, line);
  }

  private TradeOrder createCurrent(SupplyDecisionSnapshot decision, Line line) {
    return TradeOrder.createCurrent(
        UUID.randomUUID(),
        new TenantId(TENANT),
        "ORD-OUTCOME-" + UUID.randomUUID().toString().substring(0, 8),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "QT-OUTCOME",
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        CREATED_AT.minusSeconds(1),
        ACTOR,
        decision,
        snapshot(2, line),
        "b".repeat(64),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        CREATED_AT);
  }

  private static CommercialSnapshot snapshot(int schemaVersion, Line line) {
    return new CommercialSnapshot(
        schemaVersion,
        new Customer(UUID.randomUUID(), "C-OUTCOME", "Outcome Customer", 1),
        "CNY",
        new BigDecimal("10.0000"),
        30,
        new Route("SH_GENERAL_TRADE", "ROUTE-2026-01", LocalDate.parse("2026-08-01")),
        "TERMS-1",
        LocalDate.parse("2026-08-01"),
        new DeliveryAddress("CN", "Shanghai", "Shanghai", "Pudong", "1 Outcome Road", "200000"),
        List.of(line));
  }

  private void insert(TradeOrder order) {
    transactions.executeWithoutResult(
        ignored -> assertThat(orders.insertIfAbsent(new TenantId(TENANT), order, ACTOR)).isTrue());
  }

  private InventoryReservationConfirmedV1.Payload confirmed(Fixture fixture, UUID reservationId) {
    return new InventoryReservationConfirmedV1.Payload(
        reservationId,
        "RES-" + reservationId,
        fixture.order().id(),
        fixture.order().number(),
        requestHash(fixture),
        fixture.order().supplyDecision().decisionHash(),
        OUTCOME_AT,
        List.of(
            new InventoryReservationConfirmedV1.Allocation(
                fixture.line().id(),
                fixture.line().skuId(),
                fixture.line().supplyPoolId(),
                UUID.randomUUID(),
                "LOT-OUTCOME",
                SupplyType.DOMESTIC_ON_HAND,
                "2.000000",
                QuantityUnit.CASE)));
  }

  private InventoryReservationFailedV1.Payload failed(
      Fixture fixture, UUID reservationId, String reasonCode) {
    Line line = fixture.line();
    return new InventoryReservationFailedV1.Payload(
        reservationId,
        "RES-" + reservationId,
        fixture.order().id(),
        fixture.order().number(),
        requestHash(fixture),
        fixture.order().supplyDecision().decisionHash(),
        OUTCOME_AT,
        reasonCode,
        List.of(),
        List.of(
            new InventoryReservationFailedV1.LineFailure(
                line.id(),
                line.skuId(),
                line.skuCode(),
                "2.000000",
                QuantityUnit.CASE,
                "FIXED_POOL",
                line.supplyPoolId(),
                SupplyType.DOMESTIC_ON_HAND,
                null,
                null)),
        false);
  }

  private InventoryReservationFailedV1.Payload legacyFailed(Fixture fixture, UUID reservationId) {
    Line line = fixture.line();
    return new InventoryReservationFailedV1.Payload(
        reservationId,
        "RES-" + reservationId,
        fixture.order().id(),
        fixture.order().number(),
        requestHash(fixture),
        null,
        OUTCOME_AT,
        "SUPPLY_DECISION_MISSING",
        List.of(),
        List.of(
            new InventoryReservationFailedV1.LineFailure(
                line.id(),
                line.skuId(),
                line.skuCode(),
                "2.000000",
                QuantityUnit.CASE,
                null,
                null,
                SupplyType.DOMESTIC_ON_HAND,
                null,
                null)),
        false);
  }

  private String requestHash(Fixture fixture) {
    boolean legacy = fixture.order().supplyDecision() == null;
    Line line = fixture.line();
    return InventoryReservationRequestHashV1.hash(
        TENANT,
        fixture.order().id(),
        fixture.order().commercialSnapshot().route().code(),
        legacy ? null : fixture.order().supplyDecision().decisionHash(),
        List.of(
            new InventoryReservationRequestHashV1.Line(
                line.id(),
                line.sourceQuotationLineId(),
                line.skuId(),
                line.quantity(),
                QuantityUnit.CASE,
                legacy ? null : line.allocationMode().name(),
                legacy ? null : line.supplyPoolId(),
                legacy ? null : SupplyType.valueOf(line.supplyType()))));
  }

  private EventDelivery delivery(
      UUID eventId,
      UUID tenantId,
      String type,
      Fixture fixture,
      UUID reservationId,
      Object payload) {
    return delivery(eventId, tenantId, type, fixture, reservationId, payload, OUTCOME_AT);
  }

  private EventDelivery delivery(
      UUID eventId,
      UUID tenantId,
      String type,
      Fixture fixture,
      UUID reservationId,
      Object payload,
      Instant occurredAt) {
    return new EventDelivery(
        eventId,
        tenantId,
        type,
        1,
        occurredAt,
        "inventory",
        new EventDelivery.Subject("INVENTORY_RESERVATION", reservationId, "RES-" + reservationId),
        fixture.order().correlationId(),
        fixture.order().createdEventId(),
        json(payload));
  }

  private void assertFinal(
      com.rom.cellarbridge.platform.LocalEventHandler handler,
      EventDelivery delivery,
      String failureCode) {
    RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, delivery));
    assertThat(failureRecorder.record(handler, delivery, failure))
        .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.FAILED_FINAL);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error_code FROM platform_event.event_inbox WHERE event_id = ?",
                String.class,
                delivery.eventId()))
        .isEqualTo(failureCode);
  }

  private String status(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT status FROM trade_order.trade_order WHERE tenant_id = ? AND id = ?",
        String.class,
        TENANT,
        orderId);
  }

  private int outcomeTimelineCount(UUID orderId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM trade_order.timeline_entry
             WHERE tenant_id = ? AND order_id = ?
               AND event_type IN (?, ?)
            """,
            Integer.class,
            TENANT,
            orderId,
            InventoryReservationConfirmedV1.TYPE,
            InventoryReservationFailedV1.TYPE);
    return count == null ? 0 : count;
  }

  private String timelineCode(UUID orderId) {
    return jdbc.queryForObject(
        """
        SELECT code FROM trade_order.timeline_entry
         WHERE tenant_id = ? AND order_id = ?
           AND event_type = ?
        """,
        String.class,
        TENANT,
        orderId,
        InventoryReservationFailedV1.TYPE);
  }

  private String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static RuntimeException catchFailure(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected a handler failure");
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private record Fixture(TradeOrder order, Line line) {}
}
