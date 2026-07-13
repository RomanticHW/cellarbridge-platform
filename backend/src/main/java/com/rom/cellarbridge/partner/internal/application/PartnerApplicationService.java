package com.rom.cellarbridge.partner.internal.application;

import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.partner.PartnerEligibilityException;
import com.rom.cellarbridge.partner.PartnerEligibilityService;
import com.rom.cellarbridge.partner.PartnerStatus;
import com.rom.cellarbridge.partner.internal.application.PartnerRepository.ChangedField;
import com.rom.cellarbridge.partner.internal.application.PartnerRepository.EligibilityRecord;
import com.rom.cellarbridge.partner.internal.application.PartnerRepository.PartnerPage;
import com.rom.cellarbridge.partner.internal.application.PartnerRepository.PartnerSearch;
import com.rom.cellarbridge.partner.internal.domain.Partner;
import com.rom.cellarbridge.partner.internal.domain.PartnerDomainException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerApplicationService implements PartnerEligibilityService {

  private final PartnerRepository repository;
  private final PartnerCursorCodec cursorCodec;
  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorizationService;
  private final Clock clock;

  PartnerApplicationService(
      PartnerRepository repository,
      PartnerCursorCodec cursorCodec,
      TenantContextHolder contextHolder,
      AuthorizationService authorizationService,
      Clock clock) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
    this.contextHolder = contextHolder;
    this.authorizationService = authorizationService;
    this.clock = clock;
  }

  @Transactional
  public PartnerDetailView create(CreateCommand command) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.PARTNER_CREATE, context.tenantId());
    Partner.Profile profile = mapDomain(command::toProfile);
    requireUniqueIdentifier(context.tenantId(), profile, null);
    Instant now = clock.instant();
    Partner partner =
        Partner.draft(
            UUID.randomUUID(),
            context.tenantId(),
            repository.nextNumber(context.tenantId(), now),
            profile,
            context.userId(),
            now);
    try {
      repository.insert(context.tenantId(), partner, context.userId());
    } catch (DuplicateKeyException exception) {
      throw duplicateIdentifier();
    }
    repository.insertAudit(
        context.tenantId(),
        partner,
        context.userId(),
        "PARTNER_CREATED",
        null,
        PartnerStatus.DRAFT,
        null,
        changedFields(null, profile),
        now);
    return detail(partner, context);
  }

  @Transactional(readOnly = true)
  public PartnerDetailView get(UUID partnerId) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.PARTNER_READ, context.tenantId());
    return detail(requirePartner(context.tenantId(), partnerId), context);
  }

  @Transactional(readOnly = true)
  public PartnerListView list(ListCommand command) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.PARTNER_READ, context.tenantId());
    int pageSize = command.pageSize() == null ? 25 : command.pageSize();
    if (pageSize < 1 || pageSize > 100) {
      throw PartnerProblemException.badRequest(
          "VALIDATION_FAILED", "pageSize must be between 1 and 100");
    }
    String filter = command.canonicalFilter();
    String filterHash = PartnerCursorCodec.filterHash(filter);
    PartnerRepository.CursorPosition cursor =
        cursorCodec.decode(context.tenantId(), filterHash, command.cursor());
    PartnerSearch search = command.toSearch(filterHash);
    PartnerPage page = repository.list(context.tenantId(), search, cursor, pageSize);
    String nextCursor = null;
    if (page.hasNext() && !page.items().isEmpty()) {
      Partner last = page.items().getLast();
      nextCursor =
          cursorCodec.encode(
              context.tenantId(),
              filterHash,
              new PartnerRepository.CursorPosition(last.updatedAt(), last.number(), last.id()));
    }
    List<PartnerSummaryView> items =
        page.items().stream().map(partner -> summary(partner, context)).toList();
    return new PartnerListView(items, nextCursor, page.hasNext(), pageSize);
  }

  @Transactional
  public PartnerDetailView update(UUID partnerId, long expectedVersion, UpdateCommand command) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.PARTNER_CREATE, context.tenantId());
    Partner before = requirePartner(context.tenantId(), partnerId);
    requireEditableOwnership(before, context);
    Partner.Profile replacement = mapDomain(() -> command.apply(before.profile()));
    requireUniqueIdentifier(context.tenantId(), replacement, partnerId);
    Partner edited = mapDomain(() -> before.edit(replacement, context.userId(), clock.instant()));
    Partner saved;
    try {
      saved = saveExpected(context.tenantId(), edited, expectedVersion, context.userId());
    } catch (DuplicateKeyException exception) {
      throw duplicateIdentifier();
    }
    repository.insertAudit(
        context.tenantId(),
        saved,
        context.userId(),
        "PARTNER_UPDATED",
        before.status(),
        saved.status(),
        null,
        changedFields(before.profile(), saved.profile()),
        saved.updatedAt());
    return detail(saved, context);
  }

  @Transactional
  public PartnerCommandView submit(UUID partnerId, long expectedVersion) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.PARTNER_SUBMIT, context.tenantId());
    Partner before = requirePartner(context.tenantId(), partnerId);
    requireEditableOwnership(before, context);
    requireUniqueIdentifier(context.tenantId(), before.profile(), partnerId);
    boolean potentialDuplicate =
        repository.legalNameDuplicate(
            context.tenantId(), before.profile().normalizedLegalName(), partnerId);
    Partner.Transition transition =
        mapDomain(() -> before.submit(context.userId(), clock.instant(), potentialDuplicate));
    Partner saved =
        saveExpected(context.tenantId(), transition.partner(), expectedVersion, context.userId());
    repository.insertAudit(
        context.tenantId(),
        saved,
        context.userId(),
        "PARTNER_SUBMITTED",
        before.status(),
        saved.status(),
        null,
        List.of(),
        saved.updatedAt());
    UUID eventId =
        repository.insertPublication(
            context.tenantId(),
            saved,
            transition.eventType(),
            context.userId(),
            0,
            null,
            saved.updatedAt());
    repository.openReviewWorkItem(
        context.tenantId(), saved, eventId, context.userId(), saved.updatedAt());
    return commandView(saved, context);
  }

  @Transactional
  public PartnerCommandView review(UUID partnerId, long expectedVersion, ReviewCommand command) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.PARTNER_REVIEW, context.tenantId());
    Partner before = requirePartner(context.tenantId(), partnerId);
    Partner.Review review = mapDomain(command::toReview);
    Partner.Transition transition =
        switch (command.decision()) {
          case APPROVE ->
              mapDomain(() -> before.approve(context.userId(), review, clock.instant()));
          case REQUEST_CHANGES ->
              mapDomain(
                  () -> before.requestChanges(context.userId(), command.reason(), clock.instant()));
          case REJECT ->
              mapDomain(() -> before.reject(context.userId(), command.reason(), clock.instant()));
        };
    Partner saved =
        saveExpected(context.tenantId(), transition.partner(), expectedVersion, context.userId());
    int eligibilityVersion = 0;
    if (transition.eligibility() != null) {
      eligibilityVersion = repository.nextEligibilityVersion(context.tenantId(), partnerId);
      repository.insertEligibility(
          context.tenantId(),
          partnerId,
          eligibilityVersion,
          transition.eligibility(),
          context.userId());
    }
    repository.insertReviewDecision(
        context.tenantId(), before, saved, command.decision(), command.reason(), context.userId());
    repository.insertAudit(
        context.tenantId(),
        saved,
        context.userId(),
        "PARTNER_" + command.decision().name(),
        before.status(),
        saved.status(),
        command.reason(),
        List.of(),
        saved.updatedAt());
    repository.insertPublication(
        context.tenantId(),
        saved,
        transition.eventType(),
        context.userId(),
        eligibilityVersion,
        command.reason(),
        saved.updatedAt());
    repository.completeReviewWorkItems(
        context.tenantId(), saved.id(), context.userId(), saved.updatedAt());
    return commandView(saved, context);
  }

  @Transactional
  public PartnerCommandView suspend(UUID partnerId, long expectedVersion, String reason) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.PARTNER_REVIEW, context.tenantId());
    Partner before = requirePartner(context.tenantId(), partnerId);
    Partner.Transition transition =
        mapDomain(() -> before.suspend(context.userId(), reason, clock.instant()));
    Partner saved =
        saveExpected(context.tenantId(), transition.partner(), expectedVersion, context.userId());
    recordStateEvent(context, before, saved, transition.eventType(), "PARTNER_SUSPENDED", reason);
    return commandView(saved, context);
  }

  @Transactional
  public PartnerCommandView requestReactivation(
      UUID partnerId, long expectedVersion, String reason) {
    TenantContext context = context();
    authorizationService.require(PermissionCode.PARTNER_SUBMIT, context.tenantId());
    Partner before = requirePartner(context.tenantId(), partnerId);
    requireEditableOwnership(before, context);
    Partner.Transition transition =
        mapDomain(() -> before.requestReactivation(context.userId(), reason, clock.instant()));
    Partner saved =
        saveExpected(context.tenantId(), transition.partner(), expectedVersion, context.userId());
    repository.insertAudit(
        context.tenantId(),
        saved,
        context.userId(),
        "PARTNER_REACTIVATION_REQUESTED",
        before.status(),
        saved.status(),
        reason,
        List.of(),
        saved.updatedAt());
    UUID eventId =
        repository.insertPublication(
            context.tenantId(),
            saved,
            transition.eventType(),
            context.userId(),
            0,
            reason,
            saved.updatedAt());
    repository.openReviewWorkItem(
        context.tenantId(), saved, eventId, context.userId(), saved.updatedAt());
    return commandView(saved, context);
  }

  @Override
  @Transactional(readOnly = true)
  public EligibilitySnapshot requireActive(TenantId tenantId, UUID partnerId) {
    try {
      Partner partner = requirePartner(tenantId, partnerId);
      mapDomain(
          () -> {
            partner.requireActive();
            return partner;
          });
      EligibilityRecord record =
          repository
              .latestEligibility(tenantId, partnerId)
              .orElseThrow(PartnerProblemException::notFound);
      return new EligibilitySnapshot(
          partner.id(),
          partner.number(),
          partner.profile().displayName(),
          record.version(),
          record.eligibility().routeCodes(),
          record.eligibility().serviceRegions(),
          record.eligibility().currencies(),
          record.eligibility().paymentTermDays(),
          new AddressSnapshot(
              partner.profile().billingAddress().countryCode(),
              partner.profile().billingAddress().province(),
              partner.profile().billingAddress().city(),
              partner.profile().billingAddress().district(),
              partner.profile().billingAddress().line1(),
              partner.profile().billingAddress().postalCode()),
          clock.instant());
    } catch (PartnerProblemException exception) {
      throw new PartnerEligibilityException(exception.code(), exception.getMessage());
    }
  }

  private void recordStateEvent(
      TenantContext context,
      Partner before,
      Partner saved,
      Partner.EventType eventType,
      String action,
      String reason) {
    repository.insertAudit(
        context.tenantId(),
        saved,
        context.userId(),
        action,
        before.status(),
        saved.status(),
        reason,
        List.of(),
        saved.updatedAt());
    repository.insertPublication(
        context.tenantId(), saved, eventType, context.userId(), 0, reason, saved.updatedAt());
  }

  private PartnerDetailView detail(Partner partner, TenantContext context) {
    EligibilityRecord eligibility =
        repository.latestEligibility(context.tenantId(), partner.id()).orElse(null);
    boolean duplicateWarning =
        repository.legalNameDuplicate(
            context.tenantId(), partner.profile().normalizedLegalName(), partner.id());
    return new PartnerDetailView(
        summary(partner, context),
        partner.profile(),
        eligibility,
        allowedActions(partner, context),
        duplicateWarning,
        repository.timeline(context.tenantId(), partner.id()));
  }

  private PartnerSummaryView summary(Partner partner, TenantContext context) {
    Set<String> routes =
        repository
            .latestEligibility(context.tenantId(), partner.id())
            .map(record -> record.eligibility().routeCodes())
            .orElse(partner.profile().requestedRouteCodes());
    return new PartnerSummaryView(
        partner.id(),
        partner.number(),
        partner.profile().legalName(),
        partner.profile().displayName(),
        partner.status(),
        partner.profile().defaultCurrency(),
        routes.stream().sorted().toList(),
        partner.salesOwnerId(),
        partner.version(),
        partner.updatedAt());
  }

  private List<String> allowedActions(Partner partner, TenantContext context) {
    List<String> actions = new ArrayList<>();
    actions.add("VIEW");
    boolean owner = partner.salesOwnerId().equals(context.userId());
    boolean reviewer = context.hasPermission(PermissionCode.PARTNER_REVIEW);
    if ((owner || reviewer)
        && context.hasPermission(PermissionCode.PARTNER_CREATE)
        && (partner.status() == PartnerStatus.DRAFT
            || partner.status() == PartnerStatus.CHANGES_REQUESTED)) {
      actions.add("EDIT");
    }
    if ((owner || reviewer)
        && context.hasPermission(PermissionCode.PARTNER_SUBMIT)
        && (partner.status() == PartnerStatus.DRAFT
            || partner.status() == PartnerStatus.CHANGES_REQUESTED)) {
      actions.add("SUBMIT");
    }
    if (reviewer && partner.status() == PartnerStatus.PENDING_REVIEW) actions.add("REVIEW");
    if (reviewer && partner.status() == PartnerStatus.ACTIVE) actions.add("SUSPEND");
    if ((owner || reviewer)
        && context.hasPermission(PermissionCode.PARTNER_SUBMIT)
        && partner.status() == PartnerStatus.SUSPENDED) {
      actions.add("REQUEST_REACTIVATION");
    }
    return List.copyOf(actions);
  }

  private void requireEditableOwnership(Partner partner, TenantContext context) {
    if (!partner.salesOwnerId().equals(context.userId())
        && !context.hasPermission(PermissionCode.PARTNER_REVIEW)) {
      throw new org.springframework.security.access.AccessDeniedException("Access denied");
    }
  }

  private Partner saveExpected(
      TenantId tenantId, Partner partner, long expectedVersion, UUID actorId) {
    return repository
        .update(tenantId, partner, expectedVersion, actorId)
        .orElseGet(
            () -> {
              Partner current = requirePartner(tenantId, partner.id());
              throw PartnerProblemException.versionConflict(current.version(), current.status());
            });
  }

  private Partner requirePartner(TenantId tenantId, UUID partnerId) {
    return repository.find(tenantId, partnerId).orElseThrow(PartnerProblemException::notFound);
  }

  private void requireUniqueIdentifier(
      TenantId tenantId, Partner.Profile profile, UUID excludingPartnerId) {
    if (repository.registrationIdentifierExists(
        tenantId, profile.normalizedRegistrationIdentifier(), excludingPartnerId)) {
      throw duplicateIdentifier();
    }
  }

  private static PartnerProblemException duplicateIdentifier() {
    return PartnerProblemException.conflict(
        "PARTNER_DUPLICATE_IDENTIFIER",
        "A partner with this registration identifier already exists");
  }

  private static <T> T mapDomain(DomainAction<T> action) {
    try {
      return action.run();
    } catch (PartnerDomainException exception) {
      if (exception.code().equals("PARTNER_PROFILE_INCOMPLETE")) {
        throw PartnerProblemException.unprocessable(
            exception.code(), exception.getMessage(), exception.fields());
      }
      if (exception.code().equals("VALIDATION_FAILED")) {
        throw PartnerProblemException.badRequest(exception.code(), exception.getMessage());
      }
      throw PartnerProblemException.conflict(exception.code(), exception.getMessage());
    }
  }

  private static List<ChangedField> changedFields(
      Partner.Profile previous, Partner.Profile current) {
    List<ChangedField> fields = new ArrayList<>();
    compare(
        fields,
        "legalName",
        previous == null ? null : previous.legalName(),
        current.legalName(),
        false);
    compare(
        fields,
        "displayName",
        previous == null ? null : previous.displayName(),
        current.displayName(),
        false);
    compare(
        fields,
        "registrationIdentifier",
        previous == null ? null : previous.registrationIdentifier(),
        current.registrationIdentifier(),
        true);
    compare(
        fields,
        "defaultCurrency",
        previous == null ? null : previous.defaultCurrency(),
        current.defaultCurrency(),
        false);
    compare(
        fields,
        "contact",
        previous == null ? null : java.util.Objects.toString(previous.contact(), null),
        java.util.Objects.toString(current.contact(), null),
        true);
    compare(
        fields,
        "billingAddress",
        previous == null ? null : java.util.Objects.toString(previous.billingAddress(), null),
        java.util.Objects.toString(current.billingAddress(), null),
        true);
    compare(
        fields,
        "requestedEligibility",
        previous == null ? null : eligibilitySummary(previous),
        eligibilitySummary(current),
        false);
    fields.sort(Comparator.comparing(ChangedField::field));
    return List.copyOf(fields);
  }

  private static void compare(
      List<ChangedField> fields, String field, String previous, String current, boolean sensitive) {
    if (java.util.Objects.equals(previous, current)) return;
    fields.add(new ChangedField(field, summary(previous, sensitive), summary(current, sensitive)));
  }

  private static String eligibilitySummary(Partner.Profile profile) {
    return profile.requestedPaymentTermDays()
        + "|"
        + profile.requestedRouteCodes().stream().sorted().toList()
        + "|"
        + profile.requestedServiceRegions().stream().sorted().toList()
        + "|"
        + profile.requestedCurrencies().stream().sorted().toList();
  }

  private static String summary(String value, boolean sensitive) {
    if (value == null) return "absent";
    if (!sensitive) return value.length() > 80 ? value.substring(0, 80) : value;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private TenantContext context() {
    return contextHolder.requireCurrent();
  }

  @FunctionalInterface
  private interface DomainAction<T> {
    T run();
  }

  public record CreateCommand(
      String legalName,
      String displayName,
      String registrationIdentifier,
      Partner.PartnerType type,
      String defaultCurrency,
      Integer requestedPaymentTermDays,
      Set<String> requestedRouteCodes,
      Set<String> requestedServiceRegions,
      Set<String> requestedCurrencies,
      Partner.Contact contact,
      Partner.Address billingAddress,
      String duplicateResolutionNote) {

    Partner.Profile toProfile() {
      return new Partner.Profile(
          legalName,
          displayName,
          registrationIdentifier,
          type,
          defaultCurrency == null ? null : defaultCurrency.toUpperCase(Locale.ROOT),
          requestedPaymentTermDays,
          requestedRouteCodes,
          requestedServiceRegions,
          requestedCurrencies.stream()
              .map(value -> value.toUpperCase(Locale.ROOT))
              .collect(java.util.stream.Collectors.toUnmodifiableSet()),
          contact,
          billingAddress,
          duplicateResolutionNote);
    }
  }

  public record UpdateCommand(
      String legalName,
      String displayName,
      String registrationIdentifier,
      Partner.PartnerType type,
      String defaultCurrency,
      Integer requestedPaymentTermDays,
      Set<String> requestedRouteCodes,
      Set<String> requestedServiceRegions,
      Set<String> requestedCurrencies,
      Partner.Contact contact,
      Partner.Address billingAddress,
      String duplicateResolutionNote) {

    Partner.Profile apply(Partner.Profile current) {
      return new Partner.Profile(
          legalName == null ? current.legalName() : legalName,
          displayName == null ? current.displayName() : displayName,
          registrationIdentifier == null
              ? current.registrationIdentifier()
              : registrationIdentifier,
          type == null ? current.type() : type,
          defaultCurrency == null
              ? current.defaultCurrency()
              : defaultCurrency.toUpperCase(Locale.ROOT),
          requestedPaymentTermDays == null
              ? current.requestedPaymentTermDays()
              : requestedPaymentTermDays,
          requestedRouteCodes == null ? current.requestedRouteCodes() : requestedRouteCodes,
          requestedServiceRegions == null
              ? current.requestedServiceRegions()
              : requestedServiceRegions,
          requestedCurrencies == null
              ? current.requestedCurrencies()
              : requestedCurrencies.stream()
                  .map(value -> value.toUpperCase(Locale.ROOT))
                  .collect(java.util.stream.Collectors.toUnmodifiableSet()),
          contact == null ? current.contact() : contact,
          billingAddress == null ? current.billingAddress() : billingAddress,
          duplicateResolutionNote == null
              ? current.duplicateResolutionNote()
              : duplicateResolutionNote);
    }
  }

  public record ReviewCommand(
      Partner.ReviewDecision decision,
      String reason,
      Integer approvedPaymentTermDays,
      BigDecimal approvedCreditLimitAmount,
      String approvedCreditLimitCurrency,
      Set<String> approvedRouteCodes,
      Set<String> approvedServiceRegions,
      Set<String> approvedCurrencies) {

    Partner.Review toReview() {
      return new Partner.Review(
          reason,
          approvedPaymentTermDays,
          approvedCreditLimitAmount,
          approvedCreditLimitCurrency,
          approvedRouteCodes,
          approvedServiceRegions,
          approvedCurrencies);
    }
  }

  public record ListCommand(
      String keyword,
      Set<PartnerStatus> statuses,
      UUID ownerId,
      String routeCode,
      Instant updatedFrom,
      Instant updatedTo,
      Integer pageSize,
      String cursor) {

    String canonicalFilter() {
      return String.join(
          "|",
          value(keyword),
          statuses.stream()
              .map(Enum::name)
              .sorted()
              .collect(java.util.stream.Collectors.joining(",")),
          value(ownerId),
          value(routeCode),
          value(updatedFrom),
          value(updatedTo),
          "-updatedAt,number,id");
    }

    PartnerSearch toSearch(String filterHash) {
      return new PartnerSearch(
          keyword, statuses, ownerId, routeCode, updatedFrom, updatedTo, filterHash);
    }

    private static String value(Object value) {
      return value == null ? "" : value.toString();
    }
  }

  public record PartnerSummaryView(
      UUID id,
      String number,
      String legalName,
      String displayName,
      PartnerStatus status,
      String defaultCurrency,
      List<String> routeEligibility,
      UUID salesOwnerId,
      long version,
      Instant updatedAt) {}

  public record PartnerListView(
      List<PartnerSummaryView> items, String nextCursor, boolean hasNext, int pageSize) {
    public PartnerListView {
      items = List.copyOf(items);
    }
  }

  public record PartnerDetailView(
      PartnerSummaryView summary,
      Partner.Profile profile,
      EligibilityRecord eligibility,
      List<String> allowedActions,
      boolean potentialDuplicate,
      List<PartnerRepository.TimelineEntry> timeline) {
    public PartnerDetailView {
      allowedActions = List.copyOf(allowedActions);
      timeline = List.copyOf(timeline);
    }
  }

  public record PartnerCommandView(
      UUID partnerId,
      String number,
      PartnerStatus status,
      long version,
      List<String> allowedActions) {
    public PartnerCommandView {
      allowedActions = List.copyOf(allowedActions);
    }
  }

  private PartnerCommandView commandView(Partner partner, TenantContext context) {
    return new PartnerCommandView(
        partner.id(),
        partner.number(),
        partner.status(),
        partner.version(),
        allowedActions(partner, context));
  }
}
