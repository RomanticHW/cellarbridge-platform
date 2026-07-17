package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.inventory.InventoryReservationConfirmedV1;
import com.rom.cellarbridge.inventory.InventoryReservationFailedV1;
import com.rom.cellarbridge.inventory.internal.application.InventoryAllocationService.BusinessFailure;
import com.rom.cellarbridge.inventory.internal.application.TradeOrderReservationRequestParser.Line;
import com.rom.cellarbridge.inventory.internal.application.TradeOrderReservationRequestParser.Request;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.ReservationAttempt;
import com.rom.cellarbridge.inventory.internal.domain.ReservationRequestConflict;
import com.rom.cellarbridge.inventory.internal.domain.ShortageSnapshot;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/** Consumes one self-contained Trade Order fact without reading another module's tables. */
@Component
public final class TradeOrderCreatedReservationHandler implements LocalEventHandler {

  static final String CONSUMER_NAME = "inventory.trade-order-created.v1";
  static final String MANUAL_FAILURE = "SUPPLY_NOT_AUTOMATICALLY_RESERVABLE";
  static final String LEGACY_FAILURE = "SUPPLY_DECISION_MISSING";
  private static final UUID SYSTEM_ACTOR =
      UUID.nameUUIDFromBytes(CONSUMER_NAME.getBytes(StandardCharsets.UTF_8));

  private final TradeOrderReservationRequestParser parser;
  private final InventoryReservationRepository reservations;
  private final ReservationRequestConflictRepository conflicts;
  private final InventoryAllocationService allocationService;
  private final ReliableEventPublisher eventPublisher;
  private final Clock clock;

  TradeOrderCreatedReservationHandler(
      TradeOrderReservationRequestParser parser,
      InventoryReservationRepository reservations,
      ReservationRequestConflictRepository conflicts,
      InventoryAllocationService allocationService,
      ReliableEventPublisher eventPublisher,
      Clock clock) {
    this.parser = parser;
    this.reservations = reservations;
    this.conflicts = conflicts;
    this.allocationService = allocationService;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  @Override
  public String consumerName() {
    return CONSUMER_NAME;
  }

  @Override
  public String eventType() {
    return TradeOrderReservationRequestParser.EVENT_TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      Request request = parser.parse(delivery);
      Instant now = max(clock.instant(), delivery.occurredAt());
      var existing = reservations.findByTenantAndOrder(request.tenantId(), request.orderId());
      if (existing.isPresent()) {
        return replayOrConflict(existing.orElseThrow().reservation(), request, delivery, now);
      }
      if (request.kind() == TradeOrderReservationRequestParser.Kind.LEGACY) {
        return createLegacyFailure(request, delivery, now);
      }
      Reservation pending = pending(request, now);
      InventoryReservationRepository.CreateResult created = create(pending, request);
      if (created == null) {
        return conflictAfterCreateRace(request, delivery, now);
      }
      if (created.replayed()) {
        return replayOrConflict(created.reservation(), request, delivery, now);
      }
      List<Line> manual =
          request.lines().stream()
              .filter(line -> !line.requestLine().supplyType().automaticallyReservable())
              .toList();
      if (!manual.isEmpty()) {
        return fail(pending, request, delivery, now, MANUAL_FAILURE, manual, null);
      }
      try {
        InventoryAllocationService.Result result =
            allocationService.allocate(pending, SYSTEM_ACTOR, now);
        Reservation confirmed = pending.transition(Reservation.Status.CONFIRMED, null, now);
        reservations.appendAttempt(request.tenantId(), attempt(confirmed, delivery, now, null));
        reservations.updateState(request.tenantId(), confirmed, pending.version());
        publishConfirmed(confirmed, request, delivery, result, now);
        return result(confirmed);
      } catch (BusinessFailure failure) {
        Line line = findLine(request, failure.line().orderLineId());
        return fail(
            pending,
            request,
            delivery,
            now,
            failure.code(),
            List.of(line),
            failure.observedAvailable());
      }
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (ReservationPersistenceException exception) {
      if (exception.code() == ReservationPersistenceException.Code.OPTIMISTIC_VERSION_CONFLICT) {
        throw EventHandlingException.retryable("INVENTORY_STORAGE_CONFLICT");
      }
      throw EventHandlingException.finalFailure("INVENTORY_PERSISTENCE_INTEGRITY_VIOLATION");
    } catch (DataAccessException exception) {
      throw classifyDataAccess(exception);
    }
  }

  private InventoryReservationRepository.CreateResult create(Reservation pending, Request request) {
    try {
      return reservations.create(request.tenantId(), pending);
    } catch (ReservationPersistenceException exception) {
      if (exception.code() == ReservationPersistenceException.Code.RESERVATION_REQUEST_CONFLICT) {
        return null;
      }
      throw exception;
    }
  }

  private EventHandlingResult conflictAfterCreateRace(
      Request request, EventDelivery delivery, Instant now) {
    Reservation canonical =
        reservations
            .findByTenantAndOrder(request.tenantId(), request.orderId())
            .orElseThrow(() -> EventHandlingException.retryable("INVENTORY_STORAGE_CONFLICT"))
            .reservation();
    return recordConflict(canonical, request, delivery, now);
  }

  private EventHandlingResult replayOrConflict(
      Reservation canonical, Request request, EventDelivery delivery, Instant now) {
    if (!canonical.requestHash().equals(request.requestHash())) {
      return recordConflict(canonical, request, delivery, now);
    }
    if (canonical.status() == Reservation.Status.PENDING) {
      throw EventHandlingException.retryable("INVENTORY_RESERVATION_PROCESSING");
    }
    return result(canonical);
  }

  private EventHandlingResult recordConflict(
      Reservation canonical, Request request, EventDelivery delivery, Instant now) {
    ReservationRequestConflictRepository.RecordResult recorded =
        conflicts.record(
            request.tenantId(),
            new ReservationRequestConflict(
                UUID.randomUUID(),
                request.tenantId(),
                request.orderId(),
                canonical.id(),
                canonical.requestHash(),
                request.requestHash(),
                delivery.eventId(),
                delivery.correlationId(),
                now,
                ReservationRequestConflict.RESERVATION_REQUEST_CONFLICT));
    if (!recorded.replayed()) {
      publishFailed(
          canonical,
          request,
          delivery,
          now,
          ReservationRequestConflict.RESERVATION_REQUEST_CONFLICT,
          request.lines(),
          null);
    }
    return EventHandlingResult.processed(
        recorded.conflict().id().toString(),
        digest(recorded.conflict().id() + "|" + request.requestHash()));
  }

  private EventHandlingResult createLegacyFailure(
      Request request, EventDelivery delivery, Instant now) {
    Reservation failed =
        new Reservation(
            UUID.randomUUID(),
            request.tenantId(),
            request.orderId(),
            request.requestHash(),
            null,
            request.routeCode(),
            Reservation.Status.FAILED,
            LEGACY_FAILURE,
            request.requestLines(),
            0,
            now,
            now);
    InventoryReservationRepository.CreateResult created = create(failed, request);
    if (created == null) {
      return conflictAfterCreateRace(request, delivery, now);
    }
    if (created.replayed()) {
      return replayOrConflict(created.reservation(), request, delivery, now);
    }
    reservations.appendAttempt(request.tenantId(), attempt(failed, delivery, now, LEGACY_FAILURE));
    publishFailed(failed, request, delivery, now, LEGACY_FAILURE, request.lines(), null);
    return result(failed);
  }

  private EventHandlingResult fail(
      Reservation pending,
      Request request,
      EventDelivery delivery,
      Instant now,
      String failureCode,
      List<Line> failedLines,
      BigDecimal observedAvailable) {
    Reservation failed = pending.transition(Reservation.Status.FAILED, failureCode, now);
    reservations.appendAttempt(request.tenantId(), attempt(failed, delivery, now, failureCode));
    if (InventoryAllocationService.INSUFFICIENT.equals(failureCode)) {
      Line line = failedLines.getFirst();
      BigDecimal available = observedAvailable == null ? BigDecimal.ZERO : observedAvailable;
      reservations.appendShortage(
          request.tenantId(),
          new ShortageSnapshot(
              UUID.randomUUID(),
              request.tenantId(),
              failed.id(),
              line.requestLine().orderLineId(),
              line.requestLine().skuId(),
              line.requestLine().quantityUnit(),
              line.requestLine().requestedQuantity(),
              available,
              line.requestLine().requestedQuantity().subtract(available),
              failureCode,
              line.requestLine().supplyPoolId(),
              line.requestLine().supplyType(),
              now));
    }
    reservations.updateState(request.tenantId(), failed, pending.version());
    publishFailed(failed, request, delivery, now, failureCode, failedLines, observedAvailable);
    return result(failed);
  }

  private void publishConfirmed(
      Reservation confirmed,
      Request request,
      EventDelivery delivery,
      InventoryAllocationService.Result result,
      Instant now) {
    List<InventoryReservationConfirmedV1.Allocation> allocations =
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
    publish(
        UUID.randomUUID(),
        confirmed,
        delivery,
        now,
        InventoryReservationConfirmedV1.TYPE,
        new InventoryReservationConfirmedV1.Payload(
            confirmed.id(),
            number(confirmed),
            confirmed.orderId(),
            request.orderNumber(),
            confirmed.requestHash(),
            confirmed.supplyDecisionHash(),
            now,
            allocations));
  }

  private void publishFailed(
      Reservation reservation,
      Request request,
      EventDelivery delivery,
      Instant now,
      String failureCode,
      List<Line> failedLines,
      BigDecimal observedAvailable) {
    List<InventoryReservationFailedV1.LineFailure> lines =
        failedLines.stream()
            .map(
                line -> {
                  BigDecimal shortage =
                      observedAvailable == null
                          ? null
                          : line.requestLine().requestedQuantity().subtract(observedAvailable);
                  return new InventoryReservationFailedV1.LineFailure(
                      line.requestLine().orderLineId(),
                      line.requestLine().skuId(),
                      line.skuCode(),
                      line.requestLine().requestedQuantity().toPlainString(),
                      line.requestLine().quantityUnit(),
                      line.requestLine().allocationMode() == null
                          ? null
                          : line.requestLine().allocationMode().name(),
                      line.requestLine().supplyPoolId(),
                      line.requestLine().supplyType(),
                      observedAvailable == null ? null : observedAvailable.toPlainString(),
                      shortage == null ? null : shortage.toPlainString());
                })
            .toList();
    List<InventoryReservationFailedV1.Shortage> shortages =
        lines.stream()
            .filter(line -> line.observedAvailableQuantity() != null)
            .map(
                line ->
                    new InventoryReservationFailedV1.Shortage(
                        line.orderLineId(),
                        line.skuId(),
                        line.skuCode(),
                        line.requestedQuantity(),
                        line.observedAvailableQuantity(),
                        line.shortageQuantity(),
                        line.unit()))
            .toList();
    publish(
        UUID.randomUUID(),
        reservation,
        delivery,
        now,
        InventoryReservationFailedV1.TYPE,
        new InventoryReservationFailedV1.Payload(
            reservation.id(),
            number(reservation),
            reservation.orderId(),
            request.orderNumber(),
            request.requestHash(),
            request.supplyDecisionHash(),
            now,
            failureCode,
            shortages,
            lines,
            false));
  }

  private void publish(
      UUID eventId,
      Reservation reservation,
      EventDelivery delivery,
      Instant now,
      String eventType,
      Object payload) {
    eventPublisher.publish(
        new PendingEvent(
            eventId,
            reservation.tenantId().value(),
            eventType,
            1,
            now,
            "inventory",
            new PendingEvent.Subject(
                "INVENTORY_RESERVATION", reservation.id(), number(reservation)),
            delivery.correlationId(),
            delivery.eventId(),
            payload,
            Map.of()));
  }

  private static Reservation pending(Request request, Instant now) {
    return Reservation.pending(
        UUID.randomUUID(),
        request.tenantId(),
        request.orderId(),
        request.requestHash(),
        request.supplyDecisionHash(),
        request.routeCode(),
        request.requestLines(),
        now);
  }

  private static ReservationAttempt attempt(
      Reservation reservation, EventDelivery delivery, Instant now, String failureCode) {
    return new ReservationAttempt(
        UUID.randomUUID(),
        reservation.tenantId(),
        reservation.id(),
        1,
        reservation.requestHash(),
        ReservationAttempt.Trigger.EVENT,
        now,
        now,
        failureCode == null
            ? ReservationAttempt.Outcome.CONFIRMED
            : ReservationAttempt.Outcome.FAILED,
        failureCode,
        delivery.correlationId(),
        delivery.eventId());
  }

  private static Line findLine(Request request, UUID orderLineId) {
    return request.lines().stream()
        .filter(line -> line.requestLine().orderLineId().equals(orderLineId))
        .findFirst()
        .orElseThrow();
  }

  static RuntimeException classifyDataAccess(DataAccessException exception) {
    if (exception instanceof DataIntegrityViolationException) {
      return EventHandlingException.finalFailure("INVENTORY_PERSISTENCE_CONSTRAINT_VIOLATION");
    }
    if (exception instanceof TransientDataAccessException
        || exception instanceof RecoverableDataAccessException
        || exception instanceof DataAccessResourceFailureException) {
      return EventHandlingException.retryable("INVENTORY_STORAGE_UNAVAILABLE");
    }
    return exception;
  }

  private static EventHandlingResult result(Reservation reservation) {
    return EventHandlingResult.processed(
        reservation.id().toString(),
        digest(
            reservation.id()
                + "|"
                + reservation.requestHash()
                + "|"
                + reservation.status()
                + "|"
                + (reservation.failureCode() == null ? "" : reservation.failureCode())));
  }

  private static String number(Reservation reservation) {
    return "RES-" + reservation.id();
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Instant max(Instant first, Instant second) {
    return first.isBefore(second) ? second : first;
  }
}
