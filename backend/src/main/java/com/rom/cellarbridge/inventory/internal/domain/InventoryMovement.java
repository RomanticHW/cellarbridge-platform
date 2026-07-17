package com.rom.cellarbridge.inventory.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InventoryMovement(
    UUID id,
    TenantId tenantId,
    UUID reservationId,
    UUID allocationId,
    UUID orderLineId,
    UUID lotId,
    Type type,
    BigDecimal quantity,
    QuantityUnit quantityUnit,
    String businessKey,
    Instant occurredAt) {

  public InventoryMovement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(allocationId, "allocationId");
    Objects.requireNonNull(orderLineId, "orderLineId");
    Objects.requireNonNull(lotId, "lotId");
    Objects.requireNonNull(type, "type");
    quantity = ExactQuantity.positive(quantity, "quantity");
    Objects.requireNonNull(quantityUnit, "quantityUnit");
    Reservation.requireText(businessKey, "businessKey");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  public enum Type {
    RESERVE,
    RELEASE,
    CONSUME
  }
}
