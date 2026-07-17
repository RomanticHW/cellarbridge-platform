package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.inventory.internal.application.InventoryAllocationRepository.CandidateLot;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import com.rom.cellarbridge.inventory.internal.domain.InventoryMovement;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.Line;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Executes all Lot mutations under one savepoint and never converts technical failures. */
@Service
class InventoryAllocationService {

  static final String INSUFFICIENT = "INVENTORY_INSUFFICIENT";
  static final String FIXED_POOL_INELIGIBLE = "INVENTORY_FIXED_POOL_INELIGIBLE";
  static final String ALLOCATION_CONFLICT = "INVENTORY_ALLOCATION_CONFLICT";
  private static final Comparator<Line> LINE_ORDER =
      Comparator.comparing((Line line) -> line.skuId().toString())
          .thenComparing(line -> line.quantityUnit().name())
          .thenComparing(line -> line.orderLineId().toString());

  private final InventoryAllocationRepository candidates;
  private final AtomicInventoryLotRepository lots;
  private final InventoryReservationRepository reservations;

  InventoryAllocationService(
      InventoryAllocationRepository candidates,
      AtomicInventoryLotRepository lots,
      InventoryReservationRepository reservations) {
    this.candidates = candidates;
    this.lots = lots;
    this.reservations = reservations;
  }

  @Transactional(propagation = Propagation.NESTED)
  Result allocate(Reservation reservation, UUID actorId, Instant now) {
    List<ExecutedAllocation> executed = new ArrayList<>();
    for (Line line : reservation.lines().stream().sorted(LINE_ORDER).toList()) {
      if (line.allocationMode() == Reservation.AllocationMode.FIXED_POOL
          && !candidates.isFixedPoolEligible(
              reservation.tenantId(),
              line.supplyPoolId(),
              reservation.routeCode(),
              line.skuId(),
              line.quantityUnit(),
              line.supplyType(),
              now)) {
        throw new BusinessFailure(FIXED_POOL_INELIGIBLE, line, null);
      }
      List<CandidateLot> ordered =
          candidates.findAndLockCandidates(
              reservation.tenantId(),
              reservation.routeCode(),
              line.skuId(),
              line.quantityUnit(),
              line.supplyType(),
              line.allocationMode(),
              line.supplyPoolId(),
              now);
      BigDecimal remaining = line.requestedQuantity();
      BigDecimal observed = BigDecimal.ZERO.setScale(6);
      for (CandidateLot candidate : ordered) {
        if (remaining.signum() == 0) {
          break;
        }
        BigDecimal quantity = remaining.min(candidate.availableQuantity());
        if (quantity.signum() <= 0) {
          continue;
        }
        if (lots.reserveExact(
                reservation.tenantId(),
                candidate.lotId(),
                candidate.poolId(),
                line.skuId(),
                reservation.routeCode(),
                line.supplyType(),
                line.quantityUnit(),
                quantity,
                actorId,
                now)
            .isEmpty()) {
          throw new BusinessFailure(ALLOCATION_CONFLICT, line, observed);
        }
        Allocation allocation = allocation(reservation, line, candidate, quantity);
        InventoryMovement movement = movement(reservation, allocation, now);
        executed.add(new ExecutedAllocation(allocation, movement, candidate.lotCode()));
        remaining = remaining.subtract(quantity);
        observed = observed.add(quantity);
      }
      if (remaining.signum() != 0) {
        throw new BusinessFailure(INSUFFICIENT, line, observed);
      }
    }
    List<Allocation> allocations = executed.stream().map(ExecutedAllocation::allocation).toList();
    reservations.appendAllocations(reservation.tenantId(), allocations);
    executed.forEach(item -> reservations.appendMovement(reservation.tenantId(), item.movement()));
    return new Result(executed);
  }

  private static Allocation allocation(
      Reservation reservation, Line line, CandidateLot candidate, BigDecimal quantity) {
    return new Allocation(
        UUID.randomUUID(),
        reservation.tenantId(),
        reservation.id(),
        line.orderLineId(),
        line.sourceQuotationLineId(),
        line.skuId(),
        line.quantityUnit(),
        line.supplyType(),
        line.allocationMode(),
        candidate.poolId(),
        candidate.lotId(),
        quantity,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        quantity,
        candidate.warehousePriority(),
        candidate.warehouseVersion());
  }

  private static InventoryMovement movement(
      Reservation reservation, Allocation allocation, Instant now) {
    return new InventoryMovement(
        UUID.randomUUID(),
        reservation.tenantId(),
        reservation.id(),
        allocation.id(),
        allocation.orderLineId(),
        allocation.lotId(),
        InventoryMovement.Type.RESERVE,
        allocation.allocatedQuantity(),
        allocation.quantityUnit(),
        "reserve:" + reservation.id() + ":" + allocation.id(),
        now);
  }

  record Result(List<ExecutedAllocation> allocations) {
    Result {
      allocations = List.copyOf(allocations);
    }
  }

  record ExecutedAllocation(Allocation allocation, InventoryMovement movement, String lotCode) {}

  static final class BusinessFailure extends RuntimeException {
    private final String code;
    private final Line line;
    private final BigDecimal observedAvailable;

    BusinessFailure(String code, Line line, BigDecimal observedAvailable) {
      super(code);
      this.code = code;
      this.line = line;
      this.observedAvailable = observedAvailable;
    }

    String code() {
      return code;
    }

    Line line() {
      return line;
    }

    BigDecimal observedAvailable() {
      return observedAvailable;
    }
  }
}
