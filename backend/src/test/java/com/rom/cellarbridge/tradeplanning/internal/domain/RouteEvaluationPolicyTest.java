package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.Input;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.AvailabilityInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.LineInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteEvaluationPolicyTest {

  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final UUID LINE = UUID.fromString("74000000-0000-4000-8000-000000000001");
  private static final UUID SKU_2 = UUID.fromString("34000000-0000-4000-8000-000000000002");
  private static final UUID LINE_2 = UUID.fromString("74000000-0000-4000-8000-000000000002");
  private static final UUID PRIMARY_POOL = UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final UUID SECONDARY_POOL =
      UUID.fromString("36000000-0000-4000-8000-000000000002");

  @Test
  void keepsRejectedCandidateAndStablePartnerReason() {
    List<RouteCandidate> candidates =
        RouteEvaluationPolicy.evaluate(input(Set.of(TradeRouteCode.SH_GENERAL_TRADE))).candidates();

    RouteCandidate bonded =
        candidates.stream()
            .filter(candidate -> candidate.routeCode() == TradeRouteCode.NB_BONDED_B2B)
            .findFirst()
            .orElseThrow();
    assertThat(bonded.eligibility()).isEqualTo(Eligibility.REJECTED);
    assertThat(bonded.rejections())
        .extracting(rejection -> rejection.code())
        .contains("PARTNER_NOT_ELIGIBLE");
    assertThat(candidates)
        .extracting(RouteCandidate::routeCode)
        .containsExactly(
            TradeRouteCode.SH_GENERAL_TRADE,
            TradeRouteCode.NB_BONDED_B2B,
            TradeRouteCode.HK_FREE_TRADE);
  }

  @Test
  void recommendationAndScoresAreDeterministicAcrossRepeatedEvaluation() {
    Input input = input(Set.of(TradeRouteCode.SH_GENERAL_TRADE, TradeRouteCode.NB_BONDED_B2B));
    List<RouteCandidate> first = RouteEvaluationPolicy.evaluate(input).candidates();

    for (int repeat = 0; repeat < 100; repeat++) {
      List<RouteCandidate> next = RouteEvaluationPolicy.evaluate(input).candidates();
      assertThat(next).isEqualTo(first);
      assertThat(RouteEvaluationPolicy.recommend(next)).isEqualTo(TradeRouteCode.SH_GENERAL_TRADE);
    }
    assertThat(first.getFirst().score().total()).isEqualByComparingTo("84.60");
  }

  @Test
  void doesNotCombineBottleAvailabilityWithCaseCoverage() {
    RouteCandidate shanghai =
        candidate(
            input(
                Set.of(TradeRouteCode.SH_GENERAL_TRADE),
                line("60", TradePlanningQuantityUnit.CASE, "60", null),
                List.of(
                    availability(
                        PRIMARY_POOL,
                        TradeRouteCode.SH_GENERAL_TRADE,
                        TradePlanningQuantityUnit.CASE,
                        "56",
                        "HIGH"),
                    availability(
                        PRIMARY_POOL,
                        TradeRouteCode.SH_GENERAL_TRADE,
                        TradePlanningQuantityUnit.BOTTLE,
                        "10",
                        "LOW"))),
            TradeRouteCode.SH_GENERAL_TRADE);

    assertThat(shanghai.eligibility()).isEqualTo(Eligibility.REJECTED);
    assertThat(shanghai.rejections())
        .extracting(rejection -> rejection.code())
        .contains("NO_PROMISABLE_SUPPLY");
  }

  @Test
  void acceptsExactCaseAndBottleCoverageWithoutConversion() {
    RouteCandidate cases =
        candidate(
            input(
                Set.of(TradeRouteCode.SH_GENERAL_TRADE),
                line("56", TradePlanningQuantityUnit.CASE, "56", null),
                List.of(
                    availability(
                        PRIMARY_POOL,
                        TradeRouteCode.SH_GENERAL_TRADE,
                        TradePlanningQuantityUnit.CASE,
                        "56",
                        "HIGH"))),
            TradeRouteCode.SH_GENERAL_TRADE);
    RouteCandidate bottles =
        candidate(
            input(
                Set.of(TradeRouteCode.SH_GENERAL_TRADE),
                line("10", TradePlanningQuantityUnit.BOTTLE, "1.666667", null),
                List.of(
                    availability(
                        PRIMARY_POOL,
                        TradeRouteCode.SH_GENERAL_TRADE,
                        TradePlanningQuantityUnit.BOTTLE,
                        "10",
                        "HIGH"))),
            TradeRouteCode.SH_GENERAL_TRADE);

    assertThat(cases.eligibility()).isEqualTo(Eligibility.ELIGIBLE);
    assertThat(bottles.eligibility()).isEqualTo(Eligibility.ELIGIBLE);
  }

  @Test
  void rejectsInsufficientOrWrongUnitCoverageInBothDirections() {
    assertNoPromisableSupply(
        line("11", TradePlanningQuantityUnit.BOTTLE, "1.833334", null),
        List.of(
            availability(
                PRIMARY_POOL,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningQuantityUnit.BOTTLE,
                "10",
                "HIGH")));
    assertNoPromisableSupply(
        line("1", TradePlanningQuantityUnit.BOTTLE, "1", null),
        List.of(
            availability(
                PRIMARY_POOL,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningQuantityUnit.CASE,
                "56",
                "HIGH")));
    assertNoPromisableSupply(
        line("1", TradePlanningQuantityUnit.CASE, "1", null),
        List.of(
            availability(
                PRIMARY_POOL,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningQuantityUnit.BOTTLE,
                "10",
                "HIGH")));
  }

  @Test
  void preferredPoolRequiresTheSamePoolAndUnit() {
    assertSupplyFailure(
        line("6", TradePlanningQuantityUnit.CASE, "6", PRIMARY_POOL),
        List.of(
            availability(
                SECONDARY_POOL,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningQuantityUnit.CASE,
                "56",
                "HIGH")),
        "QUOTE_FIXED_SUPPLY_POOL_INELIGIBLE");
    assertSupplyFailure(
        line("6", TradePlanningQuantityUnit.CASE, "6", PRIMARY_POOL),
        List.of(
            availability(
                PRIMARY_POOL,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningQuantityUnit.BOTTLE,
                "10",
                "HIGH")),
        "QUOTE_FIXED_SUPPLY_POOL_INELIGIBLE");
  }

  @Test
  void routeMoqUsesCaseEquivalentWhileCoverageUsesRequestedUnitQuantity() {
    LineInput bottleLine = line("10", TradePlanningQuantityUnit.BOTTLE, "1", null);
    List<AvailabilityInput> availability =
        List.of(
            availability(
                SECONDARY_POOL,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningQuantityUnit.BOTTLE,
                "10",
                "HIGH"));
    RouteCandidate belowMoq =
        candidate(
            input(Set.of(TradeRouteCode.NB_BONDED_B2B), bottleLine, availability),
            TradeRouteCode.NB_BONDED_B2B);
    RouteCandidate atMoq =
        candidate(
            input(
                Set.of(TradeRouteCode.NB_BONDED_B2B),
                line("10", TradePlanningQuantityUnit.BOTTLE, "6", null),
                availability),
            TradeRouteCode.NB_BONDED_B2B);

    assertThat(belowMoq.rejections())
        .extracting(rejection -> rejection.code())
        .contains("MOQ_NOT_MET")
        .doesNotContain("NO_PROMISABLE_SUPPLY");
    assertThat(atMoq.eligibility()).isEqualTo(Eligibility.ELIGIBLE);
  }

  @Test
  void confidenceIgnoresWrongUnitsAndNonPreferredPools() {
    RouteCandidate shanghai =
        candidate(
            input(
                Set.of(TradeRouteCode.SH_GENERAL_TRADE),
                line("6", TradePlanningQuantityUnit.CASE, "6", PRIMARY_POOL),
                List.of(
                    availability(
                        PRIMARY_POOL,
                        TradeRouteCode.SH_GENERAL_TRADE,
                        TradePlanningQuantityUnit.CASE,
                        "6",
                        "HIGH"),
                    availability(
                        PRIMARY_POOL,
                        TradeRouteCode.SH_GENERAL_TRADE,
                        TradePlanningQuantityUnit.BOTTLE,
                        "100",
                        "LOW"),
                    availability(
                        SECONDARY_POOL,
                        TradeRouteCode.SH_GENERAL_TRADE,
                        TradePlanningQuantityUnit.CASE,
                        "100",
                        "LOW"))),
            TradeRouteCode.SH_GENERAL_TRADE);

    assertThat(shanghai.score().supplyConfidence()).isEqualByComparingTo("95.00");
  }

  @Test
  void exposesCorrectedPolicyVersion() {
    assertThat(RouteEvaluationPolicy.VERSION).isEqualTo("ROUTE-2026-03");
  }

  @Test
  void supplyFailuresMapToStableRuleIdsAndSortedLineParameters() {
    Input input =
        new Input(
            Set.of(TradeRouteCode.SH_GENERAL_TRADE),
            "CNY",
            "CN",
            LocalDate.of(2026, 7, 30),
            30,
            LocalDate.of(2026, 7, 14),
            List.of(
                new LineInput(
                    LINE_2,
                    SKU_2,
                    BigDecimal.ONE,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    null),
                new LineInput(
                    LINE,
                    SKU,
                    BigDecimal.ONE,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    PRIMARY_POOL)),
            List.of());

    RouteCandidate candidate = candidate(input, TradeRouteCode.SH_GENERAL_TRADE);

    assertThat(candidate.rejections())
        .filteredOn(rejection -> rejection.code().contains("SUPPLY"))
        .extracting(rejection -> rejection.ruleId())
        .containsExactly("TRD-SUPPLY-001", "TRD-SUPPLY-FIXED-001");
    assertThat(candidate.rejections())
        .filteredOn(rejection -> rejection.code().equals("NO_PROMISABLE_SUPPLY"))
        .singleElement()
        .satisfies(
            rejection ->
                assertThat(rejection.parameters().get("quotationLineIds"))
                    .isEqualTo(LINE_2.toString()));
  }

  private static Input input(Set<TradeRouteCode> partnerRoutes) {
    return input(
        partnerRoutes,
        line("6", TradePlanningQuantityUnit.CASE, "6", null),
        List.of(
            availability(
                PRIMARY_POOL,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningQuantityUnit.CASE,
                "56",
                "HIGH"),
            availability(
                SECONDARY_POOL,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningQuantityUnit.CASE,
                "7",
                "HIGH")));
  }

  private static Input input(
      Set<TradeRouteCode> partnerRoutes, LineInput line, List<AvailabilityInput> availability) {
    return new Input(
        partnerRoutes,
        "CNY",
        "CN",
        LocalDate.of(2026, 7, 30),
        30,
        LocalDate.of(2026, 7, 14),
        List.of(line),
        availability);
  }

  private static LineInput line(
      String requestedQuantity,
      TradePlanningQuantityUnit quantityUnit,
      String moqCaseEquivalentQuantity,
      UUID preferredSupplyPoolId) {
    return new LineInput(
        LINE,
        SKU,
        new BigDecimal(requestedQuantity),
        quantityUnit,
        new BigDecimal(moqCaseEquivalentQuantity),
        preferredSupplyPoolId);
  }

  private static AvailabilityInput availability(
      UUID pool,
      TradeRouteCode route,
      TradePlanningQuantityUnit quantityUnit,
      String availableQuantity,
      String confidence) {
    return new AvailabilityInput(
        pool,
        SKU,
        route,
        TradePlanningSupplyType.DOMESTIC_ON_HAND,
        quantityUnit,
        new BigDecimal(availableQuantity),
        null,
        confidence,
        "INV-READY-2026-01",
        Instant.parse("2026-07-14T12:00:00Z"));
  }

  private static RouteCandidate candidate(Input input, TradeRouteCode route) {
    return RouteEvaluationPolicy.evaluate(input).candidates().stream()
        .filter(candidate -> candidate.routeCode() == route)
        .findFirst()
        .orElseThrow();
  }

  private static void assertNoPromisableSupply(
      LineInput line, List<AvailabilityInput> availability) {
    assertSupplyFailure(line, availability, "NO_PROMISABLE_SUPPLY");
  }

  private static void assertSupplyFailure(
      LineInput line, List<AvailabilityInput> availability, String code) {
    RouteCandidate shanghai =
        candidate(
            input(Set.of(TradeRouteCode.SH_GENERAL_TRADE), line, availability),
            TradeRouteCode.SH_GENERAL_TRADE);
    assertThat(shanghai.rejections()).extracting(rejection -> rejection.code()).contains(code);
  }
}
