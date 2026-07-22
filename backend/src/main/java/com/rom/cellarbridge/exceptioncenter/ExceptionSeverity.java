package com.rom.cellarbridge.exceptioncenter;

public enum ExceptionSeverity {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  public ExceptionSeverity escalate() {
    return switch (this) {
      case LOW -> MEDIUM;
      case MEDIUM -> HIGH;
      case HIGH, CRITICAL -> CRITICAL;
    };
  }
}
