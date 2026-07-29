package com.rom.cellarbridge.quotation;

import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Caller-scoped, source-owned facts for quotation and route investigation. */
public interface QuotationInvestigationQuery {

  Optional<QuotationFact> find(UUID quotationId, UUID revisionId);

  record QuotationFact(
      UUID id,
      String number,
      UUID revisionId,
      int revisionNumber,
      QuotationStatus status,
      UntrustedBusinessData partnerDisplay,
      String currency,
      BigDecimal total,
      BigDecimal totalCost,
      BigDecimal marginRate,
      TradeRouteCode selectedRouteCode,
      QuotationSupplyDecisionStatus supplyDecisionStatus,
      RouteDecision routeDecision,
      ExactSupplyEvidence exactSupplyEvidence,
      long version,
      Instant updatedAt) {}

  record RouteDecision(
      UUID evaluationId,
      String policyVersion,
      Instant evaluatedAt,
      TradeRouteCode recommendedRouteCode,
      TradeRouteCode selectedRouteCode,
      List<RouteCandidate> candidates,
      RouteOverride routeOverride,
      SupplyDecisionEvidence supplyDecision) {
    public RouteDecision {
      candidates = List.copyOf(candidates);
    }
  }

  record RouteCandidate(
      TradeRouteCode routeCode,
      Eligibility eligibility,
      RouteScore score,
      LocalDate estimatedDeliveryDate,
      BigDecimal estimatedCharges,
      String currency,
      List<RouteRule> rejectionRules) {
    public RouteCandidate {
      rejectionRules = List.copyOf(rejectionRules);
    }
  }

  record RouteScore(
      BigDecimal cost,
      BigDecimal leadTime,
      BigDecimal supplyConfidence,
      BigDecimal operationalSimplicity,
      BigDecimal total) {}

  record RouteRule(String ruleId, String code) {}

  record RouteOverride(
      UntrustedBusinessData reason,
      UUID actorId,
      Instant occurredAt,
      TradeRouteCode originalRecommendation) {}

  record SupplyDecisionEvidence(
      String policyVersion, Instant decidedAt, Instant inventoryDataAsOf, String decisionHash) {}

  record ExactSupplyEvidence(List<ExactSupplyLine> lines) {
    public ExactSupplyEvidence {
      lines = List.copyOf(lines);
    }
  }

  record ExactSupplyLine(
      UUID quotationLineId,
      UUID skuId,
      BigDecimal requestedQuantity,
      TradePlanningQuantityUnit quantityUnit,
      SupplyAllocationMode allocationMode,
      UUID supplyPoolId,
      TradePlanningSupplyType supplyType) {}

  record UntrustedBusinessData(String classification, String value, boolean truncated) {}
}
