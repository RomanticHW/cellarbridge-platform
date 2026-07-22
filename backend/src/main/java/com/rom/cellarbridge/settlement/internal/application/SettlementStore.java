package com.rom.cellarbridge.settlement.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.settlement.PaymentMethod;
import com.rom.cellarbridge.settlement.ReceivableStatus;
import com.rom.cellarbridge.settlement.internal.domain.Receivable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SettlementStore {
  TriggerPolicy activePolicy();

  Optional<OrderSnapshot> orderSnapshot(TenantId tenantId, UUID orderId);

  boolean insertOrderSnapshot(OrderSnapshot snapshot);

  String nextNumber(TenantId tenantId, Instant at);

  Optional<ReceivableRecord> findByTrigger(
      TenantId tenantId, String triggerType, UUID triggerId, boolean lock);

  Optional<ReceivableRecord> find(
      TenantId tenantId, UUID receivableId, UUID partnerScope, boolean lock);

  boolean insertReceivable(ReceivableRecord receivable);

  ReceivablePage list(
      TenantId tenantId,
      UUID partnerScope,
      Set<ReceivableStatus> statuses,
      CursorPosition after,
      int limit);

  List<PaymentRecord> payments(TenantId tenantId, UUID receivableId);

  List<ReversalRecord> reversals(TenantId tenantId, UUID receivableId);

  List<HistoryRecord> history(TenantId tenantId, UUID receivableId);

  Optional<PaymentRecord> paymentByReference(TenantId tenantId, String externalReference);

  Optional<PaymentRecord> paymentByIdempotency(TenantId tenantId, String keyHash);

  void lockPaymentRequest(TenantId tenantId, String keyHash, String externalReference);

  Optional<PaymentRecord> payment(
      TenantId tenantId, UUID receivableId, UUID paymentId, boolean lock);

  void insertPayment(PaymentRecord payment);

  Optional<ReversalRecord> reversalByIdempotency(TenantId tenantId, String keyHash);

  void lockReversalRequest(TenantId tenantId, String keyHash);

  BigDecimal reversedAmount(TenantId tenantId, UUID paymentId);

  void insertReversal(ReversalRecord reversal);

  void updateReceivable(
      TenantId tenantId, ReceivableRecord before, Receivable after, UUID actorId, Instant at);

  void insertHistory(HistoryRecord history);

  List<ReceivableRecord> lockOverdueCandidates(LocalDate today, int limit);

  record TriggerPolicy(String code, int version, String triggerType) {}

  record OrderSnapshot(
      TenantId tenantId,
      UUID orderId,
      String orderNumber,
      UUID partnerId,
      String partnerNumber,
      String partnerName,
      int partnerVersion,
      String currency,
      BigDecimal originalAmount,
      int paymentTermDays,
      String triggerPolicyCode,
      int triggerPolicyVersion,
      String triggerType,
      UUID sourceEventId,
      String sourceSnapshotHash,
      Instant acceptedAt,
      Instant capturedAt) {}

  record ReceivableRecord(
      UUID id,
      TenantId tenantId,
      String number,
      UUID orderId,
      String orderNumber,
      UUID partnerId,
      String partnerNumber,
      String partnerName,
      int partnerVersion,
      BigDecimal originalAmount,
      BigDecimal paidNetAmount,
      BigDecimal outstandingAmount,
      String currency,
      LocalDate dueDate,
      ReceivableStatus status,
      String triggerPolicyCode,
      int triggerPolicyVersion,
      String triggerType,
      UUID triggerId,
      UUID correlationId,
      UUID causationId,
      Instant createdAt,
      UUID createdBy,
      Instant updatedAt,
      UUID updatedBy,
      long version) {}

  record PaymentRecord(
      UUID id,
      TenantId tenantId,
      UUID receivableId,
      BigDecimal amount,
      String currency,
      PaymentMethod method,
      String externalReference,
      LocalDate occurredOn,
      String note,
      UUID actorId,
      String idempotencyKeyHash,
      String requestHash,
      UUID correlationId,
      Instant recordedAt) {}

  record ReversalRecord(
      UUID id,
      TenantId tenantId,
      UUID receivableId,
      UUID paymentId,
      BigDecimal amount,
      String currency,
      String reason,
      UUID actorId,
      String idempotencyKeyHash,
      String requestHash,
      UUID correlationId,
      Instant reversedAt) {}

  record HistoryRecord(
      UUID id,
      TenantId tenantId,
      UUID receivableId,
      String action,
      ReceivableStatus previousStatus,
      ReceivableStatus newStatus,
      BigDecimal amount,
      String currency,
      UUID actorId,
      String safeReason,
      UUID sourceEventId,
      Instant occurredAt) {}

  record CursorPosition(Instant createdAt, UUID id) {}

  record ReceivablePage(
      List<ReceivableRecord> items, CursorPosition nextPosition, boolean hasNext) {
    public ReceivablePage {
      items = List.copyOf(items);
    }
  }
}
