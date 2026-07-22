package com.rom.cellarbridge.settlement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PaymentRecordedV1 {
  public static final String TYPE = "cellarbridge.settlement.payment-recorded.v1";

  private PaymentRecordedV1() {}

  public record Payload(
      UUID receivableId,
      String receivableNumber,
      UUID orderId,
      String orderNumber,
      UUID paymentId,
      String amount,
      String currency,
      String method,
      String externalReference,
      LocalDate occurredOn,
      Instant recordedAt,
      UUID actorId,
      String outstandingAmount,
      String receivableStatus) {}
}
