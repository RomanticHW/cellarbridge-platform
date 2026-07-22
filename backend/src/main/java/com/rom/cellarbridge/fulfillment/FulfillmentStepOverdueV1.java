package com.rom.cellarbridge.fulfillment;

import java.time.Instant;
import java.util.UUID;

public final class FulfillmentStepOverdueV1 {
  public static final String TYPE = "cellarbridge.fulfillment.step-overdue.v1";

  private FulfillmentStepOverdueV1() {}

  public record Payload(
      UUID planId,
      String planNumber,
      UUID orderId,
      String orderNumber,
      UUID stepId,
      String stepCode,
      Instant dueAt,
      Instant detectedAt) {}
}
