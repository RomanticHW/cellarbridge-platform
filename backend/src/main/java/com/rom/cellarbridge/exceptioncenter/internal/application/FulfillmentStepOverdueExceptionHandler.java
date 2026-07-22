package com.rom.cellarbridge.exceptioncenter.internal.application;

import com.rom.cellarbridge.exceptioncenter.ExceptionCategory;
import com.rom.cellarbridge.exceptioncenter.ExceptionSeverity;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.Detection;
import com.rom.cellarbridge.fulfillment.FulfillmentStepOverdueV1;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class FulfillmentStepOverdueExceptionHandler implements LocalEventHandler {
  private final JsonMapper json;
  private final ExceptionCaseService cases;

  FulfillmentStepOverdueExceptionHandler(JsonMapper json, ExceptionCaseService cases) {
    this.json = json;
    this.cases = cases;
  }

  @Override
  public String consumerName() {
    return "exception.fulfillment-step-overdue.v1";
  }

  @Override
  public String eventType() {
    return FulfillmentStepOverdueV1.TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      FulfillmentStepOverdueV1.Payload payload =
          json.readValue(delivery.payloadJson(), FulfillmentStepOverdueV1.Payload.class);
      if (!"FULFILLMENT_PLAN".equals(delivery.subject().type())
          || !Objects.equals(delivery.subject().id(), payload.planId())
          || payload.stepId() == null
          || payload.dueAt() == null
          || payload.detectedAt() == null) {
        throw EventHandlingException.finalFailure("FULFILLMENT_STEP_OVERDUE_EVENT_INVALID");
      }
      String details =
          json.writeValueAsString(
              Map.of(
                  "planId", payload.planId().toString(),
                  "stepId", payload.stepId().toString(),
                  "stepCode", payload.stepCode(),
                  "orderId", payload.orderId().toString(),
                  "orderNumber", payload.orderNumber(),
                  "dueAt", payload.dueAt().toString()));
      String evidence =
          json.writeValueAsString(
              Map.of(
                  "dueAt", payload.dueAt().toString(),
                  "detectedAt", payload.detectedAt().toString()));
      return cases.detect(
          new Detection(
              new TenantId(delivery.tenantId()),
              delivery.eventId(),
              delivery.eventType(),
              "FULFILLMENT_STEP",
              payload.stepId(),
              payload.planNumber() + "/" + payload.stepCode(),
              ExceptionCategory.FULFILLMENT_STEP_OVERDUE,
              "fulfillment-step-overdue:" + payload.stepId(),
              ExceptionSeverity.MEDIUM,
              payload.dueAt(),
              "Fulfillment step exceeded its planned time",
              details,
              evidence,
              delivery.correlationId(),
              delivery.eventId(),
              payload.detectedAt()));
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("FULFILLMENT_STEP_OVERDUE_EVENT_INVALID");
    }
  }
}
