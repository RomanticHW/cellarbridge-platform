package com.rom.cellarbridge.fulfillment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FulfillmentCompletedV1 {
  public static final String TYPE = "cellarbridge.fulfillment.completed.v1";

  private FulfillmentCompletedV1() {}

  public record Payload(
      UUID planId,
      String planNumber,
      UUID orderId,
      String orderNumber,
      String routeCode,
      Instant completedAt,
      List<PublicMilestone> publicMilestones) {
    public Payload {
      publicMilestones = List.copyOf(publicMilestones);
    }
  }

  public record PublicMilestone(String code, String label, Instant occurredAt) {}
}
