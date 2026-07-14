package com.rom.cellarbridge.quotation.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.quotation.internal.domain.QuotationDomainException.FailureKind;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.LineDraft;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PriceReference;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricingResult;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.SkuSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuotationPricingPolicyTest {

  @Test
  void preservesRoundedTotalAndAllocationPropertiesAcrossGeneratedInputs() {
    Random random = new Random(5_2026L);
    for (int sample = 0; sample < 1_000; sample++) {
      BigDecimal quantity = BigDecimal.valueOf(random.nextInt(40) + 1L);
      BigDecimal discount = BigDecimal.valueOf(random.nextInt(2_500), 4);
      BigDecimal charges = BigDecimal.valueOf(random.nextInt(20_000), 4);
      LineDraft line = line(quantity, discount);

      PricingResult result = QuotationPricingPolicy.price("CNY", List.of(line), charges);

      BigDecimal expected =
          new BigDecimal("1260.0000")
              .multiply(quantity)
              .multiply(BigDecimal.ONE.subtract(discount))
              .add(charges)
              .setScale(4, QuotationPricingPolicy.ROUNDING);
      assertThat(result.total()).isEqualByComparingTo(expected);
      assertThat(result.lines().getFirst().allocatedCharges()).isEqualByComparingTo(charges);
      assertThat(result.lines().getFirst().lineTotal()).isEqualByComparingTo(result.total());
      assertThat(result.total().scale()).isEqualTo(4);
    }
  }

  @Test
  void convertsCaseReferencePricesForBottleQuantitiesWithoutFloatingPoint() {
    PricingResult result =
        QuotationPricingPolicy.price(
            "CNY",
            List.of(
                new LineDraft(
                    UUID.randomUUID(),
                    sku(),
                    new BigDecimal("12"),
                    QuantityUnit.BOTTLE,
                    null,
                    null,
                    new BigDecimal("0.1000"),
                    null,
                    price())),
            BigDecimal.ZERO);

    assertThat(result.lines().getFirst().listUnitPrice()).isEqualByComparingTo("210.0000");
    assertThat(result.lines().getFirst().netUnitPrice()).isEqualByComparingTo("189.0000");
    assertThat(result.total()).isEqualByComparingTo("2268.0000");
  }

  @Test
  void rejectsAOneHundredPercentDiscount() {
    assertThatThrownBy(
            () ->
                QuotationPricingPolicy.price(
                    "CNY", List.of(line(BigDecimal.ONE, BigDecimal.ONE)), BigDecimal.ZERO))
        .isInstanceOfSatisfying(
            QuotationDomainException.class,
            problem -> {
              assertThat(problem.code()).isEqualTo("VALIDATION_FAILED");
              assertThat(problem.kind()).isEqualTo(FailureKind.BUSINESS_RULE);
            });
  }

  private static LineDraft line(BigDecimal quantity, BigDecimal discount) {
    return new LineDraft(
        UUID.randomUUID(),
        sku(),
        quantity,
        QuantityUnit.CASE,
        null,
        "DOMESTIC_ON_HAND",
        discount,
        null,
        price());
  }

  private static PriceReference price() {
    return new PriceReference(
        UUID.fromString("34000000-0000-4000-8000-000000000001"),
        "CNY",
        new BigDecimal("1260.0000"),
        new BigDecimal("930.0000"),
        "PRICE-2026-01");
  }

  private static SkuSnapshot sku() {
    return new SkuSnapshot(
        UUID.fromString("34000000-0000-4000-8000-000000000001"),
        "CB-MTV-2019-750X6",
        "Moonlit Terrace",
        "Silver Vale Estate",
        "Lumen Valley",
        "FR",
        "RED",
        "2019",
        750,
        6,
        "CASE",
        1,
        Instant.parse("2026-07-13T00:00:00Z"));
  }
}
