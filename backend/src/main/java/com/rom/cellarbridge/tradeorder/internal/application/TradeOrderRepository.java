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

  boolean insertIfAbsent(TenantId tenantId, TradeOrder order, UUID actorId);

  Optional<TradeOrder> findBySourceQuotation(TenantId tenantId, UUID sourceQuotationId);

  Optional<TradeOrder> find(TenantId tenantId, UUID orderId, UUID partnerScope, UUID ownerScope);

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
}
