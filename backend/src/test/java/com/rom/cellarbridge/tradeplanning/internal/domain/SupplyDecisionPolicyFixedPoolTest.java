package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.AvailabilityInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.Confidence;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.LineInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplyDecisionPolicyFixedPoolTest extends SupplyDecisionTestFixtures {

  @Test
  void fixedPoolMayFreezeAnExplicitManualSupplyType() {
    LineInput line = line(LINE_1, SKU_1, "4", TradePlanningQuantityUnit.BOTTLE, POOL_1);
    AvailabilityInput fixed =
        availability(
            POOL_1,
            SKU_1,
            TradeRouteCode.HK_FREE_TRADE,
            TradePlanningSupplyType.OVERSEAS_SOURCING,
            TradePlanningQuantityUnit.BOTTLE,
            "4",
            null,
            "MEDIUM",
            DATA_AS_OF_1);

    Result result =
        SupplyDecisionPolicy.decide(TradeRouteCode.HK_FREE_TRADE, List.of(line), List.of(fixed));

    assertThat(result.feasible()).isTrue();
    assertThat(result.minimumConfidence()).isEqualTo(Confidence.MEDIUM);
    assertThat(result.lineDecisions().getFirst().allocationMode())
        .isEqualTo(SupplyAllocationMode.FIXED_POOL);
    assertThat(result.lineDecisions().getFirst().supplyPoolId()).isEqualTo(POOL_1);
    assertThat(result.lineDecisions().getFirst().supplyType())
        .isEqualTo(TradePlanningSupplyType.OVERSEAS_SOURCING);
  }

  @Test
  void fixedPoolNeverFallsBackAndUsesTheStableFailureCode() {
    LineInput fixedLine = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, POOL_1);
    AvailabilityInput fallback =
        autoAvailability(
            POOL_2, TradePlanningSupplyType.DOMESTIC_ON_HAND, "100", null, "HIGH", DATA_AS_OF_2);
    List<List<AvailabilityInput>> ineligibleInputs =
        List.of(
            List.of(fallback),
            List.of(
                fixedAvailability(
                    TradeRouteCode.NB_BONDED_B2B, TradePlanningQuantityUnit.CASE, "100"),
                fallback),
            List.of(
                fixedAvailability(
                    TradeRouteCode.SH_GENERAL_TRADE, TradePlanningQuantityUnit.BOTTLE, "100"),
                fallback),
            List.of(
                fixedAvailability(
                    TradeRouteCode.SH_GENERAL_TRADE, TradePlanningQuantityUnit.CASE, "5"),
                fallback));

    for (List<AvailabilityInput> availability : ineligibleInputs) {
      Result result =
          SupplyDecisionPolicy.decide(
              TradeRouteCode.SH_GENERAL_TRADE, List.of(fixedLine), availability);
      assertThat(result.feasible()).isFalse();
      assertThat(result.failures())
          .extracting(SupplyDecisionPolicy.Failure::code)
          .containsExactly("QUOTE_FIXED_SUPPLY_POOL_INELIGIBLE");
    }
  }

  @Test
  void unknownConfidenceRejectsItsCandidateGroup() {
    LineInput auto = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    AvailabilityInput unknownAuto =
        autoAvailability(
            POOL_1, TradePlanningSupplyType.DOMESTIC_ON_HAND, "100", null, "UNKNOWN", DATA_AS_OF_1);
    LineInput fixed = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, POOL_1);

    Result autoResult =
        SupplyDecisionPolicy.decide(
            TradeRouteCode.SH_GENERAL_TRADE, List.of(auto), List.of(unknownAuto));
    Result fixedResult =
        SupplyDecisionPolicy.decide(
            TradeRouteCode.SH_GENERAL_TRADE, List.of(fixed), List.of(unknownAuto));

    assertThat(autoResult.feasible()).isFalse();
    assertThat(fixedResult.failures())
        .extracting(SupplyDecisionPolicy.Failure::code)
        .containsExactly("QUOTE_FIXED_SUPPLY_POOL_INELIGIBLE");
  }
}
