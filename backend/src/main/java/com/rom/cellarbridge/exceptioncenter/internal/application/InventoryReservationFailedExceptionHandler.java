package com.rom.cellarbridge.exceptioncenter.internal.application;

import com.rom.cellarbridge.exceptioncenter.ExceptionCategory;
import com.rom.cellarbridge.exceptioncenter.ExceptionSeverity;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.Detection;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventoryReservationFailedV1;
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
final class InventoryReservationFailedExceptionHandler implements LocalEventHandler {
  private final JsonMapper json;
  private final ExceptionCaseService cases;

  InventoryReservationFailedExceptionHandler(JsonMapper json, ExceptionCaseService cases) {
    this.json = json;
    this.cases = cases;
  }

  @Override
  public String consumerName() {
    return "exception.inventory-reservation-failed.v1";
  }

  @Override
  public String eventType() {
    return InventoryReservationFailedV1.TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      InventoryReservationFailedV1.Payload payload =
          json.readValue(delivery.payloadJson(), InventoryReservationFailedV1.Payload.class);
      if (!"INVENTORY_RESERVATION".equals(delivery.subject().type())
          || !Objects.equals(delivery.subject().id(), payload.reservationId())
          || payload.failedAt() == null
          || payload.reasonCode() == null) {
        throw EventHandlingException.finalFailure("INVENTORY_RESERVATION_FAILED_EVENT_INVALID");
      }
      String details =
          json.writeValueAsString(
              Map.of(
                  "reservationId", payload.reservationId().toString(),
                  "orderId", payload.orderId().toString(),
                  "orderNumber", payload.orderNumber(),
                  "reasonCode", payload.reasonCode(),
                  "shortageCount", payload.shortages().size(),
                  "lineFailureCount", payload.lineFailures().size(),
                  "retryable", payload.retryable()));
      String evidence =
          json.writeValueAsString(
              Map.of(
                  "reasonCode", payload.reasonCode(),
                  "shortageCount", payload.shortages().size(),
                  "lineFailureCount", payload.lineFailures().size(),
                  "failedAt", payload.failedAt().toString()));
      return cases.detect(
          new Detection(
              new TenantId(delivery.tenantId()),
              delivery.eventId(),
              delivery.eventType(),
              "INVENTORY_RESERVATION",
              payload.reservationId(),
              payload.reservationNumber(),
              ExceptionCategory.INVENTORY_SHORTAGE,
              "inventory-reservation-failed:" + payload.reservationId(),
              payload.retryable() ? ExceptionSeverity.HIGH : ExceptionSeverity.CRITICAL,
              payload.failedAt().plus(4, ChronoUnit.HOURS),
              "Inventory reservation requires operational recovery",
              details,
              evidence,
              delivery.correlationId(),
              delivery.eventId(),
              payload.failedAt()));
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("INVENTORY_RESERVATION_FAILED_EVENT_INVALID");
    }
  }
}
