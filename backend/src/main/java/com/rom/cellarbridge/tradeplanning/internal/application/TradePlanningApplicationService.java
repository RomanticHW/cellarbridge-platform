package com.rom.cellarbridge.tradeplanning.internal.application;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.CatalogSearchQuery;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventorySupplyQuery;
import com.rom.cellarbridge.inventory.InventorySupplyQuery.RouteAvailability;
import com.rom.cellarbridge.partner.PartnerEligibilityException;
import com.rom.cellarbridge.partner.PartnerEligibilityService;
import com.rom.cellarbridge.partner.PartnerEligibilityService.EligibilitySnapshot;
import com.rom.cellarbridge.tradeplanning.TradePlanningException;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningService;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteOverride;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.EvaluationOutcome;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.Input;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.AvailabilityInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.LineInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TradePlanningApplicationService implements TradePlanningService {

  private final PartnerEligibilityService partnerEligibilityService;
  private final CatalogSearchQuery catalogSearchQuery;
  private final InventorySupplyQuery inventorySupplyQuery;
  private final TradePlanningRepository repository;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  TradePlanningApplicationService(
      PartnerEligibilityService partnerEligibilityService,
      CatalogSearchQuery catalogSearchQuery,
      InventorySupplyQuery inventorySupplyQuery,
      TradePlanningRepository repository,
      JsonMapper jsonMapper,
      Clock clock) {
    this.partnerEligibilityService = partnerEligibilityService;
    this.catalogSearchQuery = catalogSearchQuery;
    this.inventorySupplyQuery = inventorySupplyQuery;
    this.repository = repository;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Override
  @Transactional
  public RouteEvaluation evaluate(TenantId tenantId, EvaluationCommand command) {
    validate(command);
    Instant evaluationTime = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID evaluationId = UUID.randomUUID();
    EligibilitySnapshot partner;
    try {
      partner = partnerEligibilityService.requireActive(tenantId, command.partnerId());
    } catch (PartnerEligibilityException exception) {
      String code =
          exception.code().equals("RESOURCE_NOT_FOUND")
              ? "RESOURCE_NOT_FOUND"
              : "PARTNER_NOT_ACTIVE";
      throw new TradePlanningException(code, exception.getMessage());
    }
    if (!partner.currencies().contains(command.currency())) {
      throw new TradePlanningException(
          "QUOTE_ROUTE_NOT_ELIGIBLE", "Partner is not eligible for the quotation currency");
    }
    command.lines().forEach(line -> requireActiveSku(tenantId, line.skuId()));
    Set<UUID> skuIds =
        command.lines().stream().map(LineDemand::skuId).collect(Collectors.toUnmodifiableSet());
    List<RouteAvailability> availability =
        inventorySupplyQuery.findRouteAvailability(tenantId, skuIds, evaluationTime);
    Input input = input(command, partner, availability, evaluationTime);
    String inputSummary =
        serialize(
            RouteEvaluationInputSnapshotV3.create(evaluationTime, command, partner, availability));
    String inputHash = sha256(inputSummary);
    EvaluationOutcome outcome = RouteEvaluationPolicy.evaluate(input);
    List<RouteCandidate> candidates = outcome.candidates();
    TradeRouteCode recommended = RouteEvaluationPolicy.recommend(candidates);
    if (recommended == null) {
      throw new TradePlanningException(
          "QUOTE_HAS_NO_ELIGIBLE_ROUTE", "No configured route satisfies the quotation inputs");
    }
    TradeRouteCode selected =
        command.requestedRouteCode() == null ? recommended : command.requestedRouteCode();
    RouteCandidate selectedCandidate =
        candidates.stream()
            .filter(candidate -> candidate.routeCode() == selected)
            .findFirst()
            .orElseThrow(
                () ->
                    new TradePlanningException(
                        "QUOTE_ROUTE_NOT_ELIGIBLE", "Requested route is not configured"));
    if (selectedCandidate.eligibility() != Eligibility.ELIGIBLE) {
      throw new TradePlanningException(
          "QUOTE_ROUTE_NOT_ELIGIBLE", "Requested route does not satisfy hard constraints");
    }
    RouteOverride override = override(command, recommended, selected, evaluationTime);
    SupplyDecisionPolicy.Result selectedSupplyDecision = outcome.supplyDecisions().get(selected);
    var supplyDecision = selectedSupplyDecision.snapshot(evaluationTime, evaluationId, inputHash);
    RouteEvaluation evaluation =
        new RouteEvaluation(
            evaluationId,
            RouteEvaluationPolicy.VERSION,
            evaluationTime,
            inputHash,
            candidates,
            recommended,
            selected,
            override,
            supplyDecision);
    repository.save(tenantId, command.partnerId(), command.actorId(), inputSummary, evaluation);
    return evaluation;
  }

  @Override
  @Transactional(readOnly = true)
  public RouteEvaluation get(TenantId tenantId, UUID evaluationId) {
    return repository
        .find(tenantId, evaluationId)
        .orElseThrow(
            () ->
                new TradePlanningException(
                    "RESOURCE_NOT_FOUND", "Route evaluation is not available"));
  }

  private void requireActiveSku(TenantId tenantId, UUID skuId) {
    if (catalogSearchQuery.get(tenantId, skuId).sku().status() != CatalogItemStatus.ACTIVE) {
      throw new TradePlanningException("SKU_NOT_ACTIVE", "Quotation contains an inactive SKU");
    }
  }

  private Input input(
      EvaluationCommand command,
      EligibilitySnapshot partner,
      List<RouteAvailability> availability,
      Instant evaluationTime) {
    Set<TradeRouteCode> partnerRoutes =
        partner.routeCodes().stream()
            .map(TradeRouteCode::valueOf)
            .collect(Collectors.toUnmodifiableSet());
    return new Input(
        partnerRoutes,
        command.currency(),
        command.destinationCountryCode(),
        command.requestedDeliveryDate(),
        command.paymentTermDays(),
        evaluationTime.atZone(ZoneOffset.UTC).toLocalDate(),
        command.lines().stream()
            .map(
                line ->
                    new LineInput(
                        line.quotationLineId(),
                        line.skuId(),
                        line.requestedQuantity(),
                        line.quantityUnit(),
                        line.moqCaseEquivalentQuantity(),
                        line.preferredSupplyPoolId()))
            .toList(),
        availability.stream()
            .map(
                item ->
                    new AvailabilityInput(
                        item.supplyPoolId(),
                        item.skuId(),
                        TradeRouteCode.valueOf(item.routeCode()),
                        TradePlanningSupplyType.valueOf(item.supplyType().name()),
                        TradePlanningQuantityUnit.valueOf(item.quantityUnit().name()),
                        item.availableQuantity(),
                        item.availableFrom(),
                        item.confidence(),
                        item.policyVersion(),
                        item.dataAsOf()))
            .toList());
  }

  private RouteOverride override(
      EvaluationCommand command, TradeRouteCode recommended, TradeRouteCode selected, Instant now) {
    if (selected == recommended) {
      return null;
    }
    if (!command.managerOverrideAllowed()) {
      throw new TradePlanningException(
          "ACCESS_DENIED", "Only an authorized manager may override the recommended route");
    }
    if (command.overrideReason() == null || command.overrideReason().strip().length() < 5) {
      throw new TradePlanningException(
          "QUOTE_ROUTE_OVERRIDE_REASON_REQUIRED", "A route override reason is required");
    }
    return new RouteOverride(command.overrideReason().strip(), command.actorId(), now, recommended);
  }

  private String serialize(RouteEvaluationInputSnapshotV3 snapshot) {
    try {
      return jsonMapper.writeValueAsString(snapshot);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize route evaluation input", exception);
    }
  }

  private static void validate(EvaluationCommand command) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(command.partnerId(), "partnerId");
    Objects.requireNonNull(command.actorId(), "actorId");
    if (command.currency() == null
        || !command.currency().matches("^[A-Z]{3}$")
        || command.destinationCountryCode() == null
        || !command.destinationCountryCode().matches("^[A-Z]{2}$")
        || command.requestedDeliveryDate() == null
        || command.paymentTermDays() < 0
        || command.paymentTermDays() > 180
        || command.lines().isEmpty()
        || command.lines().size() > 50) {
      throw new TradePlanningException("VALIDATION_FAILED", "Route evaluation input is invalid");
    }
    Set<UUID> unique =
        command.lines().stream().map(LineDemand::skuId).collect(Collectors.toUnmodifiableSet());
    if (unique.size() != command.lines().size()) {
      throw new TradePlanningException(
          "QUOTE_LINE_DUPLICATE_SKU", "Quotation SKU lines must be unique");
    }
    Set<UUID> lineIds =
        command.lines().stream()
            .map(LineDemand::quotationLineId)
            .collect(Collectors.toUnmodifiableSet());
    if (lineIds.size() != command.lines().size()) {
      throw new TradePlanningException(
          "VALIDATION_FAILED", "Quotation line identifiers must be unique");
    }
  }

  private static String sha256(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
