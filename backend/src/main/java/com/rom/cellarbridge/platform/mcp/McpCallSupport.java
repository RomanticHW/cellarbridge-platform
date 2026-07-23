package com.rom.cellarbridge.platform.mcp;

import com.rom.cellarbridge.platform.CorrelationIdFilter;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class McpCallSupport {

  public static final String SCHEMA_VERSION = "1.0";
  private static final Logger LOGGER = LoggerFactory.getLogger(McpCallSupport.class);
  private final JsonMapper json;

  public McpCallSupport(JsonMapper json) {
    this.json = json;
  }

  public McpReadEnvelope read(String sourceKind, Supplier<McpReadPayload> operation) {
    String correlationId = correlationId();
    try {
      McpReadPayload payload = operation.get();
      return new McpReadEnvelope(
          SCHEMA_VERSION,
          sourceKind,
          payload.dataAsOf(),
          payload.projectionStatus(),
          correlationId,
          payload.warnings(),
          payload.data(),
          false,
          null,
          false,
          null);
    } catch (McpSafeException exception) {
      return error(
          sourceKind,
          correlationId,
          exception.code(),
          exception.retryable(),
          exception.safeMessage());
    } catch (AccessDeniedException exception) {
      return error(
          sourceKind,
          correlationId,
          "ACCESS_DENIED",
          false,
          "The authenticated user is not allowed to perform this operation.");
    } catch (IllegalArgumentException exception) {
      return error(
          sourceKind,
          correlationId,
          "VALIDATION_FAILED",
          false,
          "The request arguments are invalid.");
    } catch (DataAccessException exception) {
      LOGGER.warn(
          "mcpCallFailed sourceKind={} correlationId={} errorCode=DEPENDENCY_UNAVAILABLE",
          sourceKind,
          correlationId);
      return error(
          sourceKind,
          correlationId,
          "DEPENDENCY_UNAVAILABLE",
          true,
          "The operation is temporarily unavailable.");
    } catch (RuntimeException exception) {
      LOGGER.warn(
          "mcpCallFailed sourceKind={} correlationId={} errorCode=INTERNAL_ERROR",
          sourceKind,
          correlationId);
      return error(
          sourceKind,
          correlationId,
          "INTERNAL_ERROR",
          false,
          "The operation could not be completed.");
    }
  }

  public CallToolResult tool(String sourceKind, Supplier<McpReadPayload> operation) {
    McpReadEnvelope envelope = read(sourceKind, operation);
    String text = json(envelope, envelope.correlationId());
    JsonNode structured = tree(text, envelope.correlationId());
    return CallToolResult.builder()
        .addTextContent(text)
        .structuredContent(structured)
        .isError(envelope.isError())
        .build();
  }

  public String json(McpReadEnvelope envelope) {
    return json(envelope, envelope.correlationId());
  }

  private String json(Object value, String correlationId) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      LOGGER.warn(
          "mcpSerializationFailed correlationId={} errorCode=INTERNAL_ERROR", correlationId);
      return """
          {"schemaVersion":"1.0","sourceKind":"MCP","dataAsOf":null,"projectionStatus":null,\
          "correlationId":"00000000-0000-0000-0000-000000000000","warnings":[],"data":null,\
          "isError":true,"code":"INTERNAL_ERROR","retryable":false,\
          "safeMessage":"The operation could not be completed."}\
          """;
    }
  }

  public static String normalizedProjectionStatus(String status) {
    if ("CURRENT".equals(status) || "EMPTY".equals(status)) {
      return status;
    }
    return "STALE";
  }

  public static List<String> projectionWarnings(String status) {
    if ("REBUILDING".equals(status)) {
      return List.of("The reporting projection is rebuilding; returned data may be stale.");
    }
    if (!"CURRENT".equals(status) && !"EMPTY".equals(status)) {
      return List.of("The reporting projection may be stale.");
    }
    return List.of();
  }

  private JsonNode tree(String value, String correlationId) {
    try {
      return json.readTree(value);
    } catch (JacksonException exception) {
      LOGGER.warn(
          "mcpSerializationTreeFailed correlationId={} errorCode=INTERNAL_ERROR", correlationId);
      try {
        return json.readTree(
            """
            {"schemaVersion":"1.0","sourceKind":"MCP","dataAsOf":null,\
            "projectionStatus":null,"correlationId":"00000000-0000-0000-0000-000000000000",\
            "warnings":[],"data":null,"isError":true,"code":"INTERNAL_ERROR",\
            "retryable":false,"safeMessage":"The operation could not be completed."}\
            """);
      } catch (JacksonException impossible) {
        throw new IllegalStateException("Static MCP error JSON could not be parsed");
      }
    }
  }

  private static McpReadEnvelope error(
      String sourceKind, String correlationId, String code, boolean retryable, String safeMessage) {
    return new McpReadEnvelope(
        SCHEMA_VERSION,
        sourceKind,
        null,
        null,
        correlationId,
        List.of(),
        null,
        true,
        code,
        retryable,
        safeMessage);
  }

  private static String correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
  }
}
