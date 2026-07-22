package com.rom.cellarbridge.exceptioncenter;

import java.time.Instant;
import java.util.UUID;

public final class ExceptionOpenedV1 {
  public static final String TYPE = "cellarbridge.exception.opened.v1";

  private ExceptionOpenedV1() {}

  public record Payload(
      UUID exceptionId,
      String exceptionNumber,
      String exceptionType,
      ExceptionSeverity severity,
      String sourceType,
      UUID sourceId,
      String sourceNumber,
      String title,
      Instant openedAt,
      Instant dueAt) {}
}
