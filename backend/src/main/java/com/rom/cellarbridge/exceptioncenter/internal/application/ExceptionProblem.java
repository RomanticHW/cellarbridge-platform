package com.rom.cellarbridge.exceptioncenter.internal.application;

public final class ExceptionProblem extends RuntimeException {
  private final String code;
  private final Long currentVersion;

  public ExceptionProblem(String code, String message) {
    this(code, message, null);
  }

  public ExceptionProblem(String code, String message, Long currentVersion) {
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
