package com.rom.cellarbridge.inventory.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.InventoryAllocationRepository;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInventoryAllocationRepository implements InventoryAllocationRepository {

  private final NamedParameterJdbcTemplate jdbc;

  JdbcInventoryAllocationRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean isFixedPoolEligible(
      TenantId tenantId,
      UUID poolId,
      String routeCode,
      UUID skuId,
      QuantityUnit unit,
      SupplyType supplyType,
      Instant availableAt) {
    Boolean eligible =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
                FROM inventory.supply_pool pool
                JOIN inventory.warehouse warehouse
                  ON warehouse.tenant_id = pool.tenant_id
                 AND warehouse.id = pool.warehouse_id
               WHERE pool.tenant_id = :tenantId
                 AND pool.id = :poolId
                 AND pool.route_code = :routeCode
                 AND pool.supply_type = :supplyType
                 AND pool.status = 'ACTIVE'
                 AND pool.automatically_reservable
                 AND warehouse.status = 'ACTIVE'
                 AND (pool.available_from IS NULL OR pool.available_from <= :availableAt)
                 AND EXISTS (
                   SELECT 1
                     FROM inventory.inventory_lot lot
                    WHERE lot.tenant_id = pool.tenant_id
                      AND lot.supply_pool_id = pool.id
                      AND lot.sku_id = :skuId
                      AND lot.quantity_unit = :unit))
            """,
            parameters(tenantId, routeCode, skuId, unit, supplyType, poolId, availableAt),
            Boolean.class);
    return Boolean.TRUE.equals(eligible);
  }

  @Override
  public List<CandidateLot> findAndLockCandidates(
      TenantId tenantId,
      String routeCode,
      UUID skuId,
      QuantityUnit unit,
      SupplyType supplyType,
      AllocationMode mode,
      UUID fixedPoolId,
      Instant availableAt) {
    return jdbc.query(
        """
        SELECT pool.id AS pool_id,
               lot.id AS lot_id,
               lot.lot_code,
               lot.on_hand_quantity - lot.reserved_quantity AS available_quantity,
               warehouse.allocation_priority,
               warehouse.version AS warehouse_version
          FROM inventory.inventory_lot lot
          JOIN inventory.supply_pool pool
            ON pool.tenant_id = lot.tenant_id
           AND pool.id = lot.supply_pool_id
          JOIN inventory.warehouse warehouse
            ON warehouse.tenant_id = pool.tenant_id
           AND warehouse.id = pool.warehouse_id
         WHERE lot.tenant_id = :tenantId
           AND lot.sku_id = :skuId
           AND lot.quantity_unit = :unit
           AND lot.status = 'AVAILABLE'
           AND lot.on_hand_quantity > lot.reserved_quantity
           AND pool.status = 'ACTIVE'
           AND pool.automatically_reservable
           AND pool.route_code = :routeCode
           AND pool.supply_type = :supplyType
           AND warehouse.status = 'ACTIVE'
           AND (pool.available_from IS NULL OR pool.available_from <= :availableAt)
           AND (lot.available_from IS NULL OR lot.available_from <= :availableAt)
           AND (:mode = 'ROUTE_ELIGIBLE_AUTO' OR pool.id = :poolId)
         ORDER BY warehouse.allocation_priority,
                  lot.available_from NULLS LAST,
                  lot.received_at NULLS LAST,
                  lot.lot_code COLLATE "C",
                  lot.id
         FOR UPDATE OF lot
        """,
        parameters(tenantId, routeCode, skuId, unit, supplyType, fixedPoolId, availableAt)
            .addValue("mode", mode.name()),
        (resultSet, rowNumber) ->
            new CandidateLot(
                resultSet.getObject("pool_id", UUID.class),
                resultSet.getObject("lot_id", UUID.class),
                resultSet.getString("lot_code"),
                resultSet.getBigDecimal("available_quantity"),
                resultSet.getInt("allocation_priority"),
                resultSet.getLong("warehouse_version")));
  }

  private static MapSqlParameterSource parameters(
      TenantId tenantId,
      String routeCode,
      UUID skuId,
      QuantityUnit unit,
      SupplyType supplyType,
      UUID poolId,
      Instant availableAt) {
    return new MapSqlParameterSource()
        .addValue("tenantId", tenantId.value())
        .addValue("routeCode", routeCode)
        .addValue("skuId", skuId)
        .addValue("unit", unit.name())
        .addValue("supplyType", supplyType.name())
        .addValue("poolId", poolId)
        .addValue("availableAt", Timestamp.from(availableAt));
  }
}
