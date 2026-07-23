package com.rom.cellarbridge.platform.mcp;

import java.time.Instant;
import java.util.List;

/** Authorized application result before the common MCP envelope is applied. */
public record McpReadPayload(
    Instant dataAsOf,
    String projectionStatus,
    McpFreshnessEvidence freshness,
    List<McpWarning> warnings,
    Object data) {

  public McpReadPayload {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public static McpReadPayload transactional(Object data) {
    return new McpReadPayload(null, "NOT_APPLICABLE", null, List.of(), data);
  }

  public static McpReadPayload projection(
      Instant dataAsOf,
      String projectionStatus,
      McpFreshnessEvidence freshness,
      List<McpWarning> warnings,
      Object data) {
    return new McpReadPayload(dataAsOf, projectionStatus, freshness, warnings, data);
  }
}
