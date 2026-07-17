package com.rom.cellarbridge.inventory.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.internal.application.AtomicInventoryLotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAtomicInventoryLotRepository implements AtomicInventoryLotRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcAtomicInventoryLotRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<LotBalance> reserve(
      TenantId tenantId,
      UUID lotId,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      UUID actorId,
      Instant now) {
    return update(
        """
        UPDATE inventory.inventory_lot AS lot
           SET reserved_quantity = reserved_quantity + :quantity,
               updated_at = :now,
               updated_by = :actorId,
               version = version + 1
         WHERE tenant_id = :tenantId
           AND id = :lotId
           AND quantity_unit = :quantityUnit
           AND status = 'AVAILABLE'
           AND on_hand_quantity - reserved_quantity >= :quantity
           AND EXISTS (
               SELECT 1
                 FROM inventory.supply_pool pool
                 JOIN inventory.warehouse warehouse
                   ON warehouse.tenant_id = pool.tenant_id
                  AND warehouse.id = pool.warehouse_id
                WHERE pool.tenant_id = lot.tenant_id
                  AND pool.id = lot.supply_pool_id
                  AND pool.status = 'ACTIVE'
                  AND pool.automatically_reservable
                  AND warehouse.status = 'ACTIVE'
           )
        RETURNING id, quantity_unit, on_hand_quantity, reserved_quantity, version
        """,
        parameters(tenantId, lotId, quantityUnit, quantity, actorId, now));
  }

  @Override
  public Optional<LotBalance> release(
      TenantId tenantId,
      UUID lotId,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      UUID actorId,
      Instant now) {
    return update(
        """
        UPDATE inventory.inventory_lot
           SET reserved_quantity = reserved_quantity - :quantity,
               updated_at = :now,
               updated_by = :actorId,
               version = version + 1
         WHERE tenant_id = :tenantId
           AND id = :lotId
           AND quantity_unit = :quantityUnit
           AND reserved_quantity >= :quantity
        RETURNING id, quantity_unit, on_hand_quantity, reserved_quantity, version
        """,
        parameters(tenantId, lotId, quantityUnit, quantity, actorId, now));
  }

  @Override
  public Optional<LotBalance> consume(
      TenantId tenantId,
      UUID lotId,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      UUID actorId,
      Instant now) {
    return update(
        """
        UPDATE inventory.inventory_lot
           SET reserved_quantity = reserved_quantity - :quantity,
               on_hand_quantity = on_hand_quantity - :quantity,
               status = CASE WHEN on_hand_quantity = :quantity THEN 'DEPLETED' ELSE status END,
               updated_at = :now,
               updated_by = :actorId,
               version = version + 1
         WHERE tenant_id = :tenantId
           AND id = :lotId
           AND quantity_unit = :quantityUnit
           AND reserved_quantity >= :quantity
           AND on_hand_quantity >= :quantity
        RETURNING id, quantity_unit, on_hand_quantity, reserved_quantity, version
        """,
        parameters(tenantId, lotId, quantityUnit, quantity, actorId, now));
  }

  private Optional<LotBalance> update(String sql, MapSqlParameterSource parameters) {
    List<LotBalance> rows =
        jdbc.query(sql, parameters, (resultSet, rowNumber) -> mapBalance(resultSet));
    if (rows.size() > 1) {
      throw new IllegalStateException("Atomic Lot update returned more than one row");
    }
    return rows.stream().findFirst();
  }

  private static MapSqlParameterSource parameters(
      TenantId tenantId,
      UUID lotId,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      UUID actorId,
      Instant now) {
    return new MapSqlParameterSource()
        .addValue("tenantId", Objects.requireNonNull(tenantId, "tenantId").value())
        .addValue("lotId", Objects.requireNonNull(lotId, "lotId"))
        .addValue("quantityUnit", Objects.requireNonNull(quantityUnit, "quantityUnit").name())
        .addValue("quantity", exactPositive(quantity))
        .addValue("actorId", Objects.requireNonNull(actorId, "actorId"))
        .addValue("now", Timestamp.from(Objects.requireNonNull(now, "now")));
  }

  private static BigDecimal exactPositive(BigDecimal quantity) {
    Objects.requireNonNull(quantity, "quantity");
    BigDecimal scaled = quantity.setScale(6, RoundingMode.UNNECESSARY);
    if (scaled.signum() <= 0 || scaled.precision() > 19) {
      throw new IllegalArgumentException("quantity must be positive numeric(19,6)");
    }
    return scaled;
  }

  private static LotBalance mapBalance(ResultSet resultSet) throws SQLException {
    return new LotBalance(
        resultSet.getObject("id", UUID.class),
        QuantityUnit.valueOf(resultSet.getString("quantity_unit")),
        resultSet.getBigDecimal("on_hand_quantity"),
        resultSet.getBigDecimal("reserved_quantity"),
        resultSet.getLong("version"));
  }
}
