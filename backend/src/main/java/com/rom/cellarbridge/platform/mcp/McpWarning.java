package com.rom.cellarbridge.platform.mcp;

import java.util.Set;
import java.util.regex.Pattern;

/** Stable machine code and safe operator-facing text attached to an MCP result. */
public record McpWarning(String code, String severity, String safeMessage) {

  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
  private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING");

  public McpWarning {
    if (code == null
        || !CODE.matcher(code).matches()
        || severity == null
        || !SEVERITIES.contains(severity)
        || safeMessage == null
        || safeMessage.isBlank()
        || safeMessage.length() > 240) {
      throw new IllegalArgumentException("MCP warning is invalid");
    }
  }

  public static McpWarning info(String code, String safeMessage) {
    return new McpWarning(code, "INFO", safeMessage);
  }

  public static McpWarning warning(String code, String safeMessage) {
    return new McpWarning(code, "WARNING", safeMessage);
  }
}
