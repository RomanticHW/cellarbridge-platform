package com.rom.cellarbridge.platform.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON Schema 2020-12 vocabulary for strict MCP business contracts. */
public final class McpSchemaSupport {

  private McpSchemaSupport() {}

  public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Map.copyOf(properties));
    schema.put("required", List.copyOf(required));
    schema.put("additionalProperties", false);
    return Map.copyOf(schema);
  }

  public static Map<String, Object> object(Map<String, Object> properties) {
    return object(properties, properties.keySet().stream().sorted().toList());
  }

  public static Map<String, Object> array(Map<String, Object> items, int maxItems) {
    return Map.of("type", "array", "items", Map.copyOf(items), "maxItems", maxItems);
  }

  public static Map<String, Object> nullable(Map<String, Object> schema) {
    return Map.of("anyOf", List.of(Map.copyOf(schema), Map.of("type", "null")));
  }

  public static Map<String, Object> string(String format) {
    return Map.of("type", "string", "format", format);
  }

  public static Map<String, Object> string(int maxLength) {
    return Map.of("type", "string", "maxLength", maxLength);
  }

  public static Map<String, Object> patternedString(int maxLength, String pattern) {
    return Map.of("type", "string", "maxLength", maxLength, "pattern", pattern);
  }

  public static Map<String, Object> enumeration(List<String> values) {
    return Map.of("type", "string", "enum", List.copyOf(values));
  }

  public static Map<String, Object> nonNegativeInteger() {
    return Map.of("type", "integer", "minimum", 0);
  }

  public static Map<String, Object> boundedInteger(int minimum, int maximum) {
    return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
  }

  public static Map<String, Object> number() {
    return Map.of("type", "number");
  }

  public static Map<String, Object> bool() {
    return Map.of("type", "boolean");
  }

  static Map<String, Object> readEnvelope(String sourceKind, Map<String, Object> dataSchema) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "schemaVersion", Map.of("type", "string", "const", McpCallSupport.SCHEMA_VERSION));
    properties.put("sourceKind", Map.of("type", "string", "const", sourceKind));
    properties.put("dataAsOf", nullable(string("date-time")));
    properties.put(
        "projectionStatus",
        nullable(
            enumeration(
                List.of("CURRENT", "STALE", "EMPTY", "REBUILDING", "UNKNOWN", "NOT_APPLICABLE"))));
    properties.put("freshness", nullable(freshness()));
    properties.put(
        "correlationId",
        Map.of("type", "string", "pattern", "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$", "maxLength", 64));
    properties.put("warnings", array(warning(), 32));
    properties.put("data", nullable(dataSchema));
    properties.put("isError", bool());
    properties.put("code", nullable(code(80)));
    properties.put("retryable", bool());
    properties.put("safeMessage", nullable(string(240)));
    Map<String, Object> schema = new LinkedHashMap<>(object(properties));
    schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    return Map.copyOf(schema);
  }

  private static Map<String, Object> warning() {
    return object(
        Map.of(
            "code", code(64),
            "severity", enumeration(List.of("INFO", "WARNING")),
            "safeMessage", string(240)));
  }

  private static Map<String, Object> freshness() {
    return object(
        Map.of(
            "mode", enumeration(List.of("SOURCE_WATERMARK", "OBSERVATION_AGE")),
            "observedAt", string("date-time"),
            "sourceWatermark", nullable(string("date-time")),
            "projectorWatermark", nullable(string("date-time")),
            "lastSuccessfulRefreshAt", nullable(string("date-time")),
            "lagSeconds", nullable(nonNegativeInteger()),
            "pendingCount", nullable(nonNegativeInteger()),
            "deadLetterCount", nullable(nonNegativeInteger())));
  }

  private static Map<String, Object> code(int maxLength) {
    return Map.of(
        "type",
        "string",
        "pattern",
        "^[A-Z][A-Z0-9_]{2," + (maxLength - 1) + "}$",
        "maxLength",
        maxLength);
  }
}
