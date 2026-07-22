package com.rom.cellarbridge.settlement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ReceivableOverdueV1 {
  public static final String TYPE = "cellarbridge.settlement.receivable-overdue.v1";

  private ReceivableOverdueV1() {}

  public record Payload(
      UUID receivableId,
      String receivableNumber,
      UUID orderId,
      String orderNumber,
      LocalDate dueDate,
      String outstandingAmount,
      String currency,
      Instant markedAt) {}
}
