package com.rom.cellarbridge.settlement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceivableInvestigationQuery {

  ReceivableInvestigation investigateReceivable(UUID receivableId);

  ReceivableInvestigations investigateByOrder(UUID orderId, int limit);

  Optional<ReceivableInvestigation> investigateByFulfillment(UUID fulfillmentPlanId);

  enum DataClassification {
    UNTRUSTED_BUSINESS_DATA
  }

  enum PaymentFactType {
    PAYMENT,
    REVERSAL
  }

  record UntrustedText(DataClassification classification, String value, boolean truncated) {}

  record ReceivableInvestigations(List<ReceivableInvestigation> items, boolean hasMore) {
    public ReceivableInvestigations {
      items = List.copyOf(items);
    }
  }

  record ReceivableInvestigation(
      UUID id,
      String number,
      UUID orderId,
      String orderNumber,
      ReceivableStatus status,
      String currency,
      LocalDate dueDate,
      FulfillmentTrigger trigger,
      InvestigationMoney originalAmount,
      InvestigationMoney outstandingAmount,
      List<PaymentFact> payments,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    public ReceivableInvestigation {
      payments = List.copyOf(payments);
    }
  }

  record FulfillmentTrigger(UUID planId, String policyCode, int policyVersion) {}

  record InvestigationMoney(String amount, String currency) {}

  record PaymentFact(
      UUID id,
      PaymentFactType type,
      InvestigationMoney amount,
      UntrustedText externalReference,
      UUID reversedPaymentId,
      UntrustedText reversalReason,
      PaymentMethod method,
      LocalDate occurredOn,
      Instant recordedAt,
      UUID actorId) {}
}
