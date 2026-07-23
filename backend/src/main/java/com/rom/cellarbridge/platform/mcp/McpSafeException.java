package com.rom.cellarbridge.platform.mcp;

/** Explicit safe failure that may cross the MCP boundary without exposing its cause. */
public final class McpSafeException extends RuntimeException {

  private final String code;
  private final boolean retryable;
  private final String safeMessage;

  private McpSafeException(String code, boolean retryable, String safeMessage) {
    super(code);
    this.code = code;
    this.retryable = retryable;
    this.safeMessage = safeMessage;
  }

  public static McpSafeException invalidRequest() {
    return new McpSafeException("VALIDATION_FAILED", false, "The request arguments are invalid.");
  }

  public static McpSafeException notFound() {
    return new McpSafeException(
        "RESOURCE_NOT_FOUND", false, "The requested resource was not found.");
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }

  public String safeMessage() {
    return safeMessage;
  }
}
