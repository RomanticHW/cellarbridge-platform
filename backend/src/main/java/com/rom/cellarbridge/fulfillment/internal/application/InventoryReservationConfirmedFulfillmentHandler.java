package com.rom.cellarbridge.fulfillment.internal.application;

import com.rom.cellarbridge.inventory.InventoryReservationConfirmedV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Creates one route-bound plan from a self-contained reservation confirmation fact. */
@Component
final class InventoryReservationConfirmedFulfillmentHandler implements LocalEventHandler {
  static final String CONSUMER_NAME = "fulfillment.inventory-reservation-confirmed.v1";
  private final JsonMapper json;
  private final FulfillmentPlanService service;

  InventoryReservationConfirmedFulfillmentHandler(JsonMapper json, FulfillmentPlanService service) {
    this.json = json;
    this.service = service;
  }

  @Override
  public String consumerName() {
    return CONSUMER_NAME;
  }

  @Override
  public String eventType() {
    return InventoryReservationConfirmedV1.TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      InventoryReservationConfirmedV1.Payload payload =
          json.readValue(delivery.payloadJson(), InventoryReservationConfirmedV1.Payload.class);
      if (!"INVENTORY_RESERVATION".equals(delivery.subject().type())
          || !Objects.equals(delivery.subject().id(), payload.reservationId())
          || payload.orderId() == null
          || payload.orderNumber() == null
          || payload.orderNumber().isBlank()
          || payload.confirmedAt() == null) {
        throw EventHandlingException.finalFailure("INVENTORY_RESERVATION_CONFIRMED_EVENT_INVALID");
      }
      if (payload.routeCode() == null) {
        // Pre-route V1 confirmations are acknowledged without inventing a plan from incomplete
        // facts.
        return EventHandlingResult.processed();
      }
      FulfillmentPlanService.PlanCreation result =
          service.createFromReservation(
              delivery,
              payload.reservationId(),
              payload.orderId(),
              payload.orderNumber(),
              payload.routeCode(),
              payload.confirmedAt());
      return EventHandlingResult.processed(result.planId().toString(), result.evidenceHash());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (FulfillmentProblem exception) {
      throw EventHandlingException.finalFailure(exception.code());
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("INVENTORY_RESERVATION_CONFIRMED_EVENT_INVALID");
    }
  }
}
