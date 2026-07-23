package com.rom.cellarbridge.platform.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deterministic result budgets applied across bounded queries and serialization. */
@ConfigurationProperties(prefix = "cellarbridge.mcp.response")
public record McpResponseProperties(int maxBytes, int maxCollectionItems, int maxPageSize) {

  public McpResponseProperties {
    if (maxBytes < 1024
        || maxBytes > 4 * 1024 * 1024
        || maxCollectionItems < 1
        || maxCollectionItems > 1000
        || maxPageSize < 1
        || maxPageSize > 100) {
      throw new IllegalArgumentException("MCP response budget is invalid");
    }
  }
}
