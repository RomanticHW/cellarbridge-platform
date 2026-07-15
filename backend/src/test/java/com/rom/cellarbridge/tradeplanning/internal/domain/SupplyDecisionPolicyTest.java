package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionHashV1;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionHashV1.HashInput;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SupplyDecisionPolicyTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID LINE_1 = uuid("72000000-0000-4000-8000-000000000001");
  private static final UUID LINE_2 = uuid("72000000-0000-4000-8000-000000000002");
  private static final UUID SKU_1 = uuid("73000000-0000-4000-8000-000000000001");
  private static final UUID SKU_2 = uuid("73000000-0000-4000-8000-000000000002");
  private static final UUID POOL_1 = uuid("74000000-0000-4000-8000-000000000001");
  private static final UUID POOL_2 = uuid("74000000-0000-4000-8000-000000000002");
  private static final UUID EVALUATION_ID = uuid("71000000-0000-4000-8000-000000000001");
  private static final String SOURCE_INPUT_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final Instant DECIDED_AT = Instant.parse("2026-07-14T10:15:30Z");
  private static final Instant DATA_AS_OF_1 = Instant.parse("2026-07-14T10:13:00Z");
  private static final Instant DATA_AS_OF_2 = Instant.parse("2026-07-14T10:14:00Z");

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
            availability(
                POOL_1,
                SKU_1,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningSupplyType.IN_TRANSIT_PRESALE,
                TradePlanningQuantityUnit.CASE,
                "100",
                null,
                "HIGH",
                DATA_AS_OF_1),
            availability(
                POOL_2,
                SKU_1,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningSupplyType.OVERSEAS_SOURCING,
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
            availability(
                POOL_1,
                SKU_1,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningSupplyType.DOMESTIC_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "4",
                null,
                "HIGH",
                DATA_AS_OF_1),
            availability(
                POOL_2,
                SKU_1,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningSupplyType.BONDED_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "3",
                null,
                "HIGH",
                DATA_AS_OF_2));

    Result result =
        SupplyDecisionPolicy.decide(
            TradeRouteCode.SH_GENERAL_TRADE, List.of(line), splitAcrossTypes);

    assertThat(result.feasible()).isFalse();
    assertThat(result.lineDecisions()).isEmpty();
  }

  @Test
  void fixedPoolNeverFallsBackAndUsesTheStableFailureCode() {
    LineInput fixedLine = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, POOL_1);
    AvailabilityInput fallback =
        availability(
            POOL_2,
            SKU_1,
            TradeRouteCode.SH_GENERAL_TRADE,
            TradePlanningSupplyType.DOMESTIC_ON_HAND,
            TradePlanningQuantityUnit.CASE,
            "100",
            null,
            "HIGH",
            DATA_AS_OF_2);
    List<List<AvailabilityInput>> ineligibleInputs =
        List.of(
            List.of(fallback),
            List.of(
                availability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.NB_BONDED_B2B,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    "100",
                    null,
                    "HIGH",
                    DATA_AS_OF_1),
                fallback),
            List.of(
                availability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.BOTTLE,
                    "100",
                    null,
                    "HIGH",
                    DATA_AS_OF_1),
                fallback),
            List.of(
                availability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    "5",
                    null,
                    "HIGH",
                    DATA_AS_OF_1),
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
        availability(
            POOL_1,
            SKU_1,
            TradeRouteCode.SH_GENERAL_TRADE,
            TradePlanningSupplyType.DOMESTIC_ON_HAND,
            TradePlanningQuantityUnit.CASE,
            "100",
            null,
            "UNKNOWN",
            DATA_AS_OF_1);
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

  @Test
  void unrelatedLowConfidenceRowsDoNotLowerTheSelectedConfidence() {
    LineInput line = line(LINE_1, SKU_1, "6", TradePlanningQuantityUnit.CASE, null);
    AvailabilityInput selected =
        availability(
            POOL_1,
            SKU_1,
            TradeRouteCode.SH_GENERAL_TRADE,
            TradePlanningSupplyType.DOMESTIC_ON_HAND,
            TradePlanningQuantityUnit.CASE,
            "6",
            null,
            "HIGH",
            DATA_AS_OF_1);
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
            availability(
                POOL_2,
                SKU_1,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningSupplyType.IN_TRANSIT_PRESALE,
                TradePlanningQuantityUnit.CASE,
                "100",
                null,
                "LOW",
                DATA_AS_OF_2),
            availability(
                POOL_2,
                SKU_1,
                TradeRouteCode.SH_GENERAL_TRADE,
                TradePlanningSupplyType.BONDED_ON_HAND,
                TradePlanningQuantityUnit.CASE,
                "5",
                null,
                "LOW",
                DATA_AS_OF_2));

    Result result =
        SupplyDecisionPolicy.decide(TradeRouteCode.SH_GENERAL_TRADE, List.of(line), unrelated);

    assertThat(result.minimumConfidence()).isEqualTo(Confidence.HIGH);
    assertThat(result.inventoryDataAsOf()).isEqualTo(DATA_AS_OF_1);
  }

  @Test
  void lineInputRejectsNullIdentityUnitAndNonPositiveQuantities() {
    assertThatThrownBy(
            () ->
                new LineInput(
                    null,
                    SKU_1,
                    BigDecimal.ONE,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new LineInput(
                    LINE_1,
                    null,
                    BigDecimal.ONE,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new LineInput(
                    LINE_1, SKU_1, null, TradePlanningQuantityUnit.CASE, BigDecimal.ONE, null))
        .isInstanceOf(NullPointerException.class);
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
    assertThatThrownBy(
            () -> new LineInput(LINE_1, SKU_1, BigDecimal.ONE, null, BigDecimal.ONE, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new LineInput(
                    LINE_1, SKU_1, BigDecimal.ONE, TradePlanningQuantityUnit.CASE, null, null))
        .isInstanceOf(NullPointerException.class);
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
    List<Runnable> malformed =
        List.of(
            () ->
                malformedAvailability(
                    null,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1),
            () ->
                malformedAvailability(
                    POOL_1,
                    null,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1),
            () ->
                malformedAvailability(
                    POOL_1,
                    SKU_1,
                    null,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1),
            () ->
                malformedAvailability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    null,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1),
            () ->
                malformedAvailability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    null,
                    BigDecimal.ONE,
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1),
            () ->
                malformedAvailability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    null,
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1),
            () ->
                malformedAvailability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    null,
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1),
            () ->
                malformedAvailability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    "HIGH",
                    null,
                    DATA_AS_OF_1),
            () ->
                malformedAvailability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    BigDecimal.ONE,
                    "HIGH",
                    "INVENTORY-2026-01",
                    null));

    malformed.forEach(
        construction ->
            assertThatThrownBy(construction::run).isInstanceOf(NullPointerException.class));
    assertThatThrownBy(
            () ->
                malformedAvailability(
                    POOL_1,
                    SKU_1,
                    TradeRouteCode.SH_GENERAL_TRADE,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND,
                    TradePlanningQuantityUnit.CASE,
                    new BigDecimal("-0.1"),
                    "HIGH",
                    "INVENTORY-2026-01",
                    DATA_AS_OF_1))
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
  void knownHashVectorMatchesTheIndependentCanonicalProjection() {
    HashInput input = hashInput(hashLines());

    assertThat(SupplyDecisionHashV1.hash(input))
        .isEqualTo("82b48ddc670367b87c5e2a9e15815717997ace341ae2223c42fc6c86bf6bea58")
        .matches("^[0-9a-f]{64}$");
  }

  @Test
  void hashIsIndependentOfLineAndJsonPropertyOrder() throws Exception {
    HashInput reversed = hashInput(hashLines().reversed());
    String naturalJson =
        """
        {
          "schemaVersion": 1,
          "policyVersion": "SUPPLY-DECISION-2026-01",
          "decidedAt": "2026-07-14T10:15:30Z",
          "sourceRouteEvaluationId": "71000000-0000-4000-8000-000000000001",
          "sourceRouteInputHash": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "selectedRouteCode": "NB_BONDED_B2B",
          "inventoryDataAsOf": "2026-07-14T10:14:00Z",
          "lineDecisions": [
            {"quotationLineId":"72000000-0000-4000-8000-000000000001","skuId":"73000000-0000-4000-8000-000000000001","requestedQuantity":"6.500000","quantityUnit":"CASE","allocationMode":"FIXED_POOL","supplyPoolId":"74000000-0000-4000-8000-000000000001","supplyType":"DOMESTIC_ON_HAND"},
            {"quotationLineId":"72000000-0000-4000-8000-000000000002","skuId":"73000000-0000-4000-8000-000000000002","requestedQuantity":"2.000000","quantityUnit":"BOTTLE","allocationMode":"ROUTE_ELIGIBLE_AUTO","supplyPoolId":null,"supplyType":"BONDED_ON_HAND"}
          ]
        }
        """;
    String reorderedJson =
        """
        {
          "lineDecisions": [
            {"supplyType":"BONDED_ON_HAND","supplyPoolId":null,"allocationMode":"ROUTE_ELIGIBLE_AUTO","quantityUnit":"BOTTLE","requestedQuantity":"2.000000","skuId":"73000000-0000-4000-8000-000000000002","quotationLineId":"72000000-0000-4000-8000-000000000002"},
            {"supplyType":"DOMESTIC_ON_HAND","supplyPoolId":"74000000-0000-4000-8000-000000000001","allocationMode":"FIXED_POOL","quantityUnit":"CASE","requestedQuantity":"6.500000","skuId":"73000000-0000-4000-8000-000000000001","quotationLineId":"72000000-0000-4000-8000-000000000001"}
          ],
          "inventoryDataAsOf": "2026-07-14T10:14:00Z",
          "selectedRouteCode": "NB_BONDED_B2B",
          "sourceRouteInputHash": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "sourceRouteEvaluationId": "71000000-0000-4000-8000-000000000001",
          "decidedAt": "2026-07-14T10:15:30Z",
          "policyVersion": "SUPPLY-DECISION-2026-01",
          "schemaVersion": 1
        }
        """;

    String expected = SupplyDecisionHashV1.hash(hashInput(hashLines()));
    assertThat(SupplyDecisionHashV1.hash(reversed)).isEqualTo(expected);
    assertThat(SupplyDecisionHashV1.hash(hashInputFromJson(naturalJson))).isEqualTo(expected);
    assertThat(SupplyDecisionHashV1.hash(hashInputFromJson(reorderedJson))).isEqualTo(expected);
  }

  @Test
  void everyCanonicalMaterialFieldChangesTheHash() {
    HashInput base = hashInput(hashLines());
    LineDecision line = hashLines().getFirst();
    List<HashInput> topLevelChanges =
        List.of(
            copy(
                base,
                2,
                base.policyVersion(),
                base.decidedAt(),
                base.sourceRouteEvaluationId(),
                base.sourceRouteInputHash(),
                base.selectedRouteCode(),
                base.inventoryDataAsOf(),
                base.lineDecisions()),
            copy(
                base,
                base.schemaVersion(),
                "SUPPLY-DECISION-2026-02",
                base.decidedAt(),
                base.sourceRouteEvaluationId(),
                base.sourceRouteInputHash(),
                base.selectedRouteCode(),
                base.inventoryDataAsOf(),
                base.lineDecisions()),
            copy(
                base,
                base.schemaVersion(),
                base.policyVersion(),
                base.decidedAt().plusSeconds(1),
                base.sourceRouteEvaluationId(),
                base.sourceRouteInputHash(),
                base.selectedRouteCode(),
                base.inventoryDataAsOf(),
                base.lineDecisions()),
            copy(
                base,
                base.schemaVersion(),
                base.policyVersion(),
                base.decidedAt(),
                uuid("71000000-0000-4000-8000-000000000009"),
                base.sourceRouteInputHash(),
                base.selectedRouteCode(),
                base.inventoryDataAsOf(),
                base.lineDecisions()),
            copy(
                base,
                base.schemaVersion(),
                base.policyVersion(),
                base.decidedAt(),
                base.sourceRouteEvaluationId(),
                "f".repeat(64),
                base.selectedRouteCode(),
                base.inventoryDataAsOf(),
                base.lineDecisions()),
            copy(
                base,
                base.schemaVersion(),
                base.policyVersion(),
                base.decidedAt(),
                base.sourceRouteEvaluationId(),
                base.sourceRouteInputHash(),
                TradeRouteCode.HK_FREE_TRADE,
                base.inventoryDataAsOf(),
                base.lineDecisions()),
            copy(
                base,
                base.schemaVersion(),
                base.policyVersion(),
                base.decidedAt(),
                base.sourceRouteEvaluationId(),
                base.sourceRouteInputHash(),
                base.selectedRouteCode(),
                base.inventoryDataAsOf().plusSeconds(1),
                base.lineDecisions()));
    List<LineDecision> changedLines =
        List.of(
            new LineDecision(
                LINE_2,
                line.skuId(),
                line.requestedQuantity(),
                line.quantityUnit(),
                line.allocationMode(),
                line.supplyPoolId(),
                line.supplyType()),
            new LineDecision(
                line.quotationLineId(),
                SKU_2,
                line.requestedQuantity(),
                line.quantityUnit(),
                line.allocationMode(),
                line.supplyPoolId(),
                line.supplyType()),
            new LineDecision(
                line.quotationLineId(),
                line.skuId(),
                new BigDecimal("6.6"),
                line.quantityUnit(),
                line.allocationMode(),
                line.supplyPoolId(),
                line.supplyType()),
            new LineDecision(
                line.quotationLineId(),
                line.skuId(),
                line.requestedQuantity(),
                TradePlanningQuantityUnit.BOTTLE,
                line.allocationMode(),
                line.supplyPoolId(),
                line.supplyType()),
            new LineDecision(
                line.quotationLineId(),
                line.skuId(),
                line.requestedQuantity(),
                line.quantityUnit(),
                SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
                line.supplyPoolId(),
                line.supplyType()),
            new LineDecision(
                line.quotationLineId(),
                line.skuId(),
                line.requestedQuantity(),
                line.quantityUnit(),
                line.allocationMode(),
                POOL_2,
                line.supplyType()),
            new LineDecision(
                line.quotationLineId(),
                line.skuId(),
                line.requestedQuantity(),
                line.quantityUnit(),
                line.allocationMode(),
                line.supplyPoolId(),
                TradePlanningSupplyType.HONG_KONG_ON_HAND));
    String expected = SupplyDecisionHashV1.hash(base);

    topLevelChanges.forEach(
        changed -> assertThat(SupplyDecisionHashV1.hash(changed)).isNotEqualTo(expected));
    changedLines.forEach(
        changed ->
            assertThat(SupplyDecisionHashV1.hash(hashInput(List.of(changed, hashLines().get(1)))))
                .isNotEqualTo(expected));
  }

  private static LineInput line(
      UUID lineId,
      UUID skuId,
      String requestedQuantity,
      TradePlanningQuantityUnit unit,
      UUID preferredPoolId) {
    return new LineInput(
        lineId, skuId, new BigDecimal(requestedQuantity), unit, BigDecimal.ONE, preferredPoolId);
  }

  private static AvailabilityInput availability(
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

  private static AvailabilityInput malformedAvailability(
      UUID poolId,
      UUID skuId,
      TradeRouteCode routeCode,
      TradePlanningSupplyType supplyType,
      TradePlanningQuantityUnit unit,
      BigDecimal availableQuantity,
      String confidence,
      String inventoryPolicyVersion,
      Instant dataAsOf) {
    return new AvailabilityInput(
        poolId,
        skuId,
        routeCode,
        supplyType,
        unit,
        availableQuantity,
        null,
        confidence,
        inventoryPolicyVersion,
        dataAsOf);
  }

  private static HashInput hashInput(List<LineDecision> lines) {
    return new HashInput(
        1,
        "SUPPLY-DECISION-2026-01",
        DECIDED_AT,
        EVALUATION_ID,
        SOURCE_INPUT_HASH,
        TradeRouteCode.NB_BONDED_B2B,
        DATA_AS_OF_2,
        lines);
  }

  private static List<LineDecision> hashLines() {
    return List.of(
        new LineDecision(
            LINE_1,
            SKU_1,
            new BigDecimal("6.5"),
            TradePlanningQuantityUnit.CASE,
            SupplyAllocationMode.FIXED_POOL,
            POOL_1,
            TradePlanningSupplyType.DOMESTIC_ON_HAND),
        new LineDecision(
            LINE_2,
            SKU_2,
            new BigDecimal("2"),
            TradePlanningQuantityUnit.BOTTLE,
            SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
            null,
            TradePlanningSupplyType.BONDED_ON_HAND));
  }

  private static HashInput hashInputFromJson(String json) throws Exception {
    JsonNode root = JSON.readTree(json);
    List<LineDecision> lines = new ArrayList<>();
    root.get("lineDecisions")
        .forEach(
            line ->
                lines.add(
                    new LineDecision(
                        uuid(line.get("quotationLineId").asText()),
                        uuid(line.get("skuId").asText()),
                        new BigDecimal(line.get("requestedQuantity").asText()),
                        TradePlanningQuantityUnit.valueOf(line.get("quantityUnit").asText()),
                        SupplyAllocationMode.valueOf(line.get("allocationMode").asText()),
                        line.get("supplyPoolId").isNull()
                            ? null
                            : uuid(line.get("supplyPoolId").asText()),
                        TradePlanningSupplyType.valueOf(line.get("supplyType").asText()))));
    return new HashInput(
        root.get("schemaVersion").asInt(),
        root.get("policyVersion").asText(),
        Instant.parse(root.get("decidedAt").asText()),
        uuid(root.get("sourceRouteEvaluationId").asText()),
        root.get("sourceRouteInputHash").asText(),
        TradeRouteCode.valueOf(root.get("selectedRouteCode").asText()),
        Instant.parse(root.get("inventoryDataAsOf").asText()),
        lines);
  }

  private static HashInput copy(
      HashInput ignored,
      int schemaVersion,
      String policyVersion,
      Instant decidedAt,
      UUID sourceRouteEvaluationId,
      String sourceRouteInputHash,
      TradeRouteCode selectedRouteCode,
      Instant inventoryDataAsOf,
      List<LineDecision> lineDecisions) {
    return new HashInput(
        schemaVersion,
        policyVersion,
        decidedAt,
        sourceRouteEvaluationId,
        sourceRouteInputHash,
        selectedRouteCode,
        inventoryDataAsOf,
        lineDecisions);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
