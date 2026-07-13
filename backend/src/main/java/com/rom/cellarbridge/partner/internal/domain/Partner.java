package com.rom.cellarbridge.partner.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.partner.PartnerStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record Partner(
    UUID id,
    TenantId tenantId,
    String number,
    Profile profile,
    PartnerStatus status,
    UUID salesOwnerId,
    UUID submittedById,
    Instant submittedAt,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  private static final Pattern NON_IDENTIFIER = Pattern.compile("[^A-Z0-9]");
  private static final Pattern NON_NAME = Pattern.compile("[^\\p{L}\\p{N}]");
  private static final Set<String> SUPPORTED_ROUTE_CODES =
      Set.of("SH_GENERAL_TRADE", "NB_BONDED_B2B", "HK_FREE_TRADE");

  public Partner {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    requireText(number, "number");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(salesOwnerId, "salesOwnerId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static Partner draft(
      UUID id, TenantId tenantId, String number, Profile profile, UUID ownerId, Instant now) {
    return new Partner(
        id, tenantId, number, profile, PartnerStatus.DRAFT, ownerId, null, null, 0, now, now);
  }

  public Partner edit(Profile replacement, UUID actorId, Instant now) {
    if (status != PartnerStatus.DRAFT && status != PartnerStatus.CHANGES_REQUESTED) {
      throw invalidTransition("Only an editable draft can be changed");
    }
    return copy(replacement, status, submittedById, submittedAt, now);
  }

  public Transition submit(UUID actorId, Instant now, boolean potentialDuplicate) {
    if (status != PartnerStatus.DRAFT && status != PartnerStatus.CHANGES_REQUESTED) {
      throw invalidTransition("Partner cannot be submitted from " + status);
    }
    List<String> missing = profile.missingForSubmission();
    if (!missing.isEmpty()) {
      throw new PartnerDomainException(
          "PARTNER_PROFILE_INCOMPLETE", "Partner profile is incomplete", missing);
    }
    if (potentialDuplicate && isBlank(profile.duplicateResolutionNote())) {
      throw new PartnerDomainException(
          "PARTNER_POTENTIAL_DUPLICATE", "A similar legal name requires a documented difference");
    }
    Partner submitted = copy(profile, PartnerStatus.PENDING_REVIEW, actorId, now, now);
    return new Transition(submitted, EventType.SUBMITTED, null);
  }

  public Transition approve(UUID reviewerId, Review approval, Instant now) {
    requirePendingReview();
    requireIndependentReviewer(reviewerId);
    approval.validateApproval(profile);
    Eligibility eligibility =
        new Eligibility(
            approval.routeCodes().isEmpty() ? profile.requestedRouteCodes() : approval.routeCodes(),
            approval.serviceRegions().isEmpty()
                ? profile.requestedServiceRegions()
                : approval.serviceRegions(),
            approval.currencies().isEmpty() ? profile.requestedCurrencies() : approval.currencies(),
            approval.paymentTermDays() == null
                ? profile.requestedPaymentTermDays()
                : approval.paymentTermDays(),
            approval.creditLimitAmount(),
            approval.creditLimitCurrency(),
            now,
            reviewerId);
    return new Transition(
        copy(profile, PartnerStatus.ACTIVE, submittedById, submittedAt, now),
        EventType.ACTIVATED,
        eligibility);
  }

  public Transition requestChanges(UUID reviewerId, String reason, Instant now) {
    requirePendingReview();
    requireIndependentReviewer(reviewerId);
    requireReason(reason);
    return new Transition(
        copy(profile, PartnerStatus.CHANGES_REQUESTED, submittedById, submittedAt, now),
        EventType.CHANGES_REQUESTED,
        null);
  }

  public Transition reject(UUID reviewerId, String reason, Instant now) {
    requirePendingReview();
    requireIndependentReviewer(reviewerId);
    requireReason(reason);
    return new Transition(
        copy(profile, PartnerStatus.REJECTED, submittedById, submittedAt, now),
        EventType.REJECTED,
        null);
  }

  public Transition suspend(UUID actorId, String reason, Instant now) {
    if (status != PartnerStatus.ACTIVE) {
      throw invalidTransition("Only an active partner can be suspended");
    }
    requireReason(reason);
    return new Transition(
        copy(profile, PartnerStatus.SUSPENDED, submittedById, submittedAt, now),
        EventType.SUSPENDED,
        null);
  }

  public Transition requestReactivation(UUID actorId, String reason, Instant now) {
    if (status != PartnerStatus.SUSPENDED) {
      throw invalidTransition("Only a suspended partner can request reactivation");
    }
    requireReason(reason);
    return new Transition(
        copy(profile, PartnerStatus.PENDING_REVIEW, actorId, now, now), EventType.SUBMITTED, null);
  }

  public void requireActive() {
    if (status != PartnerStatus.ACTIVE) {
      throw new PartnerDomainException("PARTNER_NOT_ACTIVE", "Partner is not active");
    }
  }

  private Partner copy(
      Profile nextProfile,
      PartnerStatus nextStatus,
      UUID nextSubmittedBy,
      Instant nextSubmittedAt,
      Instant now) {
    return new Partner(
        id,
        tenantId,
        number,
        nextProfile,
        nextStatus,
        salesOwnerId,
        nextSubmittedBy,
        nextSubmittedAt,
        version,
        createdAt,
        now);
  }

  private void requirePendingReview() {
    if (status != PartnerStatus.PENDING_REVIEW) {
      throw invalidTransition("Partner is not pending review");
    }
  }

  private void requireIndependentReviewer(UUID reviewerId) {
    if (reviewerId.equals(submittedById)) {
      throw new PartnerDomainException(
          "PARTNER_REVIEWER_CONFLICT", "The submitter cannot review this partner");
    }
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.strip().length() < 5) {
      throw new PartnerDomainException(
          "VALIDATION_FAILED", "A reason of at least 5 characters is required");
    }
  }

  private PartnerDomainException invalidTransition(String message) {
    return new PartnerDomainException("INVALID_STATE_TRANSITION", message);
  }

  public static String normalizeIdentifier(String value) {
    if (isBlank(value)) {
      return null;
    }
    return NON_IDENTIFIER.matcher(value.toUpperCase(Locale.ROOT)).replaceAll("");
  }

  public static String normalizeLegalName(String value) {
    if (isBlank(value)) {
      return null;
    }
    return NON_NAME.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String blankToNull(String value) {
    return isBlank(value) ? null : value.strip();
  }

  private static void requireText(String value, String name) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  public enum PartnerType {
    RESTAURANT_GROUP,
    DISTRIBUTOR,
    RETAILER,
    CORPORATE_BUYER,
    OTHER
  }

  public enum ReviewDecision {
    APPROVE,
    REQUEST_CHANGES,
    REJECT
  }

  public enum EventType {
    SUBMITTED("PartnerSubmittedForReviewV1"),
    ACTIVATED("PartnerActivatedV1"),
    CHANGES_REQUESTED("PartnerChangesRequestedV1"),
    REJECTED("PartnerRejectedV1"),
    SUSPENDED("PartnerSuspendedV1");

    private final String value;

    EventType(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public record Contact(String name, String email, String phone, boolean primary) {
    public Contact {
      name = blankToNull(name);
      email = blankToNull(email);
      phone = blankToNull(phone);
    }
  }

  public record Address(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {
    public Address {
      countryCode = blankToNull(countryCode);
      province = blankToNull(province);
      city = blankToNull(city);
      district = blankToNull(district);
      line1 = blankToNull(line1);
      postalCode = blankToNull(postalCode);
    }
  }

  public record Profile(
      String legalName,
      String displayName,
      String registrationIdentifier,
      PartnerType type,
      String defaultCurrency,
      Integer requestedPaymentTermDays,
      Set<String> requestedRouteCodes,
      Set<String> requestedServiceRegions,
      Set<String> requestedCurrencies,
      Contact contact,
      Address billingAddress,
      String duplicateResolutionNote) {

    public Profile {
      legalName = blankToNull(legalName);
      displayName = blankToNull(displayName);
      registrationIdentifier = blankToNull(registrationIdentifier);
      defaultCurrency = blankToNull(defaultCurrency);
      duplicateResolutionNote = blankToNull(duplicateResolutionNote);
      requestedRouteCodes = Set.copyOf(requestedRouteCodes);
      requestedServiceRegions = Set.copyOf(requestedServiceRegions);
      requestedCurrencies = Set.copyOf(requestedCurrencies);
      if (contact != null
          && contact.name() == null
          && contact.email() == null
          && contact.phone() == null) {
        contact = null;
      }
      if (billingAddress != null
          && billingAddress.countryCode() == null
          && billingAddress.province() == null
          && billingAddress.city() == null
          && billingAddress.district() == null
          && billingAddress.line1() == null
          && billingAddress.postalCode() == null) {
        billingAddress = null;
      }
      if (requestedPaymentTermDays != null
          && (requestedPaymentTermDays < 0 || requestedPaymentTermDays > 180)) {
        throw new IllegalArgumentException("requestedPaymentTermDays is out of range");
      }
      requireSupportedRoutes(requestedRouteCodes);
    }

    public String normalizedLegalName() {
      return normalizeLegalName(legalName);
    }

    public String normalizedRegistrationIdentifier() {
      return normalizeIdentifier(registrationIdentifier);
    }

    List<String> missingForSubmission() {
      List<String> missing = new ArrayList<>();
      if (isBlank(legalName)) missing.add("legalName");
      if (isBlank(displayName)) missing.add("displayName");
      if (isBlank(registrationIdentifier)) missing.add("registrationIdentifier");
      if (type == null) missing.add("type");
      if (isBlank(defaultCurrency)) missing.add("defaultCurrency");
      if (contact == null || isBlank(contact.name()) || isBlank(contact.email())) {
        missing.add("contact");
      }
      if (billingAddress == null
          || isBlank(billingAddress.countryCode())
          || isBlank(billingAddress.province())
          || isBlank(billingAddress.city())
          || isBlank(billingAddress.line1())) {
        missing.add("billingAddress");
      }
      if (requestedPaymentTermDays == null) missing.add("requestedPaymentTermDays");
      if (requestedRouteCodes.isEmpty()) missing.add("requestedRouteCodes");
      if (requestedServiceRegions.isEmpty()) missing.add("requestedServiceRegions");
      if (requestedCurrencies.isEmpty()) missing.add("requestedCurrencies");
      return List.copyOf(missing);
    }
  }

  public record Review(
      String reason,
      Integer paymentTermDays,
      BigDecimal creditLimitAmount,
      String creditLimitCurrency,
      Set<String> routeCodes,
      Set<String> serviceRegions,
      Set<String> currencies) {

    public Review {
      requireReason(reason);
      routeCodes = Set.copyOf(routeCodes);
      serviceRegions = Set.copyOf(serviceRegions);
      currencies = Set.copyOf(currencies);
      if (paymentTermDays != null && (paymentTermDays < 0 || paymentTermDays > 180)) {
        throw new PartnerDomainException(
            "VALIDATION_FAILED", "Approved payment term is out of range");
      }
      if (creditLimitAmount != null && creditLimitAmount.signum() < 0) {
        throw new PartnerDomainException("VALIDATION_FAILED", "Credit limit cannot be negative");
      }
      requireSupportedRoutes(routeCodes);
    }

    void validateApproval(Profile requested) {
      Set<String> resolvedRoutes =
          routeCodes.isEmpty() ? requested.requestedRouteCodes() : routeCodes;
      Set<String> resolvedRegions =
          serviceRegions.isEmpty() ? requested.requestedServiceRegions() : serviceRegions;
      Set<String> resolvedCurrencies =
          currencies.isEmpty() ? requested.requestedCurrencies() : currencies;
      Integer resolvedTerm =
          paymentTermDays == null ? requested.requestedPaymentTermDays() : paymentTermDays;
      if (resolvedRoutes.isEmpty()
          || resolvedRegions.isEmpty()
          || resolvedCurrencies.isEmpty()
          || resolvedTerm == null) {
        throw new PartnerDomainException(
            "PARTNER_PROFILE_INCOMPLETE", "Approved eligibility is incomplete");
      }
      if (creditLimitAmount != null
          && (isBlank(creditLimitCurrency) || !resolvedCurrencies.contains(creditLimitCurrency))) {
        throw new PartnerDomainException(
            "VALIDATION_FAILED", "Credit limit currency must be an approved currency");
      }
    }
  }

  public record Eligibility(
      Set<String> routeCodes,
      Set<String> serviceRegions,
      Set<String> currencies,
      int paymentTermDays,
      BigDecimal creditLimitAmount,
      String creditLimitCurrency,
      Instant effectiveFrom,
      UUID approvedBy) {

    public Eligibility {
      routeCodes = Set.copyOf(routeCodes);
      serviceRegions = Set.copyOf(serviceRegions);
      currencies = Set.copyOf(currencies);
      requireSupportedRoutes(routeCodes);
    }
  }

  private static void requireSupportedRoutes(Set<String> routeCodes) {
    if (!SUPPORTED_ROUTE_CODES.containsAll(routeCodes)) {
      throw new PartnerDomainException("VALIDATION_FAILED", "Trade route code is unsupported");
    }
  }

  public record Transition(Partner partner, EventType eventType, Eligibility eligibility) {}
}
