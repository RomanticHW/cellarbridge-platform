package com.rom.cellarbridge.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reliable fact emitted after every order line is reserved in one transaction. */
public final class InventoryReservationConfirmedV1 {

  public static final String TYPE = "cellarbridge.inventory.reservation-confirmed.v1";

  private InventoryReservationConfirmedV1() {}

  public record Payload(
      UUID reservationId,
      String reservationNumber,
      UUID orderId,
      String orderNumber,
      String requestHash,
      String supplyDecisionHash,
      Instant confirmedAt,
      List<Allocation> allocations) {

    public Payload {
      allocations = List.copyOf(allocations);
    }
  }

  public record Allocation(
      UUID orderLineId,
      UUID skuId,
      UUID supplyPoolId,
      UUID lotId,
      String lotCode,
      SupplyType supplyType,
      String quantity,
      QuantityUnit unit) {}
}
