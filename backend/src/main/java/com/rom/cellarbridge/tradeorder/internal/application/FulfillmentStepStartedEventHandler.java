package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.fulfillment.FulfillmentStepStartedV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class FulfillmentStepStartedEventHandler implements LocalEventHandler {
  private final JsonMapper json;
  private final TradeOrderFulfillmentEventService service;

  FulfillmentStepStartedEventHandler(JsonMapper json, TradeOrderFulfillmentEventService service) {
    this.json = json;
    this.service = service;
  }

  public String consumerName() {
    return "tradeorder.fulfillment-step-started.v1";
  }

  public String eventType() {
    return FulfillmentStepStartedV1.TYPE;
  }

  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      FulfillmentStepStartedV1.Payload payload =
          json.readValue(delivery.payloadJson(), FulfillmentStepStartedV1.Payload.class);
      return service.stepStarted(
          delivery, payload.orderId(), payload.stepCode(), payload.startedAt());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("ORDER_FULFILLMENT_EVENT_INVALID");
    }
  }
}
