package com.rom.cellarbridge.quotation.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.QuotationSupplyDecisionStatus;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Address;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Decision;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.DraftTerms;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.PartnerSnapshot;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Revision;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.LineDraft;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PriceReference;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricingResult;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.SkuSnapshot;
import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteScore;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuotationAggregateTest {

  private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
  private static final UUID OWNER = UUID.fromString("11200000-0000-4000-8000-000000000001");
  private static final UUID MANAGER = UUID.fromString("11200000-0000-4000-8000-000000000003");
  private static final UUID LINE_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
  private static final UUID SKU_ID = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final UUID EVALUATION_ID = UUID.fromString("62000000-0000-4000-8000-000000000001");
  private static final UUID POOL_ID = UUID.fromString("36000000-0000-4000-8000-000000000001");

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
        .isInstanceOfSatisfying(
            QuotationDomainException.class,
            failure -> assertThat(failure.currentState()).isEqualTo("PENDING_APPROVAL"));

    QuotationAggregate changes =
        submitted.decide(
            Decision.REQUEST_CHANGES, MANAGER, "Please revise discount", NOW.plusSeconds(4));
    assertThatThrownBy(() -> changes.applyRoute(evaluation(), pricing(), NOW.plusSeconds(5)))
        .isInstanceOf(QuotationDomainException.class);
    QuotationAggregate revised =
        changes.replaceDraft(partner(), terms(), pricing(), NOW.plusSeconds(6));
    assertThat(revised.currentRevision()).isEqualTo(2);
    assertThat(revised.revision().id()).isNotEqualTo(submitted.revision().id());
    assertThat(submitted.revision().frozenAt()).isNotNull();
  }

  @Test
  void resetsDraftEvidenceAndRequiresACompleteDecisionBeforeSubmission() {
    QuotationAggregate draft = draft();
    assertThat(draft.revision().supplyDecisionStatus())
        .isEqualTo(QuotationSupplyDecisionStatus.UNDECIDED);
    assertProblem(
        () -> draft.submit(List.of(), OWNER, NOW.plusSeconds(1)), "QUOTE_SUPPLY_DECISION_REQUIRED");

    QuotationAggregate routed = draft.applyRoute(evaluation(), pricing(), NOW.plusSeconds(1));
    assertThat(routed.revision().supplyDecisionStatus())
        .isEqualTo(QuotationSupplyDecisionStatus.FROZEN);
    assertThat(routed.revision().pricing().lines().getFirst().allocationMode())
        .isEqualTo(SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO);

    QuotationAggregate replaced =
        routed.replaceDraft(partner(), terms(), pricing(), NOW.plusSeconds(2));
    assertThat(replaced.revision().supplyDecisionStatus())
        .isEqualTo(QuotationSupplyDecisionStatus.UNDECIDED);
    assertThat(replaced.revision().supplyDecision()).isNull();
  }

  @Test
  void rejectsPlanningDecisionLinesThatDoNotExactlyMatchTheRevision() {
    SupplyDecisionSnapshot mismatched =
        SupplyDecisionSnapshot.create(
            SupplyDecisionSnapshot.POLICY_VERSION,
            NOW,
            EVALUATION_ID,
            "a".repeat(64),
            TradeRouteCode.SH_GENERAL_TRADE,
            NOW,
            List.of(
                new SupplyDecisionSnapshot.LineDecision(
                    UUID.randomUUID(),
                    SKU_ID,
                    new BigDecimal("6.000000"),
                    TradePlanningQuantityUnit.CASE,
                    SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
                    null,
                    TradePlanningSupplyType.DOMESTIC_ON_HAND)));
    RouteEvaluation evaluation = evaluation(mismatched);
    assertProblem(
        () -> draft().applyRoute(evaluation, pricing(), NOW.plusSeconds(1)),
        "QUOTE_SUPPLY_DECISION_CONFLICT");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("frozenLineTamperCases")
  void rejectsFrozenLineTamper(
      String caseName,
      SupplyAllocationMode persistedAllocationMode,
      String persistedSupplyType,
      String expectedCode) {
    SupplyDecisionSnapshot decision =
        decision(
            SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
            null,
            TradePlanningSupplyType.DOMESTIC_ON_HAND);

    assertProblem(
        () -> frozenRevision(pricing(persistedAllocationMode, persistedSupplyType, null), decision),
        expectedCode);
  }

  private static Stream<Arguments> frozenLineTamperCases() {
    return Stream.of(
        Arguments.of(
            "missing persisted allocation mode",
            null,
            "DOMESTIC_ON_HAND",
            "QUOTE_SUPPLY_DECISION_CONFLICT"),
        Arguments.of(
            "missing persisted supply type",
            SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
            null,
            "QUOTE_SUPPLY_DECISION_CONFLICT"),
        Arguments.of(
            "mismatched persisted supply type",
            SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
            "BONDED_ON_HAND",
            "QUOTE_SUPPLY_DECISION_CONFLICT"));
  }

  @Test
  void acceptsFrozenAutoLineWithExactSupplyEvidence() {
    Revision revision =
        frozenRevision(
            pricing(SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO, "DOMESTIC_ON_HAND", null),
            decision(
                SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
                null,
                TradePlanningSupplyType.DOMESTIC_ON_HAND));

    assertThat(revision.supplyDecisionStatus()).isEqualTo(QuotationSupplyDecisionStatus.FROZEN);
  }

  @Test
  void acceptsFrozenFixedLineWithExactSupplyEvidence() {
    Revision revision =
        frozenRevision(
            pricing(SupplyAllocationMode.FIXED_POOL, "DOMESTIC_ON_HAND", POOL_ID),
            decision(
                SupplyAllocationMode.FIXED_POOL,
                POOL_ID,
                TradePlanningSupplyType.DOMESTIC_ON_HAND));

    assertThat(revision.supplyDecisionStatus()).isEqualTo(QuotationSupplyDecisionStatus.FROZEN);
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
            QuotationDomainException.class,
            problem -> assertThat(problem.code()).isEqualTo("QUOTE_REVIEWER_CONFLICT"));
  }

  @Test
  void appliesTheCustomerDecisionDeadlineAtTheExactInstant() {
    Instant expiresAt = NOW.plusSeconds(60);

    QuotationAggregate acceptanceSource = sent(expiresAt);
    QuotationAggregate accepted =
        acceptanceSource.accept(acceptanceSource.revision().id(), expiresAt.minusMillis(1));
    assertThat(accepted.status()).isEqualTo(QuotationStatus.ACCEPTED);

    QuotationAggregate rejectedSource = sent(expiresAt);
    QuotationAggregate rejected =
        rejectedSource.reject(rejectedSource.revision().id(), expiresAt.minusMillis(1));
    assertThat(rejected.status()).isEqualTo(QuotationStatus.REJECTED_BY_CUSTOMER);

    QuotationAggregate atBoundary = sent(expiresAt);
    assertProblem(() -> atBoundary.accept(atBoundary.revision().id(), expiresAt), "QUOTE_EXPIRED");
    assertProblem(() -> atBoundary.reject(atBoundary.revision().id(), expiresAt), "QUOTE_EXPIRED");
    assertProblem(() -> atBoundary.expire(expiresAt.minusMillis(1)), "QUOTE_NOT_EXPIRED");
    assertThat(atBoundary.expire(expiresAt).status()).isEqualTo(QuotationStatus.EXPIRED);
  }

  @Test
  void makesAcceptRejectAndExpireMutuallyExclusiveTerminalTransitions() {
    Instant expiresAt = NOW.plusSeconds(60);
    Instant decisionTime = expiresAt.minusSeconds(1);

    QuotationAggregate acceptanceSource = sent(expiresAt);
    QuotationAggregate accepted =
        acceptanceSource.accept(acceptanceSource.revision().id(), decisionTime);
    assertProblem(
        () -> accepted.reject(accepted.revision().id(), decisionTime), "QUOTE_ALREADY_DECIDED");
    assertProblem(() -> accepted.expire(expiresAt), "QUOTE_NOT_ACCEPTABLE");

    QuotationAggregate rejectionSource = sent(expiresAt);
    QuotationAggregate rejected =
        rejectionSource.reject(rejectionSource.revision().id(), decisionTime);
    assertProblem(
        () -> rejected.accept(rejected.revision().id(), decisionTime), "QUOTE_ALREADY_DECIDED");
    assertProblem(() -> rejected.expire(expiresAt), "QUOTE_NOT_ACCEPTABLE");

    QuotationAggregate expirationSource = sent(expiresAt);
    QuotationAggregate expired = expirationSource.expire(expiresAt);
    assertProblem(() -> expired.accept(expired.revision().id(), expiresAt), "QUOTE_EXPIRED");
    assertProblem(() -> expired.reject(expired.revision().id(), expiresAt), "QUOTE_EXPIRED");
    assertProblem(() -> expired.expire(expiresAt), "QUOTE_NOT_ACCEPTABLE");
  }

  private static QuotationAggregate draft() {
    return draft(terms());
  }

  private static QuotationAggregate draft(DraftTerms terms) {
    return QuotationAggregate.draft(
        UUID.randomUUID(),
        new TenantId(UUID.fromString("10000000-0000-4000-8000-000000000001")),
        "QUO-202607-000001",
        partner().partnerId(),
        OWNER,
        partner(),
        terms,
        pricing(),
        NOW);
  }

  private static QuotationAggregate sent(Instant expiresAt) {
    DraftTerms terms =
        new DraftTerms(
            "CNY",
            LocalDate.of(2026, 7, 30),
            expiresAt,
            30,
            new Address("CN", "Shanghai", "Shanghai", "Pudong", "88 Harbor Avenue", "200120"));
    return draft(terms)
        .applyRoute(evaluation(), pricing(), NOW.plusSeconds(1))
        .submit(List.of(), OWNER, NOW.plusSeconds(2))
        .quotation()
        .issue(NOW.plusSeconds(3));
  }

  private static void assertProblem(Runnable transition, String code) {
    assertThatThrownBy(transition::run)
        .isInstanceOfSatisfying(
            QuotationDomainException.class, problem -> assertThat(problem.code()).isEqualTo(code));
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
    return pricing(null, null, null);
  }

  private static QuotationPricingPolicy.PricingResult pricing(
      SupplyAllocationMode allocationMode, String supplyType, UUID supplyPoolId) {
    SkuSnapshot sku =
        new SkuSnapshot(
            SKU_ID,
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
                LINE_ID,
                sku,
                new BigDecimal("6"),
                QuantityUnit.CASE,
                supplyPoolId,
                allocationMode,
                supplyType,
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
    return evaluation(
        decision(
            SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
            null,
            TradePlanningSupplyType.DOMESTIC_ON_HAND));
  }

  private static SupplyDecisionSnapshot decision(
      SupplyAllocationMode allocationMode, UUID supplyPoolId, TradePlanningSupplyType supplyType) {
    return SupplyDecisionSnapshot.create(
        SupplyDecisionSnapshot.POLICY_VERSION,
        NOW,
        EVALUATION_ID,
        "a".repeat(64),
        TradeRouteCode.SH_GENERAL_TRADE,
        NOW,
        List.of(
            new SupplyDecisionSnapshot.LineDecision(
                LINE_ID,
                SKU_ID,
                new BigDecimal("6.000000"),
                TradePlanningQuantityUnit.CASE,
                allocationMode,
                supplyPoolId,
                supplyType)));
  }

  private static Revision frozenRevision(
      PricingResult frozenPricing, SupplyDecisionSnapshot decision) {
    Revision draft = draft().revision();
    return new Revision(
        draft.id(),
        draft.number(),
        draft.partnerSnapshot(),
        draft.terms(),
        frozenPricing,
        EVALUATION_ID,
        "ROUTE-2026-03",
        TradeRouteCode.SH_GENERAL_TRADE,
        TradeRouteCode.SH_GENERAL_TRADE,
        null,
        QuotationSupplyDecisionStatus.FROZEN,
        decision,
        draft.pricePolicyVersion(),
        draft.approvalPolicyVersion(),
        List.of(),
        null,
        draft.createdAt());
  }

  private static RouteEvaluation evaluation(SupplyDecisionSnapshot decision) {
    return new RouteEvaluation(
        EVALUATION_ID,
        "ROUTE-2026-03",
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
        null,
        decision);
  }
}
