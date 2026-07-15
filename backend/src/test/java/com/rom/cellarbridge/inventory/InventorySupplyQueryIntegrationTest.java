package com.rom.cellarbridge.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventorySupplyQuery.ExactLotAvailability;
import com.rom.cellarbridge.inventory.InventorySupplyQuery.RouteAvailability;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class InventorySupplyQueryIntegrationTest extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final UUID OTHER_TENANT = UUID.fromString("20000000-0000-4000-8000-000000000001");
  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final Instant DECISION_AT = Instant.parse("2026-07-15T12:00:00Z");

  @Autowired private InventorySupplyQuery query;
  @Autowired private TenantContextHolder contextHolder;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void keepsRouteAvailabilitySeparatedByQuantityUnit() {
    List<RouteAvailability> availability =
        query.findRouteAvailability(TENANT, Set.of(SKU), DECISION_AT);

    assertThat(availability)
        .extracting(
            RouteAvailability::routeCode,
            RouteAvailability::quantityUnit,
            item -> item.availableQuantity().toPlainString())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("NB_BONDED_B2B", QuantityUnit.CASE, "7.000000"),
            org.assertj.core.groups.Tuple.tuple(
                "SH_GENERAL_TRADE", QuantityUnit.BOTTLE, "10.000000"),
            org.assertj.core.groups.Tuple.tuple(
                "SH_GENERAL_TRADE", QuantityUnit.CASE, "56.000000"));
  }

  @Test
  void excludesSupplyFromInactiveWarehousesAndPools() {
    UUID skuId = UUID.randomUUID();
    UUID activeWarehouse = insertWarehouse(TENANT.value(), "ACTIVE", DECISION_AT.minusSeconds(300));
    UUID inactiveWarehouse =
        insertWarehouse(TENANT.value(), "INACTIVE", DECISION_AT.minusSeconds(300));

    UUID eligiblePool =
        insertPool(TENANT.value(), activeWarehouse, "ACTIVE", null, DECISION_AT.minusSeconds(240));
    UUID inactiveWarehousePool =
        insertPool(
            TENANT.value(), inactiveWarehouse, "ACTIVE", null, DECISION_AT.minusSeconds(240));
    UUID inactivePool =
        insertPool(
            TENANT.value(), activeWarehouse, "INACTIVE", null, DECISION_AT.minusSeconds(240));

    insertLot(TENANT.value(), eligiblePool, skuId, QuantityUnit.CASE, "5", null);
    insertLot(TENANT.value(), inactiveWarehousePool, skuId, QuantityUnit.CASE, "30", null);
    insertLot(TENANT.value(), inactivePool, skuId, QuantityUnit.CASE, "40", null);

    assertSingleCaseQuantity(query.findRouteAvailability(TENANT, Set.of(skuId), DECISION_AT), "5");
  }

  @Test
  void excludesPoolsAndLotsThatBecomeAvailableAfterDecisionTime() {
    UUID skuId = UUID.randomUUID();
    UUID warehouse = insertWarehouse(TENANT.value(), "ACTIVE", DECISION_AT.minusSeconds(300));
    UUID eligiblePool =
        insertPool(TENANT.value(), warehouse, "ACTIVE", null, DECISION_AT.minusSeconds(240));
    UUID futurePool =
        insertPool(
            TENANT.value(),
            warehouse,
            "ACTIVE",
            DECISION_AT.plusSeconds(1),
            DECISION_AT.minusSeconds(240));

    insertLot(TENANT.value(), eligiblePool, skuId, QuantityUnit.CASE, "5", null);
    insertLot(
        TENANT.value(), eligiblePool, skuId, QuantityUnit.CASE, "30", DECISION_AT.plusSeconds(1));
    insertLot(TENANT.value(), futurePool, skuId, QuantityUnit.CASE, "40", null);

    assertSingleCaseQuantity(query.findRouteAvailability(TENANT, Set.of(skuId), DECISION_AT), "5");
  }

  @Test
  void includesPoolAndLotAvailableExactlyAtDecisionTime() {
    UUID skuId = UUID.randomUUID();
    UUID warehouse = insertWarehouse(TENANT.value(), "ACTIVE", DECISION_AT.minusSeconds(300));
    UUID pool =
        insertPool(TENANT.value(), warehouse, "ACTIVE", DECISION_AT, DECISION_AT.minusSeconds(240));
    insertLot(TENANT.value(), pool, skuId, QuantityUnit.CASE, "8", DECISION_AT);

    assertSingleCaseQuantity(query.findRouteAvailability(TENANT, Set.of(skuId), DECISION_AT), "8");
  }

  @Test
  void usesLaterPoolOrLotConstraintThenEarliestEffectiveStartForTheGroup() {
    UUID skuId = UUID.randomUUID();
    Instant poolAvailableFrom = DECISION_AT.minusSeconds(600);
    UUID warehouse = insertWarehouse(TENANT.value(), "ACTIVE", DECISION_AT.minusSeconds(900));
    UUID pool =
        insertPool(
            TENANT.value(), warehouse, "ACTIVE", poolAvailableFrom, DECISION_AT.minusSeconds(840));
    insertLot(TENANT.value(), pool, skuId, QuantityUnit.CASE, "5", DECISION_AT.minusSeconds(900));
    insertLot(TENANT.value(), pool, skuId, QuantityUnit.CASE, "7", DECISION_AT.minusSeconds(300));

    assertThat(query.findRouteAvailability(TENANT, Set.of(skuId), DECISION_AT))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.availableQuantity()).isEqualByComparingTo("12");
              assertThat(item.availableFrom()).isEqualTo(poolAvailableFrom);
            });
  }

  @Test
  void keepsGroupedAvailabilityFromNullWhenAnyContributingPairIsUnrestricted() {
    UUID skuId = UUID.randomUUID();
    UUID warehouse = insertWarehouse(TENANT.value(), "ACTIVE", DECISION_AT.minusSeconds(900));
    UUID pool =
        insertPool(TENANT.value(), warehouse, "ACTIVE", null, DECISION_AT.minusSeconds(840));
    insertLot(TENANT.value(), pool, skuId, QuantityUnit.CASE, "5", null);
    insertLot(TENANT.value(), pool, skuId, QuantityUnit.CASE, "7", DECISION_AT.minusSeconds(300));

    assertThat(query.findRouteAvailability(TENANT, Set.of(skuId), DECISION_AT))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.availableQuantity()).isEqualByComparingTo("12");
              assertThat(item.availableFrom()).isNull();
            });
  }

  @Test
  void excludesInventoryOwnedByAnotherTenant() {
    UUID skuId = UUID.randomUUID();
    UUID tenantWarehouse = insertWarehouse(TENANT.value(), "ACTIVE", DECISION_AT.minusSeconds(300));
    UUID otherWarehouse = insertWarehouse(OTHER_TENANT, "ACTIVE", DECISION_AT.minusSeconds(300));
    UUID tenantPool =
        insertPool(TENANT.value(), tenantWarehouse, "ACTIVE", null, DECISION_AT.minusSeconds(240));
    UUID otherPool =
        insertPool(OTHER_TENANT, otherWarehouse, "ACTIVE", null, DECISION_AT.minusSeconds(240));
    insertLot(TENANT.value(), tenantPool, skuId, QuantityUnit.CASE, "3", null);
    insertLot(OTHER_TENANT, otherPool, skuId, QuantityUnit.CASE, "300", null);

    assertSingleCaseQuantity(query.findRouteAvailability(TENANT, Set.of(skuId), DECISION_AT), "3");
  }

  @Test
  void keepsCaseAndBottleInventorySeparateAtDecisionTime() {
    UUID skuId = UUID.randomUUID();
    UUID warehouse = insertWarehouse(TENANT.value(), "ACTIVE", DECISION_AT.minusSeconds(300));
    UUID pool =
        insertPool(TENANT.value(), warehouse, "ACTIVE", null, DECISION_AT.minusSeconds(240));
    insertLot(TENANT.value(), pool, skuId, QuantityUnit.CASE, "4", null);
    insertLot(TENANT.value(), pool, skuId, QuantityUnit.BOTTLE, "11", null);

    assertThat(query.findRouteAvailability(TENANT, Set.of(skuId), DECISION_AT))
        .extracting(
            RouteAvailability::quantityUnit,
            item -> item.availableQuantity().stripTrailingZeros().toPlainString())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(QuantityUnit.BOTTLE, "11"),
            org.assertj.core.groups.Tuple.tuple(QuantityUnit.CASE, "4"));
  }

  @Test
  void includesWarehouseUpdateInRouteAvailabilityDataAsOf() {
    UUID skuId = UUID.randomUUID();
    Instant warehouseUpdatedAt = DECISION_AT.minusSeconds(60);
    UUID warehouse = insertWarehouse(TENANT.value(), "ACTIVE", warehouseUpdatedAt);
    UUID pool =
        insertPool(TENANT.value(), warehouse, "ACTIVE", null, DECISION_AT.minusSeconds(240));
    insertLot(TENANT.value(), pool, skuId, QuantityUnit.CASE, "6", null);

    assertThat(query.findRouteAvailability(TENANT, Set.of(skuId), DECISION_AT))
        .singleElement()
        .extracting(RouteAvailability::dataAsOf)
        .isEqualTo(warehouseUpdatedAt);
  }

  @Test
  void requiresDecisionTimeForRouteAvailability() {
    assertThatNullPointerException()
        .isThrownBy(() -> query.findRouteAvailability(TENANT, Set.of(SKU), null));
  }

  @Test
  void returnsExactUnitPriorityAndWarehouseVersionInDeterministicOrder() {
    try (TenantContextHolder.Scope ignored = contextHolder.open(context())) {
      Set<UUID> pools = query.authorizedSupplyPoolIds();
      List<ExactLotAvailability> lots = query.findAuthorizedLots(pools);

      assertThat(lots).isNotEmpty();
      assertThat(lots.getFirst().quantityUnit()).isEqualTo(QuantityUnit.BOTTLE);
      assertThat(lots.getFirst().allocationPriority()).isEqualTo(10);
      assertThat(lots.getFirst().warehouseVersion()).isZero();
      assertThat(lots)
          .extracting(ExactLotAvailability::lotCode)
          .startsWith("LOT-EAST-2019-BOTTLE-A", "LOT-EAST-2019-A", "LOT-EAST-2019-B");
    }
  }

  @Test
  void seedsExplicitWarehousePrioritiesWithoutArtificialVersionChanges() {
    assertThat(
            jdbc.queryForList(
                """
                SELECT allocation_priority
                  FROM inventory.warehouse
                 ORDER BY tenant_id, id
                """,
                Integer.class))
        .containsExactly(10, 20, 30, 90, 10);
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM inventory.warehouse ORDER BY tenant_id, id", Long.class))
        .containsOnly(0L);
  }

  private UUID insertWarehouse(UUID tenantId, String status, Instant updatedAt) {
    UUID warehouseId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory.warehouse
          (id, tenant_id, code, name, country_code, city, status, allocation_priority,
           created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, 'Route decision warehouse', 'CN', 'Shanghai', ?, 50,
                ?, ?, ?, ?, 0)
        """,
        warehouseId,
        tenantId,
        "WH-" + warehouseId,
        status,
        Timestamp.from(updatedAt.minusSeconds(60)),
        ACTOR,
        Timestamp.from(updatedAt),
        ACTOR);
    return warehouseId;
  }

  private UUID insertPool(
      UUID tenantId, UUID warehouseId, String status, Instant availableFrom, Instant updatedAt) {
    UUID poolId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory.supply_pool
          (id, tenant_id, warehouse_id, code, supply_type, route_code, currency,
           available_from, confidence, policy_version, status,
           created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, 'DOMESTIC_ON_HAND', 'SH_GENERAL_TRADE', 'CNY',
                ?, 'HIGH', 'ROUTE-DECISION-TEST', ?, ?, ?, ?, ?, 0)
        """,
        poolId,
        tenantId,
        warehouseId,
        "POOL-" + poolId,
        timestamp(availableFrom),
        status,
        Timestamp.from(updatedAt.minusSeconds(60)),
        ACTOR,
        Timestamp.from(updatedAt),
        ACTOR);
    return poolId;
  }

  private void insertLot(
      UUID tenantId,
      UUID poolId,
      UUID skuId,
      QuantityUnit quantityUnit,
      String onHandQuantity,
      Instant availableFrom) {
    UUID lotId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory.inventory_lot
          (id, tenant_id, supply_pool_id, sku_id, lot_code, status, quantity_unit,
           on_hand_quantity, reserved_quantity, available_from, received_at,
           created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, ?, 'AVAILABLE', ?, ?, 0, ?, ?, ?, ?, ?, ?, 0)
        """,
        lotId,
        tenantId,
        poolId,
        skuId,
        "LOT-" + lotId,
        quantityUnit.name(),
        new BigDecimal(onHandQuantity),
        timestamp(availableFrom),
        Timestamp.from(DECISION_AT.minusSeconds(600)),
        Timestamp.from(DECISION_AT.minusSeconds(540)),
        ACTOR,
        Timestamp.from(DECISION_AT.minusSeconds(180)),
        ACTOR);
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static void assertSingleCaseQuantity(
      List<RouteAvailability> availability, String expectedQuantity) {
    assertThat(availability)
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.routeCode()).isEqualTo("SH_GENERAL_TRADE");
              assertThat(item.quantityUnit()).isEqualTo(QuantityUnit.CASE);
              assertThat(item.availableQuantity())
                  .isEqualByComparingTo(new BigDecimal(expectedQuantity));
            });
  }

  private static TenantContext context() {
    return new TenantContext(
        UUID.fromString("11200000-0000-4000-8000-000000000004"),
        "North Admin",
        TENANT,
        "North Cellars",
        "ACTIVE",
        null,
        Set.of("Tenant Administrator"),
        Set.of("tenant-admin"),
        Set.of(PermissionCode.INVENTORY_READ_EXACT),
        "inventory-query-subject",
        "inventory-query-tenant");
  }
}
