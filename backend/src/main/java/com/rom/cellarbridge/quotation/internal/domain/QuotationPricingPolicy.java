package com.rom.cellarbridge.quotation.internal.domain;

import com.rom.cellarbridge.quotation.internal.domain.QuotationDomainException.FailureKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class QuotationPricingPolicy {

  public static final String VERSION = "PRICE-2026-01";
  public static final int MONEY_SCALE = 4;
  public static final int RATE_SCALE = 4;
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private QuotationPricingPolicy() {}

  public static PricingResult price(
      String currency, List<LineDraft> drafts, BigDecimal routeCharges) {
    if (drafts == null || drafts.isEmpty() || drafts.size() > 50) {
      throw problem("QUOTE_EMPTY", "A quotation must contain between 1 and 50 lines");
    }
    if (drafts.stream().map(LineDraft::sku).map(SkuSnapshot::skuId).distinct().count()
        != drafts.size()) {
      throw problem("QUOTE_LINE_DUPLICATE_SKU", "A SKU may appear only once per revision");
    }
    BigDecimal normalizedCharges = money(routeCharges == null ? BigDecimal.ZERO : routeCharges);
    List<BigDecimal> weights =
        drafts.stream().map(QuotationPricingPolicy::grossBeforeDiscount).toList();
    BigDecimal grossTotal = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    List<PricedLine> lines = new ArrayList<>(drafts.size());
    BigDecimal allocated = BigDecimal.ZERO;
    for (int index = 0; index < drafts.size(); index++) {
      LineDraft draft = drafts.get(index);
      requireLine(draft, currency);
      BigDecimal lineAllocation;
      if (index == drafts.size() - 1) {
        lineAllocation = money(normalizedCharges.subtract(allocated));
      } else if (grossTotal.signum() == 0) {
        lineAllocation = BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);
      } else {
        lineAllocation =
            money(normalizedCharges.multiply(weights.get(index)).divide(grossTotal, 12, ROUNDING));
        allocated = allocated.add(lineAllocation);
      }
      lines.add(priceLine(draft, currency, lineAllocation));
    }
    BigDecimal subtotal =
        money(lines.stream().map(PricedLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
    BigDecimal totalCost =
        money(lines.stream().map(PricedLine::lineCost).reduce(BigDecimal.ZERO, BigDecimal::add));
    BigDecimal marginRate =
        subtotal.signum() == 0
            ? BigDecimal.ZERO.setScale(RATE_SCALE, ROUNDING)
            : subtotal.subtract(totalCost).divide(subtotal, RATE_SCALE, ROUNDING);
    return new PricingResult(lines, subtotal, subtotal, totalCost, marginRate, normalizedCharges);
  }

  private static PricedLine priceLine(LineDraft draft, String currency, BigDecimal allocation) {
    BigDecimal listUnitPrice = unitPrice(draft.reference().listCasePrice(), draft);
    BigDecimal costUnitPrice = unitPrice(draft.reference().costCasePrice(), draft);
    BigDecimal baseUnitPrice =
        draft.manualUnitPrice() == null ? listUnitPrice : money(draft.manualUnitPrice());
    BigDecimal gross = money(baseUnitPrice.multiply(draft.quantity()));
    BigDecimal discountAmount = money(gross.multiply(draft.discountRate()));
    BigDecimal lineTotal = money(gross.subtract(discountAmount).add(allocation));
    BigDecimal netUnitPrice =
        money(baseUnitPrice.multiply(BigDecimal.ONE.subtract(draft.discountRate())));
    BigDecimal lineCost = money(costUnitPrice.multiply(draft.quantity()));
    BigDecimal marginRate =
        lineTotal.signum() == 0
            ? BigDecimal.ZERO.setScale(RATE_SCALE, ROUNDING)
            : lineTotal.subtract(lineCost).divide(lineTotal, RATE_SCALE, ROUNDING);
    return new PricedLine(
        draft.lineId(),
        draft.sku(),
        draft.quantity(),
        draft.unit(),
        draft.preferredSupplyPoolId(),
        draft.supplyType(),
        listUnitPrice,
        draft.discountRate(),
        netUnitPrice,
        allocation,
        lineTotal,
        costUnitPrice,
        lineCost,
        marginRate,
        draft.manualUnitPrice() != null,
        currency,
        draft.reference().version());
  }

  private static BigDecimal grossBeforeDiscount(LineDraft draft) {
    BigDecimal base =
        draft.manualUnitPrice() == null
            ? unitPrice(draft.reference().listCasePrice(), draft)
            : money(draft.manualUnitPrice());
    return money(base.multiply(draft.quantity()));
  }

  private static BigDecimal unitPrice(BigDecimal casePrice, LineDraft draft) {
    if (draft.unit() == QuantityUnit.CASE) {
      return money(casePrice);
    }
    return money(casePrice.divide(BigDecimal.valueOf(draft.sku().unitsPerCase()), 12, ROUNDING));
  }

  private static void requireLine(LineDraft draft, String currency) {
    Objects.requireNonNull(draft, "line");
    if (draft.quantity() == null || draft.quantity().compareTo(BigDecimal.ZERO) <= 0) {
      throw problem("VALIDATION_FAILED", "Quotation line quantity must be positive");
    }
    if (draft.discountRate() == null
        || draft.discountRate().compareTo(BigDecimal.ZERO) < 0
        || draft.discountRate().compareTo(BigDecimal.ONE) >= 0) {
      throw problem("VALIDATION_FAILED", "Discount rate must be at least zero and less than one");
    }
    if (!draft.reference().currency().equals(currency)) {
      throw problem("QUOTE_PRICE_STALE", "No current price exists in the quotation currency");
    }
    if (draft.manualUnitPrice() != null
        && draft.manualUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw problem("VALIDATION_FAILED", "Manual unit price must be positive");
    }
  }

  public static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, ROUNDING);
  }

  public static BigDecimal rate(BigDecimal value) {
    return value.setScale(RATE_SCALE, ROUNDING);
  }

  private static QuotationDomainException problem(String code, String message) {
    return new QuotationDomainException(FailureKind.BUSINESS_RULE, code, message);
  }

  public enum QuantityUnit {
    CASE,
    BOTTLE
  }

  public record SkuSnapshot(
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
      Instant capturedAt) {}

  public record PriceReference(
      UUID skuId,
      String currency,
      BigDecimal listCasePrice,
      BigDecimal costCasePrice,
      String version) {}

  public record LineDraft(
      UUID lineId,
      SkuSnapshot sku,
      BigDecimal quantity,
      QuantityUnit unit,
      UUID preferredSupplyPoolId,
      String supplyType,
      BigDecimal discountRate,
      BigDecimal manualUnitPrice,
      PriceReference reference) {}

  public record PricedLine(
      UUID lineId,
      SkuSnapshot sku,
      BigDecimal quantity,
      QuantityUnit unit,
      UUID preferredSupplyPoolId,
      String supplyType,
      BigDecimal listUnitPrice,
      BigDecimal discountRate,
      BigDecimal netUnitPrice,
      BigDecimal allocatedCharges,
      BigDecimal lineTotal,
      BigDecimal costUnitPrice,
      BigDecimal lineCost,
      BigDecimal marginRate,
      boolean manualPrice,
      String currency,
      String priceSourceVersion) {}

  public record PricingResult(
      List<PricedLine> lines,
      BigDecimal subtotal,
      BigDecimal total,
      BigDecimal totalCost,
      BigDecimal marginRate,
      BigDecimal routeCharges) {

    public PricingResult {
      lines = List.copyOf(lines);
    }
  }
}
