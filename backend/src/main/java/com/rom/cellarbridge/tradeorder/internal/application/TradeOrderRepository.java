package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TradeOrderRepository {

  String nextNumber(TenantId tenantId, Instant now);

  /** The supplied {@code order.snapshotHash} must use the current bare Snapshot Hash V1 format. */
  boolean insertIfAbsent(TenantId tenantId, TradeOrder order, UUID actorId);

  Optional<TradeOrder> findBySourceQuotation(TenantId tenantId, UUID sourceQuotationId);

  Optional<TradeOrder> find(TenantId tenantId, UUID orderId, UUID partnerScope, UUID ownerScope);

  /** Locks one tenant-scoped order while an asynchronous lifecycle outcome is applied. */
  Optional<TradeOrder> findForUpdate(TenantId tenantId, UUID orderId);

  Optional<ReservationOutcomeEvidence> findReservationOutcome(TenantId tenantId, UUID orderId);

  void saveReservationOutcome(
      TenantId tenantId,
      TradeOrder before,
      TradeOrder after,
      ReservationOutcomeEvidence outcome,
      UUID actorId);

  boolean hasTimelineEvent(TenantId tenantId, UUID orderId, UUID eventId);

  void saveFulfillmentTransition(
      TenantId tenantId, TradeOrder before, TradeOrder after, FulfillmentFact fact, UUID actorId);

  void appendFulfillmentMilestone(
      TenantId tenantId, TradeOrder order, FulfillmentFact fact, UUID actorId);

  OrderPage list(
      TenantId tenantId,
      Set<TradeOrderStatus> statuses,
      UUID partnerFilter,
      UUID ownerFilter,
      CursorPosition after,
      int limit);

  List<TimelineEntry> timeline(TenantId tenantId, UUID orderId, boolean customerOnly);

  record CursorPosition(Instant createdAt, UUID id) {}

  record OrderPage(List<TradeOrder> items, CursorPosition nextPosition, boolean hasNext) {
    public OrderPage {
      items = List.copyOf(items);
    }
  }

  record TimelineEntry(
      UUID id,
      Instant occurredAt,
      String action,
      String previousState,
      TradeOrderStatus newState,
      String safeReason,
      String visibility) {}

  record ReservationOutcomeEvidence(
      UUID eventId,
      String eventType,
      TradeOrderStatus status,
      String reasonCode,
      String evidenceHash,
      Instant occurredAt) {}

  record FulfillmentFact(
      UUID eventId,
      String eventType,
      String code,
      String safeMessage,
      String visibility,
      Instant occurredAt) {}
}
