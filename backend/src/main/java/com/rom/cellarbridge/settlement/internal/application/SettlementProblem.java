package com.rom.cellarbridge.settlement.internal.application;

public final class SettlementProblem extends RuntimeException {
  private final String code;
  private final Long currentVersion;

  public SettlementProblem(String code, String message) {
    this(code, message, null);
  }

  public SettlementProblem(String code, String message, Long currentVersion) {
    super(message);
    this.code = code;
    this.currentVersion = currentVersion;
  }

  public String code() {
    return code;
  }

  public Long currentVersion() {
    return currentVersion;
  }
}
