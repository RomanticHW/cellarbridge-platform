package com.rom.cellarbridge.tradeplanning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
  public static final String POLICY_VERSION = "SUPPLY-DECISION-2026-01";
  private static final Pattern HASH_FORMAT = Pattern.compile("^[0-9a-f]{64}$");

  public SupplyDecisionSnapshot {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Supply decision snapshot schemaVersion must be 1");
    }
    policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
    if (policyVersion.isBlank() || policyVersion.length() > 80) {
      throw new IllegalArgumentException("policyVersion must contain 1 to 80 characters");
    }
    decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    requireMicroseconds(decidedAt, "decidedAt");
    sourceRouteEvaluationId =
        Objects.requireNonNull(sourceRouteEvaluationId, "sourceRouteEvaluationId");
    sourceRouteInputHash = Objects.requireNonNull(sourceRouteInputHash, "sourceRouteInputHash");
    if (!HASH_FORMAT.matcher(sourceRouteInputHash).matches()) {
      throw new IllegalArgumentException("sourceRouteInputHash must be lowercase 64-character hex");
    }
    selectedRouteCode = Objects.requireNonNull(selectedRouteCode, "selectedRouteCode");
    inventoryDataAsOf = Objects.requireNonNull(inventoryDataAsOf, "inventoryDataAsOf");
    requireMicroseconds(inventoryDataAsOf, "inventoryDataAsOf");
    decisionHash = Objects.requireNonNull(decisionHash, "decisionHash");
    if (!HASH_FORMAT.matcher(decisionHash).matches()) {
      throw new IllegalArgumentException("decisionHash must be lowercase 64-character hex");
    }
    lineDecisions = ordered(lineDecisions);
    String expectedHash =
        SupplyDecisionHashV1.hash(
            new SupplyDecisionHashV1.HashInput(
                schemaVersion,
                policyVersion,
                decidedAt,
                sourceRouteEvaluationId,
                sourceRouteInputHash,
                selectedRouteCode,
                inventoryDataAsOf,
                lineDecisions));
    if (!decisionHash.equals(expectedHash)) {
      throw new IllegalArgumentException("decisionHash does not match snapshot content");
    }
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
    for (int index = 1; index < ordered.size(); index++) {
      if (ordered.get(index - 1).quotationLineId().equals(ordered.get(index).quotationLineId())) {
        throw new IllegalArgumentException("quotationLineId must be unique");
      }
    }
    return ordered;
  }

  private static void requireMicroseconds(Instant value, String field) {
    if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
      throw new IllegalArgumentException(field + " must use microsecond precision");
    }
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
      requestedQuantity =
          Objects.requireNonNull(requestedQuantity, "requestedQuantity")
              .setScale(6, RoundingMode.UNNECESSARY);
      if (requestedQuantity.signum() <= 0
          || requestedQuantity.precision() - requestedQuantity.scale() > 13) {
        throw new IllegalArgumentException("requestedQuantity is outside numeric(19,6)");
      }
      quantityUnit = Objects.requireNonNull(quantityUnit, "quantityUnit");
      allocationMode = Objects.requireNonNull(allocationMode, "allocationMode");
      if (allocationMode == SupplyAllocationMode.FIXED_POOL && supplyPoolId == null) {
        throw new IllegalArgumentException("FIXED_POOL requires supplyPoolId");
      }
      if (allocationMode == SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO && supplyPoolId != null) {
        throw new IllegalArgumentException("ROUTE_ELIGIBLE_AUTO must not define supplyPoolId");
      }
      supplyType = Objects.requireNonNull(supplyType, "supplyType");
      if (allocationMode == SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO
          && (supplyType == TradePlanningSupplyType.IN_TRANSIT_PRESALE
              || supplyType == TradePlanningSupplyType.OVERSEAS_SOURCING)) {
        throw new IllegalArgumentException("ROUTE_ELIGIBLE_AUTO requires an automatic supplyType");
      }
    }
  }
}
