package com.rom.cellarbridge.tradeplanning.internal.domain;

import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteRejection;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteScore;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class RouteEvaluationPolicy {

  public static final String VERSION = "ROUTE-2026-03";
  public static final LocalDate EFFECTIVE_FROM = LocalDate.of(2026, 1, 1);
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final Map<TradeRouteCode, Definition> DEFINITIONS = definitions();

  private RouteEvaluationPolicy() {}

  public static EvaluationOutcome evaluate(Input input) {
    if (input.evaluationDate().isBefore(EFFECTIVE_FROM)) {
      throw new IllegalStateException("Route policy is not effective for the evaluation date");
    }
    List<Definition> definitions =
        DEFINITIONS.values().stream()
            .sorted(Comparator.comparingInt(Definition::priority))
            .toList();
    Map<TradeRouteCode, SupplyDecisionPolicy.Result> decisions =
        new EnumMap<>(TradeRouteCode.class);
    List<RouteCandidate> candidates =
        definitions.stream()
            .map(
                definition -> {
                  SupplyDecisionPolicy.Result decision =
                      SupplyDecisionPolicy.decide(
                          definition.code(), input.lines(), input.availability());
                  decisions.put(definition.code(), decision);
                  return candidate(definition, input, decision);
                })
            .toList();
    return new EvaluationOutcome(candidates, decisions);
  }

  public static TradeRouteCode recommend(List<RouteCandidate> candidates) {
    return candidates.stream()
        .filter(candidate -> candidate.eligibility() == Eligibility.ELIGIBLE)
        .sorted(
            Comparator.comparing((RouteCandidate candidate) -> candidate.score().total())
                .reversed()
                .thenComparingInt(candidate -> DEFINITIONS.get(candidate.routeCode()).priority())
                .thenComparing(candidate -> candidate.routeCode().name()))
        .map(RouteCandidate::routeCode)
        .findFirst()
        .orElse(null);
  }

  private static RouteCandidate candidate(
      Definition definition, Input input, SupplyDecisionPolicy.Result supplyDecision) {
    List<RouteRejection> rejections = new ArrayList<>();
    reject(
        rejections,
        !input.partnerRoutes().contains(definition.code()),
        "TRD-PARTNER-001",
        "PARTNER_NOT_ELIGIBLE",
        "Partner is not eligible for this route");
    reject(
        rejections,
        !definition.destinationCountries().contains(input.destinationCountryCode()),
        "TRD-DESTINATION-001",
        "DESTINATION_NOT_SUPPORTED",
        "Destination is not supported by this route");
    reject(
        rejections,
        !definition.currencies().contains(input.currency()),
        "TRD-CURRENCY-001",
        "CURRENCY_NOT_SUPPORTED",
        "Currency is not supported by this route");
    reject(
        rejections,
        input.paymentTermDays() > definition.maximumPaymentTermDays(),
        "TRD-PAYMENT-001",
        "PAYMENT_TERM_NOT_ALLOWED",
        "Payment term exceeds this route policy");
    BigDecimal totalMoqCaseEquivalentQuantity =
        input.lines().stream()
            .map(SupplyDecisionPolicy.LineInput::moqCaseEquivalentQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    reject(
        rejections,
        totalMoqCaseEquivalentQuantity.compareTo(definition.minimumQuantity()) < 0,
        "TRD-MOQ-001",
        "MOQ_NOT_MET",
        "Minimum route quantity is not met");
    LocalDate estimatedDeliveryDate = input.evaluationDate().plusDays(definition.leadDays());
    reject(
        rejections,
        estimatedDeliveryDate.isAfter(input.requestedDeliveryDate()),
        "TRD-DELIVERY-001",
        "DELIVERY_DATE_UNACHIEVABLE",
        "Requested delivery date is earlier than the route estimate");
    rejections.addAll(supplyRejections(supplyDecision));

    if (!rejections.isEmpty()) {
      return new RouteCandidate(
          definition.code(), Eligibility.REJECTED, null, null, null, input.currency(), rejections);
    }
    BigDecimal confidence = confidence(supplyDecision.minimumConfidence());
    RouteScore score =
        new RouteScore(
            definition.costScore(),
            definition.leadTimeScore(),
            confidence,
            definition.simplicityScore(),
            weighted(
                definition.costScore(),
                definition.leadTimeScore(),
                confidence,
                definition.simplicityScore()));
    BigDecimal charges =
        totalMoqCaseEquivalentQuantity
            .multiply(definition.chargePerUnit())
            .setScale(4, RoundingMode.HALF_UP);
    return new RouteCandidate(
        definition.code(),
        Eligibility.ELIGIBLE,
        score,
        estimatedDeliveryDate,
        charges,
        input.currency(),
        List.of());
  }

  private static BigDecimal confidence(SupplyDecisionPolicy.Confidence confidence) {
    int value =
        switch (confidence) {
          case HIGH -> 95;
          case MEDIUM -> 75;
          case LOW -> 55;
        };
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.UNNECESSARY);
  }

  private static List<RouteRejection> supplyRejections(SupplyDecisionPolicy.Result supplyDecision) {
    return supplyDecision.failures().stream()
        .collect(Collectors.groupingBy(SupplyDecisionPolicy.Failure::code))
        .entrySet()
        .stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry -> {
              boolean fixed =
                  SupplyDecisionPolicy.FIXED_POOL_INELIGIBLE_CODE.equals(entry.getKey());
              String lineIds =
                  entry.getValue().stream()
                      .map(SupplyDecisionPolicy.Failure::quotationLineId)
                      .sorted()
                      .map(Object::toString)
                      .collect(Collectors.joining(","));
              return new RouteRejection(
                  fixed ? "TRD-SUPPLY-FIXED-001" : "TRD-SUPPLY-001",
                  entry.getKey(),
                  fixed
                      ? "Fixed supply pool cannot cover the quotation lines"
                      : "Current supply cannot cover the quotation lines",
                  Map.of("quotationLineIds", lineIds));
            })
        .toList();
  }

  private static BigDecimal weighted(
      BigDecimal cost, BigDecimal lead, BigDecimal confidence, BigDecimal simplicity) {
    return cost.multiply(new BigDecimal("0.40"))
        .add(lead.multiply(new BigDecimal("0.30")))
        .add(confidence.multiply(new BigDecimal("0.20")))
        .add(simplicity.multiply(new BigDecimal("0.10")))
        .min(HUNDRED)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static void reject(
      List<RouteRejection> rejections,
      boolean condition,
      String ruleId,
      String code,
      String message) {
    if (condition) {
      rejections.add(new RouteRejection(ruleId, code, message, Map.of()));
    }
  }

  private static Map<TradeRouteCode, Definition> definitions() {
    Map<TradeRouteCode, Definition> values = new EnumMap<>(TradeRouteCode.class);
    values.put(
        TradeRouteCode.SH_GENERAL_TRADE,
        new Definition(
            TradeRouteCode.SH_GENERAL_TRADE,
            1,
            Set.of("CN"),
            Set.of("CNY"),
            60,
            BigDecimal.ONE,
            5,
            new BigDecimal("72"),
            new BigDecimal("92"),
            new BigDecimal("92"),
            new BigDecimal("15.0000")));
    values.put(
        TradeRouteCode.NB_BONDED_B2B,
        new Definition(
            TradeRouteCode.NB_BONDED_B2B,
            2,
            Set.of("CN"),
            Set.of("CNY"),
            45,
            new BigDecimal("6"),
            8,
            new BigDecimal("90"),
            new BigDecimal("74"),
            new BigDecimal("72"),
            new BigDecimal("10.0000")));
    values.put(
        TradeRouteCode.HK_FREE_TRADE,
        new Definition(
            TradeRouteCode.HK_FREE_TRADE,
            3,
            Set.of("CN", "HK"),
            Set.of("CNY", "HKD"),
            30,
            new BigDecimal("3"),
            7,
            new BigDecimal("78"),
            new BigDecimal("82"),
            new BigDecimal("76"),
            new BigDecimal("12.0000")));
    return Map.copyOf(values);
  }

  public record Input(
      Set<TradeRouteCode> partnerRoutes,
      String currency,
      String destinationCountryCode,
      LocalDate requestedDeliveryDate,
      int paymentTermDays,
      LocalDate evaluationDate,
      List<SupplyDecisionPolicy.LineInput> lines,
      List<SupplyDecisionPolicy.AvailabilityInput> availability) {

    public Input {
      partnerRoutes = Set.copyOf(partnerRoutes);
      lines = List.copyOf(lines);
      availability = List.copyOf(availability);
    }
  }

  public record EvaluationOutcome(
      List<RouteCandidate> candidates,
      Map<TradeRouteCode, SupplyDecisionPolicy.Result> supplyDecisions) {

    public EvaluationOutcome {
      candidates = List.copyOf(candidates);
      supplyDecisions = Map.copyOf(supplyDecisions);
      if (!supplyDecisions.keySet().equals(DEFINITIONS.keySet())) {
        throw new IllegalArgumentException("Every route definition requires a supply decision");
      }
    }
  }

  private record Definition(
      TradeRouteCode code,
      int priority,
      Set<String> destinationCountries,
      Set<String> currencies,
      int maximumPaymentTermDays,
      BigDecimal minimumQuantity,
      int leadDays,
      BigDecimal costScore,
      BigDecimal leadTimeScore,
      BigDecimal simplicityScore,
      BigDecimal chargePerUnit) {}
}
