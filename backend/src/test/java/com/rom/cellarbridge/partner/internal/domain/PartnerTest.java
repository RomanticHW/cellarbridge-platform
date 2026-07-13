package com.rom.cellarbridge.partner.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.partner.PartnerStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartnerTest {

  private static final TenantId TENANT_ID =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final UUID OWNER_ID = UUID.fromString("11200000-0000-4000-8000-000000000001");
  private static final UUID REVIEWER_ID = UUID.fromString("11200000-0000-4000-8000-000000000003");
  private static final Instant NOW = Instant.parse("2026-07-13T08:00:00Z");

  @Test
  void submissionRequiresACompleteProfileAndDocumentsPotentialDuplicates() {
    Partner emptyDraft =
        draft(
            new Partner.Profile(
                null, null, null, null, null, null, Set.of(), Set.of(), Set.of(), null, null,
                null));

    assertThatThrownBy(() -> emptyDraft.submit(OWNER_ID, NOW.plusSeconds(1), false))
        .isInstanceOfSatisfying(
            PartnerDomainException.class,
            problem -> {
              assertThat(problem.code()).isEqualTo("PARTNER_PROFILE_INCOMPLETE");
              assertThat(problem.fields())
                  .containsExactly(
                      "legalName",
                      "displayName",
                      "registrationIdentifier",
                      "type",
                      "defaultCurrency",
                      "contact",
                      "billingAddress",
                      "requestedPaymentTermDays",
                      "requestedRouteCodes",
                      "requestedServiceRegions",
                      "requestedCurrencies");
            });

    Partner incomplete = draft(profile(null, Set.of(), Set.of(), Set.of(), null));

    assertThatThrownBy(() -> incomplete.submit(OWNER_ID, NOW.plusSeconds(1), false))
        .isInstanceOfSatisfying(
            PartnerDomainException.class,
            problem -> {
              assertThat(problem.code()).isEqualTo("PARTNER_PROFILE_INCOMPLETE");
              assertThat(problem.fields())
                  .containsExactly(
                      "registrationIdentifier",
                      "requestedPaymentTermDays",
                      "requestedRouteCodes",
                      "requestedServiceRegions",
                      "requestedCurrencies");
            });

    Partner complete =
        draft(profile("REG-301", Set.of("SH_GENERAL_TRADE"), Set.of("CN-SH"), Set.of("CNY"), 30));
    assertThatThrownBy(() -> complete.submit(OWNER_ID, NOW.plusSeconds(1), true))
        .isInstanceOfSatisfying(
            PartnerDomainException.class,
            problem -> assertThat(problem.code()).isEqualTo("PARTNER_POTENTIAL_DUPLICATE"));
  }

  @Test
  void independentReviewerCreatesAnImmutableEligibilitySnapshot() {
    Partner pending =
        draft(profile("REG-302", Set.of("SH_GENERAL_TRADE"), Set.of("CN-SH"), Set.of("CNY"), 30))
            .submit(OWNER_ID, NOW.plusSeconds(1), false)
            .partner();

    assertThatThrownBy(
            () ->
                pending.approve(
                    OWNER_ID,
                    review(Set.of(), Set.of(), Set.of(), null, null, null),
                    NOW.plusSeconds(2)))
        .isInstanceOfSatisfying(
            PartnerDomainException.class,
            problem -> assertThat(problem.code()).isEqualTo("PARTNER_REVIEWER_CONFLICT"));

    Partner.Transition approved =
        pending.approve(
            REVIEWER_ID,
            review(
                Set.of("SH_GENERAL_TRADE", "NB_BONDED_B2B"),
                Set.of("CN-SH", "CN-ZJ"),
                Set.of("CNY"),
                45,
                new BigDecimal("50000.00"),
                "CNY"),
            NOW.plusSeconds(2));

    assertThat(approved.partner().status()).isEqualTo(PartnerStatus.ACTIVE);
    assertThat(approved.eventType()).isEqualTo(Partner.EventType.ACTIVATED);
    assertThat(approved.eligibility().routeCodes())
        .containsExactlyInAnyOrder("SH_GENERAL_TRADE", "NB_BONDED_B2B");
    assertThat(approved.eligibility().paymentTermDays()).isEqualTo(45);
    assertThat(approved.eligibility().approvedBy()).isEqualTo(REVIEWER_ID);
  }

  @Test
  void supportsChangesRejectionSuspensionAndReactivationOnlyFromValidStates() {
    Partner pending =
        draft(profile("REG-303", Set.of("SH_GENERAL_TRADE"), Set.of("CN-SH"), Set.of("CNY"), 30))
            .submit(OWNER_ID, NOW.plusSeconds(1), false)
            .partner();
    Partner changes =
        pending.requestChanges(REVIEWER_ID, "Add a second contact", NOW.plusSeconds(2)).partner();
    assertThat(changes.status()).isEqualTo(PartnerStatus.CHANGES_REQUESTED);
    Partner resubmitted = changes.submit(OWNER_ID, NOW.plusSeconds(3), false).partner();
    Partner rejected =
        resubmitted.reject(REVIEWER_ID, "Coverage is outside policy", NOW.plusSeconds(4)).partner();
    assertThat(rejected.status()).isEqualTo(PartnerStatus.REJECTED);

    Partner active =
        pending
            .approve(
                REVIEWER_ID,
                review(Set.of(), Set.of(), Set.of(), null, null, null),
                NOW.plusSeconds(2))
            .partner();
    Partner suspended =
        active.suspend(REVIEWER_ID, "Commercial hold", NOW.plusSeconds(3)).partner();
    assertThat(suspended.status()).isEqualTo(PartnerStatus.SUSPENDED);
    assertThatThrownBy(suspended::requireActive)
        .isInstanceOfSatisfying(
            PartnerDomainException.class,
            problem -> assertThat(problem.code()).isEqualTo("PARTNER_NOT_ACTIVE"));
    Partner reactivation =
        suspended.requestReactivation(OWNER_ID, "Documents updated", NOW.plusSeconds(4)).partner();
    assertThat(reactivation.status()).isEqualTo(PartnerStatus.PENDING_REVIEW);

    assertThatThrownBy(() -> rejected.edit(rejected.profile(), OWNER_ID, NOW.plusSeconds(5)))
        .isInstanceOfSatisfying(
            PartnerDomainException.class,
            problem -> assertThat(problem.code()).isEqualTo("INVALID_STATE_TRANSITION"));
  }

  @Test
  void normalizesLegalNamesWithoutDiscardingUnicodeLetters() {
    assertThat(Partner.normalizeLegalName("上海·枫林 酒业（集团）")).isEqualTo("上海枫林酒业集团");
    assertThat(Partner.normalizeLegalName("CÉPAGE & Cie.")).isEqualTo("cépagecie");
  }

  private static Partner draft(Partner.Profile profile) {
    return Partner.draft(UUID.randomUUID(), TENANT_ID, "PAR-202607-000301", profile, OWNER_ID, NOW);
  }

  private static Partner.Profile profile(
      String registrationIdentifier,
      Set<String> routes,
      Set<String> regions,
      Set<String> currencies,
      Integer paymentTermDays) {
    return new Partner.Profile(
        "Cedar Dining Group",
        "Cedar Dining",
        registrationIdentifier,
        Partner.PartnerType.RESTAURANT_GROUP,
        "CNY",
        paymentTermDays,
        routes,
        regions,
        currencies,
        new Partner.Contact("Lin Wen", "lin.wen@example.test", "13800003001", true),
        new Partner.Address("CN", "Shanghai", "Shanghai", "Xuhui", "301 Huaihai Road", "200030"),
        null);
  }

  private static Partner.Review review(
      Set<String> routes,
      Set<String> regions,
      Set<String> currencies,
      Integer term,
      BigDecimal credit,
      String creditCurrency) {
    return new Partner.Review(
        "Verified commercial profile", term, credit, creditCurrency, routes, regions, currencies);
  }
}
