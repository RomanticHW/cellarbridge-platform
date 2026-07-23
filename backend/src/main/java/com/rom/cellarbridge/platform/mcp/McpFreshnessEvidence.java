package com.rom.cellarbridge.platform.mcp;

import java.time.Instant;

/** Evidence used by a client to distinguish source-aligned data from an age observation. */
public record McpFreshnessEvidence(
    String mode,
    Instant observedAt,
    Instant sourceWatermark,
    Instant projectorWatermark,
    Instant lastSuccessfulRefreshAt,
    Long lagSeconds,
    Long pendingCount,
    Long deadLetterCount) {

  public McpFreshnessEvidence {
    if (!"SOURCE_WATERMARK".equals(mode) && !"OBSERVATION_AGE".equals(mode)
        || observedAt == null
        || negative(lagSeconds)
        || negative(pendingCount)
        || negative(deadLetterCount)) {
      throw new IllegalArgumentException("MCP freshness evidence is invalid");
    }
  }

  private static boolean negative(Long value) {
    return value != null && value < 0;
  }
}
