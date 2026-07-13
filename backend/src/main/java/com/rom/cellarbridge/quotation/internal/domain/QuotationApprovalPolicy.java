package com.rom.cellarbridge.quotation.internal.domain;

import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricedLine;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricingResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class QuotationApprovalPolicy {

  public static final String VERSION = "APPROVAL-2026-01";
  public static final BigDecimal DISCOUNT_THRESHOLD = new BigDecimal("0.0800");
  public static final BigDecimal MARGIN_THRESHOLD = new BigDecimal("0.1500");

  private QuotationApprovalPolicy() {}

  public static List<Requirement> evaluate(
      PricingResult pricing,
      int paymentTermDays,
      int partnerPaymentTermDays,
      Instant now,
      Instant expiresAt,
      boolean routeOverridden) {
    List<Requirement> requirements = new ArrayList<>();
    BigDecimal maximumDiscount =
        pricing.lines().stream()
            .map(PricedLine::discountRate)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    if (maximumDiscount.compareTo(DISCOUNT_THRESHOLD) > 0) {
      requirements.add(
          new Requirement(
              "QUO-DISCOUNT-001",
              "DISCOUNT_THRESHOLD_EXCEEDED",
              maximumDiscount.toPlainString(),
              DISCOUNT_THRESHOLD.toPlainString(),
              "Discount exceeds the automatic approval threshold"));
    }
    if (pricing.marginRate().compareTo(MARGIN_THRESHOLD) < 0) {
      requirements.add(
          new Requirement(
              "QUO-MARGIN-001",
              "MARGIN_BELOW_THRESHOLD",
              pricing.marginRate().toPlainString(),
              MARGIN_THRESHOLD.toPlainString(),
              "Estimated margin is below the approval threshold"));
    }
    if (paymentTermDays > partnerPaymentTermDays) {
      requirements.add(
          new Requirement(
              "QUO-PAYMENT-001",
              "PAYMENT_TERM_EXCEPTION",
              Integer.toString(paymentTermDays),
              Integer.toString(partnerPaymentTermDays),
              "Payment term exceeds the approved partner default"));
    }
    if (pricing.lines().stream().anyMatch(PricedLine::manualPrice)) {
      requirements.add(
          new Requirement(
              "QUO-PRICE-001",
              "MANUAL_PRICE_USED",
              "true",
              "false",
              "A manually entered unit price requires approval"));
    }
    if (pricing.lines().stream().anyMatch(QuotationApprovalPolicy::abnormalPrice)) {
      requirements.add(
          new Requirement(
              "QUO-PRICE-002",
              "ABNORMAL_PRICE_DEVIATION",
              "over-20-percent",
              "20-percent",
              "A manual price differs materially from the reference price"));
    }
    if (routeOverridden) {
      requirements.add(
          new Requirement(
              "QUO-ROUTE-001",
              "NON_RECOMMENDED_ROUTE",
              "overridden",
              "recommended",
              "A non-recommended eligible route was selected"));
    }
    long validityDays = Duration.between(now, expiresAt).toDays();
    if (validityDays > 30) {
      requirements.add(
          new Requirement(
              "QUO-VALIDITY-001",
              "EXTENDED_VALIDITY",
              Long.toString(validityDays),
              "30",
              "Quotation validity exceeds the automatic approval window"));
    }
    return List.copyOf(requirements);
  }

  private static boolean abnormalPrice(PricedLine line) {
    if (!line.manualPrice() || line.listUnitPrice().signum() == 0) {
      return false;
    }
    BigDecimal deviation =
        line.netUnitPrice()
            .subtract(line.listUnitPrice())
            .abs()
            .divide(line.listUnitPrice(), 4, java.math.RoundingMode.HALF_UP);
    return deviation.compareTo(new BigDecimal("0.2000")) > 0;
  }

  public record Requirement(
      String ruleId, String code, String actualValue, String threshold, String message) {}
}
