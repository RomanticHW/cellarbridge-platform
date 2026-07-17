package com.rom.cellarbridge.inventory.internal.application;

public final class ReservationPersistenceException extends RuntimeException {

  private final Code code;

  public ReservationPersistenceException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public ReservationPersistenceException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public enum Code {
    RESERVATION_REQUEST_CONFLICT,
    OPTIMISTIC_VERSION_CONFLICT,
    PERSISTENCE_INTEGRITY_VIOLATION
  }
}
