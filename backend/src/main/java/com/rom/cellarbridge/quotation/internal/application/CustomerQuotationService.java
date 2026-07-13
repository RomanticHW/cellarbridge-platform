package com.rom.cellarbridge.quotation.internal.application;

import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import com.rom.cellarbridge.quotation.QuotationAcceptedV1;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.CustomerDecision;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.CustomerDecisionType;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.CustomerOperation;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.IdempotencyRecord;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.IdempotencyWrite;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.PortalContext;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.Address;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate.PartnerSnapshot;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.PricedLine;
import com.rom.cellarbridge.quotation.internal.domain.QuotationPricingPolicy.QuantityUnit;
import com.rom.cellarbridge.quotation.internal.domain.QuotationProblem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CustomerQuotationService {

  private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(30);
  private static final Set<QuotationStatus> PUBLIC_STATUSES =
      Set.of(
          QuotationStatus.SENT,
          QuotationStatus.ACCEPTED,
          QuotationStatus.REJECTED_BY_CUSTOMER,
          QuotationStatus.WITHDRAWN,
          QuotationStatus.EXPIRED,
          QuotationStatus.CONVERTED);

  private final QuotationRepository repository;
  private final ReliableEventPublisher eventPublisher;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  CustomerQuotationService(
      QuotationRepository repository,
      ReliableEventPublisher eventPublisher,
      JsonMapper jsonMapper,
      Clock clock) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PublicView publicView(String token) {
    Instant now = clock.instant();
    PortalContext context = requireContext(token, now, false);
    QuotationAggregate quotation = context.quotation();
    QuotationStatus status = effectiveStatus(quotation, now);
    if (!PUBLIC_STATUSES.contains(status)) {
      throw unavailable();
    }
    List<String> allowedActions =
        status == QuotationStatus.SENT
            ? List.of("ACCEPT", "REJECT").stream()
                .filter(context.allowedActions()::contains)
                .toList()
            : List.of();
    CustomerDecision decision = context.customerDecision();
    DecisionReceipt receipt =
        decision == null
            ? null
            : new DecisionReceipt(
                decision.id(),
                decision.decision() == CustomerDecisionType.ACCEPTED
                    ? "ACCEPTED"
                    : "REJECTED_BY_CUSTOMER",
                decision.decidedAt(),
                decision.decision() == CustomerDecisionType.ACCEPTED
                    ? decision.buyerReference()
                    : decision.reasonCategory());
    String currency = quotation.revision().terms().currency();
    return new PublicView(
        quotation.number(),
        quotation.currentRevision(),
        context.supplierPublicId(),
        context.supplierDisplayName(),
        context.customerPublicId(),
        quotation.revision().partnerSnapshot().displayName(),
        status,
        quotation.revision().terms().expiresAt(),
        quotation.revision().pricing().lines().stream()
            .map(CustomerQuotationService::line)
            .toList(),
        quotation
            .revision()
            .pricing()
            .total()
            .subtract(quotation.revision().pricing().routeCharges()),
        quotation.revision().pricing().routeCharges(),
        quotation.revision().pricing().total(),
        currency,
        routeLabel(quotation.revision().selectedRouteCode().name()),
        "Estimated delivery by " + quotation.revision().terms().requestedDeliveryDate(),
        quotation.revision().terms().paymentTermDays(),
        context.termsVersion(),
        termsSummary(quotation, context.termsVersion()),
        allowedActions,
        receipt);
  }

  @Transactional
  public AcceptanceResult accept(
      String token, String idempotencyKey, String acceptedTermsVersion, String buyerReference) {
    validateIdempotencyKey(idempotencyKey);
    PortalContext context = requireContext(token, clock.instant(), true);
    String normalizedReference = normalizedOptional(buyerReference, 100, "buyerReference");
    String requestHash =
        requestHash(
            CustomerOperation.ACCEPT_QUOTATION, context, acceptedTermsVersion, normalizedReference);
    String keyHash = sha256(idempotencyKey);
    repository.lockIdempotencyKey(
        context.tenantId(), context.partnerId(), CustomerOperation.ACCEPT_QUOTATION, keyHash);
    Instant now = clock.instant();
    requireActiveContext(context, now);
    requireAction(context, "ACCEPT");
    IdempotencyRecord prior =
        repository
            .findIdempotency(
                context.tenantId(),
                context.partnerId(),
                CustomerOperation.ACCEPT_QUOTATION,
                keyHash)
            .orElse(null);
    if (prior != null) {
      requireSameRequest(prior, requestHash);
      return acceptance(
          context.quotation().number(),
          requireDecision(context, prior.decisionId(), CustomerDecisionType.ACCEPTED),
          true);
    }
    if (acceptedTermsVersion == null
        || !context.termsVersion().equals(acceptedTermsVersion.strip())) {
      throw problem(
          HttpStatus.CONFLICT,
          "QUOTE_TERMS_VERSION_MISMATCH",
          "The acknowledged terms do not match this quotation revision");
    }
    if (context.customerDecision() != null) {
      CustomerDecision existing =
          requireDecision(context, context.customerDecision().id(), CustomerDecisionType.ACCEPTED);
      repository.saveIdempotencyResult(
          context.tenantId(),
          context,
          existing,
          idempotency(CustomerOperation.ACCEPT_QUOTATION, keyHash, requestHash, 200, now));
      return acceptance(context.quotation().number(), existing, true);
    }
    QuotationAggregate before = context.quotation();
    QuotationAggregate after = before.accept(context.revisionId(), now);
    CommercialSnapshot snapshot = snapshot(before, context.termsVersion());
    String snapshotJson = json(snapshot);
    String snapshotHash = sha256(snapshotJson);
    UUID acceptanceId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    CustomerDecision decision =
        new CustomerDecision(
            acceptanceId,
            context.tenantId(),
            before.id(),
            context.revisionId(),
            context.accessId(),
            context.partnerId(),
            CustomerDecisionType.ACCEPTED,
            context.termsVersion(),
            normalizedReference,
            null,
            snapshotJson,
            snapshotHash,
            keyHash,
            eventId,
            now);
    repository.saveCustomerDecision(
        context.tenantId(),
        before,
        after,
        decision,
        idempotency(CustomerOperation.ACCEPT_QUOTATION, keyHash, requestHash, 201, now),
        context.accessId());
    QuotationAcceptedV1 event = acceptedEvent(before, decision, snapshot, eventId);
    try {
      eventPublisher.publish(
          new PendingEvent(
              event.id(),
              event.tenantId(),
              event.type(),
              1,
              event.occurredAt(),
              event.producer(),
              new PendingEvent.Subject(
                  event.subject().type(), event.subject().id(), event.subject().number()),
              event.correlationId(),
              event.causationId(),
              event.payload(),
              event.metadata()));
    } catch (DataAccessException exception) {
      throw new QuotationProblem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "DEPENDENCY_UNAVAILABLE",
          "The quotation decision could not be recorded at this time",
          exception);
    }
    return acceptance(context.quotation().number(), decision, false);
  }

  @Transactional
  public RejectionResult reject(
      String token, String idempotencyKey, RejectionReasonCategory reasonCategory) {
    validateIdempotencyKey(idempotencyKey);
    PortalContext context = requireContext(token, clock.instant(), true);
    String reason = reasonCategory == null ? null : reasonCategory.name();
    String requestHash =
        requestHash(CustomerOperation.REJECT_QUOTATION, context, reason == null ? "" : reason);
    String keyHash = sha256(idempotencyKey);
    repository.lockIdempotencyKey(
        context.tenantId(), context.partnerId(), CustomerOperation.REJECT_QUOTATION, keyHash);
    Instant now = clock.instant();
    requireActiveContext(context, now);
    requireAction(context, "REJECT");
    IdempotencyRecord prior =
        repository
            .findIdempotency(
                context.tenantId(),
                context.partnerId(),
                CustomerOperation.REJECT_QUOTATION,
                keyHash)
            .orElse(null);
    if (prior != null) {
      requireSameRequest(prior, requestHash);
      return rejection(
          context.quotation().number(),
          requireDecision(context, prior.decisionId(), CustomerDecisionType.REJECTED),
          true);
    }
    if (context.customerDecision() != null) {
      CustomerDecision existing =
          requireDecision(context, context.customerDecision().id(), CustomerDecisionType.REJECTED);
      repository.saveIdempotencyResult(
          context.tenantId(),
          context,
          existing,
          idempotency(CustomerOperation.REJECT_QUOTATION, keyHash, requestHash, 200, now));
      return rejection(context.quotation().number(), existing, true);
    }
    QuotationAggregate before = context.quotation();
    QuotationAggregate after = before.reject(context.revisionId(), now);
    CommercialSnapshot snapshot = snapshot(before, context.termsVersion());
    String snapshotJson = json(snapshot);
    CustomerDecision decision =
        new CustomerDecision(
            UUID.randomUUID(),
            context.tenantId(),
            before.id(),
            context.revisionId(),
            context.accessId(),
            context.partnerId(),
            CustomerDecisionType.REJECTED,
            null,
            null,
            reason,
            snapshotJson,
            sha256(snapshotJson),
            keyHash,
            null,
            now);
    repository.saveCustomerDecision(
        context.tenantId(),
        before,
        after,
        decision,
        idempotency(CustomerOperation.REJECT_QUOTATION, keyHash, requestHash, 201, now),
        context.accessId());
    return rejection(context.quotation().number(), decision, false);
  }

  private PortalContext requireContext(String token, Instant now, boolean forUpdate) {
    if (token == null || !token.matches("^[A-Za-z0-9_-]{40,100}$")) {
      throw unavailable();
    }
    PortalContext context =
        repository
            .findPortalContext(sha256(token), now, forUpdate)
            .orElseThrow(CustomerQuotationService::unavailable);
    requireActiveContext(context, now);
    return context;
  }

  private static void requireActiveContext(PortalContext context, Instant now) {
    QuotationAggregate quotation = context.quotation();
    if (!context.allowedActions().contains("VIEW")
        || !quotation.tenantId().equals(context.tenantId())
        || !quotation.partnerId().equals(context.partnerId())
        || !quotation.revision().id().equals(context.revisionId())
        || !now.isBefore(context.accessExpiresAt())) {
      throw unavailable();
    }
  }

  private static CustomerDecision requireDecision(
      PortalContext context, UUID decisionId, CustomerDecisionType expected) {
    CustomerDecision decision = context.customerDecision();
    if (decision == null || !decision.id().equals(decisionId) || decision.decision() != expected) {
      throw problem(
          HttpStatus.CONFLICT,
          "QUOTE_ALREADY_DECIDED",
          "Quotation already has a different customer decision");
    }
    return decision;
  }

  private static void requireSameRequest(IdempotencyRecord prior, String requestHash) {
    if (!MessageDigest.isEqual(
        prior.requestHash().getBytes(StandardCharsets.US_ASCII),
        requestHash.getBytes(StandardCharsets.US_ASCII))) {
      throw problem(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "The idempotency key was already used for a different request");
    }
  }

  private static void requireAction(PortalContext context, String action) {
    if (!context.allowedActions().contains(action)) {
      throw problem(
          HttpStatus.CONFLICT,
          "QUOTE_NOT_ACCEPTABLE",
          "The customer link does not permit this action");
    }
  }

  private static void validateIdempotencyKey(String key) {
    if (key == null || !key.matches("^[A-Za-z0-9._~-]{20,200}$")) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          key == null ? "IDEMPOTENCY_KEY_REQUIRED" : "VALIDATION_FAILED",
          "A valid Idempotency-Key is required");
    }
  }

  private static String normalizedOptional(String value, int maxLength, String field) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    if (normalized.isEmpty() || normalized.length() > maxLength) {
      throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", field + " has an invalid length");
    }
    return normalized;
  }

  private static IdempotencyWrite idempotency(
      CustomerOperation operation,
      String keyHash,
      String requestHash,
      int responseStatus,
      Instant now) {
    return new IdempotencyWrite(
        UUID.randomUUID(),
        operation,
        keyHash,
        requestHash,
        responseStatus,
        now.plus(IDEMPOTENCY_RETENTION),
        now);
  }

  private static String requestHash(
      CustomerOperation operation, PortalContext context, String... values) {
    StringBuilder canonical =
        new StringBuilder(operation.name())
            .append('\n')
            .append(context.tenantId().value())
            .append('\n')
            .append(context.partnerId())
            .append('\n')
            .append(context.quotation().id())
            .append('\n')
            .append(context.revisionId());
    for (String value : values) {
      canonical.append('\n').append(value == null ? "" : value.strip());
    }
    return sha256(canonical.toString());
  }

  private static CommercialSnapshot snapshot(
      QuotationAggregate quotation, String acceptedTermsVersion) {
    PartnerSnapshot partner = quotation.revision().partnerSnapshot();
    Address address = quotation.revision().terms().deliveryAddress();
    List<QuotationAcceptedV1.Line> lines =
        quotation.revision().pricing().lines().stream()
            .map(CustomerQuotationService::eventLine)
            .toList();
    return new CommercialSnapshot(
        1,
        quotation.id(),
        quotation.revision().id(),
        quotation.number(),
        quotation.currentRevision(),
        new QuotationAcceptedV1.Customer(
            partner.partnerId(), partner.number(), partner.displayName(), partner.sourceVersion()),
        quotation.revision().terms().currency(),
        amount(quotation.revision().pricing().total()),
        quotation.revision().terms().paymentTermDays(),
        new QuotationAcceptedV1.Route(
            quotation.revision().selectedRouteCode().name(),
            quotation.revision().routePolicyVersion(),
            quotation.revision().terms().requestedDeliveryDate()),
        acceptedTermsVersion,
        quotation.revision().terms().requestedDeliveryDate(),
        new QuotationAcceptedV1.DeliveryAddress(
            address.countryCode(),
            address.province(),
            address.city(),
            address.district(),
            address.line1(),
            address.postalCode()),
        lines);
  }

  private static QuotationAcceptedV1 acceptedEvent(
      QuotationAggregate quotation,
      CustomerDecision decision,
      CommercialSnapshot snapshot,
      UUID eventId) {
    UUID commandId = UUID.randomUUID();
    QuotationAcceptedV1.Payload payload =
        new QuotationAcceptedV1.Payload(
            snapshot.quotationId(),
            snapshot.revisionId(),
            snapshot.quotationNumber(),
            snapshot.revision(),
            decision.id(),
            decision.decidedAt(),
            snapshot.customer(),
            snapshot.currency(),
            snapshot.totalAmount(),
            snapshot.paymentTermDays(),
            snapshot.route(),
            snapshot.acceptedTermsVersion(),
            snapshot.requestedDeliveryDate(),
            snapshot.deliveryAddress(),
            "sha256:" + decision.snapshotHash(),
            snapshot.lines());
    return new QuotationAcceptedV1(
        eventId,
        QuotationAcceptedV1.TYPE,
        "1.0",
        decision.decidedAt(),
        quotation.tenantId().value(),
        "quotation",
        new QuotationAcceptedV1.Subject("QUOTATION", quotation.id(), quotation.number()),
        commandId,
        commandId,
        payload,
        Map.of("idempotencyDigest", "sha256:" + decision.idempotencyDigest()));
  }

  private String json(Object value) {
    try {
      return jsonMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Could not serialize commercial quotation snapshot", exception);
    }
  }

  private static PublicLine line(PricedLine line) {
    return new PublicLine(
        line.sku().skuCode(),
        line.sku().displayName(),
        line.sku().vintage(),
        line.sku().packageType(),
        line.quantity(),
        line.unit(),
        line.netUnitPrice(),
        line.lineTotal(),
        line.currency());
  }

  private static QuotationAcceptedV1.Line eventLine(PricedLine line) {
    if (line.supplyType() == null) {
      throw problem(
          HttpStatus.CONFLICT,
          "QUOTE_NOT_ACCEPTABLE",
          "Quotation line does not have a frozen supply decision");
    }
    return new QuotationAcceptedV1.Line(
        line.lineId(),
        line.sku().skuId(),
        line.sku().skuCode(),
        line.sku().displayName(),
        quantity(line.quantity()),
        line.unit().name(),
        amount(line.netUnitPrice()),
        amount(line.lineTotal()),
        line.preferredSupplyPoolId(),
        line.supplyType());
  }

  private static QuotationStatus effectiveStatus(QuotationAggregate quotation, Instant now) {
    if (quotation.status() == QuotationStatus.SENT
        && !now.isBefore(quotation.revision().terms().expiresAt())) {
      return QuotationStatus.EXPIRED;
    }
    return quotation.status();
  }

  private static List<String> termsSummary(QuotationAggregate quotation, String termsVersion) {
    return List.of(
        "Prices and availability are fixed for this quotation revision until the stated expiry.",
        "Payment is due within "
            + quotation.revision().terms().paymentTermDays()
            + " days under the stated commercial terms.",
        "Acceptance applies to terms version " + termsVersion + ".");
  }

  private static String routeLabel(String code) {
    return switch (code) {
      case "SH_GENERAL_TRADE" -> "Shanghai general-trade delivery";
      case "NB_BONDED_B2B" -> "Ningbo bonded B2B delivery";
      case "HK_FREE_TRADE" -> "Hong Kong free-trade delivery";
      default -> "Selected trade delivery";
    };
  }

  private static String amount(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private static String quantity(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
          .toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static AcceptanceResult acceptance(
      String quotationNumber, CustomerDecision decision, boolean replayed) {
    return new AcceptanceResult(decision.id(), quotationNumber, decision.decidedAt(), replayed);
  }

  private static RejectionResult rejection(
      String quotationNumber, CustomerDecision decision, boolean replayed) {
    return new RejectionResult(
        decision.id(), quotationNumber, decision.decidedAt(), decision.reasonCategory(), replayed);
  }

  private static QuotationProblem unavailable() {
    return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Quotation is not available");
  }

  private static QuotationProblem problem(HttpStatus status, String code, String message) {
    return new QuotationProblem(status, code, message);
  }

  private record CommercialSnapshot(
      int schemaVersion,
      UUID quotationId,
      UUID revisionId,
      String quotationNumber,
      int revision,
      QuotationAcceptedV1.Customer customer,
      String currency,
      String totalAmount,
      int paymentTermDays,
      QuotationAcceptedV1.Route route,
      String acceptedTermsVersion,
      LocalDate requestedDeliveryDate,
      QuotationAcceptedV1.DeliveryAddress deliveryAddress,
      List<QuotationAcceptedV1.Line> lines) {

    private CommercialSnapshot {
      lines = List.copyOf(lines);
    }
  }

  public enum RejectionReasonCategory {
    PRICE,
    DELIVERY_TIMING,
    PAYMENT_TERMS,
    PRODUCT_SELECTION,
    OTHER
  }

  public record PublicLine(
      String skuCode,
      String description,
      String vintage,
      String packageType,
      BigDecimal quantity,
      QuantityUnit unit,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String currency) {}

  public record DecisionReceipt(
      UUID decisionId, String decision, Instant decidedAt, String reference) {}

  public record PublicView(
      String number,
      int revision,
      String supplierPublicId,
      String supplierDisplayName,
      String customerPublicId,
      String customerDisplayName,
      QuotationStatus status,
      Instant expiresAt,
      List<PublicLine> lines,
      BigDecimal subtotal,
      BigDecimal fees,
      BigDecimal total,
      String currency,
      String deliveryLabel,
      String estimatedWindow,
      int paymentTermDays,
      String termsVersion,
      List<String> termsSummary,
      List<String> allowedActions,
      DecisionReceipt decisionReceipt) {

    public PublicView {
      lines = List.copyOf(lines);
      termsSummary = List.copyOf(termsSummary);
      allowedActions = List.copyOf(allowedActions);
    }
  }

  public record AcceptanceResult(
      UUID acceptanceId, String quotationNumber, Instant acceptedAt, boolean replayed) {}

  public record RejectionResult(
      UUID rejectionId,
      String quotationNumber,
      Instant rejectedAt,
      String reasonCategory,
      boolean replayed) {}
}
