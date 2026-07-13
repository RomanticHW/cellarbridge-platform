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
      String tokenHash);

  @GlobalRegistryAccess
  Optional<QuotationAggregate> findByPortalTokenHash(String tokenHash);

  record RevisionHistory(
      int revision,
      QuotationStatus status,
      Instant createdAt,
      Instant frozenAt,
      String selectedRouteCode,
      String total,
      String currency) {}
}
