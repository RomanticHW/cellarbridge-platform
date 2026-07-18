package com.rom.cellarbridge.inventory.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record Allocation(
    UUID id,
    TenantId tenantId,
    UUID reservationId,
    UUID orderLineId,
    UUID sourceQuotationLineId,
    UUID skuId,
    QuantityUnit quantityUnit,
    SupplyType supplyType,
    AllocationMode allocationMode,
    UUID supplyPoolId,
    UUID lotId,
    BigDecimal allocatedQuantity,
    BigDecimal releasedQuantity,
    BigDecimal consumedQuantity,
    BigDecimal remainingReservedQuantity,
    int warehousePriority,
    long warehouseVersion) {

  public Allocation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(orderLineId, "orderLineId");
    Objects.requireNonNull(sourceQuotationLineId, "sourceQuotationLineId");
    Objects.requireNonNull(skuId, "skuId");
    Objects.requireNonNull(quantityUnit, "quantityUnit");
    Objects.requireNonNull(supplyType, "supplyType");
    Objects.requireNonNull(allocationMode, "allocationMode");
    Objects.requireNonNull(supplyPoolId, "supplyPoolId");
    Objects.requireNonNull(lotId, "lotId");
    allocatedQuantity = ExactQuantity.positive(allocatedQuantity, "allocatedQuantity");
    releasedQuantity = ExactQuantity.nonNegative(releasedQuantity, "releasedQuantity");
    consumedQuantity = ExactQuantity.nonNegative(consumedQuantity, "consumedQuantity");
    remainingReservedQuantity =
        ExactQuantity.nonNegative(remainingReservedQuantity, "remainingReservedQuantity");
    if (releasedQuantity
            .add(consumedQuantity)
            .add(remainingReservedQuantity)
            .compareTo(allocatedQuantity)
        != 0) {
      throw new IllegalArgumentException("Allocation quantity conservation is broken");
    }
    if (warehousePriority < 0 || warehouseVersion < 0) {
      throw new IllegalArgumentException("Warehouse priority evidence cannot be negative");
    }
  }

  public Allocation release(BigDecimal quantity) {
    BigDecimal exact = ExactQuantity.positive(quantity, "releaseQuantity");
    if (consumedQuantity.signum() != 0 || exact.compareTo(remainingReservedQuantity) > 0) {
      throw new IllegalStateException("Allocation cannot release the requested quantity");
    }
    return copy(
        releasedQuantity.add(exact), consumedQuantity, remainingReservedQuantity.subtract(exact));
  }

  public Allocation consume(BigDecimal quantity) {
    BigDecimal exact = ExactQuantity.positive(quantity, "consumeQuantity");
    if (releasedQuantity.signum() != 0 || exact.compareTo(remainingReservedQuantity) > 0) {
      throw new IllegalStateException("Allocation cannot consume the requested quantity");
    }
    return copy(
        releasedQuantity, consumedQuantity.add(exact), remainingReservedQuantity.subtract(exact));
  }

  private Allocation copy(BigDecimal released, BigDecimal consumed, BigDecimal remainingReserved) {
    return new Allocation(
        id,
        tenantId,
        reservationId,
        orderLineId,
        sourceQuotationLineId,
        skuId,
        quantityUnit,
        supplyType,
        allocationMode,
        supplyPoolId,
        lotId,
        allocatedQuantity,
        released,
        consumed,
        remainingReserved,
        warehousePriority,
        warehouseVersion);
  }
}
