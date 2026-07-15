package com.rom.cellarbridge.tradeplanning.internal.application;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.rom.cellarbridge.inventory.InventorySupplyQuery.RouteAvailability;
import com.rom.cellarbridge.partner.PartnerEligibilityService.EligibilitySnapshot;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.EvaluationCommand;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.LineDemand;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@JsonPropertyOrder({
  "schemaVersion",
  "routePolicyVersion",
  "supplyDecisionPolicyVersion",
  "evaluationTime",
  "actorId",
  "managerOverrideAllowed",
  "requestedRouteCode",
  "overrideReason",
  "partner",
  "commercial",
  "lines",
  "availability"
})
record RouteEvaluationInputSnapshotV3(
    int schemaVersion,
    String routePolicyVersion,
    String supplyDecisionPolicyVersion,
    Instant evaluationTime,
    UUID actorId,
    boolean managerOverrideAllowed,
    String requestedRouteCode,
    String overrideReason,
    PartnerSnapshot partner,
    CommercialSnapshot commercial,
    List<LineSnapshot> lines,
    List<AvailabilitySnapshot> availability) {

  static final int SCHEMA_VERSION = 3;

  static RouteEvaluationInputSnapshotV3 create(
      Instant evaluationTime,
      EvaluationCommand command,
      EligibilitySnapshot partner,
      List<RouteAvailability> availability) {
    return new RouteEvaluationInputSnapshotV3(
        SCHEMA_VERSION,
        RouteEvaluationPolicy.VERSION,
        SupplyDecisionPolicy.VERSION,
        evaluationTime,
        command.actorId(),
        command.managerOverrideAllowed(),
        command.requestedRouteCode() == null ? null : command.requestedRouteCode().name(),
        command.overrideReason() == null ? null : command.overrideReason().strip(),
        new PartnerSnapshot(
            partner.partnerId(),
            partner.sourceVersion(),
            partner.capturedAt(),
            partner.routeCodes().stream().sorted().toList(),
            partner.serviceRegions().stream().sorted().toList(),
            partner.currencies().stream().sorted().toList(),
            partner.paymentTermDays()),
        new CommercialSnapshot(
            command.currency(),
            command.destinationCountryCode(),
            command.requestedDeliveryDate(),
            command.paymentTermDays()),
        command.lines().stream()
            .sorted(Comparator.comparing(LineDemand::quotationLineId))
            .map(LineSnapshot::from)
            .toList(),
        availability.stream()
            .map(AvailabilitySnapshot::from)
            .sorted(
                Comparator.comparing(AvailabilitySnapshot::routeCode)
                    .thenComparing(AvailabilitySnapshot::skuId)
                    .thenComparing(AvailabilitySnapshot::quantityUnit)
                    .thenComparing(AvailabilitySnapshot::supplyType)
                    .thenComparing(AvailabilitySnapshot::supplyPoolId))
            .toList());
  }

  @JsonPropertyOrder({
    "partnerId",
    "sourceVersion",
    "capturedAt",
    "routeCodes",
    "serviceRegions",
    "currencies",
    "paymentTermDays"
  })
  record PartnerSnapshot(
      UUID partnerId,
      int sourceVersion,
      Instant capturedAt,
      List<String> routeCodes,
      List<String> serviceRegions,
      List<String> currencies,
      int paymentTermDays) {}

  @JsonPropertyOrder({
    "currency",
    "destinationCountryCode",
    "requestedDeliveryDate",
    "paymentTermDays"
  })
  record CommercialSnapshot(
      String currency,
      String destinationCountryCode,
      LocalDate requestedDeliveryDate,
      int paymentTermDays) {}

  @JsonPropertyOrder({
    "quotationLineId",
    "skuId",
    "requestedQuantity",
    "quantityUnit",
    "moqCaseEquivalentQuantity",
    "preferredSupplyPoolId"
  })
  record LineSnapshot(
      UUID quotationLineId,
      UUID skuId,
      String requestedQuantity,
      String quantityUnit,
      String moqCaseEquivalentQuantity,
      UUID preferredSupplyPoolId) {

    static LineSnapshot from(LineDemand line) {
      return new LineSnapshot(
          line.quotationLineId(),
          line.skuId(),
          quantity(line.requestedQuantity()),
          line.quantityUnit().name(),
          quantity(line.moqCaseEquivalentQuantity()),
          line.preferredSupplyPoolId());
    }
  }

  @JsonPropertyOrder({
    "supplyPoolId",
    "skuId",
    "routeCode",
    "supplyType",
    "quantityUnit",
    "availableQuantity",
    "availableFrom",
    "confidence",
    "inventoryPolicyVersion",
    "dataAsOf"
  })
  record AvailabilitySnapshot(
      UUID supplyPoolId,
      UUID skuId,
      String routeCode,
      String supplyType,
      String quantityUnit,
      String availableQuantity,
      Instant availableFrom,
      String confidence,
      String inventoryPolicyVersion,
      Instant dataAsOf) {

    static AvailabilitySnapshot from(RouteAvailability item) {
      return new AvailabilitySnapshot(
          item.supplyPoolId(),
          item.skuId(),
          item.routeCode(),
          item.supplyType().name(),
          item.quantityUnit().name(),
          quantity(item.availableQuantity()),
          item.availableFrom(),
          item.confidence(),
          item.policyVersion(),
          item.dataAsOf());
    }
  }

  private static String quantity(java.math.BigDecimal value) {
    return value.setScale(6, RoundingMode.UNNECESSARY).toPlainString();
  }
}
