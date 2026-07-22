package com.rom.cellarbridge.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventoryRecoveryOperations;
import com.rom.cellarbridge.inventory.InventoryReservationConfirmedV1;
import com.rom.cellarbridge.inventory.InventoryReservationFailedV1;
import com.rom.cellarbridge.inventory.internal.application.TradeOrderCreatedReservationHandler;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.quotation.QuotationAcceptedV1;
import com.rom.cellarbridge.quotation.QuotationSnapshotHashV1;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import com.rom.cellarbridge.tradeorder.TradeOrderCreatedV1;
import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class TradeOrderCreatedReservationIntegrationTest extends PostgresIntegrationTestSupport {

  private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
  private static final String ROUTE = "SH_GENERAL_TRADE";

  @Autowired private LocalEventDeliveryService deliveryService;
  @Autowired private JdbcEventFailureRecorder failureRecorder;
  @Autowired private TradeOrderCreatedReservationHandler handler;
  @Autowired private InventoryRecoveryOperations recovery;
  @Autowired private TenantContextHolder contexts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  @Test
  void reservesAcrossDeterministicallyOrderedLotsAndReplaysConcurrentRequestsWithoutDuplicates()
      throws Exception {
    UUID sku = UUID.randomUUID();
    Stock stock = stock(sku, "1", "2");
    TradeOrderCreatedV1.Payload payload =
        current(
            UUID.randomUUID(),
            List.of(line(sku, "3", "ROUTE_ELIGIBLE_AUTO", null, "DOMESTIC_ON_HAND")));
    EventDelivery first = delivery(payload);

    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var calls =
          List.of(first, delivery(payload)).stream()
              .map(
                  event ->
                      executor.submit(
                          () -> {
                            barrier.await();
                            return deliveryService.deliver(handler, event);
                          }))
              .toList();
      for (var call : calls) {
        assertThat(call.get()).isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
      }
    }

    assertThat(reservationStatus(payload.orderId())).isEqualTo("CONFIRMED");
    assertThat(count("inventory.reservation_attempt", payload.orderId())).isEqualTo(1);
    assertThat(count("inventory.allocation", payload.orderId())).isEqualTo(2);
    assertThat(count("inventory.inventory_movement", payload.orderId())).isEqualTo(2);
    assertThat(outcomeCount(payload.orderId(), InventoryReservationConfirmedV1.TYPE)).isEqualTo(1);
    assertThat(reserved(stock.lotIds().get(0))).isEqualByComparingTo("1.000000");
    assertThat(reserved(stock.lotIds().get(1))).isEqualByComparingTo("2.000000");
  }

  @Test
  void rollsBackAllLotMutationsWhenALaterLineIsInsufficient() {
    UUID firstSku = UUID.fromString("00000000-0000-4000-8000-000000000010");
    UUID secondSku = UUID.fromString("00000000-0000-4000-8000-000000000020");
    Stock first = stock(firstSku, "5");
    Stock second = stock(secondSku, "1");
    TradeOrderCreatedV1.Payload payload =
        current(
            UUID.randomUUID(),
            List.of(
                line(firstSku, "2", "FIXED_POOL", first.poolId(), "DOMESTIC_ON_HAND"),
                line(secondSku, "2", "FIXED_POOL", second.poolId(), "DOMESTIC_ON_HAND")));

    deliveryService.deliver(handler, delivery(payload));

    assertThat(reservationStatus(payload.orderId())).isEqualTo("FAILED");
    assertThat(reservationFailure(payload.orderId())).isEqualTo("INVENTORY_INSUFFICIENT");
    assertThat(reserved(first.lotIds().getFirst())).isEqualByComparingTo("0.000000");
    assertThat(reserved(second.lotIds().getFirst())).isEqualByComparingTo("0.000000");
    assertThat(count("inventory.allocation", payload.orderId())).isZero();
    assertThat(count("inventory.inventory_movement", payload.orderId())).isZero();
    assertThat(count("inventory.shortage_snapshot", payload.orderId())).isEqualTo(1);
    assertThat(outcomeCount(payload.orderId(), InventoryReservationFailedV1.TYPE)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT (payload #>> '{payload,retryable}')::boolean FROM platform_event.event_publication WHERE tenant_id = ? AND subject_id = ? AND event_type = ?",
                Boolean.class,
                TENANT,
                reservationId(payload.orderId()),
                InventoryReservationFailedV1.TYPE))
        .isTrue();

    try (TenantContextHolder.Scope ignored = contexts.open(exceptionOperator())) {
      var stillFailed =
          recovery.retryReservation(
              reservationId(payload.orderId()),
              payload.orderNumber(),
              UUID.randomUUID(),
              UUID.randomUUID());
      assertThat(stillFailed.status()).isEqualTo(InventoryRecoveryOperations.Status.FAILED);
      assertThat(stillFailed.failureCode()).isEqualTo("INVENTORY_INSUFFICIENT");
    }
    assertThat(count("inventory.reservation_attempt", payload.orderId())).isEqualTo(2);
    assertThat(totalQuantityFacts(payload.orderId())).isZero();

    jdbc.update(
        "UPDATE inventory.inventory_lot SET on_hand_quantity = 2 WHERE tenant_id = ? AND id = ?",
        TENANT,
        second.lotIds().getFirst());
    try (TenantContextHolder.Scope ignored = contexts.open(exceptionOperator())) {
      UUID correlationId = UUID.randomUUID();
      UUID causationId = UUID.randomUUID();
      var confirmed =
          recovery.retryReservation(
              reservationId(payload.orderId()), payload.orderNumber(), correlationId, causationId);
      var replayed =
          recovery.retryReservation(
              reservationId(payload.orderId()), payload.orderNumber(), correlationId, causationId);
      assertThat(confirmed.status()).isEqualTo(InventoryRecoveryOperations.Status.CONFIRMED);
      assertThat(replayed.status()).isEqualTo(InventoryRecoveryOperations.Status.CONFIRMED);
      assertThat(replayed.replayed()).isTrue();
    }
    assertThat(reservationStatus(payload.orderId())).isEqualTo("CONFIRMED");
    assertThat(count("inventory.reservation_attempt", payload.orderId())).isEqualTo(3);
    assertThat(count("inventory.allocation", payload.orderId())).isEqualTo(2);
    assertThat(outcomeCount(payload.orderId(), InventoryReservationConfirmedV1.TYPE)).isEqualTo(1);
    assertThat(reserved(first.lotIds().getFirst())).isEqualByComparingTo("2.000000");
    assertThat(reserved(second.lotIds().getFirst())).isEqualByComparingTo("2.000000");
  }

  @Test
  void recordsManualAndLegacyFailuresWithoutTouchingInventory() {
    UUID manualSku = UUID.randomUUID();
    TradeOrderCreatedV1.Payload manual =
        current(
            UUID.randomUUID(),
            List.of(line(manualSku, "2", "FIXED_POOL", UUID.randomUUID(), "IN_TRANSIT_PRESALE")));
    TradeOrderCreatedV1.Payload currentLegacy =
        current(
            UUID.randomUUID(),
            List.of(line(UUID.randomUUID(), "1", "ROUTE_ELIGIBLE_AUTO", null, "DOMESTIC_ON_HAND")));

    deliveryService.deliver(handler, delivery(manual));
    deliveryService.deliver(handler, delivery(currentLegacy.orderId(), legacyJson(currentLegacy)));

    assertThat(reservationFailure(manual.orderId()))
        .isEqualTo("SUPPLY_NOT_AUTOMATICALLY_RESERVABLE");
    assertThat(reservationFailure(currentLegacy.orderId())).isEqualTo("SUPPLY_DECISION_MISSING");
    assertThat(totalQuantityFacts(manual.orderId())).isZero();
    assertThat(totalQuantityFacts(currentLegacy.orderId())).isZero();
    assertThat(outcomeCount(manual.orderId(), InventoryReservationFailedV1.TYPE)).isEqualTo(1);
  }

  @Test
  void rejectsAnIneligibleFixedPoolWithoutCreatingQuantityFacts() {
    TradeOrderCreatedV1.Payload payload =
        current(
            UUID.randomUUID(),
            List.of(
                line(UUID.randomUUID(), "1", "FIXED_POOL", UUID.randomUUID(), "DOMESTIC_ON_HAND")));

    deliveryService.deliver(handler, delivery(payload));

    assertThat(reservationFailure(payload.orderId())).isEqualTo("INVENTORY_FIXED_POOL_INELIGIBLE");
    assertThat(totalQuantityFacts(payload.orderId())).isZero();
  }

  @Test
  void preservesTheCanonicalReservationAndRecordsOneImmutableRequestConflict() {
    UUID sku = UUID.randomUUID();
    Stock stock = stock(sku, "10");
    UUID orderId = UUID.randomUUID();
    TradeOrderCreatedV1.Payload accepted =
        current(orderId, List.of(line(sku, "2", "FIXED_POOL", stock.poolId(), "DOMESTIC_ON_HAND")));
    TradeOrderCreatedV1.Payload conflicting =
        current(orderId, List.of(line(sku, "3", "FIXED_POOL", stock.poolId(), "DOMESTIC_ON_HAND")));
    deliveryService.deliver(handler, delivery(accepted));
    String canonicalHash = requestHash(orderId);

    deliveryService.deliver(handler, delivery(conflicting));
    deliveryService.deliver(handler, delivery(conflicting));

    assertThat(reservationStatus(orderId)).isEqualTo("CONFIRMED");
    assertThat(requestHash(orderId)).isEqualTo(canonicalHash);
    assertThat(count("inventory.allocation", orderId)).isEqualTo(1);
    assertThat(reserved(stock.lotIds().getFirst())).isEqualByComparingTo("2.000000");
    assertThat(conflictCount(orderId)).isEqualTo(1);
    assertThat(outcomeCount(orderId, InventoryReservationConfirmedV1.TYPE)).isEqualTo(1);
    assertThat(outcomeCount(orderId, InventoryReservationFailedV1.TYPE)).isEqualTo(1);
  }

  @Test
  void serializesConcurrentOrdersOnOneLotWithoutOverselling() throws Exception {
    UUID sku = UUID.randomUUID();
    UUID secondSku = UUID.randomUUID();
    Stock stock = stock(sku, "5");
    Stock second = stock(secondSku, "5");
    List<TradeOrderCreatedV1.Payload> payloads = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      payloads.add(
          current(
              UUID.randomUUID(),
              index % 2 == 0
                  ? List.of(
                      line(sku, "1", "FIXED_POOL", stock.poolId(), "DOMESTIC_ON_HAND"),
                      line(secondSku, "1", "FIXED_POOL", second.poolId(), "DOMESTIC_ON_HAND"))
                  : List.of(
                      line(secondSku, "1", "FIXED_POOL", second.poolId(), "DOMESTIC_ON_HAND"),
                      line(sku, "1", "FIXED_POOL", stock.poolId(), "DOMESTIC_ON_HAND"))));
    }
    CyclicBarrier barrier = new CyclicBarrier(payloads.size());
    try (var executor = Executors.newFixedThreadPool(payloads.size())) {
      var futures =
          payloads.stream()
              .map(
                  payload ->
                      executor.submit(
                          () -> {
                            barrier.await();
                            return deliveryService.deliver(handler, delivery(payload));
                          }))
              .toList();
      for (var future : futures) {
        assertThat(future.get()).isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
      }
    }

    BigDecimal finalReserved = reserved(stock.lotIds().getFirst());
    int confirmed = statusCount(payloads, "CONFIRMED");
    int failed = statusCount(payloads, "FAILED");
    System.out.printf(
        "reservationConcurrency coordination=barrier threads=8 attempts=8 confirmed=%d failed=%d retries=0 reserved=%s onHand=5.000000%n",
        confirmed, failed, finalReserved);
    assertThat(finalReserved).isEqualByComparingTo("5.000000");
    assertThat(reserved(second.lotIds().getFirst())).isEqualByComparingTo("5.000000");
    assertThat(confirmed).isEqualTo(5);
    assertThat(failed).isEqualTo(3);
    assertThat(outcomeCount(payloads)).isEqualTo(8);
  }

  @Test
  void rejectsMixedOrTamperedDecisionAndCommercialEvidenceAsFinalPoisonEvents() {
    TradeOrderCreatedV1.Payload payload =
        current(
            UUID.randomUUID(),
            List.of(line(UUID.randomUUID(), "1", "ROUTE_ELIGIBLE_AUTO", null, "DOMESTIC_ON_HAND")));
    ObjectNode body = object(payload);
    ((ObjectNode) body.path("lines").get(0)).putNull("allocationMode");
    assertFinalPoison(payload.orderId(), body, "TRADE_ORDER_CREATED_SUPPLY_DECISION_INVALID");

    ObjectNode tampered = object(payload);
    ((ObjectNode) tampered.path("supplyDecision")).put("decisionHash", "f".repeat(64));
    assertFinalPoison(
        payload.orderId(), tampered, "TRADE_ORDER_CREATED_SUPPLY_DECISION_HASH_MISMATCH");

    ObjectNode mixed = object(payload);
    ((ObjectNode) mixed.path("lines").get(0)).remove("allocationMode");
    assertFinalPoison(payload.orderId(), mixed, "TRADE_ORDER_CREATED_SUPPLY_DECISION_INVALID");

    ObjectNode commercial = object(payload);
    commercial.put("snapshotHash", "f".repeat(64));
    assertFinalPoison(
        payload.orderId(), commercial, "TRADE_ORDER_CREATED_COMMERCIAL_HASH_MISMATCH");
  }

  private TradeOrderCreatedV1.Payload current(UUID orderId, List<LineSpec> specs) {
    List<TradeOrderCreatedV1.Line> lines =
        specs.stream()
            .map(
                spec ->
                    new TradeOrderCreatedV1.Line(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        spec.skuId(),
                        "SKU-" + spec.skuId().toString().substring(0, 8),
                        "Reservation test item",
                        spec.quantity(),
                        "CASE",
                        "10.00",
                        "10.00",
                        spec.poolId(),
                        spec.mode(),
                        spec.supplyType()))
            .toList();
    SupplyDecisionSnapshot decision =
        SupplyDecisionSnapshot.create(
            SupplyDecisionSnapshot.POLICY_VERSION,
            NOW,
            UUID.randomUUID(),
            "a".repeat(64),
            TradeRouteCode.SH_GENERAL_TRADE,
            NOW,
            lines.stream()
                .map(
                    line ->
                        new SupplyDecisionSnapshot.LineDecision(
                            line.sourceQuotationLineId(),
                            line.skuId(),
                            new BigDecimal(line.quantity()),
                            TradePlanningQuantityUnit.CASE,
                            SupplyAllocationMode.valueOf(line.allocationMode()),
                            line.supplyPoolId(),
                            TradePlanningSupplyType.valueOf(line.supplyType())))
                .toList());
    UUID quotationId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    var customer = new TradeOrderCreatedV1.Customer(UUID.randomUUID(), "C-1", "Cellar", 1);
    var route = new TradeOrderCreatedV1.Route(ROUTE, "ROUTE-2026-01", LocalDate.of(2026, 8, 1));
    var address =
        new TradeOrderCreatedV1.DeliveryAddress(
            "CN", "Shanghai", "Shanghai", "Pudong", "1 Road", "200000");
    String snapshotHash =
        QuotationSnapshotHashV1.hash(
            new QuotationSnapshotHashV1.Snapshot(
                1,
                quotationId,
                revisionId,
                "QT-TEST",
                1,
                new QuotationAcceptedV1.Customer(
                    customer.partnerId(),
                    customer.partnerNumber(),
                    customer.displayName(),
                    customer.sourceVersion()),
                "CNY",
                "10.00",
                30,
                new QuotationAcceptedV1.Route(
                    route.code(), route.policyVersion(), route.estimatedDeliveryDate()),
                "TERMS-1",
                LocalDate.of(2026, 8, 1),
                new QuotationAcceptedV1.DeliveryAddress(
                    address.countryCode(),
                    address.province(),
                    address.city(),
                    address.district(),
                    address.line1(),
                    address.postalCode()),
                lines.stream()
                    .map(
                        line ->
                            new QuotationAcceptedV1.Line(
                                line.sourceQuotationLineId(),
                                line.skuId(),
                                line.skuCode(),
                                line.description(),
                                line.quantity(),
                                line.unit(),
                                line.netUnitPrice(),
                                line.lineTotal(),
                                line.supplyPoolId(),
                                line.allocationMode(),
                                line.supplyType()))
                    .toList()));
    return new TradeOrderCreatedV1.Payload(
        orderId,
        "SO-" + orderId.toString().substring(0, 8),
        quotationId,
        revisionId,
        "QT-TEST",
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        NOW,
        ACTOR,
        customer,
        "CNY",
        "10.00",
        30,
        route,
        "TERMS-1",
        LocalDate.of(2026, 8, 1),
        address,
        snapshotHash,
        new TradeOrderCreatedV1.SupplyDecision(
            decision.schemaVersion(),
            decision.policyVersion(),
            decision.decidedAt(),
            decision.sourceRouteEvaluationId(),
            decision.sourceRouteInputHash(),
            decision.selectedRouteCode().name(),
            decision.inventoryDataAsOf(),
            decision.decisionHash()),
        lines,
        NOW);
  }

  private Stock stock(UUID skuId, String... quantities) {
    UUID warehouseId = UUID.randomUUID();
    UUID poolId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory.warehouse
          (id, tenant_id, code, name, country_code, city, status,
           created_at, created_by, updated_at, updated_by, version, allocation_priority)
        VALUES (?, ?, ?, 'Test Warehouse', 'CN', 'Shanghai', 'ACTIVE', ?, ?, ?, ?, 0, 10)
        """,
        warehouseId,
        TENANT,
        "WH-" + warehouseId,
        Timestamp.from(NOW),
        ACTOR,
        Timestamp.from(NOW),
        ACTOR);
    jdbc.update(
        """
        INSERT INTO inventory.supply_pool
          (id, tenant_id, warehouse_id, code, supply_type, route_code, currency,
           confidence, policy_version, status, created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, 'DOMESTIC_ON_HAND', ?, 'CNY', 'HIGH', 'TEST-1', 'ACTIVE',
                ?, ?, ?, ?, 0)
        """,
        poolId,
        TENANT,
        warehouseId,
        "POOL-" + poolId,
        ROUTE,
        Timestamp.from(NOW),
        ACTOR,
        Timestamp.from(NOW),
        ACTOR);
    List<UUID> lotIds = new ArrayList<>();
    for (int index = 0; index < quantities.length; index++) {
      UUID lotId = UUID.randomUUID();
      lotIds.add(lotId);
      jdbc.update(
          """
          INSERT INTO inventory.inventory_lot
            (id, tenant_id, supply_pool_id, sku_id, lot_code, status,
             on_hand_quantity, reserved_quantity, available_from, received_at,
             created_at, created_by, updated_at, updated_by, version, quantity_unit)
          VALUES (?, ?, ?, ?, ?, 'AVAILABLE', ?, 0, ?, ?, ?, ?, ?, ?, 0, 'CASE')
          """,
          lotId,
          TENANT,
          poolId,
          skuId,
          "LOT-" + index,
          new BigDecimal(quantities[index]),
          Timestamp.from(NOW.minusSeconds(60)),
          Timestamp.from(NOW.plusSeconds(index)),
          Timestamp.from(NOW),
          ACTOR,
          Timestamp.from(NOW),
          ACTOR);
    }
    return new Stock(poolId, lotIds);
  }

  private EventDelivery delivery(TradeOrderCreatedV1.Payload payload) {
    return delivery(payload.orderId(), object(payload).toString());
  }

  private EventDelivery delivery(UUID orderId, String payloadJson) {
    JsonNode body = read(payloadJson);
    String number = body.path("orderNumber").asText();
    return new EventDelivery(
        UUID.randomUUID(),
        TENANT,
        TradeOrderCreatedV1.TYPE,
        1,
        NOW,
        "trade-order",
        new EventDelivery.Subject("TRADE_ORDER", orderId, number),
        UUID.randomUUID(),
        UUID.randomUUID(),
        payloadJson);
  }

  private String legacyJson(TradeOrderCreatedV1.Payload payload) {
    ObjectNode body = object(payload);
    body.put("snapshotHash", "sha256:" + body.path("snapshotHash").asText());
    body.remove("supplyDecision");
    body.path("lines").forEach(line -> ((ObjectNode) line).remove("allocationMode"));
    return body.toString();
  }

  private ObjectNode object(Object value) {
    return json.valueToTree(value);
  }

  private void assertFinalPoison(UUID orderId, ObjectNode body, String code) {
    EventDelivery delivery = delivery(orderId, body.toString());
    RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, delivery));
    assertThat(failureRecorder.record(handler, delivery, failure))
        .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.FAILED_FINAL);
    assertThat(count("inventory.reservation", orderId)).isZero();
    assertThat(inboxError(delivery.eventId())).isEqualTo(code);
  }

  private JsonNode read(String value) {
    try {
      return json.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String reservationStatus(UUID orderId) {
    return reservationValue(orderId, "status");
  }

  private String reservationFailure(UUID orderId) {
    return reservationValue(orderId, "failure_code");
  }

  private String requestHash(UUID orderId) {
    return reservationValue(orderId, "request_hash");
  }

  private String reservationValue(UUID orderId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM inventory.reservation WHERE tenant_id = ? AND order_id = ?",
        String.class,
        TENANT,
        orderId);
  }

  private BigDecimal reserved(UUID lotId) {
    return jdbc.queryForObject(
        "SELECT reserved_quantity FROM inventory.inventory_lot WHERE tenant_id = ? AND id = ?",
        BigDecimal.class,
        TENANT,
        lotId);
  }

  private int count(String table, UUID orderId) {
    String join =
        table.equals("inventory.reservation")
            ? ""
            : " JOIN inventory.reservation r ON r.tenant_id = f.tenant_id AND r.id = f.reservation_id";
    String alias = table.equals("inventory.reservation") ? "r" : "f";
    return jdbc.queryForObject(
        "SELECT count(*) FROM "
            + table
            + " "
            + alias
            + join
            + " WHERE r.tenant_id = ? AND r.order_id = ?",
        Integer.class,
        TENANT,
        orderId);
  }

  private int totalQuantityFacts(UUID orderId) {
    return count("inventory.allocation", orderId) + count("inventory.inventory_movement", orderId);
  }

  private int conflictCount(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM inventory.reservation_request_conflict WHERE tenant_id = ? AND order_id = ?",
        Integer.class,
        TENANT,
        orderId);
  }

  private int outcomeCount(UUID orderId, String eventType) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FROM platform_event.event_publication
         WHERE tenant_id = ? AND subject_id = ? AND event_type = ?
        """,
        Integer.class,
        TENANT,
        reservationId(orderId),
        eventType);
  }

  private int outcomeCount(List<TradeOrderCreatedV1.Payload> payloads) {
    return payloads.stream()
        .mapToInt(
            payload ->
                outcomeCount(payload.orderId(), InventoryReservationConfirmedV1.TYPE)
                    + outcomeCount(payload.orderId(), InventoryReservationFailedV1.TYPE))
        .sum();
  }

  private UUID reservationId(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT id FROM inventory.reservation WHERE tenant_id = ? AND order_id = ?",
        UUID.class,
        TENANT,
        orderId);
  }

  private int statusCount(List<TradeOrderCreatedV1.Payload> payloads, String status) {
    return (int)
        payloads.stream()
            .filter(payload -> status.equals(reservationStatus(payload.orderId())))
            .count();
  }

  private String inboxError(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT last_error_code FROM platform_event.event_inbox WHERE consumer_name = ? AND event_id = ?",
        String.class,
        handler.consumerName(),
        eventId);
  }

  private static LineSpec line(
      UUID skuId, String quantity, String mode, UUID poolId, String supplyType) {
    return new LineSpec(skuId, quantity, mode, poolId, supplyType);
  }

  private static TenantContext exceptionOperator() {
    return new TenantContext(
        ACTOR,
        "Inventory Recovery Operator",
        new TenantId(TENANT),
        "Synthetic Cellars",
        "ACTIVE",
        null,
        java.util.Set.of("Trade Operator"),
        java.util.Set.of("trade-operator"),
        java.util.Set.of(PermissionCode.EXCEPTION_RECOVER),
        "subject-inventory-recovery",
        "tenant-inventory-recovery");
  }

  private static RuntimeException catchFailure(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected handler failure");
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  private record LineSpec(
      UUID skuId, String quantity, String mode, UUID poolId, String supplyType) {}

  private record Stock(UUID poolId, List<UUID> lotIds) {}
}
