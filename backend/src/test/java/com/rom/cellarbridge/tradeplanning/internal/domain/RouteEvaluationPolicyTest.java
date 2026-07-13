package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.AvailabilityInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.Input;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.LineInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteEvaluationPolicyTest {

  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");

  @Test
  void keepsRejectedCandidateAndStablePartnerReason() {
    List<RouteCandidate> candidates =
        RouteEvaluationPolicy.evaluate(input(Set.of(TradeRouteCode.SH_GENERAL_TRADE)));

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
    List<RouteCandidate> first = RouteEvaluationPolicy.evaluate(input);

    for (int repeat = 0; repeat < 100; repeat++) {
      List<RouteCandidate> next = RouteEvaluationPolicy.evaluate(input);
      assertThat(next).isEqualTo(first);
      assertThat(RouteEvaluationPolicy.recommend(next)).isEqualTo(TradeRouteCode.SH_GENERAL_TRADE);
    }
    assertThat(first.getFirst().score().total()).isEqualByComparingTo("84.60");
  }

  private static Input input(Set<TradeRouteCode> partnerRoutes) {
    return new Input(
        partnerRoutes,
        "CNY",
        "CN",
        LocalDate.of(2026, 7, 30),
        30,
        LocalDate.of(2026, 7, 14),
        List.of(new LineInput(SKU, new BigDecimal("6"), null)),
        List.of(
            new AvailabilityInput(
                UUID.fromString("36000000-0000-4000-8000-000000000001"),
                SKU,
                TradeRouteCode.SH_GENERAL_TRADE,
                new BigDecimal("56"),
                "HIGH"),
            new AvailabilityInput(
                UUID.fromString("36000000-0000-4000-8000-000000000002"),
                SKU,
                TradeRouteCode.NB_BONDED_B2B,
                new BigDecimal("7"),
                "HIGH")));
  }
}
