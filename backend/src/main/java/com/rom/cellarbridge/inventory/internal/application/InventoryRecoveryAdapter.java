package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.inventory.InventoryRecoveryOperations;
import com.rom.cellarbridge.inventory.InventoryReservationConfirmedV1;
import com.rom.cellarbridge.inventory.internal.application.InventoryAllocationService.BusinessFailure;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.ReservationAttempt;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InventoryRecoveryAdapter implements InventoryRecoveryOperations {

  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorization;
  private final InventoryReservationRepository reservations;
  private final InventoryAllocationService allocationService;
  private final ReliableEventPublisher events;
  private final Clock clock;

  InventoryRecoveryAdapter(
      TenantContextHolder contextHolder,
      AuthorizationService authorization,
      InventoryReservationRepository reservations,
      InventoryAllocationService allocationService,
      ReliableEventPublisher events,
      Clock clock) {
    this.contextHolder = contextHolder;
    this.authorization = authorization;
    this.reservations = reservations;
    this.allocationService = allocationService;
    this.events = events;
    this.clock = clock;
  }

  @Override
  @Transactional
  public RecoveryResult retryReservation(
      UUID reservationId, String orderNumber, UUID correlationId, UUID causationId) {
    TenantContext context = contextHolder.requireCurrent();
    authorization.require(PermissionCode.EXCEPTION_RECOVER, context.tenantId());
    if (context.partnerId() != null) {
      throw new AccessDeniedException("Customer cannot recover inventory Reservations");
    }
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(causationId, "causationId");
    if (orderNumber == null || orderNumber.isBlank()) {
      throw new IllegalArgumentException("Order number is required for Reservation recovery");
    }

    InventoryReservationRepository.ReservationAggregate aggregate =
        reservations
            .findByIdForUpdate(context.tenantId(), reservationId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation was not found"));
    Reservation reservation = aggregate.reservation();
    Optional<ReservationAttempt> priorAttempt =
        aggregate.attempts().stream()
            .filter(
                attempt ->
                    attempt.trigger() == ReservationAttempt.Trigger.MANUAL_RETRY
                        && attempt.causationId().equals(causationId))
            .findFirst();
    if (priorAttempt.isPresent()) {
      ReservationAttempt prior = priorAttempt.orElseThrow();
      if (prior.outcome() == ReservationAttempt.Outcome.FAILED) {
        return new RecoveryResult(
            reservation.id(),
            InventoryRecoveryOperations.Status.FAILED,
            reservation.version(),
            prior.failureCode(),
            true);
      }
      return result(reservation, true);
    }
    if (reservation.status() == Reservation.Status.CONFIRMED
        || reservation.status() == Reservation.Status.RELEASED
        || reservation.status() == Reservation.Status.CONSUMED) {
      return result(reservation, true);
    }
    if (reservation.status() != Reservation.Status.FAILED
        || reservation.supplyDecisionHash() == null
        || reservation.lines().stream()
            .anyMatch(line -> !line.supplyType().automaticallyReservable())) {
      return result(reservation, false);
    }

    Instant now = clock.instant();
    int attemptNumber = aggregate.attempts().size() + 1;
    try {
      InventoryAllocationService.Result allocation =
          allocationService.allocate(reservation, context.userId(), now);
      Reservation confirmed = reservation.retryOutcome(Reservation.Status.CONFIRMED, null, now);
      reservations.appendAttempt(
          context.tenantId(),
          attempt(
              confirmed,
              attemptNumber,
              ReservationAttempt.Outcome.CONFIRMED,
              null,
              correlationId,
              causationId,
              now));
      reservations.updateState(context.tenantId(), confirmed, reservation.version());
      publishConfirmed(confirmed, orderNumber.trim(), allocation, correlationId, causationId, now);
      return result(confirmed, false);
    } catch (BusinessFailure failure) {
      Reservation failed = reservation.retryOutcome(Reservation.Status.FAILED, failure.code(), now);
      reservations.appendAttempt(
          context.tenantId(),
          attempt(
              failed,
              attemptNumber,
              ReservationAttempt.Outcome.FAILED,
              failure.code(),
              correlationId,
              causationId,
              now));
      reservations.updateState(context.tenantId(), failed, reservation.version());
      return result(failed, false);
    }
  }

  private void publishConfirmed(
      Reservation reservation,
      String orderNumber,
      InventoryAllocationService.Result result,
      UUID correlationId,
      UUID causationId,
      Instant now) {
    var allocations =
        result.allocations().stream()
            .map(
                item ->
                    new InventoryReservationConfirmedV1.Allocation(
                        item.allocation().orderLineId(),
                        item.allocation().skuId(),
                        item.allocation().supplyPoolId(),
                        item.allocation().lotId(),
                        item.lotCode(),
                        item.allocation().supplyType(),
                        item.allocation().allocatedQuantity().toPlainString(),
                        item.allocation().quantityUnit()))
            .toList();
    events.publish(
        new PendingEvent(
            UUID.randomUUID(),
            reservation.tenantId().value(),
            InventoryReservationConfirmedV1.TYPE,
            1,
            now,
            "inventory",
            new PendingEvent.Subject(
                "INVENTORY_RESERVATION", reservation.id(), number(reservation)),
            correlationId,
            causationId,
            new InventoryReservationConfirmedV1.Payload(
                reservation.id(),
                number(reservation),
                reservation.orderId(),
                orderNumber,
                reservation.requestHash(),
                reservation.supplyDecisionHash(),
                reservation.routeCode(),
                now,
                allocations),
            Map.of()));
  }

  private static ReservationAttempt attempt(
      Reservation reservation,
      int attemptNumber,
      ReservationAttempt.Outcome outcome,
      String failureCode,
      UUID correlationId,
      UUID causationId,
      Instant now) {
    return new ReservationAttempt(
        UUID.randomUUID(),
        reservation.tenantId(),
        reservation.id(),
        attemptNumber,
        reservation.requestHash(),
        ReservationAttempt.Trigger.MANUAL_RETRY,
        now,
        now,
        outcome,
        failureCode,
        correlationId,
        causationId);
  }

  private static RecoveryResult result(Reservation reservation, boolean replayed) {
    InventoryRecoveryOperations.Status status =
        switch (reservation.status()) {
          case CONFIRMED -> InventoryRecoveryOperations.Status.CONFIRMED;
          case RELEASED -> InventoryRecoveryOperations.Status.RELEASED;
          case CONSUMED -> InventoryRecoveryOperations.Status.CONSUMED;
          case PENDING, FAILED -> InventoryRecoveryOperations.Status.FAILED;
        };
    return new RecoveryResult(
        reservation.id(), status, reservation.version(), reservation.failureCode(), replayed);
  }

  private static String number(Reservation reservation) {
    return "RES-" + reservation.id();
  }
}
