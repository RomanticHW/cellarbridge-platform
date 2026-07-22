package com.rom.cellarbridge.settlement;

import java.time.Instant;
import java.util.UUID;

public final class PaymentReversedV1 {
  public static final String TYPE = "cellarbridge.settlement.payment-reversed.v1";

  private PaymentReversedV1() {}

  public record Payload(
      UUID receivableId,
      String receivableNumber,
      UUID orderId,
      String orderNumber,
      UUID paymentId,
      UUID reversalId,
      String amount,
      String currency,
      String safeReason,
      Instant reversedAt,
      UUID actorId,
      String outstandingAmount,
      String receivableStatus) {}
}
