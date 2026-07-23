package com.rom.cellarbridge.platform.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/** Canonical parsing shared by MCP adapters. */
public final class McpInputValidation {

  private McpInputValidation() {}

  public static String optionalText(String value, int min, int max) {
    if (value == null) return null;
    String normalized = value.strip();
    if (normalized.length() < min || normalized.length() > max) {
      throw McpSafeException.invalidRequest();
    }
    return normalized;
  }

  public static String uppercase(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  public static Instant instant(String value) {
    return value == null ? null : parse(value, Instant::parse);
  }

  public static LocalDate localDate(String value) {
    return parse(value, LocalDate::parse);
  }

  public static UUID optionalUuid(String value) {
    return value == null ? null : uuid(value);
  }

  public static UUID uuid(String value) {
    UUID parsed = parse(value, UUID::fromString);
    if (!parsed.toString().equals(value)) throw McpSafeException.invalidRequest();
    return parsed;
  }

  private static <T> T parse(String value, Function<String, T> parser) {
    try {
      return parser.apply(value);
    } catch (RuntimeException exception) {
      throw McpSafeException.invalidRequest();
    }
  }
}
