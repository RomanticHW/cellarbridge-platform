package com.rom.cellarbridge.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpPlatformContractTest {

  private static final int MAX_BYTES = 4 * 1024 * 1024;
  private static final int MAX_COLLECTION_ITEMS = 100;

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void serializesWarningsAndSourceWatermarkFreshnessEvidence() throws Exception {
    Instant observedAt = Instant.parse("2026-07-23T12:00:30Z");
    Instant sourceWatermark = Instant.parse("2026-07-23T12:00:20Z");
    Instant processedThrough = Instant.parse("2026-07-23T12:00:08Z");
    Instant lastRefresh = Instant.parse("2026-07-23T12:00:10Z");
    McpFreshnessEvidence freshness =
        new McpFreshnessEvidence(
            "SOURCE_WATERMARK",
            observedAt,
            sourceWatermark,
            processedThrough,
            lastRefresh,
            12L,
            2L,
            1L);
    McpCallSupport calls = calls(MAX_BYTES, MAX_COLLECTION_ITEMS);

    McpReadEnvelope envelope =
        calls.read(
            "REPORTING_PROJECTION",
            () ->
                McpReadPayload.projection(
                    processedThrough,
                    "STALE",
                    freshness,
                    List.of(
                        McpWarning.warning(
                            "PROJECTION_DEAD_LETTER",
                            "A source event requires operator attention.")),
                    Map.of("pendingWorkItems", 2)));
    JsonNode serialized = json.readTree(calls.json(envelope));

    assertThat(serialized.path("projectionStatus").asText()).isEqualTo("STALE");
    JsonNode evidence = serialized.path("freshness");
    assertThat(evidence.path("mode").asText()).isEqualTo("SOURCE_WATERMARK");
    assertThat(evidence.path("sourceWatermark").asText()).isEqualTo(sourceWatermark.toString());
    assertThat(evidence.path("projectorWatermark").asText()).isEqualTo(processedThrough.toString());
    assertThat(evidence.path("lagSeconds").asLong()).isEqualTo(12L);
    assertThat(serialized.path("warnings").path(0).path("code").asText())
        .isEqualTo("PROJECTION_DEAD_LETTER");
  }

  @Test
  void enforcesTheExactUtf8ByteBoundaryWithABoundedSafeError() {
    String confidentialPayload = "confidential-payload-".repeat(120);
    Map<String, String> data = Map.of("notes", confidentialPayload);
    CallToolResult reference = transactional(MAX_BYTES, MAX_COLLECTION_ITEMS, data);
    int exactBytes =
        textContent(reference).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    CallToolResult exact = transactional(exactBytes, MAX_COLLECTION_ITEMS, data);
    CallToolResult result = transactional(exactBytes - 1, MAX_COLLECTION_ITEMS, data);

    assertThat(exact.isError()).isFalse();
    assertResultTooLarge(result);
    assertThat(textContent(result)).doesNotContain(confidentialPayload, "confidential-payload");
  }

  @Test
  void enforcesTheExactRecursiveCollectionBoundary() {
    Map<String, Object> data =
        Map.of(
            "items",
            List.of(
                Map.of("id", "one"),
                Map.of("id", "two"),
                Map.of("id", "three"),
                Map.of("id", "four")));
    CallToolResult exact = transactional(MAX_BYTES, 4, data);
    CallToolResult result = transactional(MAX_BYTES, 3, data);

    assertThat(exact.isError()).isFalse();
    assertResultTooLarge(result);
    assertThat(textContent(result)).doesNotContain("\"one\"", "\"four\"");
  }

  @Test
  void marksUnexpectedFailuresAsErrorsWithoutLeakingExceptionDetails() {
    String secret =
        "Bearer eyJhbGciOiJub25lIn0.private-token SELECT * FROM tenant_secret "
            + "com.rom.cellarbridge.internal.Hidden";
    McpCallSupport calls = calls(MAX_BYTES, MAX_COLLECTION_ITEMS);

    List<CallToolResult> results =
        List.of(
            calls.tool(
                "SECURED_SOURCE",
                () -> {
                  throw new IllegalStateException(secret);
                }),
            calls.tool(
                "SECURED_SOURCE", () -> McpReadPayload.transactional(new BrokenPayload(secret))));
    for (CallToolResult result : results) {
      assertSafeError(
          result, "SECURED_SOURCE", "INTERNAL_ERROR", "The operation could not be completed.");
      assertThat(textContent(result))
          .doesNotContain(
              secret,
              "private-token",
              "tenant_secret",
              "SELECT",
              "com.rom.cellarbridge",
              "IllegalStateException");
    }
  }

  @Test
  void rejectsInvalidWarningFreshnessAndBudgetConfiguration() {
    assertThatThrownBy(() -> new McpWarning("bad-code", "WARNING", "Safe"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new McpResponseProperties(1023, 1, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private McpCallSupport calls(int maxBytes, int maxCollectionItems) {
    return new McpCallSupport(json, new McpResponseProperties(maxBytes, maxCollectionItems, 100));
  }

  private CallToolResult transactional(int maxBytes, int maxItems, Object data) {
    return calls(maxBytes, maxItems)
        .tool("TRANSACTIONAL", () -> McpReadPayload.transactional(data));
  }

  private static String textContent(CallToolResult result) {
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst()).isInstanceOf(TextContent.class);
    return ((TextContent) result.content().getFirst()).text();
  }

  private static JsonNode structured(CallToolResult result) {
    assertThat(result.structuredContent()).isInstanceOf(JsonNode.class);
    return (JsonNode) result.structuredContent();
  }

  private void assertResultTooLarge(CallToolResult result) {
    assertSafeError(
        result, "TRANSACTIONAL", "RESULT_TOO_LARGE", "Narrow the query or request a smaller page.");
  }

  private void assertSafeError(
      CallToolResult result, String sourceKind, String code, String safeMessage) {
    JsonNode structured = structured(result);
    assertThat(result.isError()).isTrue();
    assertThat(structured.path("sourceKind").asText()).isEqualTo(sourceKind);
    assertThat(structured.path("isError").asBoolean()).isTrue();
    assertThat(structured.path("code").asText()).isEqualTo(code);
    assertThat(structured.path("retryable").asBoolean()).isFalse();
    assertThat(structured.path("safeMessage").asText()).isEqualTo(safeMessage);
    assertThat(structured.path("data").isNull()).isTrue();
    assertThat(json.readTree(textContent(result))).isEqualTo(structured);
  }

  private record BrokenPayload(String marker) {
    public String getValue() {
      throw new IllegalStateException(marker);
    }
  }
}
