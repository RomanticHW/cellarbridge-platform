package com.rom.cellarbridge.exceptioncenter.internal.application;

import com.rom.cellarbridge.exceptioncenter.ExceptionCategory;
import com.rom.cellarbridge.exceptioncenter.ExceptionSeverity;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.Detection;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventPublicationOperations;
import com.rom.cellarbridge.platform.SchedulerTelemetry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class FailedDeliveryExceptionScanner {
  private final EventPublicationOperations publications;
  private final ExceptionCaseService cases;
  private final JsonMapper json;
  private final Clock clock;
  private final SchedulerTelemetry telemetry;

  FailedDeliveryExceptionScanner(
      EventPublicationOperations publications,
      ExceptionCaseService cases,
      JsonMapper json,
      Clock clock,
      SchedulerTelemetry telemetry) {
    this.publications = publications;
    this.cases = cases;
    this.json = json;
    this.clock = clock;
    this.telemetry = telemetry;
  }

  @Scheduled(fixedDelayString = "${cellarbridge.exception.failed-delivery-scan-ms:30000}")
  void scan() {
    telemetry.run(
        "failed-delivery-exceptions",
        () -> {
          for (EventPublicationOperations.FailedDelivery failure : publications.listFinal(0, 100)) {
            cases.detect(detection(failure));
          }
        });
  }

  private Detection detection(EventPublicationOperations.FailedDelivery failure) {
    try {
      String details =
          json.writeValueAsString(
              Map.of(
                  "eventId", failure.eventId().toString(),
                  "eventType", failure.eventType(),
                  "consumerName", failure.consumerName(),
                  "errorCode", failure.errorCode(),
                  "deliveryVersion", failure.version()));
      String evidence =
          json.writeValueAsString(
              Map.of(
                  "attempts", failure.attempts(),
                  "errorCode", failure.errorCode(),
                  "lastAttemptAt", failure.lastAttemptAt().toString()));
      UUID detectionId =
          UUID.nameUUIDFromBytes(
              (failure.eventId() + "|" + failure.consumerName()).getBytes(StandardCharsets.UTF_8));
      return new Detection(
          new TenantId(failure.tenantId()),
          detectionId,
          "cellarbridge.platform.delivery-failed.v1",
          "EVENT_PUBLICATION",
          failure.eventId(),
          failure.eventType() + "/" + failure.consumerName(),
          ExceptionCategory.EVENT_DELIVERY_FAILED,
          "event-delivery-failed:" + failure.eventId() + ":" + failure.consumerName(),
          ExceptionSeverity.HIGH,
          clock.instant().plus(1, ChronoUnit.HOURS),
          "A final event delivery failure requires controlled replay review",
          details,
          evidence,
          failure.correlationId(),
          failure.eventId(),
          failure.lastAttemptAt());
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize failed delivery evidence", exception);
    }
  }
}
