package com.rom.cellarbridge.tradeorder.internal.domain;

import org.springframework.http.HttpStatus;

public final class TradeOrderProblem extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public TradeOrderProblem(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
