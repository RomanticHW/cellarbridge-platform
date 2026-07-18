package com.rom.cellarbridge.fulfillment;

import java.time.Instant;
import java.util.UUID;

public final class PublicMilestoneReachedV1 {
  public static final String TYPE = "cellarbridge.fulfillment.public-milestone-reached.v1";

  private PublicMilestoneReachedV1() {}

  public record Payload(
      UUID planId,
      String planNumber,
      UUID orderId,
      String orderNumber,
      String code,
      String label,
      Instant occurredAt) {}
}
