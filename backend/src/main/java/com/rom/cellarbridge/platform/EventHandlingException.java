package com.rom.cellarbridge.platform;

import java.util.regex.Pattern;

/** Safe handler failure classification used by the bounded local-delivery retry policy. */
public final class EventHandlingException extends RuntimeException {

  private static final Pattern FAILURE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,79}$");
  private final String failureCode;
  private final boolean retryable;

  private EventHandlingException(String failureCode, boolean retryable) {
    super(validate(failureCode));
    this.failureCode = failureCode;
    this.retryable = retryable;
  }

  public static EventHandlingException retryable(String failureCode) {
    return new EventHandlingException(failureCode, true);
  }

  public static EventHandlingException finalFailure(String failureCode) {
    return new EventHandlingException(failureCode, false);
  }

  public String failureCode() {
    return failureCode;
  }

  public boolean retryable() {
    return retryable;
  }

  private static String validate(String failureCode) {
    if (failureCode == null || !FAILURE_CODE.matcher(failureCode).matches()) {
      throw new IllegalArgumentException("failureCode must be a safe upper-snake-case code");
    }
    return failureCode;
  }
}
