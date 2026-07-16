package com.rom.cellarbridge.quotation.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.QuotationSupplyDecisionStatus;
import com.rom.cellarbridge.quotation.internal.domain.QuotationApprovalPolicy.Requirement;
import com.rom.cellarbridge.quotation.internal.domain.QuotationDomainException.FailureKind;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricingResult;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot.LineDecision;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record QuotationAggregate(
    UUID id,
    TenantId tenantId,
    String number,
    UUID partnerId,
    QuotationStatus status,
    int currentRevision,
    UUID ownerId,
    UUID submittedById,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Revision revision,
    List<ApprovalDecision> approvals,
    List<TimelineEntry> timeline) {

  public QuotationAggregate {
    approvals = List.copyOf(approvals);
    timeline = List.copyOf(timeline);
  }

  public static QuotationAggregate draft(
      UUID id,
      TenantId tenantId,
      String number,
      UUID partnerId,
      UUID ownerId,
      PartnerSnapshot partnerSnapshot,
      DraftTerms terms,
      PricingResult pricing,
      Instant now) {
    Revision revision = Revision.draft(UUID.randomUUID(), 1, partnerSnapshot, terms, pricing, now);
    return new QuotationAggregate(
        id,
        tenantId,
        number,
        partnerId,
        QuotationStatus.DRAFT,
        1,
        ownerId,
        null,
        0,
        now,
        now,
        revision,
        List.of(),
        List.of());
  }

  public QuotationAggregate replaceDraft(
      PartnerSnapshot partnerSnapshot, DraftTerms terms, PricingResult pricing, Instant now) {
    requireEditable();
    boolean newRevision = status == QuotationStatus.CHANGES_REQUESTED;
    int revisionNumber = newRevision ? currentRevision + 1 : currentRevision;
    UUID revisionId = newRevision ? UUID.randomUUID() : revision.id();
    Revision replacement =
        Revision.draft(revisionId, revisionNumber, partnerSnapshot, terms, pricing, now);
    return new QuotationAggregate(
        id,
        tenantId,
        number,
        partnerId,
        QuotationStatus.DRAFT,
        revisionNumber,
        ownerId,
        null,
        version + 1,
        createdAt,
        now,
        replacement,
        newRevision ? approvals : List.of(),
        timeline);
  }

  public QuotationAggregate applyRoute(
      RouteEvaluation evaluation, PricingResult pricing, Instant now) {
    if (status != QuotationStatus.DRAFT) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "INVALID_STATE_TRANSITION",
          "Create a new draft revision before applying a route");
    }
    Revision routed = revision.withRoute(evaluation, pricing);
    return with(QuotationStatus.DRAFT, null, version + 1, now, routed, approvals);
  }

  public Submission submit(List<Requirement> requirements, UUID actorId, Instant now) {
    if (status != QuotationStatus.DRAFT && status != QuotationStatus.CHANGES_REQUESTED) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "INVALID_STATE_TRANSITION",
          "The current quotation revision is immutable");
    }
    requireFrozenSupplyDecision(FailureKind.BUSINESS_RULE);
    if (revision.routeEvaluationId() == null || revision.selectedRouteCode() == null) {
      throw problem(
          FailureKind.BUSINESS_RULE,
          "QUOTE_HAS_NO_ELIGIBLE_ROUTE",
          "Evaluate and select an eligible route before submission");
    }
    if (!now.isBefore(revision.terms().expiresAt())) {
      throw problem(FailureKind.STATE_CONFLICT, "QUOTE_EXPIRED", "Quotation has already expired");
    }
    QuotationStatus next =
        requirements.isEmpty() ? QuotationStatus.APPROVED : QuotationStatus.PENDING_APPROVAL;
    Revision frozen = revision.freeze(requirements, now);
    return new Submission(with(next, actorId, version + 1, now, frozen, approvals), requirements);
  }

  public QuotationAggregate decide(Decision decision, UUID reviewerId, String reason, Instant now) {
    if (status != QuotationStatus.PENDING_APPROVAL) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "INVALID_STATE_TRANSITION",
          "Quotation is not awaiting approval");
    }
    if (Objects.equals(submittedById, reviewerId)) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "QUOTE_REVIEWER_CONFLICT",
          "The quotation submitter cannot approve their own revision");
    }
    if (reason == null || reason.strip().length() < 5) {
      throw problem(FailureKind.VALIDATION, "VALIDATION_FAILED", "Decision reason is required");
    }
    QuotationStatus next =
        switch (decision) {
          case APPROVE -> QuotationStatus.APPROVED;
          case REQUEST_CHANGES -> QuotationStatus.CHANGES_REQUESTED;
          case REJECT -> QuotationStatus.REJECTED;
        };
    ApprovalDecision recorded =
        new ApprovalDecision(
            UUID.randomUUID(), revision.id(), decision, reviewerId, reason.strip(), now);
    List<ApprovalDecision> decisions =
        java.util.stream.Stream.concat(approvals.stream(), java.util.stream.Stream.of(recorded))
            .toList();
    return with(next, submittedById, version + 1, now, revision, decisions);
  }

  public QuotationAggregate issue(Instant now) {
    if (status != QuotationStatus.APPROVED) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "QUOTE_NOT_ISSUABLE",
          "Only an approved quotation can be issued");
    }
    requireFrozenSupplyDecision(FailureKind.BUSINESS_RULE);
    if (!now.isBefore(revision.terms().expiresAt())) {
      throw problem(FailureKind.STATE_CONFLICT, "QUOTE_EXPIRED", "Quotation has expired");
    }
    return with(QuotationStatus.SENT, submittedById, version + 1, now, revision, approvals);
  }

  public QuotationAggregate accept(UUID boundRevisionId, Instant now) {
    requireCurrentPortalRevision(boundRevisionId);
    requireFrozenSupplyDecision(FailureKind.STATE_CONFLICT);
    requireOpenCustomerDecision(now);
    return with(QuotationStatus.ACCEPTED, submittedById, version + 1, now, revision, approvals);
  }

  public QuotationAggregate convert(UUID boundRevisionId, Instant now) {
    requireCurrentPortalRevision(boundRevisionId);
    if (status == QuotationStatus.CONVERTED) {
      return this;
    }
    if (status != QuotationStatus.ACCEPTED) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "INVALID_STATE_TRANSITION",
          "Only an accepted quotation can be converted to an order");
    }
    return with(QuotationStatus.CONVERTED, submittedById, version + 1, now, revision, approvals);
  }

  public QuotationAggregate reject(UUID boundRevisionId, Instant now) {
    requireCurrentPortalRevision(boundRevisionId);
    requireFrozenSupplyDecision(FailureKind.STATE_CONFLICT);
    requireOpenCustomerDecision(now);
    return with(
        QuotationStatus.REJECTED_BY_CUSTOMER, submittedById, version + 1, now, revision, approvals);
  }

  public QuotationAggregate expire(Instant now) {
    if (status != QuotationStatus.SENT) {
      throw problem(
          FailureKind.STATE_CONFLICT, "QUOTE_NOT_ACCEPTABLE", "Only a sent quotation can expire");
    }
    if (now.isBefore(revision.terms().expiresAt())) {
      throw problem(FailureKind.STATE_CONFLICT, "QUOTE_NOT_EXPIRED", "Quotation is still valid");
    }
    return with(QuotationStatus.EXPIRED, submittedById, version + 1, now, revision, approvals);
  }

  public void requireOwnerOrManager(UUID actorId, boolean manager) {
    if (!ownerId.equals(actorId) && !manager) {
      throw problem(
          FailureKind.ACCESS_DENIED, "ACCESS_DENIED", "Quotation belongs to another owner");
    }
  }

  private void requireEditable() {
    if ((status != QuotationStatus.DRAFT && status != QuotationStatus.CHANGES_REQUESTED)
        || revision.supplyDecisionStatus()
            == QuotationSupplyDecisionStatus.LEGACY_REEVALUATION_REQUIRED) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "INVALID_STATE_TRANSITION",
          "The current quotation revision is immutable");
    }
  }

  private void requireFrozenSupplyDecision(FailureKind failureKind) {
    if (revision.supplyDecisionStatus() != QuotationSupplyDecisionStatus.FROZEN
        || revision.supplyDecision() == null) {
      throw problem(
          failureKind,
          "QUOTE_SUPPLY_DECISION_REQUIRED",
          "A complete frozen supply decision is required for this quotation action");
    }
  }

  private void requireCurrentPortalRevision(UUID boundRevisionId) {
    if (!revision.id().equals(boundRevisionId)) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "QUOTE_NOT_ACCEPTABLE",
          "The customer link is not bound to the current quotation revision");
    }
  }

  private void requireOpenCustomerDecision(Instant now) {
    if (status == QuotationStatus.WITHDRAWN) {
      throw problem(FailureKind.STATE_CONFLICT, "QUOTE_WITHDRAWN", "Quotation has been withdrawn");
    }
    if (status == QuotationStatus.ACCEPTED || status == QuotationStatus.REJECTED_BY_CUSTOMER) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "QUOTE_ALREADY_DECIDED",
          "Quotation already has a customer decision");
    }
    if (status == QuotationStatus.EXPIRED || !now.isBefore(revision.terms().expiresAt())) {
      throw problem(FailureKind.STATE_CONFLICT, "QUOTE_EXPIRED", "Quotation has expired");
    }
    if (status != QuotationStatus.SENT) {
      throw problem(
          FailureKind.STATE_CONFLICT,
          "QUOTE_NOT_ACCEPTABLE",
          "Quotation is not open for a customer decision");
    }
  }

  private QuotationAggregate with(
      QuotationStatus nextStatus,
      UUID nextSubmittedBy,
      long nextVersion,
      Instant now,
      Revision nextRevision,
      List<ApprovalDecision> nextApprovals) {
    return new QuotationAggregate(
        id,
        tenantId,
        number,
        partnerId,
        nextStatus,
        nextRevision.number(),
        ownerId,
        nextSubmittedBy,
        nextVersion,
        createdAt,
        now,
        nextRevision,
        nextApprovals,
        timeline);
  }

  private QuotationDomainException problem(FailureKind kind, String code, String message) {
    return new QuotationDomainException(kind, code, message, status.name(), java.util.Map.of());
  }

  public enum Decision {
    APPROVE,
    REQUEST_CHANGES,
    REJECT
  }

  public record PartnerSnapshot(
      UUID partnerId,
      String number,
      String displayName,
      int paymentTermDays,
      int sourceVersion,
      Instant capturedAt) {}

  public record DraftTerms(
      String currency,
      LocalDate requestedDeliveryDate,
      Instant expiresAt,
      int paymentTermDays,
      Address deliveryAddress) {}

  public record Address(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {}

  public record Revision(
      UUID id,
      int number,
      PartnerSnapshot partnerSnapshot,
      DraftTerms terms,
      PricingResult pricing,
      UUID routeEvaluationId,
      String routePolicyVersion,
      TradeRouteCode recommendedRouteCode,
      TradeRouteCode selectedRouteCode,
      String routeOverrideReason,
      QuotationSupplyDecisionStatus supplyDecisionStatus,
      SupplyDecisionSnapshot supplyDecision,
      String pricePolicyVersion,
      String approvalPolicyVersion,
      List<Requirement> approvalRequirements,
      Instant frozenAt,
      Instant createdAt) {

    public Revision {
      approvalRequirements = List.copyOf(approvalRequirements);
      supplyDecisionStatus = Objects.requireNonNull(supplyDecisionStatus, "supplyDecisionStatus");
      switch (supplyDecisionStatus) {
        case UNDECIDED -> {
          if (routeEvaluationId != null || supplyDecision != null) {
            throw conflict("UNDECIDED revisions must not contain route decision evidence");
          }
        }
        case LEGACY_REEVALUATION_REQUIRED -> {
          if (routeEvaluationId == null || supplyDecision != null) {
            throw conflict("Legacy revisions require route evidence without a supply decision");
          }
        }
        case FROZEN ->
            requireFrozenDecision(
                routeEvaluationId, routePolicyVersion, selectedRouteCode, pricing, supplyDecision);
      }
    }

    static Revision draft(
        UUID id,
        int number,
        PartnerSnapshot partnerSnapshot,
        DraftTerms terms,
        PricingResult pricing,
        Instant now) {
      return new Revision(
          id,
          number,
          partnerSnapshot,
          terms,
          pricing,
          null,
          null,
          null,
          null,
          null,
          QuotationSupplyDecisionStatus.UNDECIDED,
          null,
          QuotationPricingPolicy.VERSION,
          QuotationApprovalPolicy.VERSION,
          List.of(),
          null,
          now);
    }

    Revision withRoute(RouteEvaluation evaluation, PricingResult routedPricing) {
      SupplyDecisionSnapshot decision = evaluation.supplyDecision();
      if (decision == null) {
        throw conflict("Planning Evaluation does not contain a supply decision");
      }
      PricingResult decidedPricing = applyDecisionsToLines(routedPricing, decision);
      return new Revision(
          id,
          number,
          partnerSnapshot,
          terms,
          decidedPricing,
          evaluation.evaluationId(),
          evaluation.policyVersion(),
          evaluation.recommendedRouteCode(),
          evaluation.selectedRouteCode(),
          evaluation.override() == null ? null : evaluation.override().reason(),
          QuotationSupplyDecisionStatus.FROZEN,
          decision,
          pricePolicyVersion,
          approvalPolicyVersion,
          List.of(),
          null,
          createdAt);
    }

    Revision freeze(List<Requirement> requirements, Instant now) {
      return new Revision(
          id,
          number,
          partnerSnapshot,
          terms,
          pricing,
          routeEvaluationId,
          routePolicyVersion,
          recommendedRouteCode,
          selectedRouteCode,
          routeOverrideReason,
          supplyDecisionStatus,
          supplyDecision,
          pricePolicyVersion,
          approvalPolicyVersion,
          requirements,
          now,
          createdAt);
    }

    private static PricingResult applyDecisionsToLines(
        PricingResult pricing, SupplyDecisionSnapshot decision) {
      Map<UUID, LineDecision> byLine = decisionsByLine(pricing, decision);
      List<QuotationPricingPolicy.PricedLine> lines =
          pricing.lines().stream()
              .map(line -> applyDecisionToLine(line, byLine.get(line.lineId())))
              .toList();
      return new PricingResult(
          lines,
          pricing.subtotal(),
          pricing.total(),
          pricing.totalCost(),
          pricing.marginRate(),
          pricing.routeCharges());
    }

    private static QuotationPricingPolicy.PricedLine applyDecisionToLine(
        QuotationPricingPolicy.PricedLine undecidedLine, LineDecision decision) {
      verifyLineIdentity(undecidedLine, decision);
      return new QuotationPricingPolicy.PricedLine(
          undecidedLine.lineId(),
          undecidedLine.sku(),
          undecidedLine.quantity(),
          undecidedLine.unit(),
          decision.supplyPoolId(),
          decision.allocationMode(),
          decision.supplyType().name(),
          undecidedLine.listUnitPrice(),
          undecidedLine.discountRate(),
          undecidedLine.netUnitPrice(),
          undecidedLine.allocatedCharges(),
          undecidedLine.lineTotal(),
          undecidedLine.costUnitPrice(),
          undecidedLine.lineCost(),
          undecidedLine.marginRate(),
          undecidedLine.manualPrice(),
          undecidedLine.currency(),
          undecidedLine.priceSourceVersion());
    }

    private static void verifyFrozenLines(PricingResult pricing, SupplyDecisionSnapshot decision) {
      Map<UUID, LineDecision> byLine = decisionsByLine(pricing, decision);
      pricing.lines().forEach(line -> verifyFrozenLine(line, byLine.get(line.lineId())));
    }

    private static void verifyFrozenLine(
        QuotationPricingPolicy.PricedLine persistedLine, LineDecision decision) {
      verifyLineIdentity(persistedLine, decision);
      if (persistedLine.allocationMode() != decision.allocationMode()
          || !Objects.equals(persistedLine.supplyType(), decision.supplyType().name())) {
        throw conflict("Frozen quotation line supply evidence conflicts with its decision");
      }
    }

    private static void verifyLineIdentity(
        QuotationPricingPolicy.PricedLine line, LineDecision decision) {
      if (decision == null
          || !decision.skuId().equals(line.sku().skuId())
          || !decision
              .requestedQuantity()
              .equals(line.quantity().setScale(6, RoundingMode.UNNECESSARY))
          || decision.quantityUnit() != TradePlanningQuantityUnit.valueOf(line.unit().name())
          || !Objects.equals(decision.supplyPoolId(), line.preferredSupplyPoolId())) {
        throw conflict("Supply decision line evidence conflicts with quotation input");
      }
    }

    private static Map<UUID, LineDecision> decisionsByLine(
        PricingResult pricing, SupplyDecisionSnapshot decision) {
      Map<UUID, LineDecision> byLine = new java.util.HashMap<>();
      for (LineDecision line : decision.lineDecisions()) {
        if (byLine.put(line.quotationLineId(), line) != null) {
          throw conflict("Supply decision contains duplicate quotation line evidence");
        }
      }
      if (byLine.size() != pricing.lines().size()) {
        throw conflict("Supply decision line set does not match the quotation revision");
      }
      return Map.copyOf(byLine);
    }

    private static void requireFrozenDecision(
        UUID routeEvaluationId,
        String routePolicyVersion,
        TradeRouteCode selectedRouteCode,
        PricingResult pricing,
        SupplyDecisionSnapshot decision) {
      if (decision == null
          || routeEvaluationId == null
          || !routeEvaluationId.equals(decision.sourceRouteEvaluationId())
          || routePolicyVersion == null
          || selectedRouteCode == null
          || selectedRouteCode != decision.selectedRouteCode()
          || decision.schemaVersion() != SupplyDecisionSnapshot.SCHEMA_VERSION
          || !SupplyDecisionSnapshot.POLICY_VERSION.equals(decision.policyVersion())) {
        throw conflict("Frozen supply decision does not match the selected route evidence");
      }
      verifyFrozenLines(pricing, decision);
    }

    private static QuotationDomainException conflict(String message) {
      return new QuotationDomainException(
          FailureKind.STATE_CONFLICT, "QUOTE_SUPPLY_DECISION_CONFLICT", message);
    }
  }

  public record ApprovalDecision(
      UUID id,
      UUID revisionId,
      Decision decision,
      UUID reviewerId,
      String reason,
      Instant occurredAt) {}

  public record TimelineEntry(
      UUID id,
      Instant occurredAt,
      String action,
      String previousState,
      String newState,
      String safeReason) {}

  public record Submission(QuotationAggregate quotation, List<Requirement> requirements) {
    public Submission {
      requirements = List.copyOf(requirements);
    }
  }
}
