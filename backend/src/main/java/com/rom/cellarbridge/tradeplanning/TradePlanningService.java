package com.rom.cellarbridge.tradeplanning;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

  record LineDemand(UUID skuId, BigDecimal quantity, UUID preferredSupplyPoolId) {}

  record RouteEvaluation(
      UUID evaluationId,
      String policyVersion,
      Instant evaluatedAt,
      String inputHash,
      List<RouteCandidate> candidates,
      TradeRouteCode recommendedRouteCode,
      TradeRouteCode selectedRouteCode,
      RouteOverride override) {

    public RouteEvaluation {
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
