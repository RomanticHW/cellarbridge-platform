package com.rom.cellarbridge.platform.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Strict MCP schema and argument support shared by the read-only business adapters. */
public final class McpCapabilitySupport {

  private static final Map<String, Object> OUTPUT_SCHEMA = outputSchema();

  private McpCapabilitySupport() {}

  public static SyncToolSpecification readOnlyTool(
      McpCallSupport calls,
      String sourceKind,
      String name,
      String title,
      String description,
      Map<String, Object> properties,
      List<String> required,
      Function<Arguments, McpReadPayload> operation) {
    Map<String, Object> immutableProperties = Map.copyOf(properties);
    JsonSchema inputSchema =
        JsonSchema.builder()
            .type("object")
            .properties(immutableProperties)
            .required(List.copyOf(required))
            .additionalProperties(false)
            .build();
    Tool tool =
        Tool.builder(name)
            .title(title)
            .description(description)
            .inputSchema(inputSchema)
            .outputSchema(OUTPUT_SCHEMA)
            .annotations(
                ToolAnnotations.builder()
                    .title(title)
                    .readOnlyHint(true)
                    .destructiveHint(false)
                    .idempotentHint(true)
                    .openWorldHint(false)
                    .build())
            .build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (context, request) ->
                calls.tool(
                    sourceKind,
                    () -> {
                      Arguments arguments =
                          new Arguments(
                              request == null ? null : request.arguments(),
                              immutableProperties.keySet());
                      arguments.validateKeys();
                      return operation.apply(arguments);
                    }))
        .build();
  }

  public static Map<String, Object> text(String description, Integer minLength, Integer maxLength) {
    Map<String, Object> schema = property("string", description);
    put(schema, "minLength", minLength);
    put(schema, "maxLength", maxLength);
    return Map.copyOf(schema);
  }

  public static Map<String, Object> formattedText(
      String description, String format, Integer maxLength) {
    Map<String, Object> schema = property("string", description);
    schema.put("format", format);
    put(schema, "maxLength", maxLength);
    return Map.copyOf(schema);
  }

  public static Map<String, Object> enumeratedText(
      String description, List<String> values, boolean nullable) {
    Map<String, Object> schema = property("string", description);
    schema.put("enum", List.copyOf(values));
    if (nullable) {
      schema.put("type", List.of("string", "null"));
    }
    return Map.copyOf(schema);
  }

  public static Map<String, Object> integer(String description, Integer minimum, Integer maximum) {
    Map<String, Object> schema = property("integer", description);
    put(schema, "minimum", minimum);
    put(schema, "maximum", maximum);
    return Map.copyOf(schema);
  }

  public static Map<String, Object> bool(String description) {
    return Map.copyOf(property("boolean", description));
  }

  public static Map<String, Object> textArray(
      String description, List<String> allowedValues, int maxItems) {
    Map<String, Object> items = property("string", "Allowed value");
    if (!allowedValues.isEmpty()) {
      items.put("enum", List.copyOf(allowedValues));
    }
    Map<String, Object> schema = property("array", description);
    schema.put("items", Map.copyOf(items));
    schema.put("uniqueItems", true);
    schema.put("maxItems", maxItems);
    return Map.copyOf(schema);
  }

  public static void requireNoPromptArguments(GetPromptRequest request) {
    if (request != null && request.arguments() != null && !request.arguments().isEmpty()) {
      throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
          .message("This prompt does not accept arguments.")
          .build();
    }
  }

  private static Map<String, Object> property(String type, String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", type);
    schema.put("description", description);
    return schema;
  }

  private static void put(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static Map<String, Object> outputSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("schemaVersion", Map.of("type", "string"));
    properties.put("sourceKind", Map.of("type", "string"));
    properties.put(
        "dataAsOf",
        Map.of(
            "anyOf",
            List.of(Map.of("type", "string", "format", "date-time"), Map.of("type", "null"))));
    properties.put(
        "projectionStatus",
        Map.of(
            "anyOf",
            List.of(
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of("CURRENT", "STALE", "EMPTY", "NOT_APPLICABLE")),
                Map.of("type", "null"))));
    properties.put("correlationId", Map.of("type", "string", "format", "uuid"));
    properties.put("warnings", Map.of("type", "array", "items", Map.of("type", "string")));
    properties.put("data", Map.of());
    properties.put("isError", Map.of("type", "boolean"));
    properties.put("code", Map.of("type", List.of("string", "null")));
    properties.put("retryable", Map.of("type", "boolean"));
    properties.put("safeMessage", Map.of("type", List.of("string", "null")));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    schema.put("type", "object");
    schema.put("properties", Map.copyOf(properties));
    schema.put(
        "required",
        List.of(
            "schemaVersion",
            "sourceKind",
            "dataAsOf",
            "projectionStatus",
            "correlationId",
            "warnings",
            "data",
            "isError",
            "code",
            "retryable",
            "safeMessage"));
    schema.put("additionalProperties", false);
    return Map.copyOf(schema);
  }

  public static final class Arguments {
    private final Map<String, Object> values;
    private final Set<String> allowedKeys;

    private Arguments(Map<String, Object> values, Set<String> allowedKeys) {
      this.values = values == null ? Map.of() : Map.copyOf(values);
      this.allowedKeys = Set.copyOf(allowedKeys);
    }

    public String text(String name) {
      Object value = values.get(name);
      if (value == null) {
        return null;
      }
      if (!(value instanceof String text)) {
        throw McpSafeException.invalidRequest();
      }
      return text;
    }

    public String requiredText(String name) {
      String value = text(name);
      if (value == null || value.isBlank()) {
        throw McpSafeException.invalidRequest();
      }
      return value;
    }

    public Integer integer(String name) {
      Object value = values.get(name);
      if (value == null) {
        return null;
      }
      if (value instanceof Integer integer) {
        return integer;
      }
      if (value instanceof Long number
          && number >= Integer.MIN_VALUE
          && number <= Integer.MAX_VALUE) {
        return number.intValue();
      }
      throw McpSafeException.invalidRequest();
    }

    public Boolean bool(String name) {
      Object value = values.get(name);
      if (value == null) {
        return null;
      }
      if (value instanceof Boolean bool) {
        return bool;
      }
      throw McpSafeException.invalidRequest();
    }

    public List<String> textList(String name) {
      Object value = values.get(name);
      if (value == null) {
        return null;
      }
      if (!(value instanceof List<?> list)) {
        throw McpSafeException.invalidRequest();
      }
      List<String> result = new ArrayList<>(list.size());
      for (Object item : list) {
        if (!(item instanceof String text)) {
          throw McpSafeException.invalidRequest();
        }
        result.add(text);
      }
      return List.copyOf(result);
    }

    private void validateKeys() {
      if (!allowedKeys.containsAll(values.keySet())) {
        throw McpSafeException.invalidRequest();
      }
    }
  }
}
