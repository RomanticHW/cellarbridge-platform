package com.rom.cellarbridge.quotation.internal.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.application.CustomerQuotationService;
import com.rom.cellarbridge.quotation.internal.application.CustomerQuotationService.AcceptanceResult;
import com.rom.cellarbridge.quotation.internal.application.CustomerQuotationService.DecisionReceipt;
import com.rom.cellarbridge.quotation.internal.application.CustomerQuotationService.PublicLine;
import com.rom.cellarbridge.quotation.internal.application.CustomerQuotationService.PublicView;
import com.rom.cellarbridge.quotation.internal.application.CustomerQuotationService.RejectionReasonCategory;
import com.rom.cellarbridge.quotation.internal.application.CustomerQuotationService.RejectionResult;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/portal/quotations")
final class PublicQuotationController {

  private final CustomerQuotationService service;

  PublicQuotationController(CustomerQuotationService service) {
    this.service = service;
  }

  @GetMapping("/{publicToken}")
  ResponseEntity<PublicQuotationResponse> get(@PathVariable String publicToken) {
    return noStore(PublicQuotationResponse.from(service.publicView(publicToken)));
  }

  @PostMapping("/{publicToken}/acceptance")
  ResponseEntity<QuotationAcceptanceResponse> accept(
      @PathVariable String publicToken,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody QuotationAcceptanceRequest request) {
    AcceptanceResult result =
        service.accept(
            publicToken, idempotencyKey, request.acceptedTermsVersion(), request.buyerReference());
    return decisionResponse(QuotationAcceptanceResponse.from(result), result.replayed());
  }

  @PostMapping("/{publicToken}/rejection")
  ResponseEntity<QuotationRejectionResponse> reject(
      @PathVariable String publicToken,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody QuotationRejectionRequest request) {
    RejectionResult result = service.reject(publicToken, idempotencyKey, request.reasonCategory());
    return decisionResponse(QuotationRejectionResponse.from(result), result.replayed());
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  private static <T> ResponseEntity<T> decisionResponse(T body, boolean replayed) {
    return ResponseEntity.status(replayed ? HttpStatus.OK : HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(replayed))
        .body(body);
  }

  record QuotationAcceptanceRequest(
      @NotBlank @Size(max = 50) String acceptedTermsVersion,
      @Size(max = 100) String buyerReference) {

    @JsonAnySetter
    void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown customer quotation request field");
    }
  }

  record QuotationRejectionRequest(RejectionReasonCategory reasonCategory) {

    @JsonAnySetter
    void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown customer quotation request field");
    }
  }

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

  record PublicLineResponse(
      String skuCode,
      String description,
      String vintage,
      @JsonProperty("package") String packageType,
      QuantityResponse quantity,
      MoneyResponse unitPrice,
      MoneyResponse lineTotal) {
    static PublicLineResponse from(PublicLine line) {
      return new PublicLineResponse(
          line.skuCode(),
          line.description(),
          line.vintage(),
          line.packageType(),
          QuantityResponse.of(line.quantity(), line.unit()),
          MoneyResponse.of(line.unitPrice(), line.currency()),
          MoneyResponse.of(line.lineTotal(), line.currency()));
    }
  }

  record DeliveryOptionResponse(String label, String estimatedWindow) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DecisionReceiptResponse(
      UUID decisionId, String decision, Instant decidedAt, String reference) {
    static DecisionReceiptResponse from(DecisionReceipt receipt) {
      return receipt == null
          ? null
          : new DecisionReceiptResponse(
              receipt.decisionId(), receipt.decision(), receipt.decidedAt(), receipt.reference());
    }
  }

  record PublicQuotationResponse(
      String number,
      int revision,
      String supplierPublicId,
      String supplierDisplayName,
      String customerPublicId,
      String customerDisplayName,
      QuotationStatus status,
      Instant expiresAt,
      List<PublicLineResponse> lines,
      MoneyResponse subtotal,
      MoneyResponse fees,
      MoneyResponse total,
      DeliveryOptionResponse deliveryOption,
      int paymentTermDays,
      String termsVersion,
      List<String> termsSummary,
      List<String> allowedActions,
      String orderNumber,
      DecisionReceiptResponse decisionReceipt) {
    static PublicQuotationResponse from(PublicView view) {
      return new PublicQuotationResponse(
          view.number(),
          view.revision(),
          view.supplierPublicId(),
          view.supplierDisplayName(),
          view.customerPublicId(),
          view.customerDisplayName(),
          view.status(),
          view.expiresAt(),
          view.lines().stream().map(PublicLineResponse::from).toList(),
          MoneyResponse.of(view.subtotal(), view.currency()),
          MoneyResponse.of(view.fees(), view.currency()),
          MoneyResponse.of(view.total(), view.currency()),
          new DeliveryOptionResponse(view.deliveryLabel(), view.estimatedWindow()),
          view.paymentTermDays(),
          view.termsVersion(),
          view.termsSummary(),
          view.allowedActions(),
          null,
          DecisionReceiptResponse.from(view.decisionReceipt()));
    }
  }

  record QuotationAcceptanceResponse(
      UUID acceptanceId,
      String quotationNumber,
      String status,
      Instant acceptedAt,
      String orderCreationStatus,
      UUID orderId,
      String orderNumber,
      boolean replayed) {
    static QuotationAcceptanceResponse from(AcceptanceResult result) {
      return new QuotationAcceptanceResponse(
          result.acceptanceId(),
          result.quotationNumber(),
          "ACCEPTED",
          result.acceptedAt(),
          "PENDING",
          null,
          null,
          result.replayed());
    }
  }

  record QuotationRejectionResponse(
      UUID rejectionId,
      String quotationNumber,
      String status,
      Instant rejectedAt,
      String reasonCategory,
      boolean replayed) {
    static QuotationRejectionResponse from(RejectionResult result) {
      return new QuotationRejectionResponse(
          result.rejectionId(),
          result.quotationNumber(),
          "REJECTED_BY_CUSTOMER",
          result.rejectedAt(),
          result.reasonCategory(),
          result.replayed());
    }
  }
}
