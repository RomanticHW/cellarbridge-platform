package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.fulfillment.PublicMilestoneReachedV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class PublicMilestoneReachedEventHandler implements LocalEventHandler {
  private final JsonMapper json;
  private final TradeOrderFulfillmentEventService service;

  PublicMilestoneReachedEventHandler(JsonMapper json, TradeOrderFulfillmentEventService service) {
    this.json = json;
    this.service = service;
  }

  public String consumerName() {
    return "tradeorder.public-milestone-reached.v1";
  }

  public String eventType() {
    return PublicMilestoneReachedV1.TYPE;
  }

  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      PublicMilestoneReachedV1.Payload payload =
          json.readValue(delivery.payloadJson(), PublicMilestoneReachedV1.Payload.class);
      return service.publicMilestone(
          delivery, payload.orderId(), payload.code(), payload.label(), payload.occurredAt());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_EVENT_INVALID");
    }
  }
}
