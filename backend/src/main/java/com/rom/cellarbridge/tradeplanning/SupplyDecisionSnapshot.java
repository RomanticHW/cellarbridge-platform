package com.rom.cellarbridge.tradeplanning;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable route-bound supply decision evidence. It is not an inventory reservation. */
public record SupplyDecisionSnapshot(
    int schemaVersion,
    String policyVersion,
    Instant decidedAt,
    UUID sourceRouteEvaluationId,
    String sourceRouteInputHash,
    TradeRouteCode selectedRouteCode,
    Instant inventoryDataAsOf,
    String decisionHash,
    List<LineDecision> lineDecisions) {

  public static final int SCHEMA_VERSION = 1;
  private static final Pattern HASH_FORMAT = Pattern.compile("^[0-9a-f]{64}$");

  public SupplyDecisionSnapshot {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Supply decision snapshot schemaVersion must be 1");
    }
    policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
    decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    sourceRouteEvaluationId =
        Objects.requireNonNull(sourceRouteEvaluationId, "sourceRouteEvaluationId");
    sourceRouteInputHash = Objects.requireNonNull(sourceRouteInputHash, "sourceRouteInputHash");
    selectedRouteCode = Objects.requireNonNull(selectedRouteCode, "selectedRouteCode");
    inventoryDataAsOf = Objects.requireNonNull(inventoryDataAsOf, "inventoryDataAsOf");
    decisionHash = Objects.requireNonNull(decisionHash, "decisionHash");
    if (!HASH_FORMAT.matcher(decisionHash).matches()) {
      throw new IllegalArgumentException("decisionHash must be lowercase 64-character hex");
    }
    lineDecisions = ordered(lineDecisions);
  }

  public static SupplyDecisionSnapshot create(
      String policyVersion,
      Instant decidedAt,
      UUID sourceRouteEvaluationId,
      String sourceRouteInputHash,
      TradeRouteCode selectedRouteCode,
      Instant inventoryDataAsOf,
      List<LineDecision> lineDecisions) {
    List<LineDecision> orderedLines = ordered(lineDecisions);
    SupplyDecisionHashV1.HashInput hashInput =
        new SupplyDecisionHashV1.HashInput(
            SCHEMA_VERSION,
            policyVersion,
            decidedAt,
            sourceRouteEvaluationId,
            sourceRouteInputHash,
            selectedRouteCode,
            inventoryDataAsOf,
            orderedLines);
    return new SupplyDecisionSnapshot(
        SCHEMA_VERSION,
        policyVersion,
        decidedAt,
        sourceRouteEvaluationId,
        sourceRouteInputHash,
        selectedRouteCode,
        inventoryDataAsOf,
        SupplyDecisionHashV1.hash(hashInput),
        orderedLines);
  }

  private static List<LineDecision> ordered(List<LineDecision> lineDecisions) {
    Objects.requireNonNull(lineDecisions, "lineDecisions");
    List<LineDecision> ordered =
        lineDecisions.stream()
            .map(line -> Objects.requireNonNull(line, "lineDecision"))
            .sorted(Comparator.comparing(LineDecision::quotationLineId))
            .toList();
    if (ordered.isEmpty()) {
      throw new IllegalArgumentException("lineDecisions must not be empty");
    }
    return ordered;
  }

  public record LineDecision(
      UUID quotationLineId,
      UUID skuId,
      BigDecimal requestedQuantity,
      TradePlanningQuantityUnit quantityUnit,
      SupplyAllocationMode allocationMode,
      UUID supplyPoolId,
      TradePlanningSupplyType supplyType) {

    public LineDecision {
      quotationLineId = Objects.requireNonNull(quotationLineId, "quotationLineId");
      skuId = Objects.requireNonNull(skuId, "skuId");
      requestedQuantity = Objects.requireNonNull(requestedQuantity, "requestedQuantity");
      if (requestedQuantity.signum() <= 0) {
        throw new IllegalArgumentException("requestedQuantity must be positive");
      }
      quantityUnit = Objects.requireNonNull(quantityUnit, "quantityUnit");
      allocationMode = Objects.requireNonNull(allocationMode, "allocationMode");
      supplyType = Objects.requireNonNull(supplyType, "supplyType");
    }
  }
}
