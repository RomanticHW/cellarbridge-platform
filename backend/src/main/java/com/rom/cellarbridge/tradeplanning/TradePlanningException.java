package com.rom.cellarbridge.tradeplanning;

public final class TradePlanningException extends RuntimeException {

  private final String code;

  public TradePlanningException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
