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

  public static McpSafeException invalidCursor() {
    return new McpSafeException(
        "CURSOR_INVALID", false, "The cursor is invalid, expired, or does not match this query.");
  }

  public static McpSafeException resultTooLarge() {
    return new McpSafeException(
        "RESULT_TOO_LARGE", false, "Narrow the query or request a smaller page.");
  }

  public static McpSafeException timeout() {
    return new McpSafeException(
        "DOWNSTREAM_TIMEOUT", true, "The operation exceeded its execution deadline.");
  }

  public static McpSafeException overloaded() {
    return new McpSafeException(
        "DEPENDENCY_OVERLOADED", true, "The operation is temporarily at capacity.");
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
