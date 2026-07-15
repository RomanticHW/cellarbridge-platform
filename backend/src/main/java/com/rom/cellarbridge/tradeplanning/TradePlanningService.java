package com.rom.cellarbridge.tradeplanning;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public interface TradePlanningService {

  RouteEvaluation evaluate(TenantId tenantId, EvaluationCommand command);

  RouteEvaluation get(TenantId tenantId, UUID evaluationId);

  record EvaluationCommand(
      UUID partnerId,
      String currency,
      String destinationCountryCode,
      LocalDate requestedDeliveryDate,
      int paymentTermDays,
      List<LineDemand> lines,
      TradeRouteCode requestedRouteCode,
      String overrideReason,
      UUID actorId,
      boolean managerOverrideAllowed) {

    public EvaluationCommand {
      lines = List.copyOf(lines);
    }
  }

  record LineDemand(
      UUID quotationLineId,
      UUID skuId,
      BigDecimal requestedQuantity,
      TradePlanningQuantityUnit quantityUnit,
      BigDecimal moqCaseEquivalentQuantity,
      UUID preferredSupplyPoolId) {

    public LineDemand {
      quotationLineId = Objects.requireNonNull(quotationLineId, "quotationLineId");
      skuId = Objects.requireNonNull(skuId, "skuId");
      requestedQuantity = persistenceQuantity(requestedQuantity, "requestedQuantity", false);
      quantityUnit = Objects.requireNonNull(quantityUnit, "quantityUnit");
      moqCaseEquivalentQuantity =
          persistenceQuantity(moqCaseEquivalentQuantity, "moqCaseEquivalentQuantity", false);
    }

    private static BigDecimal persistenceQuantity(
        BigDecimal value, String field, boolean allowZero) {
      BigDecimal normalized =
          Objects.requireNonNull(value, field).setScale(6, RoundingMode.UNNECESSARY);
      if ((allowZero ? normalized.signum() < 0 : normalized.signum() <= 0)
          || normalized.precision() - normalized.scale() > 13) {
        throw new IllegalArgumentException(field + " is outside numeric(19,6)");
      }
      return normalized;
    }
  }

  record RouteEvaluation(
      UUID evaluationId,
      String policyVersion,
      Instant evaluatedAt,
      String inputHash,
      List<RouteCandidate> candidates,
      TradeRouteCode recommendedRouteCode,
      TradeRouteCode selectedRouteCode,
      RouteOverride override,
      SupplyDecisionSnapshot supplyDecision) {

    private static final Pattern HASH_FORMAT = Pattern.compile("^[0-9a-f]{64}$");

    public RouteEvaluation {
      evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
      if (policyVersion == null || policyVersion.isBlank()) {
        throw new IllegalArgumentException("policyVersion must not be blank");
      }
      evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
      if (!evaluatedAt.equals(evaluatedAt.truncatedTo(ChronoUnit.MICROS))) {
        throw new IllegalArgumentException("evaluatedAt must use microsecond precision");
      }
      if (inputHash == null || !HASH_FORMAT.matcher(inputHash).matches()) {
        throw new IllegalArgumentException("inputHash must be lowercase 64-character hex");
      }
      candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
      if (selectedRouteCode != null
          && candidates.stream()
              .noneMatch(
                  candidate ->
                      candidate.routeCode() == selectedRouteCode
                          && candidate.eligibility() == Eligibility.ELIGIBLE)) {
        throw new IllegalArgumentException("selectedRouteCode must identify an eligible candidate");
      }
      if ("ROUTE-2026-03".equals(policyVersion) && supplyDecision == null) {
        throw new IllegalArgumentException("ROUTE-2026-03 requires a supply decision");
      }
      if (supplyDecision != null
          && (!supplyDecision.sourceRouteEvaluationId().equals(evaluationId)
              || !supplyDecision.sourceRouteInputHash().equals(inputHash)
              || supplyDecision.selectedRouteCode() != selectedRouteCode
              || !supplyDecision.decidedAt().equals(evaluatedAt)
              || !supplyDecision.policyVersion().equals(SupplyDecisionSnapshot.POLICY_VERSION))) {
        throw new IllegalArgumentException("Supply decision does not match route evaluation root");
      }
    }

    public RouteEvaluation(
        UUID evaluationId,
        String policyVersion,
        Instant evaluatedAt,
        String inputHash,
        List<RouteCandidate> candidates,
        TradeRouteCode recommendedRouteCode,
        TradeRouteCode selectedRouteCode,
        RouteOverride override) {
      this(
          evaluationId,
          policyVersion,
          evaluatedAt,
          inputHash,
          candidates,
          recommendedRouteCode,
          selectedRouteCode,
          override,
          null);
    }
  }

  record RouteCandidate(
      TradeRouteCode routeCode,
      Eligibility eligibility,
      RouteScore score,
      LocalDate estimatedDeliveryDate,
      BigDecimal estimatedCharges,
      String currency,
      List<RouteRejection> rejections) {

    public RouteCandidate {
      rejections = List.copyOf(rejections);
    }
  }

  enum Eligibility {
    ELIGIBLE,
    REJECTED
  }

  record RouteScore(
      BigDecimal cost,
      BigDecimal leadTime,
      BigDecimal supplyConfidence,
      BigDecimal operationalSimplicity,
      BigDecimal total) {}

  record RouteRejection(
      String ruleId, String code, String message, Map<String, String> parameters) {
    public RouteRejection {
      parameters = Map.copyOf(parameters);
    }
  }

  record RouteOverride(
      String reason, UUID actorId, Instant occurredAt, TradeRouteCode originalRecommendation) {}
}
