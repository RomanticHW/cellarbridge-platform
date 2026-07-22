package com.rom.cellarbridge.fulfillment;

import java.time.Instant;
import java.util.UUID;

public final class FulfillmentStepStartedV1 {
  public static final String TYPE = "cellarbridge.fulfillment.step-started.v1";

  private FulfillmentStepStartedV1() {}

  public record Payload(
      UUID planId,
      String planNumber,
      UUID orderId,
      String orderNumber,
      UUID stepId,
      String stepCode,
      Instant startedAt) {}
}
