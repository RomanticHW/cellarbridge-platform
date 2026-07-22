package com.rom.cellarbridge.fulfillment;

import java.time.Instant;
import java.util.UUID;

public final class FulfillmentStepFailedV1 {
  public static final String TYPE = "cellarbridge.fulfillment.step-failed.v1";

  private FulfillmentStepFailedV1() {}

  public record Payload(
      UUID planId,
      String planNumber,
      UUID orderId,
      String orderNumber,
      UUID stepId,
      String stepCode,
      String failureCode,
      String safeMessage,
      Instant failedAt,
      int attempt,
      boolean retryable) {}
}
