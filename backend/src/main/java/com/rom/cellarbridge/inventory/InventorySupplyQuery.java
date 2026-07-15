package com.rom.cellarbridge.inventory;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface InventorySupplyQuery {

  Set<UUID> authorizedSupplyPoolIds();

  List<ExactLotAvailability> findAuthorizedLots(Set<UUID> supplyPoolIds);

  /** Internal module collaboration query. Results are not a reservation or customer promise. */
  List<RouteAvailability> findRouteAvailability(TenantId tenantId, Set<UUID> skuIds);

  record ExactLotAvailability(
      UUID supplyPoolId,
      UUID skuId,
      UUID lotId,
      String lotCode,
      String warehouseLabel,
      QuantityUnit quantityUnit,
      BigDecimal onHandQuantity,
      BigDecimal reservedQuantity,
      BigDecimal availableQuantity,
      int allocationPriority,
      long warehouseVersion,
      Instant availableFrom,
      Instant dataAsOf) {}

  record RouteAvailability(
      UUID supplyPoolId,
      UUID skuId,
      String routeCode,
      SupplyType supplyType,
      String currency,
      QuantityUnit quantityUnit,
      BigDecimal availableQuantity,
      Instant availableFrom,
      String confidence,
      String policyVersion,
      Instant dataAsOf) {}
}
