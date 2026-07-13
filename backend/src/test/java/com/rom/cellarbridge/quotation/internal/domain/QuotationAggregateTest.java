package com.rom.cellarbridge.quotation.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Address;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Decision;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.DraftTerms;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.PartnerSnapshot;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.LineDraft;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PriceReference;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.SkuSnapshot;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteScore;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuotationAggregateTest {

  private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
  private static final UUID OWNER = UUID.fromString("11200000-0000-4000-8000-000000000001");
  private static final UUID MANAGER = UUID.fromString("11200000-0000-4000-8000-000000000003");

  @Test
  void freezesSubmittedRevisionAndRequiresNewRevisionAfterRequestedChanges() {
    QuotationAggregate routed = draft().applyRoute(evaluation(), pricing(), NOW.plusSeconds(1));
    QuotationAggregate submitted =
        routed
            .submit(
                List.of(
                    new QuotationApprovalPolicy.Requirement(
                        "QUO-DISCOUNT-001",
                        "DISCOUNT_THRESHOLD_EXCEEDED",
                        "0.0900",
                        "0.0800",
                        "Discount requires approval")),
                OWNER,
                NOW.plusSeconds(2))
            .quotation();
    assertThat(submitted.status()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    assertThat(submitted.revision().frozenAt()).isNotNull();
    assertThatThrownBy(() -> submitted.applyRoute(evaluation(), pricing(), NOW.plusSeconds(3)))
        .isInstanceOf(QuotationProblem.class);

    QuotationAggregate changes =
        submitted.decide(
            Decision.REQUEST_CHANGES, MANAGER, "Please revise discount", NOW.plusSeconds(4));
    assertThatThrownBy(() -> changes.applyRoute(evaluation(), pricing(), NOW.plusSeconds(5)))
        .isInstanceOf(QuotationProblem.class);
    QuotationAggregate revised =
        changes.replaceDraft(partner(), terms(), pricing(), NOW.plusSeconds(6));
    assertThat(revised.currentRevision()).isEqualTo(2);
    assertThat(revised.revision().id()).isNotEqualTo(submitted.revision().id());
    assertThat(submitted.revision().frozenAt()).isNotNull();
  }

  @Test
  void prohibitsSubmitterFromApprovingOwnRevision() {
    QuotationAggregate submitted =
        draft()
            .applyRoute(evaluation(), pricing(), NOW.plusSeconds(1))
            .submit(
                List.of(
                    new QuotationApprovalPolicy.Requirement(
                        "QUO-DISCOUNT-001", "DISCOUNT", "0.09", "0.08", "Approval")),
                OWNER,
                NOW.plusSeconds(2))
            .quotation();

    assertThatThrownBy(
            () ->
                submitted.decide(Decision.APPROVE, OWNER, "Approve quotation", NOW.plusSeconds(3)))
        .isInstanceOfSatisfying(
            QuotationProblem.class,
            problem -> assertThat(problem.code()).isEqualTo("QUOTE_REVIEWER_CONFLICT"));
  }

  private static QuotationAggregate draft() {
    return QuotationAggregate.draft(
        UUID.randomUUID(),
        new TenantId(UUID.fromString("10000000-0000-4000-8000-000000000001")),
        "QUO-202607-000001",
        partner().partnerId(),
        OWNER,
        partner(),
        terms(),
        pricing(),
        NOW);
  }

  private static PartnerSnapshot partner() {
    return new PartnerSnapshot(
        UUID.fromString("53000000-0000-4000-8000-000000000001"),
        "PAR-DEMO-QUOTATION",
        "Aurora Market Services",
        30,
        1,
        NOW);
  }

  private static DraftTerms terms() {
    return new DraftTerms(
        "CNY",
        LocalDate.of(2026, 7, 30),
        NOW.plusSeconds(864_000),
        30,
        new Address("CN", "Shanghai", "Shanghai", "Pudong", "88 Harbor Avenue", "200120"));
  }

  private static QuotationPricingPolicy.PricingResult pricing() {
    SkuSnapshot sku =
        new SkuSnapshot(
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
            NOW);
    return QuotationPricingPolicy.price(
        "CNY",
        List.of(
            new LineDraft(
                UUID.randomUUID(),
                sku,
                new BigDecimal("6"),
                QuantityUnit.CASE,
                null,
                "DOMESTIC_ON_HAND",
                new BigDecimal("0.0900"),
                null,
                new PriceReference(
                    sku.skuId(),
                    "CNY",
                    new BigDecimal("1260"),
                    new BigDecimal("930"),
                    "PRICE-2026-01"))),
        new BigDecimal("90"));
  }

  private static RouteEvaluation evaluation() {
    return new RouteEvaluation(
        UUID.randomUUID(),
        "ROUTE-2026-01",
        NOW,
        "a".repeat(64),
        List.of(
            new RouteCandidate(
                TradeRouteCode.SH_GENERAL_TRADE,
                Eligibility.ELIGIBLE,
                new RouteScore(
                    new BigDecimal("72"),
                    new BigDecimal("92"),
                    new BigDecimal("95"),
                    new BigDecimal("92"),
                    new BigDecimal("84.60")),
                LocalDate.of(2026, 7, 19),
                new BigDecimal("90"),
                "CNY",
                List.of())),
        TradeRouteCode.SH_GENERAL_TRADE,
        TradeRouteCode.SH_GENERAL_TRADE,
        null);
  }
}
