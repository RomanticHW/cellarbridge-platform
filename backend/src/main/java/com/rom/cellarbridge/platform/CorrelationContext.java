package com.rom.cellarbridge.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.MDC;

/** Bridges the safe request correlation header into UUID-based event contracts. */
public final class CorrelationContext {

  private CorrelationContext() {}

  public static UUID currentOrCreate() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (value == null || value.isBlank()) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
  }
}
