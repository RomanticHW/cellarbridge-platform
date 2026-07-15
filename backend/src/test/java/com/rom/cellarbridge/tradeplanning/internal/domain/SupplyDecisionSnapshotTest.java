package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot.LineDecision;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
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
