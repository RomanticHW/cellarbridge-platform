package com.rom.cellarbridge.platform;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A complete, immutable integration event waiting for reliable external publication. */
public record PendingEvent(
    UUID eventId,
    UUID tenantId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String producer,
    Subject subject,
    UUID correlationId,
    UUID causationId,
    Object payload,
    Map<String, ?> metadata) {

  public static final String SPEC_VERSION = "1.0";

  private static final Pattern EVENT_TYPE_PATTERN =
      Pattern.compile("^cellarbridge\\.[a-z0-9.-]+\\.v[0-9]+$");

  public PendingEvent {
    Objects.requireNonNull(eventId, "eventId is required");
    Objects.requireNonNull(tenantId, "tenantId is required");
    Objects.requireNonNull(occurredAt, "occurredAt is required");
    Objects.requireNonNull(subject, "subject is required");
    Objects.requireNonNull(correlationId, "correlationId is required");
    Objects.requireNonNull(causationId, "causationId is required");
    Objects.requireNonNull(payload, "payload is required");
    requireText(eventType, "eventType", 160);
    requireText(producer, "producer", 100);
    if (eventVersion < 1) {
      throw new IllegalArgumentException("eventVersion must be positive");
    }
    if (!EVENT_TYPE_PATTERN.matcher(eventType).matches()
        || !eventType.endsWith(".v" + eventVersion)) {
      throw new IllegalArgumentException("eventType must end with the declared eventVersion");
    }
    Map<String, ?> safeMetadata =
        metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    safeMetadata.forEach(PendingEvent::validateMetadataEntry);
    metadata = safeMetadata;
  }

  private static void validateMetadataEntry(String key, Object value) {
    requireText(key, "metadata key", 100);
    if (value != null
        && !(value instanceof String)
        && !(value instanceof Number)
        && !(value instanceof Boolean)) {
      throw new IllegalArgumentException("metadata values must be scalar JSON values");
    }
  }

  private static void requireText(String value, String name, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
    }
  }

  /** Stable routing identity for the business object represented by the event. */
  public record Subject(String type, UUID id, String number) {

    public Subject {
      requireText(type, "subject.type", 80);
      Objects.requireNonNull(id, "subject.id is required");
      requireText(number, "subject.number", 80);
    }
  }
}
