package com.rom.cellarbridge.exceptioncenter;

import java.time.Instant;
import java.util.UUID;

public final class ExceptionClosedV1 {
  public static final String TYPE = "cellarbridge.exception.closed.v1";

  private ExceptionClosedV1() {}

  public record Payload(
      UUID exceptionId,
      String exceptionNumber,
      String reasonCode,
      String safeReason,
      UUID actorId,
      Instant closedAt) {}
}
