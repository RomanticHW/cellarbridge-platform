package com.rom.cellarbridge.quotation.internal.application;

import com.rom.cellarbridge.catalog.CatalogItemStatus;
import com.rom.cellarbridge.catalog.CatalogQueryException;
import com.rom.cellarbridge.catalog.CatalogSearchQuery;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.CatalogSearchItem;
import com.rom.cellarbridge.catalog.CatalogSearchQuery.CatalogSkuView;
import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.partner.PartnerEligibilityException;
import com.rom.cellarbridge.partner.PartnerEligibilityService;
import com.rom.cellarbridge.partner.PartnerEligibilityService.AddressSnapshot;
import com.rom.cellarbridge.partner.PartnerEligibilityService.EligibilitySnapshot;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Address;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.ApprovalDecision;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Decision;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.DraftTerms;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.PartnerSnapshot;
import com.rom.cellarbridge.quotation.internal.domain.QuotationApprovalPolicy;
import com.rom.cellarbridge.quotation.internal.domain.QuotationApprovalPolicy.Requirement;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.LineDraft;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PriceReference;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricedLine;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricingResult;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.SkuSnapshot;
import com.rom.cellarbridge.quotation.internal.domain.QuotationProblem;
import com.rom.cellarbridge.tradeplanning.TradePlanningException;
import com.rom.cellarbridge.tradeplanning.TradePlanningService;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.EvaluationCommand;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.LineDemand;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotationApplicationService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  static final String TERMS_VERSION = "TERMS-2026-01";
  private static final Duration PORTAL_READ_RETENTION = Duration.ofDays(30);
  private final QuotationRepository repository;
  private final PartnerEligibilityService partnerEligibilityService;
  private final CatalogSearchQuery catalogSearchQuery;
  private final TradePlanningService tradePlanningService;
  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorizationService;
  private final Clock clock;

  QuotationApplicationService(
      QuotationRepository repository,
      PartnerEligibilityService partnerEligibilityService,
      CatalogSearchQuery catalogSearchQuery,
      TradePlanningService tradePlanningService,
      TenantContextHolder contextHolder,
      AuthorizationService authorizationService,
      Clock clock) {
    this.repository = repository;
    this.partnerEligibilityService = partnerEligibilityService;
    this.catalogSearchQuery = catalogSearchQuery;
    this.tradePlanningService = tradePlanningService;
    this.contextHolder = contextHolder;
    this.authorizationService = authorizationService;
    this.clock = clock;
  }

  @Transactional
  public DetailView create(CreateCommand command) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.QUOTATION_CREATE, context.tenantId());
    Instant now = clock.instant();
    PreparedDraft prepared = prepare(context, command, now);
    QuotationAggregate quotation =
        QuotationAggregate.draft(
            UUID.randomUUID(),
            context.tenantId(),
            repository.nextNumber(context.tenantId(), now),
            command.partnerId(),
            context.userId(),
            prepared.partner(),
            prepared.terms(),
            prepared.pricing(),
            now);
    repository.insert(context.tenantId(), quotation, context.userId());
    return detail(quotation, context);
  }

  @Transactional(readOnly = true)
  public DetailView get(UUID quotationId) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.QUOTATION_READ, context.tenantId());
    QuotationAggregate quotation = requireQuotation(context, quotationId);
    quotation.requireOwnerOrManager(context.userId(), canManage(context));
    return detail(quotation, context);
  }

  @Transactional(readOnly = true)
  public ListView list(ListCommand command) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.QUOTATION_READ, context.tenantId());
    int pageSize = command.pageSize() == null ? 25 : command.pageSize();
    if (pageSize < 1 || pageSize > 100) {
      throw problem(
          HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "pageSize must be between 1 and 100");
    }
    UUID ownerFilter = canManage(context) ? command.ownerId() : context.userId();
    List<SummaryView> items =
        repository
            .list(
                context.tenantId(), command.statuses(), ownerFilter, command.partnerId(), pageSize)
            .stream()
            .map(this::summary)
            .toList();
    return new ListView(items, null, false, pageSize);
  }

  @Transactional
  public DetailView update(UUID quotationId, long expectedVersion, CreateCommand command) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.QUOTATION_CREATE, context.tenantId());
    QuotationAggregate before = requireQuotation(context, quotationId);
    before.requireOwnerOrManager(context.userId(), canManage(context));
    requireExpected(before, expectedVersion);
    if (!before.partnerId().equals(command.partnerId())) {
      throw problem(
          HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Quotation partner cannot change");
    }
    Instant now = clock.instant();
    PreparedDraft prepared = prepare(context, command, now);
    QuotationAggregate after =
        before.replaceDraft(prepared.partner(), prepared.terms(), prepared.pricing(), now);
    repository.saveDraft(context.tenantId(), before, after, expectedVersion, context.userId());
    return detail(after, context);
  }

  @Transactional
  public RouteEvaluation evaluateRoutes(
      UUID quotationId,
      long expectedVersion,
      TradeRouteCode requestedRoute,
      String overrideReason) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.QUOTATION_CREATE, context.tenantId());
    QuotationAggregate before = requireQuotation(context, quotationId);
    before.requireOwnerOrManager(context.userId(), canManage(context));
    requireExpected(before, expectedVersion);
    if (before.status() == QuotationStatus.CHANGES_REQUESTED) {
      throw problem(
          HttpStatus.CONFLICT,
          "INVALID_STATE_TRANSITION",
          "Create a new editable revision before re-evaluating routes");
    }
    RouteEvaluation evaluation =
        evaluate(before, context, requestedRoute, overrideReason, canManage(context));
    BigDecimal routeCharges =
        evaluation.candidates().stream()
            .filter(candidate -> candidate.routeCode() == evaluation.selectedRouteCode())
            .findFirst()
            .orElseThrow()
            .estimatedCharges();
    PricingResult pricing = reprice(before.revision().pricing().lines(), routeCharges);
    QuotationAggregate after = before.applyRoute(evaluation, pricing, clock.instant());
    repository.saveRoute(context.tenantId(), before, after, expectedVersion, context.userId());
    return evaluation;
  }

  @Transactional
  public CommandView submit(UUID quotationId, long expectedVersion) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.QUOTATION_SUBMIT, context.tenantId());
    QuotationAggregate before = requireQuotation(context, quotationId);
    before.requireOwnerOrManager(context.userId(), canManage(context));
    requireExpected(before, expectedVersion);
    Instant now = clock.instant();
    List<Requirement> requirements =
        QuotationApprovalPolicy.evaluate(
            before.revision().pricing(),
            before.revision().terms().paymentTermDays(),
            before.revision().partnerSnapshot().paymentTermDays(),
            now,
            before.revision().terms().expiresAt(),
            before.revision().routeOverrideReason() != null);
    QuotationAggregate.Submission submission = before.submit(requirements, context.userId(), now);
    repository.saveSubmission(
        context.tenantId(),
        before,
        submission.quotation(),
        requirements,
        expectedVersion,
        context.userId());
    return command(submission.quotation(), context);
  }

  @Transactional
  public CommandView decide(
      UUID quotationId, long expectedVersion, Decision decision, String reason) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.QUOTATION_APPROVE, context.tenantId());
    QuotationAggregate before = requireQuotation(context, quotationId);
    requireExpected(before, expectedVersion);
    QuotationAggregate after = before.decide(decision, context.userId(), reason, clock.instant());
    ApprovalDecision recorded = after.approvals().getLast();
    boolean saved =
        repository.saveDecision(
            context.tenantId(), before, after, recorded, expectedVersion, context.userId());
    if (!saved) {
      return command(requireQuotation(context, quotationId), context);
    }
    return command(after, context);
  }

  @Transactional
  public IssueView issue(UUID quotationId, long expectedVersion) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.QUOTATION_ISSUE, context.tenantId());
    QuotationAggregate before = requireQuotation(context, quotationId);
    before.requireOwnerOrManager(context.userId(), canManage(context));
    requireExpected(before, expectedVersion);
    RouteEvaluation current = evaluate(before, context, null, null, false);
    boolean stillEligible =
        current.candidates().stream()
            .anyMatch(
                candidate ->
                    candidate.routeCode() == before.revision().selectedRouteCode()
                        && candidate.eligibility() == TradePlanningService.Eligibility.ELIGIBLE);
    if (!stillEligible) {
      throw problem(
          HttpStatus.CONFLICT,
          "QUOTE_ROUTE_NOT_ELIGIBLE",
          "The selected route no longer satisfies current hard constraints");
    }
    QuotationAggregate after = before.issue(clock.instant());
    byte[] tokenBytes = new byte[32];
    SECURE_RANDOM.nextBytes(tokenBytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    repository.saveIssue(
        context.tenantId(),
        before,
        after,
        expectedVersion,
        context.userId(),
        UUID.randomUUID(),
        sha256(token),
        context.tenantId().value().toString(),
        context.tenantName(),
        TERMS_VERSION,
        after.revision().terms().expiresAt().plus(PORTAL_READ_RETENTION));
    return new IssueView(
        after.id(),
        after.status(),
        after.version(),
        "/portal/quotations/" + token,
        after.revision().terms().expiresAt());
  }

  private RouteEvaluation evaluate(
      QuotationAggregate quotation,
      TenantContext context,
      TradeRouteCode requestedRoute,
      String overrideReason,
      boolean allowOverride) {
    try {
      return tradePlanningService.evaluate(
          context.tenantId(),
          new EvaluationCommand(
              quotation.partnerId(),
              quotation.revision().terms().currency(),
              quotation.revision().terms().deliveryAddress().countryCode(),
              quotation.revision().terms().requestedDeliveryDate(),
              quotation.revision().terms().paymentTermDays(),
              quotation.revision().pricing().lines().stream()
                  .map(
                      line ->
                          new LineDemand(
                              line.sku().skuId(),
                              caseEquivalent(line),
                              line.preferredSupplyPoolId()))
                  .toList(),
              requestedRoute,
              overrideReason,
              context.userId(),
              allowOverride));
    } catch (TradePlanningException exception) {
      HttpStatus status =
          switch (exception.code()) {
            case "ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VALIDATION_FAILED" -> HttpStatus.BAD_REQUEST;
            case "QUOTE_HAS_NO_ELIGIBLE_ROUTE", "QUOTE_ROUTE_OVERRIDE_REASON_REQUIRED" ->
                HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.CONFLICT;
          };
      throw problem(status, exception.code(), exception.getMessage());
    }
  }

  private PreparedDraft prepare(TenantContext context, CreateCommand command, Instant now) {
    validate(command, now);
    EligibilitySnapshot eligibility;
    try {
      eligibility =
          partnerEligibilityService.requireActive(context.tenantId(), command.partnerId());
    } catch (PartnerEligibilityException exception) {
      HttpStatus status =
          exception.code().equals("RESOURCE_NOT_FOUND")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.CONFLICT;
      String code =
          exception.code().equals("RESOURCE_NOT_FOUND")
              ? "RESOURCE_NOT_FOUND"
              : "PARTNER_NOT_ACTIVE";
      throw problem(status, code, exception.getMessage());
    }
    if (!eligibility.currencies().contains(command.currency())) {
      throw problem(
          HttpStatus.CONFLICT,
          "PARTNER_ROUTE_NOT_ELIGIBLE",
          "Partner is not eligible for the quotation currency");
    }
    Address address = address(command.deliveryAddress(), eligibility.billingAddress());
    int paymentTerm =
        command.paymentTermDays() == null
            ? eligibility.paymentTermDays()
            : command.paymentTermDays();
    PartnerSnapshot partner =
        new PartnerSnapshot(
            eligibility.partnerId(),
            eligibility.partnerNumber(),
            eligibility.displayName(),
            eligibility.paymentTermDays(),
            eligibility.sourceVersion(),
            eligibility.capturedAt());
    Set<UUID> skuIds =
        command.lines().stream().map(LineCommand::skuId).collect(Collectors.toUnmodifiableSet());
    Map<UUID, PriceReference> prices =
        repository.currentPrices(context.tenantId(), skuIds, command.currency(), now);
    List<LineDraft> lines = new ArrayList<>();
    for (LineCommand input : command.lines()) {
      CatalogSearchItem item;
      try {
        item = catalogSearchQuery.get(context.tenantId(), input.skuId());
      } catch (CatalogQueryException exception) {
        throw problem(
            HttpStatus.CONFLICT, "SKU_NOT_ACTIVE", "Quotation contains an unavailable SKU");
      }
      if (item.sku().status() != CatalogItemStatus.ACTIVE) {
        throw problem(HttpStatus.CONFLICT, "SKU_NOT_ACTIVE", "Quotation contains an inactive SKU");
      }
      PriceReference price = prices.get(input.skuId());
      if (price == null) {
        throw problem(
            HttpStatus.CONFLICT,
            "QUOTE_PRICE_STALE",
            "No effective price exists for one or more quotation lines");
      }
      String supplyType =
          item.supplies().stream()
              .filter(
                  supply ->
                      input.preferredSupplyPoolId() == null
                          || supply.supplyPoolId().equals(input.preferredSupplyPoolId()))
              .findFirst()
              .map(CatalogSearchQuery.SupplyProjectionView::supplyType)
              .orElse(null);
      lines.add(
          new LineDraft(
              UUID.randomUUID(),
              snapshot(item.sku(), now),
              input.quantity(),
              input.unit(),
              input.preferredSupplyPoolId(),
              supplyType,
              input.discountRate() == null
                  ? BigDecimal.ZERO.setScale(QuotationPricingPolicy.RATE_SCALE)
                  : QuotationPricingPolicy.rate(input.discountRate()),
              input.manualUnitPrice(),
              price));
    }
    PricingResult pricing =
        QuotationPricingPolicy.price(command.currency(), lines, BigDecimal.ZERO);
    DraftTerms terms =
        new DraftTerms(
            command.currency(),
            command.requestedDeliveryDate(),
            command.expiresAt(),
            paymentTerm,
            address);
    return new PreparedDraft(partner, terms, pricing);
  }

  private PricingResult reprice(List<PricedLine> lines, BigDecimal routeCharges) {
    List<LineDraft> drafts =
        lines.stream()
            .map(
                line ->
                    new LineDraft(
                        line.lineId(),
                        line.sku(),
                        line.quantity(),
                        line.unit(),
                        line.preferredSupplyPoolId(),
                        line.supplyType(),
                        line.discountRate(),
                        line.manualPrice() ? manualBasePrice(line) : null,
                        new PriceReference(
                            line.sku().skuId(),
                            line.currency(),
                            line.unit() == QuantityUnit.CASE
                                ? line.listUnitPrice()
                                : line.listUnitPrice()
                                    .multiply(BigDecimal.valueOf(line.sku().unitsPerCase())),
                            line.unit() == QuantityUnit.CASE
                                ? line.costUnitPrice()
                                : line.costUnitPrice()
                                    .multiply(BigDecimal.valueOf(line.sku().unitsPerCase())),
                            line.priceSourceVersion())))
            .toList();
    return QuotationPricingPolicy.price(lines.getFirst().currency(), drafts, routeCharges);
  }

  private static BigDecimal manualBasePrice(PricedLine line) {
    return line.netUnitPrice()
        .divide(BigDecimal.ONE.subtract(line.discountRate()), 8, RoundingMode.HALF_UP);
  }

  private DetailView detail(QuotationAggregate quotation, TenantContext context) {
    RouteEvaluation route =
        quotation.revision().routeEvaluationId() == null
            ? null
            : tradePlanningService.get(
                quotation.tenantId(), quotation.revision().routeEvaluationId());
    boolean sensitive = context.hasPermission(PermissionCode.QUOTATION_READ_COMMERCIAL_SENSITIVE);
    return new DetailView(
        summary(quotation),
        quotation.revision(),
        route,
        sensitive ? quotation.revision().pricing().marginRate() : null,
        allowedActions(quotation, context),
        quotation.approvals(),
        quotation.timeline());
  }

  private SummaryView summary(QuotationAggregate quotation) {
    return new SummaryView(
        quotation.id(),
        quotation.number(),
        quotation.partnerId(),
        quotation.revision().partnerSnapshot().displayName(),
        quotation.currentRevision(),
        quotation.status(),
        quotation.revision().pricing().total(),
        quotation.revision().terms().currency(),
        quotation.revision().selectedRouteCode(),
        quotation.revision().terms().expiresAt(),
        quotation.ownerId(),
        quotation.version(),
        quotation.updatedAt());
  }

  private CommandView command(QuotationAggregate quotation, TenantContext context) {
    return new CommandView(
        quotation.id(),
        quotation.number(),
        quotation.currentRevision(),
        quotation.status(),
        quotation.version(),
        allowedActions(quotation, context));
  }

  private List<String> allowedActions(QuotationAggregate quotation, TenantContext context) {
    List<String> actions = new ArrayList<>();
    actions.add("VIEW");
    boolean ownerOrManager = quotation.ownerId().equals(context.userId()) || canManage(context);
    if (ownerOrManager
        && context.hasPermission(PermissionCode.QUOTATION_CREATE)
        && (quotation.status() == QuotationStatus.DRAFT
            || quotation.status() == QuotationStatus.CHANGES_REQUESTED)) {
      actions.add("EDIT");
      if (quotation.status() == QuotationStatus.DRAFT) {
        actions.add("EVALUATE_ROUTE");
        actions.add("SUBMIT");
      }
    }
    if (context.hasPermission(PermissionCode.QUOTATION_APPROVE)
        && quotation.status() == QuotationStatus.PENDING_APPROVAL
        && !Objects.equals(quotation.submittedById(), context.userId())) {
      actions.add("APPROVE");
    }
    if (ownerOrManager
        && context.hasPermission(PermissionCode.QUOTATION_ISSUE)
        && quotation.status() == QuotationStatus.APPROVED) {
      actions.add("ISSUE");
    }
    return List.copyOf(actions);
  }

  private QuotationAggregate requireQuotation(TenantContext context, UUID quotationId) {
    return repository
        .find(context.tenantId(), quotationId)
        .orElseThrow(
            () ->
                problem(
                    HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND",
                    "Quotation was not found in the current access scope"));
  }

  private static void requireExpected(QuotationAggregate quotation, long expectedVersion) {
    if (quotation.version() != expectedVersion) {
      throw new QuotationProblem(
          HttpStatus.PRECONDITION_FAILED,
          "RESOURCE_VERSION_CONFLICT",
          "Quotation changed after it was loaded",
          quotation.version(),
          quotation.status().name());
    }
  }

  private TenantContext context() {
    return contextHolder.requireCurrent();
  }

  private static boolean canManage(TenantContext context) {
    return context.hasPermission(PermissionCode.QUOTATION_APPROVE);
  }

  private static void validate(CreateCommand command, Instant now) {
    if (command == null
        || command.partnerId() == null
        || command.currency() == null
        || !command.currency().matches("^[A-Z]{3}$")
        || command.requestedDeliveryDate() == null
        || command.expiresAt() == null
        || !now.isBefore(command.expiresAt())
        || command.lines() == null
        || command.lines().isEmpty()
        || command.lines().size() > 50
        || command.paymentTermDays() != null
            && (command.paymentTermDays() < 0 || command.paymentTermDays() > 180)) {
      throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Quotation input is invalid");
    }
  }

  private static SkuSnapshot snapshot(CatalogSkuView sku, Instant now) {
    return new SkuSnapshot(
        sku.id(),
        sku.code(),
        sku.displayName(),
        sku.producerName(),
        sku.regionName(),
        sku.countryCode(),
        sku.category(),
        sku.vintage(),
        sku.volumeMl(),
        sku.unitsPerCase(),
        sku.packageType(),
        sku.version(),
        now);
  }

  private static Address address(AddressCommand requested, AddressSnapshot fallback) {
    if (requested != null) {
      return new Address(
          requested.countryCode(),
          requested.province(),
          requested.city(),
          requested.district(),
          requested.line1(),
          requested.postalCode());
    }
    return new Address(
        fallback.countryCode(),
        fallback.province(),
        fallback.city(),
        fallback.district(),
        fallback.line1(),
        fallback.postalCode());
  }

  private static BigDecimal caseEquivalent(PricedLine line) {
    if (line.unit() == QuantityUnit.CASE) {
      return line.quantity();
    }
    return line.quantity()
        .divide(BigDecimal.valueOf(line.sku().unitsPerCase()), 6, RoundingMode.CEILING);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
          .toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static QuotationProblem problem(HttpStatus status, String code, String message) {
    return new QuotationProblem(status, code, message);
  }

  private record PreparedDraft(PartnerSnapshot partner, DraftTerms terms, PricingResult pricing) {}

  public record AddressCommand(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {}

  public record LineCommand(
      UUID skuId,
      BigDecimal quantity,
      QuantityUnit unit,
      UUID preferredSupplyPoolId,
      BigDecimal discountRate,
      BigDecimal manualUnitPrice) {}

  public record CreateCommand(
      UUID partnerId,
      String currency,
      LocalDate requestedDeliveryDate,
      Instant expiresAt,
      Integer paymentTermDays,
      AddressCommand deliveryAddress,
      List<LineCommand> lines) {

    public CreateCommand {
      lines = lines == null ? List.of() : List.copyOf(lines);
    }
  }

  public record ListCommand(
      Set<QuotationStatus> statuses, UUID ownerId, UUID partnerId, Integer pageSize) {
    public ListCommand {
      statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }
  }

  public record SummaryView(
      UUID id,
      String number,
      UUID partnerId,
      String partnerName,
      int revision,
      QuotationStatus status,
      BigDecimal total,
      String currency,
      TradeRouteCode selectedRouteCode,
      Instant expiresAt,
      UUID ownerId,
      long version,
      Instant updatedAt) {}

  public record ListView(
      List<SummaryView> items, String nextCursor, boolean hasNext, int pageSize) {
    public ListView {
      items = List.copyOf(items);
    }
  }

  public record DetailView(
      SummaryView summary,
      QuotationAggregate.Revision revision,
      RouteEvaluation routeEvaluation,
      BigDecimal estimatedMarginRate,
      List<String> allowedActions,
      List<ApprovalDecision> approvals,
      List<QuotationAggregate.TimelineEntry> timeline) {
    public DetailView {
      allowedActions = List.copyOf(allowedActions);
      approvals = List.copyOf(approvals);
      timeline = List.copyOf(timeline);
    }
  }

  public record CommandView(
      UUID quotationId,
      String number,
      int revision,
      QuotationStatus status,
      long version,
      List<String> allowedActions) {
    public CommandView {
      allowedActions = List.copyOf(allowedActions);
    }
  }

  public record IssueView(
      UUID quotationId,
      QuotationStatus status,
      long version,
      String portalUrl,
      Instant expiresAt) {}
}
