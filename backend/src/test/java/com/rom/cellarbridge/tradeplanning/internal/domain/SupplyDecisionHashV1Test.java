package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionHashV1;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionHashV1.HashInput;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot.LineDecision;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SupplyDecisionHashV1Test extends SupplyDecisionTestFixtures {

  private static final JsonMapper JSON = JsonMapper.builder().build();

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
    String expected = SupplyDecisionHashV1.hash(base);

    for (HashField field : HashField.values()) {
      assertThat(SupplyDecisionHashV1.hash(change(base, field)))
          .as(field.name())
          .isNotEqualTo(expected);
    }
    for (LineField field : LineField.values()) {
      LineDecision changed = change(hashLines().getFirst(), field);
      assertThat(SupplyDecisionHashV1.hash(hashInput(List.of(changed, hashLines().get(1)))))
          .as(field.name())
          .isNotEqualTo(expected);
    }
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

  private static HashInput change(HashInput input, HashField field) {
    return new HashInput(
        field == HashField.SCHEMA_VERSION ? 2 : input.schemaVersion(),
        field == HashField.POLICY_VERSION ? "SUPPLY-DECISION-2026-02" : input.policyVersion(),
        field == HashField.DECIDED_AT ? input.decidedAt().plusSeconds(1) : input.decidedAt(),
        field == HashField.SOURCE_ROUTE_EVALUATION_ID
            ? uuid("71000000-0000-4000-8000-000000000009")
            : input.sourceRouteEvaluationId(),
        field == HashField.SOURCE_ROUTE_INPUT_HASH ? "f".repeat(64) : input.sourceRouteInputHash(),
        field == HashField.SELECTED_ROUTE_CODE
            ? TradeRouteCode.HK_FREE_TRADE
            : input.selectedRouteCode(),
        field == HashField.INVENTORY_DATA_AS_OF
            ? input.inventoryDataAsOf().plusSeconds(1)
            : input.inventoryDataAsOf(),
        input.lineDecisions());
  }

  private static LineDecision change(LineDecision line, LineField field) {
    return new LineDecision(
        field == LineField.QUOTATION_LINE_ID ? LINE_2 : line.quotationLineId(),
        field == LineField.SKU_ID ? SKU_2 : line.skuId(),
        field == LineField.REQUESTED_QUANTITY ? new BigDecimal("6.6") : line.requestedQuantity(),
        field == LineField.QUANTITY_UNIT ? TradePlanningQuantityUnit.BOTTLE : line.quantityUnit(),
        field == LineField.ALLOCATION_MODE
            ? SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO
            : line.allocationMode(),
        field == LineField.ALLOCATION_MODE
            ? null
            : field == LineField.SUPPLY_POOL_ID ? POOL_2 : line.supplyPoolId(),
        field == LineField.SUPPLY_TYPE
            ? TradePlanningSupplyType.HONG_KONG_ON_HAND
            : line.supplyType());
  }

  private enum HashField {
    SCHEMA_VERSION,
    POLICY_VERSION,
    DECIDED_AT,
    SOURCE_ROUTE_EVALUATION_ID,
    SOURCE_ROUTE_INPUT_HASH,
    SELECTED_ROUTE_CODE,
    INVENTORY_DATA_AS_OF
  }

  private enum LineField {
    QUOTATION_LINE_ID,
    SKU_ID,
    REQUESTED_QUANTITY,
    QUANTITY_UNIT,
    ALLOCATION_MODE,
    SUPPLY_POOL_ID,
    SUPPLY_TYPE
  }
}
