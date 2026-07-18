package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.internal.application.ReservationOperationRepository.Action;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import com.rom.cellarbridge.inventory.internal.domain.InventoryMovement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryReservationMutationService {

  private final AtomicInventoryLotRepository lots;
  private final InventoryReservationRepository reservations;
  private final ReservationOperationRepository operations;

  public InventoryReservationMutationService(
      AtomicInventoryLotRepository lots,
      InventoryReservationRepository reservations,
      ReservationOperationRepository operations) {
    this.lots = lots;
    this.reservations = reservations;
    this.operations = operations;
  }

  @Transactional(propagation = Propagation.NESTED)
  public List<Allocation> apply(
      TenantId tenantId,
      UUID commandId,
      Action action,
      List<PlannedChange> changes,
      UUID actorId,
      Instant now) {
    List<Allocation> updated = new ArrayList<>();
    for (PlannedChange change : changes) {
      Allocation before = change.allocation();
      Allocation after =
          action == Action.RELEASE
              ? before.release(change.quantity())
              : before.consume(change.quantity());
      boolean lotUpdated =
          (action == Action.RELEASE
                  ? lots.release(
                      tenantId,
                      before.lotId(),
                      before.quantityUnit(),
                      change.quantity(),
                      actorId,
                      now)
                  : lots.consume(
                      tenantId,
                      before.lotId(),
                      before.quantityUnit(),
                      change.quantity(),
                      actorId,
                      now))
              .isPresent();
      if (!lotUpdated || !operations.updateAllocation(tenantId, before, after, action)) {
        throw new MutationConflict();
      }
      reservations.appendMovement(
          tenantId,
          new InventoryMovement(
              UUID.randomUUID(),
              tenantId,
              before.reservationId(),
              before.id(),
              before.orderLineId(),
              before.lotId(),
              action == Action.RELEASE
                  ? InventoryMovement.Type.RELEASE
                  : InventoryMovement.Type.CONSUME,
              change.quantity(),
              before.quantityUnit(),
              "operation:" + commandId + ":" + before.id(),
              now));
      updated.add(after);
    }
    return List.copyOf(updated);
  }

  record PlannedChange(Allocation allocation, BigDecimal quantity) {}

  static final class MutationConflict extends RuntimeException {}
}
