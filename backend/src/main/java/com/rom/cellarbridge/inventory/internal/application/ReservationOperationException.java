package com.rom.cellarbridge.inventory.internal.application;

public final class ReservationOperationException extends RuntimeException {

  private final String code;

  public ReservationOperationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
