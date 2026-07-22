package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.FulfillmentFact;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
final class TradeOrderFulfillmentEventService {
  private static final UUID SYSTEM_ACTOR =
      UUID.nameUUIDFromBytes("tradeorder.fulfillment.v1".getBytes(StandardCharsets.UTF_8));
  private final TradeOrderRepository repository;

  TradeOrderFulfillmentEventService(TradeOrderRepository repository) {
    this.repository = repository;
  }

  EventHandlingResult planCreated(EventDelivery delivery, UUID orderId, Instant at) {
    return transition(
        delivery,
        orderId,
        at,
        "FULFILLMENT_PLAN_CREATED",
        "Fulfillment plan created",
        Target.READY);
  }

  EventHandlingResult stepStarted(
      EventDelivery delivery, UUID orderId, String stepCode, Instant at) {
    try {
      TenantId tenantId = new TenantId(delivery.tenantId());
      TradeOrder order = locked(tenantId, orderId);
      if (repository.hasTimelineEvent(tenantId, orderId, delivery.eventId()))
        return result(orderId, delivery.eventId());
      FulfillmentFact fact =
          fact(
              delivery,
              "FULFILLMENT_STEP_STARTED",
              "Fulfillment step started: " + safeCode(stepCode),
              "INTERNAL",
              at);
      if (order.status() == TradeOrderStatus.READY_FOR_FULFILLMENT) {
        repository.saveFulfillmentTransition(
            tenantId, order, order.beginFulfillment(at), fact, SYSTEM_ACTOR);
      } else if (order.status() == TradeOrderStatus.IN_FULFILLMENT) {
        repository.appendFulfillmentMilestone(tenantId, order, fact, SYSTEM_ACTOR);
      } else {
        throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_STATE_CONFLICT");
      }
      return result(orderId, delivery.eventId());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (DataAccessException exception) {
      throw TradeOrderReservationOutcomeService.classifyDataAccess(exception);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      throw EventHandlingException.finalFailure(
          "ORDER_FULFILLMENT_PERSISTENCE_CONSTRAINT_VIOLATION");
    }
  }

  EventHandlingResult publicMilestone(
      EventDelivery delivery, UUID orderId, String code, String label, Instant at) {
    try {
      TenantId tenantId = new TenantId(delivery.tenantId());
      TradeOrder order = locked(tenantId, orderId);
      if (repository.hasTimelineEvent(tenantId, orderId, delivery.eventId()))
        return result(orderId, delivery.eventId());
      if (order.status() != TradeOrderStatus.IN_FULFILLMENT
          && order.status() != TradeOrderStatus.FULFILLED) {
        throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_STATE_CONFLICT");
      }
      repository.appendFulfillmentMilestone(
          tenantId,
          order,
          fact(delivery, safeCode(code), safeLabel(label), "CUSTOMER", at),
          SYSTEM_ACTOR);
      return result(orderId, delivery.eventId());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (DataAccessException exception) {
      throw TradeOrderReservationOutcomeService.classifyDataAccess(exception);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      throw EventHandlingException.finalFailure(
          "ORDER_FULFILLMENT_PERSISTENCE_CONSTRAINT_VIOLATION");
    }
  }

  EventHandlingResult completed(EventDelivery delivery, UUID orderId, Instant at) {
    return transition(
        delivery, orderId, at, "FULFILLMENT_COMPLETED", "Fulfillment completed", Target.FULFILLED);
  }

  private EventHandlingResult transition(
      EventDelivery delivery,
      UUID orderId,
      Instant at,
      String code,
      String message,
      Target target) {
    try {
      TenantId tenantId = new TenantId(delivery.tenantId());
      TradeOrder order = locked(tenantId, orderId);
      if (repository.hasTimelineEvent(tenantId, orderId, delivery.eventId()))
        return result(orderId, delivery.eventId());
      TradeOrder after;
      String visibility;
      if (target == Target.READY && order.status() == TradeOrderStatus.RESERVED) {
        after = order.markReadyForFulfillment(at);
        visibility = "INTERNAL";
      } else if (target == Target.FULFILLED && order.status() == TradeOrderStatus.IN_FULFILLMENT) {
        after = order.fulfill(at);
        visibility = "CUSTOMER";
      } else {
        throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_STATE_CONFLICT");
      }
      repository.saveFulfillmentTransition(
          tenantId, order, after, fact(delivery, code, message, visibility, at), SYSTEM_ACTOR);
      return result(orderId, delivery.eventId());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (DataAccessException exception) {
      throw TradeOrderReservationOutcomeService.classifyDataAccess(exception);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      throw EventHandlingException.finalFailure(
          "ORDER_FULFILLMENT_PERSISTENCE_CONSTRAINT_VIOLATION");
    }
  }

  private TradeOrder locked(TenantId tenantId, UUID orderId) {
    return repository
        .findForUpdate(tenantId, orderId)
        .orElseThrow(
            () -> EventHandlingException.finalFailure("ORDER_FULFILLMENT_ORDER_NOT_FOUND"));
  }

  private static FulfillmentFact fact(
      EventDelivery delivery, String code, String message, String visibility, Instant at) {
    if (at == null || at.isBefore(delivery.occurredAt().minusSeconds(1))) {
      throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_EVENT_INVALID");
    }
    return new FulfillmentFact(
        delivery.eventId(), delivery.eventType(), code, message, visibility, at);
  }

  private static String safeCode(String value) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_]{2,79}")) {
      throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_EVENT_INVALID");
    }
    return value;
  }

  private static String safeLabel(String value) {
    if (value == null || value.isBlank() || value.length() > 160 || value.contains("\n")) {
      throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_EVENT_INVALID");
    }
    return value;
  }

  private static EventHandlingResult result(UUID orderId, UUID eventId) {
    return EventHandlingResult.processed(orderId.toString(), digest(orderId + "|" + eventId));
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

  private enum Target {
    READY,
    FULFILLED
  }
}
