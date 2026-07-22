package com.rom.cellarbridge.fulfillment.internal.application;

public final class FulfillmentProblem extends RuntimeException {
  private final String code;
  private final Long currentVersion;

  public FulfillmentProblem(String code, String message) {
    this(code, message, null);
  }

  public FulfillmentProblem(String code, String message, Long currentVersion) {
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
