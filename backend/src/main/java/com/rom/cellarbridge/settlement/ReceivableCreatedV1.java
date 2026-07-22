package com.rom.cellarbridge.settlement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ReceivableCreatedV1 {
  public static final String TYPE = "cellarbridge.settlement.receivable-created.v1";

  private ReceivableCreatedV1() {}

  public record Payload(
      UUID receivableId,
      String receivableNumber,
      UUID orderId,
      String orderNumber,
      UUID partnerId,
      String partnerNumber,
      String originalAmount,
      String currency,
      LocalDate dueDate,
      Instant createdAt,
      String triggerType,
      UUID triggerId,
      String triggerPolicyCode,
      int triggerPolicyVersion) {}
}
