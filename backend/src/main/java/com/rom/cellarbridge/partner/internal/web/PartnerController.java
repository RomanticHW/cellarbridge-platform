package com.rom.cellarbridge.partner.internal.web;

import com.rom.cellarbridge.partner.PartnerStatus;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.CreateCommand;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.EligibilityView;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.ListCommand;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.PartnerCommandView;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.PartnerDetailView;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.PartnerListView;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.ReviewCommand;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.TimelineView;
import com.rom.cellarbridge.partner.internal.application.PartnerApplicationService.UpdateCommand;
import com.rom.cellarbridge.partner.internal.domain.Partner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/partners")
final class PartnerController {

  private static final String ROUTE_CODE_PATTERN =
      "^(SH_GENERAL_TRADE|NB_BONDED_B2B|HK_FREE_TRADE)$";

  private final PartnerApplicationService service;

  PartnerController(PartnerApplicationService service) {
    this.service = service;
  }

  @GetMapping
  PartnerPageResponse list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Set<PartnerStatus> status,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) @Pattern(regexp = ROUTE_CODE_PATTERN) String routeCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant updatedFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant updatedTo,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    PartnerListView result =
        service.list(
            new ListCommand(
                keyword,
                status == null ? Set.of() : status,
                ownerId,
                routeCode,
                updatedFrom,
                updatedTo,
                pageSize,
                cursor));
    return new PartnerPageResponse(
        result.items().stream().map(PartnerSummaryResponse::from).toList(),
        new PageInfoResponse(result.nextCursor(), result.hasNext(), result.pageSize()));
  }

  @PostMapping
  ResponseEntity<PartnerDetailResponse> create(@Valid @RequestBody CreatePartnerRequest request) {
    PartnerDetailView created = service.create(request.toCommand());
    return ResponseEntity.created(URI.create("/api/v1/partners/" + created.summary().id()))
        .eTag(etag(created.summary().version()))
        .body(PartnerDetailResponse.from(created));
  }

  @GetMapping("/{partnerId}")
  ResponseEntity<PartnerDetailResponse> get(@PathVariable UUID partnerId) {
    PartnerDetailView detail = service.get(partnerId);
    return ResponseEntity.ok()
        .eTag(etag(detail.summary().version()))
        .body(PartnerDetailResponse.from(detail));
  }

  @PatchMapping(
      path = "/{partnerId}",
      consumes = "application/merge-patch+json",
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<PartnerDetailResponse> update(
      @PathVariable UUID partnerId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody UpdatePartnerRequest request) {
    PartnerDetailView updated =
        service.update(partnerId, expectedVersion(ifMatch), request.toCommand());
    return ResponseEntity.ok()
        .eTag(etag(updated.summary().version()))
        .body(PartnerDetailResponse.from(updated));
  }

  @PostMapping("/{partnerId}/submission")
  ResponseEntity<PartnerCommandResponse> submit(
      @PathVariable UUID partnerId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
    PartnerCommandView result = service.submit(partnerId, expectedVersion(ifMatch));
    return ResponseEntity.ok()
        .eTag(etag(result.version()))
        .body(PartnerCommandResponse.from(result));
  }

  @PostMapping("/{partnerId}/review")
  ResponseEntity<PartnerCommandResponse> review(
      @PathVariable UUID partnerId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody PartnerReviewRequest request) {
    PartnerCommandView result =
        service.review(partnerId, expectedVersion(ifMatch), request.toCommand());
    return ResponseEntity.ok()
        .eTag(etag(result.version()))
        .body(PartnerCommandResponse.from(result));
  }

  @PostMapping("/{partnerId}/suspension")
  ResponseEntity<PartnerCommandResponse> suspend(
      @PathVariable UUID partnerId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody ReasonRequest request) {
    PartnerCommandView result =
        service.suspend(partnerId, expectedVersion(ifMatch), strip(request.reason()));
    return ResponseEntity.ok()
        .eTag(etag(result.version()))
        .body(PartnerCommandResponse.from(result));
  }

  @PostMapping("/{partnerId}/reactivation")
  ResponseEntity<PartnerCommandResponse> reactivate(
      @PathVariable UUID partnerId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody ReasonRequest request) {
    PartnerCommandView result =
        service.requestReactivation(partnerId, expectedVersion(ifMatch), strip(request.reason()));
    return ResponseEntity.ok()
        .eTag(etag(result.version()))
        .body(PartnerCommandResponse.from(result));
  }

  private static long expectedVersion(String ifMatch) {
    if (ifMatch == null) {
      throw com.rom.cellarbridge.partner.internal.application.PartnerProblemException
          .preconditionRequired();
    }
    if (!ifMatch.matches("\"[0-9]+\"")) {
      throw com.rom.cellarbridge.partner.internal.application.PartnerProblemException.badRequest(
          "VALIDATION_FAILED", "If-Match must contain a quoted numeric version");
    }
    return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
  }

  private static String etag(long version) {
    return "\"" + version + "\"";
  }

  private static String strip(String value) {
    return value == null ? null : value.strip();
  }

  record ContactRequest(
      @Size(max = 100) String name,
      @Email @Size(max = 254) String email,
      @Size(max = 40) String phone,
      boolean primary) {
    Partner.Contact toDomain() {
      return new Partner.Contact(strip(name), strip(email), strip(phone), primary);
    }
  }

  record AddressRequest(
      @Pattern(regexp = "^$|^[A-Z]{2}$") String countryCode,
      @Size(max = 100) String province,
      @Size(max = 100) String city,
      @Size(max = 100) String district,
      @Size(max = 200) String line1,
      @Size(max = 20) String postalCode) {
    Partner.Address toDomain() {
      return new Partner.Address(
          strip(countryCode),
          strip(province),
          strip(city),
          strip(district),
          strip(line1),
          strip(postalCode));
    }
  }

  record CreatePartnerRequest(
      @Size(max = 200) String legalName,
      @Size(max = 100) String displayName,
      @Size(max = 100) String registrationIdentifier,
      Partner.PartnerType type,
      @Pattern(regexp = "^$|^[A-Z]{3}$") String defaultCurrency,
      @Min(0) @Max(180) Integer requestedPaymentTermDays,
      Set<@Pattern(regexp = ROUTE_CODE_PATTERN) String> requestedRouteCodes,
      Set<@NotBlank @Size(max = 80) String> requestedServiceRegions,
      Set<@Pattern(regexp = "^[A-Z]{3}$") String> requestedCurrencies,
      @Valid ContactRequest contact,
      @Valid AddressRequest billingAddress,
      @Size(max = 500) String duplicateResolutionNote) {
    CreateCommand toCommand() {
      return new CreateCommand(
          strip(legalName),
          strip(displayName),
          strip(registrationIdentifier),
          type,
          defaultCurrency,
          requestedPaymentTermDays,
          requestedRouteCodes == null ? Set.of() : requestedRouteCodes,
          requestedServiceRegions == null ? Set.of() : requestedServiceRegions,
          requestedCurrencies == null ? Set.of() : requestedCurrencies,
          contact == null ? null : contact.toDomain(),
          billingAddress == null ? null : billingAddress.toDomain(),
          strip(duplicateResolutionNote));
    }
  }

  record UpdatePartnerRequest(
      @Size(max = 200) String legalName,
      @Size(max = 100) String displayName,
      @Size(max = 100) String registrationIdentifier,
      Partner.PartnerType type,
      @Pattern(regexp = "^$|^[A-Z]{3}$") String defaultCurrency,
      @Min(0) @Max(180) Integer requestedPaymentTermDays,
      Set<@Pattern(regexp = ROUTE_CODE_PATTERN) String> requestedRouteCodes,
      Set<@NotBlank @Size(max = 80) String> requestedServiceRegions,
      Set<@Pattern(regexp = "^[A-Z]{3}$") String> requestedCurrencies,
      @Valid ContactRequest contact,
      @Valid AddressRequest billingAddress,
      @Size(max = 500) String duplicateResolutionNote) {
    UpdateCommand toCommand() {
      return new UpdateCommand(
          strip(legalName),
          strip(displayName),
          strip(registrationIdentifier),
          type,
          strip(defaultCurrency),
          requestedPaymentTermDays,
          requestedRouteCodes,
          requestedServiceRegions,
          requestedCurrencies,
          contact == null ? null : contact.toDomain(),
          billingAddress == null ? null : billingAddress.toDomain(),
          strip(duplicateResolutionNote));
    }
  }

  record MoneyRequest(
      @NotBlank @Pattern(regexp = "^[0-9]+(\\.[0-9]{1,4})?$") String amount,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  record PartnerReviewRequest(
      @NotNull Partner.ReviewDecision decision,
      @NotBlank @Size(min = 5, max = 500) String reason,
      @Min(0) @Max(180) Integer approvedPaymentTermDays,
      @Valid MoneyRequest approvedCreditLimit,
      Set<@Pattern(regexp = ROUTE_CODE_PATTERN) String> approvedRouteCodes,
      Set<@NotBlank @Size(max = 80) String> approvedServiceRegions,
      Set<@Pattern(regexp = "^[A-Z]{3}$") String> approvedCurrencies) {
    ReviewCommand toCommand() {
      return new ReviewCommand(
          decision,
          strip(reason),
          approvedPaymentTermDays,
          approvedCreditLimit == null ? null : new BigDecimal(approvedCreditLimit.amount()),
          approvedCreditLimit == null ? null : approvedCreditLimit.currency(),
          approvedRouteCodes == null ? Set.of() : approvedRouteCodes,
          approvedServiceRegions == null ? Set.of() : approvedServiceRegions,
          approvedCurrencies == null ? Set.of() : approvedCurrencies);
    }
  }

  record ReasonRequest(@NotBlank @Size(min = 5, max = 500) String reason) {}

  record PartnerSummaryResponse(
      UUID id,
      String number,
      String legalName,
      String displayName,
      PartnerStatus status,
      String defaultCurrency,
      List<String> routeEligibility,
      UUID salesOwnerId,
      long version,
      Instant updatedAt) {
    static PartnerSummaryResponse from(PartnerApplicationService.PartnerSummaryView source) {
      return new PartnerSummaryResponse(
          source.id(),
          source.number(),
          source.legalName(),
          source.displayName(),
          source.status(),
          source.defaultCurrency(),
          source.routeEligibility(),
          source.salesOwnerId(),
          source.version(),
          source.updatedAt());
    }
  }

  record PageInfoResponse(String nextCursor, boolean hasNext, int pageSize) {}

  record PartnerPageResponse(List<PartnerSummaryResponse> items, PageInfoResponse pageInfo) {}

  record ContactResponse(String name, String email, String phone, boolean primary) {}

  record AddressResponse(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {}

  record MoneyResponse(String amount, String currency) {}

  record EligibilityResponse(
      int version,
      List<String> routeCodes,
      List<String> serviceRegions,
      List<String> currencies,
      int paymentTermDays,
      MoneyResponse creditLimit,
      Instant effectiveFrom) {
    static EligibilityResponse from(EligibilityView source) {
      Partner.Eligibility value = source.eligibility();
      MoneyResponse credit =
          value.creditLimitAmount() == null
              ? null
              : new MoneyResponse(
                  value.creditLimitAmount().toPlainString(), value.creditLimitCurrency());
      return new EligibilityResponse(
          source.version(),
          value.routeCodes().stream().sorted().toList(),
          value.serviceRegions().stream().sorted().toList(),
          value.currencies().stream().sorted().toList(),
          value.paymentTermDays(),
          credit,
          value.effectiveFrom());
    }
  }

  record TimelineResponse(
      UUID id,
      Instant occurredAt,
      String action,
      String previousState,
      String newState,
      String safeReason,
      List<String> changedFields) {
    static TimelineResponse from(TimelineView source) {
      return new TimelineResponse(
          source.id(),
          source.occurredAt(),
          source.action(),
          source.previousState(),
          source.newState(),
          source.safeReason(),
          source.changedFields());
    }
  }

  record PartnerDetailResponse(
      UUID id,
      String number,
      String legalName,
      String displayName,
      PartnerStatus status,
      String defaultCurrency,
      List<String> routeEligibility,
      UUID salesOwnerId,
      long version,
      Instant updatedAt,
      String type,
      String registrationIdentifierMasked,
      List<ContactResponse> contacts,
      AddressResponse billingAddress,
      Integer paymentTermDays,
      MoneyResponse creditLimit,
      EligibilityResponse eligibility,
      List<String> requestedServiceRegions,
      List<String> requestedCurrencies,
      List<String> allowedActions,
      List<String> duplicateWarnings,
      List<TimelineResponse> timeline) {
    static PartnerDetailResponse from(PartnerDetailView source) {
      Partner.Profile profile = source.profile();
      PartnerSummaryResponse summary = PartnerSummaryResponse.from(source.summary());
      EligibilityResponse eligibility =
          source.eligibility() == null ? null : EligibilityResponse.from(source.eligibility());
      return new PartnerDetailResponse(
          summary.id(),
          summary.number(),
          summary.legalName(),
          summary.displayName(),
          summary.status(),
          summary.defaultCurrency(),
          summary.routeEligibility(),
          summary.salesOwnerId(),
          summary.version(),
          summary.updatedAt(),
          profile.type() == null ? null : profile.type().name(),
          mask(profile.registrationIdentifier()),
          profile.contact() == null
              ? List.of()
              : List.of(
                  new ContactResponse(
                      profile.contact().name(),
                      profile.contact().email(),
                      profile.contact().phone(),
                      profile.contact().primary())),
          profile.billingAddress() == null
              ? null
              : new AddressResponse(
                  profile.billingAddress().countryCode(),
                  profile.billingAddress().province(),
                  profile.billingAddress().city(),
                  profile.billingAddress().district(),
                  profile.billingAddress().line1(),
                  profile.billingAddress().postalCode()),
          eligibility == null
              ? profile.requestedPaymentTermDays()
              : Integer.valueOf(eligibility.paymentTermDays()),
          eligibility == null ? null : eligibility.creditLimit(),
          eligibility,
          profile.requestedServiceRegions().stream().sorted().toList(),
          profile.requestedCurrencies().stream().sorted().toList(),
          source.allowedActions(),
          source.potentialDuplicate()
              ? List.of("A similar legal name exists in this tenant")
              : List.of(),
          source.timeline().stream().map(TimelineResponse::from).toList());
    }

    private static String mask(String value) {
      if (value == null || value.isBlank()) return null;
      String compact = Partner.normalizeIdentifier(value);
      if (compact.length() <= 4) return "****";
      return "****" + compact.substring(compact.length() - 4);
    }
  }

  record PartnerCommandResponse(
      UUID partnerId,
      String number,
      PartnerStatus status,
      long version,
      List<String> allowedActions) {
    static PartnerCommandResponse from(PartnerCommandView source) {
      return new PartnerCommandResponse(
          source.partnerId(),
          source.number(),
          source.status(),
          source.version(),
          source.allowedActions());
    }
  }
}
