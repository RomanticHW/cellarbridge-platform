package com.rom.cellarbridge.partner;

public final class PartnerEligibilityException extends RuntimeException {

  private final String code;

  public PartnerEligibilityException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
