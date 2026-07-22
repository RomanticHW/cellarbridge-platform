package com.rom.cellarbridge.exceptioncenter.internal.application;

import com.rom.cellarbridge.exceptioncenter.ExceptionCategory;
import com.rom.cellarbridge.exceptioncenter.ExceptionSeverity;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.Detection;
import com.rom.cellarbridge.fulfillment.FulfillmentStepFailedV1;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class FulfillmentStepFailedExceptionHandler implements LocalEventHandler {
  private final JsonMapper json;
  private final ExceptionCaseService cases;

  FulfillmentStepFailedExceptionHandler(JsonMapper json, ExceptionCaseService cases) {
    this.json = json;
    this.cases = cases;
  }

  @Override
  public String consumerName() {
    return "exception.fulfillment-step-failed.v1";
  }

  @Override
  public String eventType() {
    return FulfillmentStepFailedV1.TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      FulfillmentStepFailedV1.Payload payload =
          json.readValue(delivery.payloadJson(), FulfillmentStepFailedV1.Payload.class);
      if (!"FULFILLMENT_PLAN".equals(delivery.subject().type())
          || !Objects.equals(delivery.subject().id(), payload.planId())
          || payload.stepId() == null
          || payload.failedAt() == null
          || payload.planNumber() == null
          || payload.stepCode() == null
          || payload.failureCode() == null) {
        throw EventHandlingException.finalFailure("FULFILLMENT_STEP_FAILED_EVENT_INVALID");
      }
      String safeDetails =
          json.writeValueAsString(
              Map.of(
                  "planId", payload.planId().toString(),
                  "stepId", payload.stepId().toString(),
                  "stepCode", payload.stepCode(),
                  "orderId", payload.orderId().toString(),
                  "orderNumber", payload.orderNumber(),
                  "failureCode", payload.failureCode(),
                  "retryable", payload.retryable()));
      String evidence =
          json.writeValueAsString(
              Map.of(
                  "failureCode", payload.failureCode(),
                  "safeMessage", payload.safeMessage(),
                  "attempt", payload.attempt(),
                  "failedAt", payload.failedAt().toString()));
      return cases.detect(
          new Detection(
              new TenantId(delivery.tenantId()),
              delivery.eventId(),
              delivery.eventType(),
              "FULFILLMENT_STEP",
              payload.stepId(),
              payload.planNumber() + "/" + payload.stepCode(),
              ExceptionCategory.FULFILLMENT_STEP_FAILED,
              "fulfillment-step-failed:" + payload.stepId(),
              payload.retryable() ? ExceptionSeverity.HIGH : ExceptionSeverity.CRITICAL,
              payload.failedAt().plus(2, ChronoUnit.HOURS),
              "Fulfillment step requires recovery",
              safeDetails,
              evidence,
              delivery.correlationId(),
              delivery.eventId(),
              payload.failedAt()));
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("FULFILLMENT_STEP_FAILED_EVENT_INVALID");
    }
  }
}
