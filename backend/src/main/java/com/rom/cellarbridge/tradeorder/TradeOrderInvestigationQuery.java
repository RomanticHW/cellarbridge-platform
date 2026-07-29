package com.rom.cellarbridge.tradeorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Caller-scoped, source-owned facts for trade-order investigation. */
public interface TradeOrderInvestigationQuery {

  Optional<TradeOrderFact> findById(UUID orderId);

  Optional<TradeOrderFact> findBySourceQuotation(UUID quotationId);

  record TradeOrderFact(
      UUID id,
      String number,
      TradeOrderStatus status,
      SourceQuotation sourceQuotation,
      UntrustedBusinessData partnerDisplay,
      BigDecimal total,
      String currency,
      String routeCode,
      SnapshotStatus reservationStatus,
      SnapshotStatus fulfillmentStatus,
      List<Milestone> milestones,
      long version,
      Instant updatedAt) {
    public TradeOrderFact {
      milestones = List.copyOf(milestones);
    }
  }

  record SourceQuotation(UUID id, String number, UUID revisionId, int revisionNumber) {}

  record Milestone(Instant occurredAt, String code, TradeOrderStatus status) {}

  enum SnapshotStatus {
    NOT_STARTED,
    PENDING,
    CONFIRMED,
    FAILED,
    READY,
    IN_PROGRESS,
    COMPLETED,
    UNKNOWN
  }

  record UntrustedBusinessData(String classification, String value, boolean truncated) {}
}
