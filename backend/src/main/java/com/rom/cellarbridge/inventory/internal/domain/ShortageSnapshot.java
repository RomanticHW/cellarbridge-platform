package com.rom.cellarbridge.inventory.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ShortageSnapshot(
    UUID id,
    TenantId tenantId,
    UUID reservationId,
    UUID orderLineId,
    UUID skuId,
    QuantityUnit quantityUnit,
    BigDecimal requestedQuantity,
    BigDecimal availableQuantity,
    BigDecimal shortageQuantity,
    String failureCode,
    UUID supplyPoolId,
    SupplyType supplyType,
    Instant observedAt) {

  public ShortageSnapshot {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(orderLineId, "orderLineId");
    Objects.requireNonNull(skuId, "skuId");
    Objects.requireNonNull(quantityUnit, "quantityUnit");
    requestedQuantity = ExactQuantity.positive(requestedQuantity, "requestedQuantity");
    availableQuantity = ExactQuantity.nonNegative(availableQuantity, "availableQuantity");
    shortageQuantity = ExactQuantity.positive(shortageQuantity, "shortageQuantity");
    Reservation.requireText(failureCode, "failureCode");
    Objects.requireNonNull(observedAt, "observedAt");
    BigDecimal expected = requestedQuantity.subtract(availableQuantity).max(ExactQuantity.ZERO);
    if (expected.compareTo(shortageQuantity) != 0) {
      throw new IllegalArgumentException("Shortage must equal requested minus available");
    }
  }
}
