package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import com.rom.cellarbridge.inventory.internal.domain.InventoryMovement;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.ReservationAttempt;
import com.rom.cellarbridge.inventory.internal.domain.ShortageSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryReservationRepository {

  CreateResult create(TenantId tenantId, Reservation reservation);

  Optional<ReservationAggregate> findByTenantAndOrder(TenantId tenantId, UUID orderId);

  Optional<ReservationAggregate> findById(TenantId tenantId, UUID reservationId);

  Optional<ReservationAggregate> findByRequestHash(TenantId tenantId, String requestHash);

  void updateState(TenantId tenantId, Reservation reservation, long expectedVersion);

  boolean compareAndUpdateVersion(TenantId tenantId, Reservation reservation, long expectedVersion);

  void appendAttempt(TenantId tenantId, ReservationAttempt attempt);

  List<ReservationAttempt> findAttempts(TenantId tenantId, UUID reservationId);

  void appendAllocations(TenantId tenantId, List<Allocation> allocations);

  List<Allocation> findAllocationsByReservation(TenantId tenantId, UUID reservationId);

  void appendMovement(TenantId tenantId, InventoryMovement movement);

  List<InventoryMovement> findMovements(TenantId tenantId, UUID reservationId);

  void appendShortage(TenantId tenantId, ShortageSnapshot shortage);

  List<ShortageSnapshot> findShortages(TenantId tenantId, UUID reservationId);

  record CreateResult(Reservation reservation, boolean replayed) {}

  record ReservationAggregate(
      Reservation reservation,
      List<ReservationAttempt> attempts,
      List<Allocation> allocations,
      List<InventoryMovement> movements,
      List<ShortageSnapshot> shortages) {

    public ReservationAggregate {
      attempts = List.copyOf(attempts);
      allocations = List.copyOf(allocations);
      movements = List.copyOf(movements);
      shortages = List.copyOf(shortages);
    }
  }
}
