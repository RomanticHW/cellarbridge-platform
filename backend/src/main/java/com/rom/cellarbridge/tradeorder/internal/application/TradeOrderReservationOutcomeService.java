package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.application.InventoryReservationOutcomeParser.Outcome;
import com.rom.cellarbridge.tradeorder.internal.application.InventoryReservationOutcomeParser.OutcomeValidationException;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.ReservationOutcomeEvidence;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/** Applies one Inventory reservation outcome to the Trade Order aggregate. */
@Component
final class TradeOrderReservationOutcomeService {

  private static final UUID SYSTEM_ACTOR =
      UUID.nameUUIDFromBytes(
          "tradeorder.inventory-reservation-outcome.v1".getBytes(StandardCharsets.UTF_8));

  private final TradeOrderRepository repository;
  private final InventoryReservationOutcomeParser parser;

  TradeOrderReservationOutcomeService(
      TradeOrderRepository repository, InventoryReservationOutcomeParser parser) {
    this.repository = repository;
    this.parser = parser;
  }

  EventHandlingResult confirmed(EventDelivery delivery) {
    return handle(delivery, true);
  }

  EventHandlingResult failed(EventDelivery delivery) {
    return handle(delivery, false);
  }

  private EventHandlingResult handle(EventDelivery delivery, boolean confirmed) {
    try {
      TenantId tenantId = new TenantId(delivery.tenantId());
      UUID orderId = parser.orderId(delivery);
      TradeOrder order =
          repository
              .findForUpdate(tenantId, orderId)
              .orElseThrow(
                  () -> EventHandlingException.finalFailure("ORDER_RESERVATION_ORDER_NOT_FOUND"));
      Outcome outcome =
          confirmed ? parser.parseConfirmed(delivery, order) : parser.parseFailed(delivery, order);
      ReservationOutcomeEvidence existing =
          repository.findReservationOutcome(tenantId, orderId).orElse(null);
      if (existing != null) {
        if (sameOutcome(existing, outcome) && order.status() == outcome.status()) {
          return result(orderId, outcome.evidenceHash());
        }
        throw EventHandlingException.finalFailure("ORDER_RESERVATION_OUTCOME_CONFLICT");
      }
      if (order.status() != TradeOrderStatus.PENDING_RESERVATION) {
        throw EventHandlingException.finalFailure("ORDER_RESERVATION_OUTCOME_CONFLICT");
      }
      TradeOrder transitioned =
          confirmed
              ? order.reservationSucceeded(outcome.occurredAt())
              : order.reservationFailed(outcome.occurredAt());
      repository.saveReservationOutcome(
          tenantId, order, transitioned, evidence(outcome), SYSTEM_ACTOR);
      return result(orderId, outcome.evidenceHash());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (OutcomeValidationException exception) {
      throw EventHandlingException.finalFailure(exception.failureCode());
    } catch (DataAccessException exception) {
      throw classifyDataAccess(exception);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      throw EventHandlingException.finalFailure(
          "ORDER_RESERVATION_PERSISTENCE_CONSTRAINT_VIOLATION");
    }
  }

  private static ReservationOutcomeEvidence evidence(Outcome outcome) {
    return new ReservationOutcomeEvidence(
        outcome.eventId(),
        outcome.eventType(),
        outcome.status(),
        outcome.reasonCode(),
        outcome.evidenceHash(),
        outcome.occurredAt());
  }

  private static boolean sameOutcome(ReservationOutcomeEvidence existing, Outcome incoming) {
    return Objects.equals(existing.eventType(), incoming.eventType())
        && existing.status() == incoming.status()
        && Objects.equals(existing.reasonCode(), incoming.reasonCode())
        && Objects.equals(existing.evidenceHash(), incoming.evidenceHash())
        && Objects.equals(existing.occurredAt(), incoming.occurredAt());
  }

  static RuntimeException classifyDataAccess(DataAccessException exception) {
    if (exception instanceof DataIntegrityViolationException) {
      return EventHandlingException.finalFailure(
          "ORDER_RESERVATION_PERSISTENCE_CONSTRAINT_VIOLATION");
    }
    if (exception instanceof TransientDataAccessException
        || exception instanceof RecoverableDataAccessException
        || exception instanceof DataAccessResourceFailureException) {
      return EventHandlingException.retryable("ORDER_RESERVATION_STORAGE_UNAVAILABLE");
    }
    return exception;
  }

  private static EventHandlingResult result(UUID orderId, String evidenceHash) {
    return EventHandlingResult.processed(orderId.toString(), evidenceHash);
  }
}
