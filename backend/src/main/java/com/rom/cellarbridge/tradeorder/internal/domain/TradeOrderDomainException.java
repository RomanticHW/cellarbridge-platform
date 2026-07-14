package com.rom.cellarbridge.tradeorder.internal.domain;

import java.util.Map;
import java.util.Objects;

public final class TradeOrderDomainException extends RuntimeException {

  public enum FailureKind {
    VALIDATION,
    BUSINESS_RULE,
    STATE_CONFLICT,
    ACCESS_DENIED
  }

  private final FailureKind kind;
  private final String code;
  private final String safeMessage;
  private final String currentState;
  private final Map<String, Object> details;

  public TradeOrderDomainException(FailureKind kind, String code, String safeMessage) {
    this(kind, code, safeMessage, null, Map.of());
  }

  public TradeOrderDomainException(
      FailureKind kind,
      String code,
      String safeMessage,
      String currentState,
      Map<String, ?> details) {
    super(Objects.requireNonNull(safeMessage, "safeMessage"));
    this.kind = Objects.requireNonNull(kind, "kind");
    this.code = Objects.requireNonNull(code, "code");
    this.safeMessage = safeMessage;
    this.currentState = currentState;
    this.details = Map.copyOf(Objects.requireNonNull(details, "details"));
  }

  public FailureKind kind() {
    return kind;
  }

  public String code() {
    return code;
  }

  public String safeMessage() {
    return safeMessage;
  }

  public String currentState() {
    return currentState;
  }

  public Map<String, Object> details() {
    return details;
  }
}
