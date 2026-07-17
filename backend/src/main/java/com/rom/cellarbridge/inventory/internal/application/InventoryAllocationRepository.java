package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InventoryAllocationRepository {

  boolean isFixedPoolEligible(
      TenantId tenantId,
      UUID poolId,
      String routeCode,
      UUID skuId,
      QuantityUnit unit,
      SupplyType supplyType,
      Instant availableAt);

  List<CandidateLot> findAndLockCandidates(
      TenantId tenantId,
      String routeCode,
      UUID skuId,
      QuantityUnit unit,
      SupplyType supplyType,
      AllocationMode mode,
      UUID fixedPoolId,
      Instant availableAt);

  record CandidateLot(
      UUID poolId,
      UUID lotId,
      String lotCode,
      BigDecimal availableQuantity,
      int warehousePriority,
      long warehouseVersion) {}
}
