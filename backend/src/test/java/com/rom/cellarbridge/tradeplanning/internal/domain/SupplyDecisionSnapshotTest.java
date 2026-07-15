package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot.LineDecision;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteScore;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplyDecisionSnapshotTest extends SupplyDecisionTestFixtures {

  @Test
  void rejectsDuplicateLineIdsAndMismatchedDecisionHash() {
    LineDecision line = line(SupplyAllocationMode.FIXED_POOL, POOL_1);

    assertThatThrownBy(() -> snapshot(List.of(line, line)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("quotationLineId must be unique");

    SupplyDecisionSnapshot valid = snapshot(List.of(line));
    assertThatThrownBy(
            () ->
                new SupplyDecisionSnapshot(
                    valid.schemaVersion(),
                    valid.policyVersion(),
                    valid.decidedAt(),
                    valid.sourceRouteEvaluationId(),
                    valid.sourceRouteInputHash(),
                    valid.selectedRouteCode(),
                    valid.inventoryDataAsOf(),
                    "0".repeat(64),
                    valid.lineDecisions()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("decisionHash does not match snapshot content");
  }

  @Test
  void fixedPoolDecisionRequiresAPool() {
    assertThatThrownBy(() -> line(SupplyAllocationMode.FIXED_POOL, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("FIXED_POOL requires supplyPoolId");
  }

  @Test
  void automaticDecisionMustNotFreezeAPool() {
    assertThatThrownBy(() -> line(SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO, POOL_1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ROUTE_ELIGIBLE_AUTO must not define supplyPoolId");
  }

  @Test
  void validatesPersistenceHashesPoliciesTimesAndAutomaticTypes() {
    assertThatThrownBy(
            () ->
                SupplyDecisionSnapshot.create(
                    " ",
                    DECIDED_AT,
                    EVALUATION_ID,
                    SOURCE_INPUT_HASH,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    DATA_AS_OF_1,
                    List.of(line(SupplyAllocationMode.FIXED_POOL, POOL_1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SupplyDecisionSnapshot.create(
                    "x".repeat(81),
                    DECIDED_AT,
                    EVALUATION_ID,
                    SOURCE_INPUT_HASH,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    DATA_AS_OF_1,
                    List.of(line(SupplyAllocationMode.FIXED_POOL, POOL_1))))
        .isInstanceOf(IllegalArgumentException.class);
    for (String invalidHash :
        List.of("sha256:" + SOURCE_INPUT_HASH, SOURCE_INPUT_HASH.toUpperCase(), "a".repeat(63))) {
      assertThatThrownBy(
              () ->
                  SupplyDecisionSnapshot.create(
                      SupplyDecisionSnapshot.POLICY_VERSION,
                      DECIDED_AT,
                      EVALUATION_ID,
                      invalidHash,
                      TradeRouteCode.SH_GENERAL_TRADE,
                      DATA_AS_OF_1,
                      List.of(line(SupplyAllocationMode.FIXED_POOL, POOL_1))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                SupplyDecisionSnapshot.create(
                    SupplyDecisionSnapshot.POLICY_VERSION,
                    Instant.parse("2026-07-14T10:15:30.000000001Z"),
                    EVALUATION_ID,
                    SOURCE_INPUT_HASH,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    DATA_AS_OF_1,
                    List.of(line(SupplyAllocationMode.FIXED_POOL, POOL_1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new LineDecision(
                    LINE_1,
                    SKU_1,
                    BigDecimal.ONE,
                    TradePlanningQuantityUnit.CASE,
                    SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
                    null,
                    TradePlanningSupplyType.IN_TRANSIT_PRESALE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new LineDecision(
                    LINE_1,
                    SKU_1,
                    BigDecimal.ONE,
                    TradePlanningQuantityUnit.CASE,
                    SupplyAllocationMode.FIXED_POOL,
                    POOL_1,
                    TradePlanningSupplyType.OVERSEAS_SOURCING)
                .supplyType())
        .isEqualTo(TradePlanningSupplyType.OVERSEAS_SOURCING);
  }

  @Test
  void routeEvaluationRequiresCurrentDecisionAndMatchingRootEvidence() {
    SupplyDecisionSnapshot decision =
        snapshot(List.of(line(SupplyAllocationMode.FIXED_POOL, POOL_1)));
    RouteCandidate candidate =
        new RouteCandidate(
            TradeRouteCode.SH_GENERAL_TRADE,
            Eligibility.ELIGIBLE,
            new RouteScore(
                new BigDecimal("72"),
                new BigDecimal("92"),
                new BigDecimal("95"),
                new BigDecimal("92"),
                new BigDecimal("84.60")),
            LocalDate.of(2026, 7, 19),
            new BigDecimal("90"),
            "CNY",
            List.of());
    assertThatThrownBy(
            () ->
                new RouteEvaluation(
                    EVALUATION_ID,
                    "ROUTE-2026-03",
                    DECIDED_AT,
                    SOURCE_INPUT_HASH,
                    List.of(candidate),
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RouteEvaluation(
                    EVALUATION_ID,
                    "ROUTE-2026-03",
                    DECIDED_AT,
                    "f".repeat(64),
                    List.of(candidate),
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    null,
                    decision))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static LineDecision line(SupplyAllocationMode mode, java.util.UUID poolId) {
    return new LineDecision(
        LINE_1,
        SKU_1,
        BigDecimal.ONE,
        TradePlanningQuantityUnit.CASE,
        mode,
        poolId,
        TradePlanningSupplyType.DOMESTIC_ON_HAND);
  }

  private static SupplyDecisionSnapshot snapshot(List<LineDecision> lines) {
    return SupplyDecisionSnapshot.create(
        "SUPPLY-DECISION-2026-01",
        DECIDED_AT,
        EVALUATION_ID,
        SOURCE_INPUT_HASH,
        TradeRouteCode.SH_GENERAL_TRADE,
        DATA_AS_OF_1,
        lines);
  }
}
