package com.rom.cellarbridge.platform.mcp;

import com.rom.cellarbridge.platform.CorrelationIdFilter;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class McpCallSupport {

  public static final String SCHEMA_VERSION = "2.0";
  private static final Logger LOGGER = LoggerFactory.getLogger(McpCallSupport.class);
  private final JsonMapper json;
  private final McpResponseProperties responseProperties;
  private final McpReadExecutor executor;

  public McpCallSupport(JsonMapper json, McpResponseProperties responseProperties) {
    this(json, responseProperties, null);
  }

  @Autowired
  McpCallSupport(
      JsonMapper json, McpResponseProperties responseProperties, McpReadExecutor executor) {
    this.json = json;
    this.responseProperties = responseProperties;
    this.executor = executor;
  }

  public McpReadEnvelope read(String sourceKind, Supplier<McpReadPayload> operation) {
    String correlationId = correlationId();
    try {
      McpReadPayload payload = executor == null ? operation.get() : executor.execute(operation);
      return new McpReadEnvelope(
          SCHEMA_VERSION,
          sourceKind,
          payload.dataAsOf(),
          payload.projectionStatus(),
          payload.freshness(),
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
    } catch (QueryTimeoutException | TransactionTimedOutException exception) {
      return error(
          sourceKind,
          correlationId,
          "DOWNSTREAM_TIMEOUT",
          true,
          "The operation exceeded its execution deadline.");
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
    SerializedEnvelope serialized = serialize(envelope);
    return CallToolResult.builder()
        .addTextContent(serialized.text())
        .structuredContent(serialized.structured())
        .isError(serialized.envelope().isError())
        .build();
  }

  public String json(McpReadEnvelope envelope) {
    return serialize(envelope).text();
  }

  private SerializedEnvelope serialize(McpReadEnvelope envelope) {
    LimitedOutputStream output = new LimitedOutputStream(responseProperties.maxBytes());
    try {
      json.writeValue(output, envelope);
      byte[] bytes = output.toByteArray();
      JsonNode structured = json.readTree(bytes);
      if (collectionItems(structured) > responseProperties.maxCollectionItems()) {
        return resultTooLarge(envelope);
      }
      return new SerializedEnvelope(
          envelope, new String(bytes, StandardCharsets.UTF_8), structured);
    } catch (RuntimeException exception) {
      if (output.exceeded()) {
        return resultTooLarge(envelope);
      }
      LOGGER.warn(
          "mcpSerializationFailed sourceKind={} correlationId={} errorCode=INTERNAL_ERROR",
          envelope.sourceKind(),
          envelope.correlationId());
      return serializedError(
          error(
              envelope.sourceKind(),
              envelope.correlationId(),
              "INTERNAL_ERROR",
              false,
              "The operation could not be completed."));
    }
  }

  private SerializedEnvelope resultTooLarge(McpReadEnvelope envelope) {
    return serializedError(
        error(
            envelope.sourceKind(),
            envelope.correlationId(),
            "RESULT_TOO_LARGE",
            false,
            "Narrow the query or request a smaller page."));
  }

  private SerializedEnvelope serializedError(McpReadEnvelope envelope) {
    try {
      byte[] bytes = json.writeValueAsBytes(envelope);
      if (bytes.length > responseProperties.maxBytes()) {
        throw new IllegalStateException("Configured MCP error budget is too small");
      }
      return new SerializedEnvelope(
          envelope, new String(bytes, StandardCharsets.UTF_8), json.readTree(bytes));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Safe MCP error serialization failed", exception);
    }
  }

  public static String normalizedProjectionStatus(String status) {
    if (List.of("CURRENT", "STALE", "EMPTY", "REBUILDING", "UNKNOWN").contains(status)) {
      return status;
    }
    return "STALE";
  }

  private static McpReadEnvelope error(
      String sourceKind, String correlationId, String code, boolean retryable, String safeMessage) {
    return new McpReadEnvelope(
        SCHEMA_VERSION,
        sourceKind,
        null,
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

  private static int collectionItems(JsonNode node) {
    if (node == null || node.isValueNode()) {
      return 0;
    }
    int count = node.isArray() ? node.size() : 0;
    for (JsonNode child : node) {
      count = Math.addExact(count, collectionItems(child));
    }
    return count;
  }

  private static final class LimitedOutputStream extends ByteArrayOutputStream {
    private final int limit;
    private boolean exceeded;

    private LimitedOutputStream(int limit) {
      this.limit = limit;
    }

    @Override
    public synchronized void write(int value) {
      requireCapacity(1);
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      requireCapacity(length);
      super.write(bytes, offset, length);
    }

    private void requireCapacity(int length) {
      if (length > limit - count) {
        exceeded = true;
        throw new IllegalStateException("MCP response byte limit exceeded");
      }
    }

    private boolean exceeded() {
      return exceeded;
    }
  }

  private record SerializedEnvelope(McpReadEnvelope envelope, String text, JsonNode structured) {}
}
