package com.rom.cellarbridge.settlement.internal.application;

import com.rom.cellarbridge.fulfillment.FulfillmentCompletedV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class FulfillmentCompletedSettlementHandler implements LocalEventHandler {
  private final JsonMapper json;
  private final SettlementService settlement;

  FulfillmentCompletedSettlementHandler(JsonMapper json, SettlementService settlement) {
    this.json = json;
    this.settlement = settlement;
  }

  @Override
  public String consumerName() {
    return "settlement.receivable-trigger.v1";
  }

  @Override
  public String eventType() {
    return FulfillmentCompletedV1.TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      FulfillmentCompletedV1.Payload payload =
          json.readValue(delivery.payloadJson(), FulfillmentCompletedV1.Payload.class);
      validate(delivery, payload);
      SettlementService.CreationResult result = settlement.createFromFulfillment(delivery, payload);
      String reference =
          result.receivableId() == null
              ? "no-charge-order:" + payload.orderId()
              : result.receivableId().toString();
      return EventHandlingResult.processed(reference, result.evidenceHash());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (SettlementProblem exception) {
      throw EventHandlingException.finalFailure(exception.code());
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("SETTLEMENT_TRIGGER_EVENT_INVALID");
    }
  }

  private static void validate(EventDelivery delivery, FulfillmentCompletedV1.Payload payload) {
    if (!"FULFILLMENT_PLAN".equals(delivery.subject().type())
        || !Objects.equals(delivery.subject().id(), payload.planId())
        || !Objects.equals(delivery.subject().number(), payload.planNumber())
        || payload.orderId() == null
        || payload.orderNumber() == null
        || payload.orderNumber().isBlank()
        || payload.completedAt() == null) {
      throw EventHandlingException.finalFailure("SETTLEMENT_TRIGGER_EVENT_INVALID");
    }
  }
}
