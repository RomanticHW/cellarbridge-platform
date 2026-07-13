package com.rom.cellarbridge.quotation.internal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.PublicLine;
import com.rom.cellarbridge.quotation.internal.application.QuotationApplicationService.PublicView;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/portal/quotations")
final class PublicQuotationController {

  private final QuotationApplicationService service;

  PublicQuotationController(QuotationApplicationService service) {
    this.service = service;
  }

  @GetMapping("/{publicToken}")
  ResponseEntity<PublicQuotationResponse> get(@PathVariable String publicToken) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(PublicQuotationResponse.from(service.publicView(publicToken)));
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
      String description,
      String vintage,
      @JsonProperty("package") String packageType,
      QuantityResponse quantity,
      MoneyResponse unitPrice,
      MoneyResponse lineTotal) {
    static PublicLineResponse from(PublicLine line) {
      return new PublicLineResponse(
          line.description(),
          line.vintage(),
          line.packageType(),
          QuantityResponse.of(line.quantity(), line.unit()),
          MoneyResponse.of(line.unitPrice(), line.currency()),
          MoneyResponse.of(line.lineTotal(), line.currency()));
    }
  }

  record DeliveryOptionResponse(String label, String estimatedWindow) {}

  record PublicQuotationResponse(
      String number,
      int revision,
      String supplierDisplayName,
      String customerDisplayName,
      QuotationStatus status,
      Instant expiresAt,
      List<PublicLineResponse> lines,
      MoneyResponse total,
      DeliveryOptionResponse deliveryOption,
      int paymentTermDays,
      String termsVersion,
      List<String> allowedActions,
      String orderNumber) {
    static PublicQuotationResponse from(PublicView view) {
      return new PublicQuotationResponse(
          view.number(),
          view.revision(),
          view.supplierDisplayName(),
          view.customerDisplayName(),
          view.status(),
          view.expiresAt(),
          view.lines().stream().map(PublicLineResponse::from).toList(),
          MoneyResponse.of(view.total(), view.currency()),
          new DeliveryOptionResponse(view.deliveryLabel(), view.estimatedWindow()),
          view.paymentTermDays(),
          view.termsVersion(),
          view.allowedActions(),
          null);
    }
  }
}
