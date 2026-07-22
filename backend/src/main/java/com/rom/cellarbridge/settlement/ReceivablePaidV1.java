package com.rom.cellarbridge.settlement;

import java.time.Instant;
import java.util.UUID;

public final class ReceivablePaidV1 {
  public static final String TYPE = "cellarbridge.settlement.receivable-paid.v1";

  private ReceivablePaidV1() {}

  public record Payload(
      UUID receivableId,
      String receivableNumber,
      UUID orderId,
      String orderNumber,
      String originalAmount,
      String currency,
      Instant paidAt) {}
}
