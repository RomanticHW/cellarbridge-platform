package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.inventory.InventorySupplyQuery;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcInventorySupplyQuery implements InventorySupplyQuery {

  private final NamedParameterJdbcTemplate jdbc;
  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorizationService;

  public JdbcInventorySupplyQuery(
      NamedParameterJdbcTemplate jdbc,
      TenantContextHolder contextHolder,
      AuthorizationService authorizationService) {
    this.jdbc = jdbc;
    this.contextHolder = contextHolder;
    this.authorizationService = authorizationService;
  }

  @Override
  public Set<UUID> authorizedSupplyPoolIds() {
    TenantContext context = requireExactAccess();
    return jdbc
        .query(
            """
            SELECT sp.id
              FROM inventory.supply_pool sp
              JOIN inventory.warehouse w
                ON w.tenant_id = sp.tenant_id
               AND w.id = sp.warehouse_id
              JOIN inventory.warehouse_assignment wa
                ON wa.tenant_id = sp.tenant_id
               AND wa.warehouse_id = sp.warehouse_id
             WHERE sp.tenant_id = :tenantId
               AND wa.user_id = :userId
               AND sp.status = 'ACTIVE'
               AND w.status = 'ACTIVE'
             ORDER BY sp.id
            """,
            parameters(context),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
        .stream()
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public List<ExactLotAvailability> findAuthorizedLots(Set<UUID> supplyPoolIds) {
    if (supplyPoolIds == null || supplyPoolIds.isEmpty()) {
      return List.of();
    }
    TenantContext context = requireExactAccess();
    MapSqlParameterSource parameters = parameters(context).addValue("supplyPoolIds", supplyPoolIds);
    return jdbc.query(
        """
        SELECT l.supply_pool_id,
               l.sku_id,
               l.id AS lot_id,
               l.lot_code,
               w.name AS warehouse_label,
               l.on_hand_quantity,
               l.reserved_quantity,
               l.on_hand_quantity - l.reserved_quantity AS available_quantity,
               l.available_from,
               GREATEST(l.updated_at, sp.updated_at, w.updated_at) AS data_as_of
          FROM inventory.inventory_lot l
          JOIN inventory.supply_pool sp
            ON sp.tenant_id = l.tenant_id
           AND sp.id = l.supply_pool_id
          JOIN inventory.warehouse w
            ON w.tenant_id = sp.tenant_id
           AND w.id = sp.warehouse_id
          JOIN inventory.warehouse_assignment wa
            ON wa.tenant_id = w.tenant_id
           AND wa.warehouse_id = w.id
           AND wa.user_id = :userId
         WHERE l.tenant_id = :tenantId
           AND l.supply_pool_id IN (:supplyPoolIds)
           AND l.status = 'AVAILABLE'
           AND sp.status = 'ACTIVE'
           AND w.status = 'ACTIVE'
         ORDER BY l.supply_pool_id, l.available_from NULLS LAST, l.lot_code, l.id
        """,
        parameters,
        (resultSet, rowNumber) ->
            new ExactLotAvailability(
                resultSet.getObject("supply_pool_id", UUID.class),
                resultSet.getObject("sku_id", UUID.class),
                resultSet.getObject("lot_id", UUID.class),
                resultSet.getString("lot_code"),
                resultSet.getString("warehouse_label"),
                resultSet.getBigDecimal("on_hand_quantity"),
                resultSet.getBigDecimal("reserved_quantity"),
                resultSet.getBigDecimal("available_quantity"),
                resultSet.getTimestamp("available_from") == null
                    ? null
                    : resultSet.getTimestamp("available_from").toInstant(),
                resultSet.getTimestamp("data_as_of").toInstant()));
  }

  private TenantContext requireExactAccess() {
    TenantContext context = contextHolder.requireCurrent();
    authorizationService.require(PermissionCode.INVENTORY_READ_EXACT, context.tenantId());
    return context;
  }

  private static MapSqlParameterSource parameters(TenantContext context) {
    return new MapSqlParameterSource()
        .addValue("tenantId", context.tenantId().value())
        .addValue("userId", context.userId());
  }
}
