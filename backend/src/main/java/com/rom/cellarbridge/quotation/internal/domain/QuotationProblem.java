package com.rom.cellarbridge.quotation.internal.domain;

import org.springframework.http.HttpStatus;

public final class QuotationProblem extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final Long currentVersion;
  private final String currentState;

  public QuotationProblem(HttpStatus status, String code, String message) {
    this(status, code, message, null, null);
  }

  public QuotationProblem(HttpStatus status, String code, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.code = code;
    this.currentVersion = null;
    this.currentState = null;
  }

  public QuotationProblem(
      HttpStatus status, String code, String message, Long currentVersion, String currentState) {
    super(message);
    this.status = status;
    this.code = code;
    this.currentVersion = currentVersion;
    this.currentState = currentState;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public Long currentVersion() {
    return currentVersion;
  }

  public String currentState() {
    return currentState;
  }
}
