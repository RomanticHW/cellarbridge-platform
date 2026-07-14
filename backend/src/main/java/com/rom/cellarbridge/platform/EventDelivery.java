package com.rom.cellarbridge.platform;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable headers and raw business payload supplied to a local event handler. */
public record EventDelivery(
    UUID eventId,
    UUID tenantId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String producer,
    Subject subject,
    UUID correlationId,
    UUID causationId,
    String payloadJson) {

  private static final Pattern EVENT_TYPE_PATTERN =
      Pattern.compile("^cellarbridge\\.[a-z0-9.-]+\\.v[0-9]+$");

  public EventDelivery {
    Objects.requireNonNull(eventId, "eventId is required");
    Objects.requireNonNull(tenantId, "tenantId is required");
    requireText(eventType, "eventType", 160);
    if (eventVersion < 1
        || !EVENT_TYPE_PATTERN.matcher(eventType).matches()
        || !eventType.endsWith(".v" + eventVersion)) {
      throw new IllegalArgumentException("eventType and eventVersion must identify one version");
    }
    Objects.requireNonNull(occurredAt, "occurredAt is required");
    requireText(producer, "producer", 100);
    Objects.requireNonNull(subject, "subject is required");
    Objects.requireNonNull(correlationId, "correlationId is required");
    Objects.requireNonNull(causationId, "causationId is required");
    if (payloadJson == null || payloadJson.isBlank()) {
      throw new IllegalArgumentException("payloadJson must not be blank");
    }
  }

  private static void requireText(String value, String name, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
    }
  }

  /** Stable routing identity carried by the integration-event envelope. */
  public record Subject(String type, UUID id, String number) {

    public Subject {
      requireText(type, "subject.type", 80);
      Objects.requireNonNull(id, "subject.id is required");
      requireText(number, "subject.number", 80);
    }
  }
}
