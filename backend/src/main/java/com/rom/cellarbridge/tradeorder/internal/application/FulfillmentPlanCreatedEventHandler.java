package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.fulfillment.FulfillmentPlanCreatedV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class FulfillmentPlanCreatedEventHandler implements LocalEventHandler {
  private final JsonMapper json;
  private final TradeOrderFulfillmentEventService service;

  FulfillmentPlanCreatedEventHandler(JsonMapper json, TradeOrderFulfillmentEventService service) {
    this.json = json;
    this.service = service;
  }

  public String consumerName() {
    return "tradeorder.fulfillment-plan-created.v1";
  }

  public String eventType() {
    return FulfillmentPlanCreatedV1.TYPE;
  }

  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      FulfillmentPlanCreatedV1.Payload payload =
          json.readValue(delivery.payloadJson(), FulfillmentPlanCreatedV1.Payload.class);
      return service.planCreated(delivery, payload.orderId(), payload.createdAt());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_EVENT_INVALID");
    }
  }
}
