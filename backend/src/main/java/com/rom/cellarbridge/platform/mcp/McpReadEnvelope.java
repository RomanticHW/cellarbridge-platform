package com.rom.cellarbridge.platform.mcp;

import java.time.Instant;
import java.util.List;

/** Stable, safe envelope shared by CellarBridge read-only MCP capabilities. */
public record McpReadEnvelope(
    String schemaVersion,
    String sourceKind,
    Instant dataAsOf,
    String projectionStatus,
    String correlationId,
    List<String> warnings,
    Object data,
    boolean isError,
    String code,
    boolean retryable,
    String safeMessage) {

  public McpReadEnvelope {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
