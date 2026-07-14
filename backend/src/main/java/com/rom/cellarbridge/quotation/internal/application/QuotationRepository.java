package com.rom.cellarbridge.quotation.internal.application;

import com.rom.cellarbridge.identityaccess.GlobalRegistryAccess;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate;
import com.rom.cellarbridge.quotation.internal.domain.QuotationApprovalPolicy.Requirement;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PriceReference;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface QuotationRepository {

  String nextNumber(TenantId tenantId, Instant now);

  Map<UUID, PriceReference> currentPrices(
      TenantId tenantId, Set<UUID> skuIds, String currency, Instant now);

  void insert(TenantId tenantId, QuotationAggregate quotation, UUID actorId);

  Optional<QuotationAggregate> find(TenantId tenantId, UUID quotationId);

  List<QuotationAggregate> list(
      TenantId tenantId, Set<QuotationStatus> statuses, UUID ownerId, UUID partnerId, int limit);

  void saveDraft(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      long expectedVersion,
      UUID actorId);

  void saveRoute(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      long expectedVersion,
      UUID actorId);

  void saveSubmission(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      List<Requirement> requirements,
      long expectedVersion,
      UUID actorId);

  boolean saveDecision(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      QuotationAggregate.ApprovalDecision decision,
      long expectedVersion,
      UUID actorId);

  void saveIssue(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      long expectedVersion,
      UUID actorId,
      UUID accessId,
      String tokenHash,
      String supplierPublicId,
      String supplierDisplayName,
      String termsVersion,
      Instant portalExpiresAt);

  @GlobalRegistryAccess
  Optional<PortalContext> findPortalContext(String tokenHash, Instant now, boolean forUpdate);

  Optional<IdempotencyRecord> findIdempotency(
      TenantId tenantId, UUID partnerId, CustomerOperation operation, String keyHash);

  void lockIdempotencyKey(
      TenantId tenantId, UUID partnerId, CustomerOperation operation, String keyHash);

  void saveCustomerDecision(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      CustomerDecision decision,
      IdempotencyWrite idempotency,
      UUID actorId);

  void saveIdempotencyResult(
      TenantId tenantId,
      PortalContext context,
      CustomerDecision decision,
      IdempotencyWrite idempotency);

  @GlobalRegistryAccess
  List<ExpirationWorkItem> claimExpired(
      Instant now, UUID claimOwner, Instant claimUntil, int batchSize);

  Optional<QuotationAggregate> findForUpdate(TenantId tenantId, UUID quotationId);

  void saveExpiration(
      TenantId tenantId, QuotationAggregate before, QuotationAggregate after, UUID systemActorId);

  void completeExpiration(
      TenantId tenantId, ExpirationWorkItem workItem, Instant now, UUID systemActorId);

  Optional<OrderLink> findOrderLink(TenantId tenantId, UUID quotationId);

  Optional<AcceptedOrderSource> findAcceptedOrderSource(TenantId tenantId, UUID quotationId);

  void saveOrderConversion(
      TenantId tenantId,
      QuotationAggregate before,
      QuotationAggregate after,
      OrderLink orderLink,
      UUID systemActorId);

  record RevisionHistory(
      int revision,
      QuotationStatus status,
      Instant createdAt,
      Instant frozenAt,
      String selectedRouteCode,
      String total,
      String currency) {}

  enum CustomerOperation {
    ACCEPT_QUOTATION,
    REJECT_QUOTATION
  }

  enum CustomerDecisionType {
    ACCEPTED,
    REJECTED
  }

  record CustomerDecision(
      UUID id,
      TenantId tenantId,
      UUID quotationId,
      UUID revisionId,
      UUID portalAccessId,
      UUID partnerId,
      CustomerDecisionType decision,
      String acceptedTermsVersion,
      String buyerReference,
      String reasonCategory,
      String commercialSnapshot,
      String snapshotHash,
      String idempotencyDigest,
      UUID acceptedEventId,
      Instant decidedAt) {}

  record PortalContext(
      UUID accessId,
      TenantId tenantId,
      UUID partnerId,
      UUID revisionId,
      String supplierPublicId,
      String supplierDisplayName,
      String customerPublicId,
      String termsVersion,
      Set<String> allowedActions,
      Instant accessExpiresAt,
      QuotationAggregate quotation,
      CustomerDecision customerDecision) {

    public PortalContext {
      allowedActions = Set.copyOf(allowedActions);
    }
  }

  record IdempotencyRecord(String requestHash, UUID decisionId) {}

  record IdempotencyWrite(
      UUID id,
      CustomerOperation operation,
      String keyHash,
      String requestHash,
      int responseStatus,
      Instant expiresAt,
      Instant createdAt) {}

  record ExpirationWorkItem(
      UUID id, TenantId tenantId, UUID quotationId, UUID revisionId, Instant dueAt) {}

  record OrderLink(
      UUID quotationId,
      UUID revisionId,
      UUID acceptanceId,
      UUID orderId,
      String orderNumber,
      String snapshotHash,
      UUID sourceEventId,
      Instant convertedAt) {}

  record AcceptedOrderSource(UUID acceptanceId, UUID revisionId, String snapshotHash) {}
}
