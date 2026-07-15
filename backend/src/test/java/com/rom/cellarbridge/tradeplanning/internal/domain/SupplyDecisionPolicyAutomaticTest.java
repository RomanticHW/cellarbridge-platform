package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot.LineDecision;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.AvailabilityInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.Confidence;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.LineInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.Result;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplyDecisionPolicyAutomaticTest extends SupplyDecisionTestFixtures {

  @Test
  void autoChoosesOneViableTypeAndFreezesNoPool() {
    LineInput line = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    List<AvailabilityInput> availability =
        List.of(
            availability(
                POOL_1,
                SKU_1,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningSupplyType.DOMESTIC_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "10",
                Instant.parse("2026-07-14T09:00:00Z"),
                "MEDIUM",
                DATA_AS_OF_1),
            availability(
                POOL_1,
                SKU_1,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningSupplyType.BONDED_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "3",
                null,
                "HIGH",
                DATA_AS_OF_1),
            availability(
                POOL_2,
                SKU_1,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningSupplyType.BONDED_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "3",
                Instant.parse("2026-07-14T08:00:00Z"),
                "HIGH",
                DATA_AS_OF_2));

    Result result =
        SupplyDecisionPolicy.decide(TradeRouteCode.NB_BONDED_B2B, List.of(line), availability);

    assertThat(result.feasible()).isTrue();
    assertThat(result.minimumConfidence()).isEqualTo(Confidence.HIGH);
    assertThat(result.inventoryDataAsOf()).isEqualTo(DATA_AS_OF_2);
    assertThat(result.failures()).isEmpty();
    assertThat(result.lineDecisions())
        .containsExactly(
            new LineDecision(
                LINE_1,
                SKU_1,
                new BigDecimal("6"),
                TradePlanningQuantityUnit.CASE,
                SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
                null,
                TradePlanningSupplyType.BONDED_ON_HAND));
  }

  @Test
  void availabilityOrderDoesNotChangeDecisionsOrHashAcrossOneHundredShuffles() {
    List<LineInput> lines =
        List.of(
            line(LINE_2, SKU_2, "2", TradePlanningQuantityUnit.BOTTLE, null),
            line(LINE_1, SKU_1, "6.5", TradePlanningQuantityUnit.CASE, POOL_1));
    List<AvailabilityInput> original =
        List.of(
            availability(
                POOL_2,
                SKU_2,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningSupplyType.BONDED_ON_HAND,
                TradePlanningQuantityUnit.BOTTLE,
                "1",
                null,
                "HIGH",
                DATA_AS_OF_1),
            availability(
                POOL_1,
                SKU_1,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningSupplyType.DOMESTIC_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "6.5",
                null,
                "MEDIUM",
                DATA_AS_OF_2),
            availability(
                POOL_1,
                SKU_2,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningSupplyType.BONDED_ON_HAND,
                TradePlanningQuantityUnit.BOTTLE,
                "1",
                Instant.parse("2026-07-14T08:00:00Z"),
                "HIGH",
                DATA_AS_OF_2),
            availability(
                POOL_2,
                SKU_2,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningSupplyType.DOMESTIC_ON_HAND,
                TradePlanningQuantityUnit.BOTTLE,
                "2",
                null,
                "LOW",
                DATA_AS_OF_1));
    SupplyDecisionSnapshot expected =
        SupplyDecisionPolicy.decide(TradeRouteCode.NB_BONDED_B2B, lines, original)
            .snapshot(DECIDED_AT, EVALUATION_ID, SOURCE_INPUT_HASH);

    for (int seed = 0; seed < 100; seed++) {
      List<AvailabilityInput> shuffled = new ArrayList<>(original);
      Collections.shuffle(shuffled, new Random(seed));
      SupplyDecisionSnapshot actual =
          SupplyDecisionPolicy.decide(TradeRouteCode.NB_BONDED_B2B, lines, shuffled)
              .snapshot(DECIDED_AT, EVALUATION_ID, SOURCE_INPUT_HASH);
      assertThat(actual).isEqualTo(expected);
      assertThat(actual.decisionHash()).matches("^[0-9a-f]{64}$");
    }
    assertThat(expected.lineDecisions())
        .extracting(LineDecision::quotationLineId)
        .containsExactly(LINE_1, LINE_2);
  }

  @Test
  void autoExcludesManualSupplyTypes() {
    LineInput line = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    List<AvailabilityInput> manualOnly =
        List.of(
            autoAvailability(
                POOL_1,
                TradePlanningSupplyType.IN_TRANSIT_PRESALE,
                "100",
                null,
                "HIGH",
                DATA_AS_OF_1),
            autoAvailability(
                POOL_2,
                TradePlanningSupplyType.OVERSEAS_SOURCING,
                "100",
                null,
                "HIGH",
                DATA_AS_OF_2),
            availability(
                POOL_1,
                SKU_2,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningSupplyType.DOMESTIC_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "100",
                null,
                "HIGH",
                DATA_AS_OF_2));

    Result result =
        SupplyDecisionPolicy.decide(TradeRouteCode.SH_GENERAL_TRADE, List.of(line), manualOnly);

    assertThat(result.feasible()).isFalse();
    assertThat(result.failures())
        .extracting(SupplyDecisionPolicy.Failure::code)
        .containsExactly("NO_PROMISABLE_SUPPLY");
  }

  @Test
  void autoNeverCombinesIndividuallyInsufficientSupplyTypes() {
    LineInput line = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    List<AvailabilityInput> splitAcrossTypes =
        List.of(
            autoAvailability(
                POOL_1, TradePlanningSupplyType.DOMESTIC_ON_HAND, "4", null, "HIGH", DATA_AS_OF_1),
            autoAvailability(
                POOL_2, TradePlanningSupplyType.BONDED_ON_HAND, "3", null, "HIGH", DATA_AS_OF_2));

    Result result =
        SupplyDecisionPolicy.decide(
            TradeRouteCode.SH_GENERAL_TRADE, List.of(line), splitAcrossTypes);

    assertThat(result.feasible()).isFalse();
    assertThat(result.lineDecisions()).isEmpty();
  }

  @Test
  void unrelatedLowConfidenceRowsDoNotLowerTheSelectedConfidence() {
    LineInput line = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    AvailabilityInput selected =
        autoAvailability(
            POOL_1, TradePlanningSupplyType.DOMESTIC_ON_HAND, "6", null, "HIGH", DATA_AS_OF_1);
    List<AvailabilityInput> unrelated =
        List.of(
            selected,
            availability(
                POOL_2,
                SKU_1,
                TradeRouteCode.NB_BONDED_B2B,
                TradePlanningSupplyType.DOMESTIC_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "100",
                null,
                "LOW",
                DATA_AS_OF_2),
            availability(
                POOL_2,
                SKU_1,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningSupplyType.DOMESTIC_ON_HAND,
                TradePlanningQuantityUnit.BOTTLE,
                "100",
                null,
                "LOW",
                DATA_AS_OF_2),
            autoAvailability(
                POOL_2,
                TradePlanningSupplyType.IN_TRANSIT_PRESALE,
                "100",
                null,
                "LOW",
                DATA_AS_OF_2),
            autoAvailability(
                POOL_2, TradePlanningSupplyType.BONDED_ON_HAND, "5", null, "LOW", DATA_AS_OF_2));

    Result result =
        SupplyDecisionPolicy.decide(TradeRouteCode.SH_GENERAL_TRADE, List.of(line), unrelated);

    assertThat(result.minimumConfidence()).isEqualTo(Confidence.HIGH);
    assertThat(result.inventoryDataAsOf()).isEqualTo(DATA_AS_OF_1);
  }

  @Test
  void lineInputRejectsNullIdentityUnitAndNonPositiveQuantities() {
    for (String field :
        List.of(
            "quotationLineId",
            "skuId",
            "requestedQuantity",
            "quantityUnit",
            "moqCaseEquivalentQuantity")) {
      assertThatThrownBy(() -> lineWithNull(field))
          .as(field)
          .isInstanceOf(NullPointerException.class);
    }
    assertThatThrownBy(
            () ->
                new LineInput(
                    LINE_1,
                    SKU_1,
                    BigDecimal.ZERO,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    LineInput boundary =
        new LineInput(
            LINE_1,
            SKU_1,
            new BigDecimal("9999999999999.999999"),
            TradePlanningQuantityUnit.CASE,
            new BigDecimal("1.1234560"),
            null);
    assertThat(boundary.requestedQuantity().scale()).isEqualTo(6);
    assertThat(boundary.moqCaseEquivalentQuantity()).isEqualByComparingTo("1.123456");
    assertThatThrownBy(
            () ->
                new LineInput(
                    LINE_1,
                    SKU_1,
                    new BigDecimal("10000000000000.000000"),
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new LineInput(
                    LINE_1,
                    SKU_1,
                    new BigDecimal("1.1234567"),
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    null))
        .isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(
            () ->
                new LineInput(
                    LINE_1,
                    SKU_1,
                    BigDecimal.ONE,
                    TradePlanningQuantityUnit.CASE,
                    new BigDecimal("-0.1"),
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void availabilityInputRejectsMalformedMaterialFieldsButAllowsNullAvailableFrom() {
    for (String field :
        List.of(
            "supplyPoolId",
            "skuId",
            "routeCode",
            "supplyType",
            "quantityUnit",
            "availableQuantity",
            "confidence",
            "inventoryPolicyVersion",
            "dataAsOf")) {
      assertThatThrownBy(() -> availabilityWithNull(field))
          .as(field)
          .isInstanceOf(NullPointerException.class);
    }
    assertThatThrownBy(
            () ->
                malformedAvailability(
                    new BigDecimal("-0.1"), "HIGH", "INVENTORY-2026-01", DATA_AS_OF_1))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(
            availability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    "0",
                    null,
                    "HIGH",
                    DATA_AS_OF_1)
                .availableFrom())
        .isNull();
    assertThat(
            malformedAvailability(
                    new BigDecimal("9999999999999.999999"),
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1)
                .availableQuantity()
                .scale())
        .isEqualTo(6);
    assertThatThrownBy(
            () ->
                malformedAvailability(
                    new BigDecimal("10000000000000.000000"),
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void policyRejectsDuplicateQuotationLineIdsAndNullCollections() {
    LineInput first = line(LINE_1, SKU_1, "1", TradePlanningQuantityUnit.CASE, null);
    LineInput duplicate = line(LINE_1, SKU_2, "1", TradePlanningQuantityUnit.CASE, null);

    assertThatThrownBy(
            () ->
                SupplyDecisionPolicy.decide(
                    TradeRouteCode.SH_GENERAL_TRADE, List.of(first, duplicate), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SupplyDecisionPolicy.decide(null, List.of(first), List.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> SupplyDecisionPolicy.decide(TradeRouteCode.SH_GENERAL_TRADE, null, List.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                SupplyDecisionPolicy.decide(TradeRouteCode.SH_GENERAL_TRADE, List.of(first), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void autoRanksCandidateGroupsByTheirMinimumConfidence() {
    LineInput line = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    List<AvailabilityInput> availability =
        List.of(
            autoAvailability(
                POOL_1, TradePlanningSupplyType.DOMESTIC_ON_HAND, "3", null, "HIGH", DATA_AS_OF_1),
            autoAvailability(
                POOL_2, TradePlanningSupplyType.DOMESTIC_ON_HAND, "3", null, "LOW", DATA_AS_OF_2),
            autoAvailability(
                POOL_1, TradePlanningSupplyType.BONDED_ON_HAND, "6", null, "MEDIUM", DATA_AS_OF_1));

    Result result =
        SupplyDecisionPolicy.decide(TradeRouteCode.SH_GENERAL_TRADE, List.of(line), availability);

    assertThat(result.minimumConfidence()).isEqualTo(Confidence.MEDIUM);
    assertThat(result.lineDecisions().getFirst().supplyType())
        .isEqualTo(TradePlanningSupplyType.BONDED_ON_HAND);
  }

  @Test
  void autoUsesEarliestAvailableFromAfterConfidenceTies() {
    Instant earlier = Instant.parse("2026-07-14T08:00:00Z");
    Instant later = Instant.parse("2026-07-14T09:00:00Z");
    LineInput line = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    List<AvailabilityInput> availability =
        List.of(
            autoAvailability(
                POOL_1, TradePlanningSupplyType.DOMESTIC_ON_HAND, "6", later, "HIGH", DATA_AS_OF_1),
            autoAvailability(
                POOL_2,
                TradePlanningSupplyType.BONDED_ON_HAND,
                "6",
                earlier,
                "HIGH",
                DATA_AS_OF_2));

    Result result =
        SupplyDecisionPolicy.decide(TradeRouteCode.SH_GENERAL_TRADE, List.of(line), availability);

    assertThat(result.lineDecisions().getFirst().supplyType())
        .isEqualTo(TradePlanningSupplyType.BONDED_ON_HAND);
  }

  @Test
  void autoUsesStableSupplyTypePriorityAfterConfidenceAndTimeTies() {
    LineInput line = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    List<AvailabilityInput> availability =
        List.of(
            autoAvailability(
                POOL_1, TradePlanningSupplyType.HONG_KONG_ON_HAND, "6", null, "HIGH", DATA_AS_OF_1),
            autoAvailability(
                POOL_2, TradePlanningSupplyType.DOMESTIC_ON_HAND, "6", null, "HIGH", DATA_AS_OF_2),
            autoAvailability(
                POOL_1, TradePlanningSupplyType.BONDED_ON_HAND, "6", null, "HIGH", DATA_AS_OF_1));

    Result result =
        SupplyDecisionPolicy.decide(TradeRouteCode.SH_GENERAL_TRADE, List.of(line), availability);

    assertThat(result.lineDecisions().getFirst().supplyType())
        .isEqualTo(TradePlanningSupplyType.DOMESTIC_ON_HAND);
  }

  @Test
  void unknownSupplyTypeAndAllocationModeNamesAreRejected() {
    assertThatThrownBy(() -> TradePlanningSupplyType.valueOf("UNKNOWN_SUPPLY_TYPE"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SupplyAllocationMode.valueOf("UNKNOWN_ALLOCATION_MODE"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

abstract class SupplyDecisionTestFixtures {

  static final UUID LINE_1 = uuid("72000000-0000-4000-8000-000000000001");
  static final UUID LINE_2 = uuid("72000000-0000-4000-8000-000000000002");
  static final UUID SKU_1 = uuid("73000000-0000-4000-8000-000000000001");
  static final UUID SKU_2 = uuid("73000000-0000-4000-8000-000000000002");
  static final UUID POOL_1 = uuid("74000000-0000-4000-8000-000000000001");
  static final UUID POOL_2 = uuid("74000000-0000-4000-8000-000000000002");
  static final UUID EVALUATION_ID = uuid("71000000-0000-4000-8000-000000000001");
  static final String SOURCE_INPUT_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  static final Instant DECIDED_AT = Instant.parse("2026-07-14T10:15:30Z");
  static final Instant DATA_AS_OF_1 = Instant.parse("2026-07-14T10:13:00Z");
  static final Instant DATA_AS_OF_2 = Instant.parse("2026-07-14T10:14:00Z");

  static LineInput line(
      UUID lineId,
      UUID skuId,
      String requestedQuantity,
      TradePlanningQuantityUnit unit,
      UUID preferredPoolId) {
    return new LineInput(
        lineId, skuId, new BigDecimal(requestedQuantity), unit, BigDecimal.ONE, preferredPoolId);
  }

  static AvailabilityInput availability(
      UUID poolId,
      UUID skuId,
      TradeRouteCode routeCode,
      TradePlanningSupplyType supplyType,
      TradePlanningQuantityUnit unit,
      String availableQuantity,
      Instant availableFrom,
      String confidence,
      Instant dataAsOf) {
    return new AvailabilityInput(
        poolId,
        skuId,
        routeCode,
        supplyType,
        unit,
        new BigDecimal(availableQuantity),
        availableFrom,
        confidence,
        "INVENTORY-2026-01",
        dataAsOf);
  }

  static AvailabilityInput autoAvailability(
      UUID poolId,
      TradePlanningSupplyType supplyType,
      String availableQuantity,
      Instant availableFrom,
      String confidence,
      Instant dataAsOf) {
    return availability(
        poolId,
        SKU_1,
        TradeRouteCode.SH_GENERAL_TRADE,
        supplyType,
        TradePlanningQuantityUnit.CASE,
        availableQuantity,
        availableFrom,
        confidence,
        dataAsOf);
  }

  static AvailabilityInput fixedAvailability(
      TradeRouteCode routeCode, TradePlanningQuantityUnit unit, String availableQuantity) {
    return availability(
        POOL_1,
        SKU_1,
        routeCode,
        TradePlanningSupplyType.DOMESTIC_ON_HAND,
        unit,
        availableQuantity,
        null,
        "HIGH",
        DATA_AS_OF_1);
  }

  static LineInput lineWithNull(String field) {
    return new LineInput(
        "quotationLineId".equals(field) ? null : LINE_1,
        "skuId".equals(field) ? null : SKU_1,
        "requestedQuantity".equals(field) ? null : BigDecimal.ONE,
        "quantityUnit".equals(field) ? null : TradePlanningQuantityUnit.CASE,
        "moqCaseEquivalentQuantity".equals(field) ? null : BigDecimal.ONE,
        null);
  }

  static AvailabilityInput availabilityWithNull(String field) {
    return new AvailabilityInput(
        "supplyPoolId".equals(field) ? null : POOL_1,
        "skuId".equals(field) ? null : SKU_1,
        "routeCode".equals(field) ? null : TradeRouteCode.SH_GENERAL_TRADE,
        "supplyType".equals(field) ? null : TradePlanningSupplyType.DOMESTIC_ON_HAND,
        "quantityUnit".equals(field) ? null : TradePlanningQuantityUnit.CASE,
        "availableQuantity".equals(field) ? null : BigDecimal.ONE,
        null,
        "confidence".equals(field) ? null : "HIGH",
        "inventoryPolicyVersion".equals(field) ? null : "INVENTORY-2026-01",
        "dataAsOf".equals(field) ? null : DATA_AS_OF_1);
  }

  static AvailabilityInput malformedAvailability(
      BigDecimal availableQuantity,
      String confidence,
      String inventoryPolicyVersion,
      Instant dataAsOf) {
    return new AvailabilityInput(
        POOL_1,
        SKU_1,
        TradeRouteCode.SH_GENERAL_TRADE,
        TradePlanningSupplyType.DOMESTIC_ON_HAND,
        TradePlanningQuantityUnit.CASE,
        availableQuantity,
        null,
        confidence,
        inventoryPolicyVersion,
        dataAsOf);
  }

  static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
