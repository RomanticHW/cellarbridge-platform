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
import com.rom.cellarbridge.tradeplanning.TradePlanningService;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteOverride;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.AvailabilityInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.Input;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.LineInput;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        inventorySupplyQuery.findRouteAvailability(tenantId, skuIds);
    Input input = input(command, partner, availability);
    List<RouteCandidate> candidates = RouteEvaluationPolicy.evaluate(input);
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
    Instant now = clock.instant();
    RouteOverride override = override(command, recommended, selected, now);
    String inputSummary = inputSummary(command, partner, availability);
    RouteEvaluation evaluation =
        new RouteEvaluation(
            UUID.randomUUID(),
            RouteEvaluationPolicy.VERSION,
            now,
            sha256(inputSummary),
            candidates,
            recommended,
            selected,
            override);
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
      List<RouteAvailability> availability) {
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
        clock.instant().atZone(ZoneOffset.UTC).toLocalDate(),
        command.lines().stream()
            .map(line -> new LineInput(line.skuId(), line.quantity(), line.preferredSupplyPoolId()))
            .toList(),
        availability.stream()
            .map(
                item ->
                    new AvailabilityInput(
                        item.supplyPoolId(),
                        item.skuId(),
                        TradeRouteCode.valueOf(item.routeCode()),
                        item.availableQuantity(),
                        item.confidence()))
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

  private String inputSummary(
      EvaluationCommand command,
      EligibilitySnapshot partner,
      List<RouteAvailability> availability) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("schemaVersion", 1);
    summary.put("partnerId", command.partnerId());
    summary.put("partnerEligibilityVersion", partner.sourceVersion());
    summary.put("currency", command.currency());
    summary.put("destinationCountryCode", command.destinationCountryCode());
    summary.put("requestedDeliveryDate", command.requestedDeliveryDate());
    summary.put("paymentTermDays", command.paymentTermDays());
    summary.put("lines", command.lines());
    summary.put("availability", availability);
    try {
      return jsonMapper.writeValueAsString(summary);
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
    if (command.lines().stream()
        .anyMatch(
            line -> line.quantity() == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0)) {
      throw new TradePlanningException("VALIDATION_FAILED", "Line quantities must be positive");
    }
  }

  private static String sha256(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)))
          .toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
