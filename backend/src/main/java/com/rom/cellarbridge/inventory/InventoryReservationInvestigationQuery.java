package com.rom.cellarbridge.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Source-owned, read-only Inventory Reservation facts for bounded business investigation. */
public interface InventoryReservationInvestigationQuery {

  Optional<ReservationSource> findById(UUID reservationId);

  Optional<ReservationSource> findByOrderId(UUID orderId);

  /** Does not distinguish a missing Reservation from one outside the current Warehouse scope. */
  boolean hasCurrentWarehouseAssignment(UUID reservationId);

  record UntrustedBusinessData(String classification, String value, boolean truncated) {}

  record ReservationSource(
      UUID id,
      UUID orderId,
      String status,
      String failureCode,
      Integer latestAttemptNumber,
      long version,
      Instant updatedAt,
      List<ExactAllocation> allocations,
      List<ExactShortage> shortages) {

    public ReservationSource {
      allocations = List.copyOf(allocations);
      shortages = List.copyOf(shortages);
    }
  }

  record ExactAllocation(
      UUID allocationId,
      UUID orderLineId,
      UUID skuId,
      String requestedQuantity,
      String allocatedQuantity,
      String releasedQuantity,
      String consumedQuantity,
      String remainingReservedQuantity,
      String quantityUnit,
      UUID supplyPoolId,
      UUID lotId,
      UntrustedBusinessData warehouseLabel) {}

  record ExactShortage(
      UUID orderLineId,
      UUID skuId,
      String requestedQuantity,
      String availableQuantity,
      String shortageQuantity,
      String quantityUnit,
      String failureCode,
      UUID supplyPoolId,
      UntrustedBusinessData warehouseLabel) {}
}
