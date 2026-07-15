package com.rom.cellarbridge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventorySupplyQuery.ExactLotAvailability;
import com.rom.cellarbridge.inventory.InventorySupplyQuery.RouteAvailability;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventorySupplyQueryIntegrationTest extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");

  @Autowired private InventorySupplyQuery query;
  @Autowired private TenantContextHolder contextHolder;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void keepsRouteAvailabilitySeparatedByQuantityUnit() {
    List<RouteAvailability> availability = query.findRouteAvailability(TENANT, Set.of(SKU));

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
