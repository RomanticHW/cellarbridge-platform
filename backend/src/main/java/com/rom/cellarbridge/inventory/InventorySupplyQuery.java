package com.rom.cellarbridge.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface InventorySupplyQuery {

  Set<UUID> authorizedSupplyPoolIds();

  List<ExactLotAvailability> findAuthorizedLots(Set<UUID> supplyPoolIds);

  record ExactLotAvailability(
      UUID supplyPoolId,
      UUID skuId,
      UUID lotId,
      String lotCode,
      String warehouseLabel,
      BigDecimal onHandQuantity,
      BigDecimal reservedQuantity,
      BigDecimal availableQuantity,
      Instant availableFrom,
      Instant dataAsOf) {}
}
