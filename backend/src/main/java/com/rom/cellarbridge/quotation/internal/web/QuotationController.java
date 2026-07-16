package com.rom.cellarbridge.quotation.internal.web;

import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.QuotationSupplyDecisionStatus;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.AddressCommand;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.CommandView;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.CreateCommand;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.DetailView;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.IssueView;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.LineCommand;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.ListCommand;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.ListView;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.SummaryView;
import com.rom.cellarbridge.quotation.internal.application.QuotationProblem;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.ApprovalDecision;
import com.rom.cellarbridge.quotation.internal.domain.QuotationApprovalPolicy.Requirement;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricedLine;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteRejection;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteScore;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quotations")
final class QuotationController {

  private final QuotationApplicationService service;

  QuotationController(QuotationApplicationService service) {
    this.service = service;
  }

  @GetMapping
  QuotationPageResponse list(
      @RequestParam(required = false) Set<QuotationStatus> status,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) UUID partnerId,
      @RequestParam(required = false) Integer pageSize) {
    ListView result = service.list(new ListCommand(status, ownerId, partnerId, pageSize));
    return new QuotationPageResponse(
        result.items().stream().map(QuotationSummaryResponse::from).toList(),
        new PageInfoResponse(result.nextCursor(), result.hasNext(), result.pageSize()));
  }

  @PostMapping
  ResponseEntity<QuotationDetailResponse> create(
      @Valid @RequestBody QuotationDraftRequest request) {
    DetailView created = service.create(request.toCommand());
    return ResponseEntity.created(URI.create("/api/v1/quotations/" + created.summary().id()))
        .eTag(etag(created.summary().version()))
        .body(QuotationDetailResponse.from(created));
  }

  @GetMapping("/{quotationId}")
  ResponseEntity<QuotationDetailResponse> get(@PathVariable UUID quotationId) {
    DetailView detail = service.get(quotationId);
    return ResponseEntity.ok()
        .eTag(etag(detail.summary().version()))
        .body(QuotationDetailResponse.from(detail));
  }

  @PutMapping("/{quotationId}")
  ResponseEntity<QuotationDetailResponse> update(
      @PathVariable UUID quotationId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody QuotationDraftRequest request) {
    DetailView detail = service.update(quotationId, expectedVersion(ifMatch), request.toCommand());
    return ResponseEntity.ok()
        .eTag(etag(detail.summary().version()))
        .body(QuotationDetailResponse.from(detail));
  }

  @PostMapping("/{quotationId}/route-evaluations")
  ResponseEntity<RouteEvaluationResponse> evaluate(
      @PathVariable UUID quotationId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody(required = false) RouteEvaluationRequest request) {
    RouteEvaluation evaluation =
        service.evaluateRoutes(
            quotationId,
            expectedVersion(ifMatch),
            request == null ? null : request.requestedRouteCode(),
            request == null ? null : strip(request.overrideReason()));
    return ResponseEntity.ok().body(RouteEvaluationResponse.from(evaluation));
  }

  @PostMapping("/{quotationId}/submission")
  ResponseEntity<QuotationCommandResponse> submit(
      @PathVariable UUID quotationId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
    CommandView result = service.submit(quotationId, expectedVersion(ifMatch));
    return ResponseEntity.ok()
        .eTag(etag(result.version()))
        .body(QuotationCommandResponse.from(result));
  }

  @PostMapping("/{quotationId}/approval")
  ResponseEntity<QuotationCommandResponse> decide(
      @PathVariable UUID quotationId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody ApprovalRequest request) {
    CommandView result =
        service.decide(
            quotationId, expectedVersion(ifMatch), request.decision(), strip(request.reason()));
    return ResponseEntity.ok()
        .eTag(etag(result.version()))
        .body(QuotationCommandResponse.from(result));
  }

  @PostMapping("/{quotationId}/issue")
  ResponseEntity<IssueResponse> issue(
      @PathVariable UUID quotationId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
    IssueView result = service.issue(quotationId, expectedVersion(ifMatch));
    return ResponseEntity.ok().eTag(etag(result.version())).body(IssueResponse.from(result));
  }

  private static long expectedVersion(String ifMatch) {
    if (ifMatch == null) {
      throw new QuotationProblem(
          org.springframework.http.HttpStatus.PRECONDITION_REQUIRED,
          "PRECONDITION_REQUIRED",
          "If-Match is required");
    }
    if (!ifMatch.matches("\"[0-9]+\"")) {
      throw new QuotationProblem(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "VALIDATION_FAILED",
          "If-Match must contain a quoted numeric version");
    }
    return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
  }

  private static String etag(long version) {
    return "\"" + version + "\"";
  }

  private static String strip(String value) {
    return value == null ? null : value.strip();
  }

  record MoneyRequest(
      @NotBlank @Pattern(regexp = "^[0-9]+(\\.[0-9]{1,4})?$") String amount,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  record QuantityRequest(
      @NotBlank @Pattern(regexp = "^[0-9]+(\\.[0-9]{1,6})?$") String value,
      @NotNull QuantityUnit unit) {}

  record AddressRequest(
      @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
      @NotBlank @Size(max = 100) String province,
      @NotBlank @Size(max = 100) String city,
      @Size(max = 100) String district,
      @NotBlank @Size(max = 200) String line1,
      @Size(max = 20) String postalCode) {
    AddressCommand toCommand() {
      return new AddressCommand(
          countryCode,
          strip(province),
          strip(city),
          strip(district),
          strip(line1),
          strip(postalCode));
    }
  }

  record QuotationLineRequest(
      @NotNull UUID skuId,
      @NotNull @Valid QuantityRequest quantity,
      UUID preferredSupplyPoolId,
      @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal discountRate,
      @Valid MoneyRequest manualUnitPrice) {
    LineCommand toCommand(String currency) {
      if (manualUnitPrice != null && !manualUnitPrice.currency().equals(currency)) {
        throw new QuotationProblem(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Manual price currency must equal quotation currency");
      }
      return new LineCommand(
          skuId,
          new BigDecimal(quantity.value()),
          quantity.unit(),
          preferredSupplyPoolId,
          discountRate,
          manualUnitPrice == null ? null : new BigDecimal(manualUnitPrice.amount()));
    }
  }

  record QuotationDraftRequest(
      @NotNull UUID partnerId,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull LocalDate requestedDeliveryDate,
      @NotNull Instant expiresAt,
      @Min(0) @Max(180) Integer paymentTermDays,
      @Valid AddressRequest deliveryAddress,
      @NotEmpty @Size(max = 50) List<@Valid QuotationLineRequest> lines) {
    CreateCommand toCommand() {
      return new CreateCommand(
          partnerId,
          currency,
          requestedDeliveryDate,
          expiresAt,
          paymentTermDays,
          deliveryAddress == null ? null : deliveryAddress.toCommand(),
          lines.stream().map(line -> line.toCommand(currency)).toList());
    }
  }

  record RouteEvaluationRequest(
      TradeRouteCode requestedRouteCode, @Size(min = 5, max = 500) String overrideReason) {}

  record ApprovalRequest(
      @NotNull QuotationAggregate.Decision decision,
      @NotBlank @Size(min = 5, max = 500) String reason) {}

  record MoneyResponse(String amount, String currency) {
    static MoneyResponse of(BigDecimal amount, String currency) {
      return new MoneyResponse(amount.toPlainString(), currency);
    }
  }

  record QuantityResponse(String value, QuantityUnit unit) {
    static QuantityResponse of(BigDecimal value, QuantityUnit unit) {
      return new QuantityResponse(value.stripTrailingZeros().toPlainString(), unit);
    }
  }

  record PageInfoResponse(String nextCursor, boolean hasNext, int pageSize) {}

  record QuotationPageResponse(List<QuotationSummaryResponse> items, PageInfoResponse pageInfo) {}

  record QuotationSummaryResponse(
      UUID id,
      String number,
      UUID partnerId,
      String partnerName,
      int revision,
      QuotationStatus status,
      MoneyResponse total,
      TradeRouteCode selectedRouteCode,
      Instant expiresAt,
      UUID ownerId,
      long version,
      Instant updatedAt) {
    static QuotationSummaryResponse from(SummaryView view) {
      return new QuotationSummaryResponse(
          view.id(),
          view.number(),
          view.partnerId(),
          view.partnerName(),
          view.revision(),
          view.status(),
          MoneyResponse.of(view.total(), view.currency()),
          view.selectedRouteCode(),
          view.expiresAt(),
          view.ownerId(),
          view.version(),
          view.updatedAt());
    }
  }

  record PartnerSnapshotResponse(
      UUID partnerId,
      String number,
      String displayName,
      int paymentTermDays,
      int sourceVersion,
      Instant capturedAt) {}

  record SkuSnapshotResponse(
      UUID skuId,
      String skuCode,
      String displayName,
      String producerName,
      String regionName,
      String countryCode,
      String category,
      String vintage,
      int volumeMl,
      int unitsPerCase,
      String packageType,
      long sourceVersion,
      Instant updatedAt) {}

  record PriceLineResponse(
      UUID lineId,
      SkuSnapshotResponse sku,
      QuantityResponse quantity,
      MoneyResponse listUnitPrice,
      String discountRate,
      MoneyResponse netUnitPrice,
      MoneyResponse allocatedCharges,
      MoneyResponse lineTotal,
      SupplyAllocationMode allocationMode,
      String supplyType,
      UUID supplyPoolId) {
    static PriceLineResponse from(
        PricedLine line, QuotationSupplyDecisionStatus supplyDecisionStatus) {
      SupplyAllocationMode allocationMode = null;
      String supplyType = null;
      UUID supplyPoolId = null;
      switch (supplyDecisionStatus) {
        case UNDECIDED -> supplyPoolId = line.preferredSupplyPoolId();
        case FROZEN -> {
          allocationMode = line.allocationMode();
          supplyType = line.supplyType();
          supplyPoolId = line.preferredSupplyPoolId();
        }
        case LEGACY_REEVALUATION_REQUIRED -> {
          // Legacy line fields predate verified route-bound evidence and remain hidden.
        }
      }
      return new PriceLineResponse(
          line.lineId(),
          new SkuSnapshotResponse(
              line.sku().skuId(),
              line.sku().skuCode(),
              line.sku().displayName(),
              line.sku().producerName(),
              line.sku().regionName(),
              line.sku().countryCode(),
              line.sku().category(),
              line.sku().vintage(),
              line.sku().volumeMl(),
              line.sku().unitsPerCase(),
              line.sku().packageType(),
              line.sku().sourceVersion(),
              line.sku().capturedAt()),
          QuantityResponse.of(line.quantity(), line.unit()),
          MoneyResponse.of(line.listUnitPrice(), line.currency()),
          line.discountRate().toPlainString(),
          MoneyResponse.of(line.netUnitPrice(), line.currency()),
          MoneyResponse.of(line.allocatedCharges(), line.currency()),
          MoneyResponse.of(line.lineTotal(), line.currency()),
          allocationMode,
          supplyType,
          supplyPoolId);
    }
  }

  record RequirementResponse(
      String ruleId, String code, String actualValue, String threshold, String message) {
    static RequirementResponse from(Requirement requirement) {
      return new RequirementResponse(
          requirement.ruleId(),
          requirement.code(),
          requirement.actualValue(),
          requirement.threshold(),
          requirement.message());
    }
  }

  record ApprovalResponse(
      QuotationAggregate.Decision decision, UUID reviewerId, String reason, Instant occurredAt) {
    static ApprovalResponse from(ApprovalDecision decision) {
      return new ApprovalResponse(
          decision.decision(), decision.reviewerId(), decision.reason(), decision.occurredAt());
    }
  }

  record TimelineResponse(
      UUID id,
      Instant occurredAt,
      String action,
      String previousState,
      String newState,
      String safeReason) {}

  record QuotationDetailResponse(
      UUID id,
      String number,
      UUID partnerId,
      String partnerName,
      int revision,
      QuotationStatus status,
      MoneyResponse total,
      TradeRouteCode selectedRouteCode,
      Instant expiresAt,
      UUID ownerId,
      long version,
      Instant updatedAt,
      PartnerSnapshotResponse partnerSnapshot,
      LocalDate requestedDeliveryDate,
      int paymentTermDays,
      AddressResponse deliveryAddress,
      List<PriceLineResponse> lines,
      MoneyResponse subtotal,
      String estimatedMarginRate,
      QuotationSupplyDecisionStatus supplyDecisionStatus,
      SupplyDecisionSummaryResponse supplyDecision,
      RouteEvaluationResponse routeEvaluation,
      List<RequirementResponse> approvalRequirements,
      List<ApprovalResponse> approvals,
      List<String> allowedActions,
      List<TimelineResponse> timeline) {
    static QuotationDetailResponse from(DetailView view) {
      SummaryView summary = view.summary();
      QuotationAggregate.Revision revision = view.revision();
      return new QuotationDetailResponse(
          summary.id(),
          summary.number(),
          summary.partnerId(),
          summary.partnerName(),
          summary.revision(),
          summary.status(),
          MoneyResponse.of(summary.total(), summary.currency()),
          summary.selectedRouteCode(),
          summary.expiresAt(),
          summary.ownerId(),
          summary.version(),
          summary.updatedAt(),
          new PartnerSnapshotResponse(
              revision.partnerSnapshot().partnerId(),
              revision.partnerSnapshot().number(),
              revision.partnerSnapshot().displayName(),
              revision.partnerSnapshot().paymentTermDays(),
              revision.partnerSnapshot().sourceVersion(),
              revision.partnerSnapshot().capturedAt()),
          revision.terms().requestedDeliveryDate(),
          revision.terms().paymentTermDays(),
          AddressResponse.from(revision.terms().deliveryAddress()),
          revision.pricing().lines().stream()
              .map(line -> PriceLineResponse.from(line, revision.supplyDecisionStatus()))
              .toList(),
          MoneyResponse.of(revision.pricing().subtotal(), revision.terms().currency()),
          view.estimatedMarginRate() == null ? null : view.estimatedMarginRate().toPlainString(),
          revision.supplyDecisionStatus(),
          SupplyDecisionSummaryResponse.from(revision.supplyDecision()),
          view.routeEvaluation() == null
              ? null
              : RouteEvaluationResponse.from(view.routeEvaluation()),
          revision.approvalRequirements().stream().map(RequirementResponse::from).toList(),
          view.approvals().stream().map(ApprovalResponse::from).toList(),
          view.allowedActions(),
          view.timeline().stream()
              .map(
                  item ->
                      new TimelineResponse(
                          item.id(),
                          item.occurredAt(),
                          item.action(),
                          item.previousState(),
                          item.newState(),
                          item.safeReason()))
              .toList());
    }
  }

  record AddressResponse(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {
    static AddressResponse from(QuotationAggregate.Address address) {
      return new AddressResponse(
          address.countryCode(),
          address.province(),
          address.city(),
          address.district(),
          address.line1(),
          address.postalCode());
    }
  }

  record RejectionResponse(
      String ruleId, String code, String message, java.util.Map<String, String> parameters) {
    static RejectionResponse from(RouteRejection rejection) {
      return new RejectionResponse(
          rejection.ruleId(), rejection.code(), rejection.message(), rejection.parameters());
    }
  }

  record ScoreResponse(
      String cost,
      String leadTime,
      String supplyConfidence,
      String operationalSimplicity,
      String total) {
    static ScoreResponse from(RouteScore score) {
      return new ScoreResponse(
          score.cost().toPlainString(),
          score.leadTime().toPlainString(),
          score.supplyConfidence().toPlainString(),
          score.operationalSimplicity().toPlainString(),
          score.total().toPlainString());
    }
  }

  record RouteCandidateResponse(
      TradeRouteCode routeCode,
      String eligibility,
      ScoreResponse score,
      LocalDate estimatedDeliveryDate,
      MoneyResponse estimatedCharges,
      List<RejectionResponse> rejections) {
    static RouteCandidateResponse from(RouteCandidate candidate) {
      return new RouteCandidateResponse(
          candidate.routeCode(),
          candidate.eligibility().name(),
          candidate.score() == null ? null : ScoreResponse.from(candidate.score()),
          candidate.estimatedDeliveryDate(),
          candidate.estimatedCharges() == null
              ? null
              : MoneyResponse.of(candidate.estimatedCharges(), candidate.currency()),
          candidate.rejections().stream().map(RejectionResponse::from).toList());
    }
  }

  record OverrideResponse(
      String reason, UUID actorId, Instant occurredAt, TradeRouteCode originalRecommendation) {}

  record LineSupplyDecisionResponse(
      UUID quotationLineId,
      UUID skuId,
      String requestedQuantity,
      String quantityUnit,
      SupplyAllocationMode allocationMode,
      UUID supplyPoolId,
      String supplyType) {
    static LineSupplyDecisionResponse from(SupplyDecisionSnapshot.LineDecision line) {
      return new LineSupplyDecisionResponse(
          line.quotationLineId(),
          line.skuId(),
          line.requestedQuantity().toPlainString(),
          line.quantityUnit().name(),
          line.allocationMode(),
          line.supplyPoolId(),
          line.supplyType().name());
    }
  }

  record SupplyDecisionSummaryResponse(
      int schemaVersion,
      String policyVersion,
      Instant decidedAt,
      UUID sourceRouteEvaluationId,
      String sourceRouteInputHash,
      TradeRouteCode selectedRouteCode,
      Instant inventoryDataAsOf,
      String decisionHash,
      List<LineSupplyDecisionResponse> lineDecisions) {
    static SupplyDecisionSummaryResponse from(SupplyDecisionSnapshot decision) {
      if (decision == null) {
        return null;
      }
      return new SupplyDecisionSummaryResponse(
          decision.schemaVersion(),
          decision.policyVersion(),
          decision.decidedAt(),
          decision.sourceRouteEvaluationId(),
          decision.sourceRouteInputHash(),
          decision.selectedRouteCode(),
          decision.inventoryDataAsOf(),
          decision.decisionHash(),
          decision.lineDecisions().stream().map(LineSupplyDecisionResponse::from).toList());
    }
  }

  record RouteEvaluationResponse(
      UUID evaluationId,
      String policyVersion,
      Instant evaluatedAt,
      String inputHash,
      List<RouteCandidateResponse> candidates,
      TradeRouteCode recommendedRouteCode,
      TradeRouteCode selectedRouteCode,
      OverrideResponse override,
      SupplyDecisionSummaryResponse supplyDecision) {
    static RouteEvaluationResponse from(RouteEvaluation evaluation) {
      return new RouteEvaluationResponse(
          evaluation.evaluationId(),
          evaluation.policyVersion(),
          evaluation.evaluatedAt(),
          evaluation.inputHash(),
          evaluation.candidates().stream().map(RouteCandidateResponse::from).toList(),
          evaluation.recommendedRouteCode(),
          evaluation.selectedRouteCode(),
          evaluation.override() == null
              ? null
              : new OverrideResponse(
                  evaluation.override().reason(),
                  evaluation.override().actorId(),
                  evaluation.override().occurredAt(),
                  evaluation.override().originalRecommendation()),
          SupplyDecisionSummaryResponse.from(evaluation.supplyDecision()));
    }
  }

  record QuotationCommandResponse(
      UUID quotationId,
      String number,
      int revision,
      QuotationStatus status,
      long version,
      List<String> allowedActions) {
    static QuotationCommandResponse from(CommandView view) {
      return new QuotationCommandResponse(
          view.quotationId(),
          view.number(),
          view.revision(),
          view.status(),
          view.version(),
          view.allowedActions());
    }
  }

  record IssueResponse(
      UUID quotationId, QuotationStatus status, long version, String portalUrl, Instant expiresAt) {
    static IssueResponse from(IssueView view) {
      return new IssueResponse(
          view.quotationId(), view.status(), view.version(), view.portalUrl(), view.expiresAt());
    }
  }
}
