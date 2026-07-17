package com.rom.cellarbridge.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reliable business failure fact; technical delivery failures never use this event. */
public final class InventoryReservationFailedV1 {

  public static final String TYPE = "cellarbridge.inventory.reservation-failed.v1";

  private InventoryReservationFailedV1() {}

  public record Payload(
      UUID reservationId,
      String reservationNumber,
      UUID orderId,
      String orderNumber,
      String requestHash,
      String supplyDecisionHash,
      Instant failedAt,
      String reasonCode,
      List<LineFailure> lineFailures,
      boolean retryable) {

    public Payload {
      lineFailures = List.copyOf(lineFailures);
    }
  }

  public record LineFailure(
      UUID orderLineId,
      UUID skuId,
      String skuCode,
      String requestedQuantity,
      QuantityUnit unit,
      String allocationMode,
      UUID supplyPoolId,
      SupplyType supplyType,
      String observedAvailableQuantity,
      String shortageQuantity) {}
}
