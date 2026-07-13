package com.rom.cellarbridge.partner.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.partner.PartnerStatus;
import com.rom.cellarbridge.partner.internal.domain.Partner;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PartnerRepository {

  String nextNumber(TenantId tenantId, Instant now);

  Partner insert(TenantId tenantId, Partner partner, UUID actorId);

  Optional<Partner> find(TenantId tenantId, UUID partnerId);

  Optional<Partner> update(TenantId tenantId, Partner partner, long expectedVersion, UUID actorId);

  boolean registrationIdentifierExists(
      TenantId tenantId, String normalizedIdentifier, UUID excludingPartnerId);

  boolean legalNameDuplicate(
      TenantId tenantId, String normalizedLegalName, UUID excludingPartnerId);

  PartnerPage list(TenantId tenantId, PartnerSearch search, CursorPosition cursor, int pageSize);

  int nextEligibilityVersion(TenantId tenantId, UUID partnerId);

  void insertEligibility(
      TenantId tenantId,
      UUID partnerId,
      int eligibilityVersion,
      Partner.Eligibility eligibility,
      UUID actorId);

  Optional<EligibilityRecord> latestEligibility(TenantId tenantId, UUID partnerId);

  void insertReviewDecision(
      TenantId tenantId,
      Partner before,
      Partner after,
      Partner.ReviewDecision decision,
      String reason,
      UUID reviewerId);

  void insertAudit(
      TenantId tenantId,
      Partner partner,
      UUID actorId,
      String action,
      PartnerStatus previousState,
      PartnerStatus newState,
      String safeReason,
      List<ChangedField> changedFields,
      Instant occurredAt);

  UUID insertPublication(
      TenantId tenantId,
      Partner partner,
      Partner.EventType eventType,
      UUID actorId,
      int eligibilityVersion,
      String safeReason,
      Instant occurredAt);

  void openReviewWorkItem(
      TenantId tenantId, Partner partner, UUID eventId, UUID actorId, Instant occurredAt);

  void completeReviewWorkItems(TenantId tenantId, UUID partnerId, UUID actorId, Instant occurredAt);

  List<TimelineEntry> timeline(TenantId tenantId, UUID partnerId);

  record PartnerSearch(
      String keyword,
      Set<PartnerStatus> statuses,
      UUID ownerId,
      String routeCode,
      Instant updatedFrom,
      Instant updatedTo,
      String filterHash) {

    public PartnerSearch {
      statuses = Set.copyOf(statuses);
    }
  }

  record CursorPosition(Instant updatedAt, String number, UUID id) {}

  record PartnerPage(List<Partner> items, boolean hasNext) {
    public PartnerPage {
      items = List.copyOf(items);
    }
  }

  record EligibilityRecord(int version, Partner.Eligibility eligibility) {}

  record ChangedField(String field, String previousSummary, String newSummary) {}

  record TimelineEntry(
      UUID id,
      Instant occurredAt,
      String action,
      String previousState,
      String newState,
      String safeReason,
      List<String> changedFields) {

    public TimelineEntry {
      changedFields = List.copyOf(changedFields);
    }
  }
}
