package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AtomicInventoryLotRepository {

  Optional<LotBalance> reserve(
      TenantId tenantId,
      UUID lotId,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      UUID actorId,
      Instant now);

  Optional<LotBalance> reserveExact(
      TenantId tenantId,
      UUID lotId,
      UUID supplyPoolId,
      UUID skuId,
      String routeCode,
      SupplyType supplyType,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      UUID actorId,
      Instant now);

  Optional<LotBalance> release(
      TenantId tenantId,
      UUID lotId,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      UUID actorId,
      Instant now);

  Optional<LotBalance> consume(
      TenantId tenantId,
      UUID lotId,
      QuantityUnit quantityUnit,
      BigDecimal quantity,
      UUID actorId,
      Instant now);

  record LotBalance(
      UUID lotId,
      QuantityUnit quantityUnit,
      BigDecimal onHandQuantity,
      BigDecimal reservedQuantity,
      long version) {}
}
