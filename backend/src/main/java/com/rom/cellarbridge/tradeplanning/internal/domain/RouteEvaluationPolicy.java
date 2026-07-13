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
import java.util.UUID;

public final class RouteEvaluationPolicy {

  public static final String VERSION = "ROUTE-2026-01";
  public static final LocalDate EFFECTIVE_FROM = LocalDate.of(2026, 1, 1);
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final Map<TradeRouteCode, Definition> DEFINITIONS = definitions();

  private RouteEvaluationPolicy() {}

  public static List<RouteCandidate> evaluate(Input input) {
    if (input.evaluationDate().isBefore(EFFECTIVE_FROM)) {
      throw new IllegalStateException("Route policy is not effective for the evaluation date");
    }
    return DEFINITIONS.values().stream()
        .sorted(Comparator.comparingInt(Definition::priority))
        .map(definition -> candidate(definition, input))
        .toList();
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

  private static RouteCandidate candidate(Definition definition, Input input) {
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
    BigDecimal totalQuantity =
        input.lines().stream().map(LineInput::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    reject(
        rejections,
        totalQuantity.compareTo(definition.minimumQuantity()) < 0,
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
    reject(
        rejections,
        !hasSupply(definition.code(), input.lines(), input.availability()),
        "TRD-SUPPLY-001",
        "NO_PROMISABLE_SUPPLY",
        "Current supply cannot cover every quotation line");

    if (!rejections.isEmpty()) {
      return new RouteCandidate(
          definition.code(), Eligibility.REJECTED, null, null, null, input.currency(), rejections);
    }
    BigDecimal confidence = confidence(definition.code(), input.lines(), input.availability());
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
        totalQuantity.multiply(definition.chargePerUnit()).setScale(4, RoundingMode.HALF_UP);
    return new RouteCandidate(
        definition.code(),
        Eligibility.ELIGIBLE,
        score,
        estimatedDeliveryDate,
        charges,
        input.currency(),
        List.of());
  }

  private static boolean hasSupply(
      TradeRouteCode route, List<LineInput> lines, List<AvailabilityInput> availability) {
    return lines.stream()
        .allMatch(
            line ->
                availability.stream()
                        .filter(
                            item -> item.routeCode() == route && item.skuId().equals(line.skuId()))
                        .filter(
                            item ->
                                line.preferredSupplyPoolId() == null
                                    || item.supplyPoolId().equals(line.preferredSupplyPoolId()))
                        .map(AvailabilityInput::availableQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(line.quantity())
                    >= 0);
  }

  private static BigDecimal confidence(
      TradeRouteCode route, List<LineInput> lines, List<AvailabilityInput> availability) {
    int value =
        availability.stream()
            .filter(item -> item.routeCode() == route)
            .filter(item -> lines.stream().anyMatch(line -> line.skuId().equals(item.skuId())))
            .mapToInt(
                item ->
                    switch (item.confidence()) {
                      case "HIGH" -> 95;
                      case "MEDIUM" -> 75;
                      default -> 55;
                    })
            .min()
            .orElse(0);
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.UNNECESSARY);
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
      List<LineInput> lines,
      List<AvailabilityInput> availability) {

    public Input {
      partnerRoutes = Set.copyOf(partnerRoutes);
      lines = List.copyOf(lines);
      availability = List.copyOf(availability);
    }
  }

  public record LineInput(UUID skuId, BigDecimal quantity, UUID preferredSupplyPoolId) {}

  public record AvailabilityInput(
      UUID supplyPoolId,
      UUID skuId,
      TradeRouteCode routeCode,
      BigDecimal availableQuantity,
      String confidence) {}

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
