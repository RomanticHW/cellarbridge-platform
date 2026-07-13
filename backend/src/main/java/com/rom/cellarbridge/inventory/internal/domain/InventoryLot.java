package com.rom.cellarbridge.inventory.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public final class InventoryLot {

  private final UUID id;
  private final UUID skuId;
  private final UUID supplyPoolId;
  private final BigDecimal onHand;
  private final BigDecimal reserved;

  public InventoryLot(
      UUID id, UUID skuId, UUID supplyPoolId, BigDecimal onHand, BigDecimal reserved) {
    this.id = Objects.requireNonNull(id, "id");
    this.skuId = Objects.requireNonNull(skuId, "skuId");
    this.supplyPoolId = Objects.requireNonNull(supplyPoolId, "supplyPoolId");
    this.onHand = requireNonNegative(onHand, "onHand");
    this.reserved = requireNonNegative(reserved, "reserved");
    if (reserved.compareTo(onHand) > 0) {
      throw new IllegalArgumentException("Reserved quantity cannot exceed on-hand quantity");
    }
  }

  public BigDecimal available() {
    return onHand.subtract(reserved);
  }

  public UUID id() {
    return id;
  }

  public UUID skuId() {
    return skuId;
  }

  public UUID supplyPoolId() {
    return supplyPoolId;
  }

  public BigDecimal onHand() {
    return onHand;
  }

  public BigDecimal reserved() {
    return reserved;
  }

  private static BigDecimal requireNonNegative(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(field + " cannot be negative");
    }
    return value;
  }
}
