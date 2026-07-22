package com.rom.cellarbridge.fulfillment;

import java.time.Instant;
import java.util.UUID;

public final class FulfillmentPlanCreatedV1 {
  public static final String TYPE = "cellarbridge.fulfillment.plan-created.v1";

  private FulfillmentPlanCreatedV1() {}

  public record Payload(
      UUID planId,
      String planNumber,
      UUID orderId,
      String orderNumber,
      String routeCode,
      String templateCode,
      String templateVersion,
      Instant createdAt) {}
}
