package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import com.rom.cellarbridge.quotation.QuotationAcceptedV1;
import com.rom.cellarbridge.quotation.QuotationSnapshotHashV1;
import com.rom.cellarbridge.tradeorder.TradeOrderCreatedV1;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.CommercialSnapshot;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Customer;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.DeliveryAddress;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Line;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Route;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Creates at most one immutable order from a self-contained accepted-quotation fact. */
@Component
public class QuotationAcceptedEventHandler implements LocalEventHandler {

  static final String CONSUMER_NAME = "tradeorder.quotation-accepted.v1";
  static final String EVENT_TYPE = "cellarbridge.quotation.accepted.v1";
  private static final UUID SYSTEM_ACTOR_ID =
      UUID.nameUUIDFromBytes(CONSUMER_NAME.getBytes(StandardCharsets.UTF_8));
  private static final Set<String> ROUTES =
      Set.of("SH_GENERAL_TRADE", "NB_BONDED_B2B", "HK_FREE_TRADE");
  private static final Set<String> SUPPLY_TYPES =
      Set.of(
          "DOMESTIC_ON_HAND",
          "BONDED_ON_HAND",
          "HONG_KONG_ON_HAND",
          "IN_TRANSIT_PRESALE",
          "OVERSEAS_SOURCING");

  private final TradeOrderRepository repository;
  private final ReliableEventPublisher eventPublisher;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  public QuotationAcceptedEventHandler(
      TradeOrderRepository repository,
      ReliableEventPublisher eventPublisher,
      JsonMapper jsonMapper,
      Clock clock) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Override
  public String consumerName() {
    return CONSUMER_NAME;
  }

  @Override
  public String eventType() {
    return EVENT_TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      AcceptedEvent accepted = parse(delivery);
      QuotationAcceptedV1.Payload payload = accepted.payload();
      TenantId tenantId = new TenantId(delivery.tenantId());
      TradeOrder existing =
          repository.findBySourceQuotation(tenantId, payload.quotationId()).orElse(null);
      if (existing != null) {
        String existingHash = requireSameSource(existing, payload, accepted.snapshotHash());
        return result(existing, existingHash);
      }

      Instant now = max(clock.instant(), payload.acceptedAt());
      TradeOrder order = createOrder(tenantId, delivery, payload, accepted.snapshotHash(), now);
      if (!repository.insertIfAbsent(tenantId, order, SYSTEM_ACTOR_ID)) {
        TradeOrder winner =
            repository
                .findBySourceQuotation(tenantId, payload.quotationId())
                .orElseThrow(
                    () -> EventHandlingException.finalFailure("ORDER_SOURCE_EVENT_REUSED"));
        String winnerHash = requireSameSource(winner, payload, accepted.snapshotHash());
        return result(winner, winnerHash);
      }

      TradeOrderCreatedV1 created = createdEvent(order);
      eventPublisher.publish(
          new PendingEvent(
              created.id(),
              created.tenantId(),
              created.type(),
              1,
              created.occurredAt(),
              created.producer(),
              new PendingEvent.Subject(
                  created.subject().type(), created.subject().id(), created.subject().number()),
              created.correlationId(),
              created.causationId(),
              created.payload(),
              created.metadata()));
      return result(order, accepted.snapshotHash());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | ContractViolation exception) {
      throw EventHandlingException.finalFailure("QUOTATION_ACCEPTED_EVENT_INVALID");
    } catch (DataAccessException exception) {
      throw classifyDataAccess(exception);
    }
  }

  private AcceptedEvent parse(EventDelivery delivery) throws JacksonException {
    if (!EVENT_TYPE.equals(delivery.eventType()) || delivery.eventVersion() != 1) {
      throw invalid();
    }
    JsonNode body = jsonMapper.readTree(delivery.payloadJson());
    requiredInteger(body, "revision");
    requiredInteger(body, "paymentTermDays");
    requiredInteger(body.path("customer"), "sourceVersion");
    QuotationAcceptedV1.Payload payload =
        jsonMapper.readValue(body.toString(), QuotationAcceptedV1.Payload.class);
    validate(delivery, payload);
    String normalizedHash;
    try {
      normalizedHash = QuotationSnapshotHashV1.normalizeIncomingHash(payload.snapshotHash());
    } catch (QuotationSnapshotHashV1.InvalidSnapshotHashFormatException exception) {
      throw invalid();
    }
    if (!normalizedHash.equals(
        QuotationSnapshotHashV1.hash(QuotationSnapshotHashV1.Snapshot.from(payload)))) {
      throw EventHandlingException.finalFailure("QUOTATION_ACCEPTED_SNAPSHOT_HASH_MISMATCH");
    }
    return new AcceptedEvent(payload, normalizedHash);
  }

  static void validate(EventDelivery delivery, QuotationAcceptedV1.Payload payload) {
    required(payload != null);
    required(delivery.subject() != null);
    required(
        "QUOTATION".equals(delivery.subject().type())
            && Objects.equals(delivery.subject().id(), payload.quotationId())
            && Objects.equals(delivery.subject().number(), payload.quotationNumber()));
    required(
        payload.quotationId() != null
            && payload.revisionId() != null
            && payload.acceptanceId() != null
            && payload.acceptedAt() != null
            && payload.revision() >= 1);
    text(payload.quotationNumber(), 30);
    QuotationAcceptedV1.Customer customer = payload.customer();
    required(customer != null && customer.partnerId() != null && customer.sourceVersion() >= 0);
    text(customer.partnerNumber(), 80);
    text(customer.displayName(), 160);
    required(payload.currency() != null && payload.currency().matches("^[A-Z]{3}$"));
    BigDecimal total = decimal(payload.totalAmount(), 4, 15, false);
    required(payload.paymentTermDays() >= 0 && payload.paymentTermDays() <= 180);
    QuotationAcceptedV1.Route route = payload.route();
    required(
        route != null
            && route.code() != null
            && ROUTES.contains(route.code())
            && route.estimatedDeliveryDate() != null);
    text(route.policyVersion(), 80);
    text(payload.acceptedTermsVersion(), 50);
    required(payload.requestedDeliveryDate() != null && payload.deliveryAddress() != null);
    address(payload.deliveryAddress());
    required(payload.lines() != null && !payload.lines().isEmpty() && payload.lines().size() <= 50);
    Set<UUID> lineIds = new HashSet<>();
    Set<UUID> skuIds = new HashSet<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (QuotationAcceptedV1.Line line : payload.lines()) {
      required(
          line != null
              && line.quotationLineId() != null
              && lineIds.add(line.quotationLineId())
              && line.skuId() != null
              && skuIds.add(line.skuId())
              && ("CASE".equals(line.unit()) || "BOTTLE".equals(line.unit()))
              && line.supplyType() != null
              && SUPPLY_TYPES.contains(line.supplyType()));
      text(line.skuCode(), 80);
      text(line.description(), 240);
      decimal(line.quantity(), 6, 13, true);
      decimal(line.netUnitPrice(), 4, 15, false);
      sum = sum.add(decimal(line.lineTotal(), 4, 15, false));
    }
    required(
        sum.setScale(4, RoundingMode.HALF_UP).compareTo(total.setScale(4, RoundingMode.HALF_UP))
            == 0);
  }

  private static void address(QuotationAcceptedV1.DeliveryAddress address) {
    required(address.countryCode() != null && address.countryCode().matches("^[A-Z]{2}$"));
    text(address.province(), 100);
    text(address.city(), 100);
    optionalText(address.district(), 100);
    text(address.line1(), 200);
    optionalText(address.postalCode(), 20);
  }

  private static BigDecimal decimal(String value, int scale, int integerDigits, boolean positive) {
    required(value != null && value.matches("^[0-9]+(?:\\.[0-9]{1," + scale + "})?$"));
    BigDecimal decimal = new BigDecimal(value);
    required(
        (positive ? decimal.signum() > 0 : decimal.signum() >= 0)
            && Math.max(0, decimal.precision() - decimal.scale()) <= integerDigits);
    return decimal;
  }

  private static void text(String value, int maxLength) {
    required(
        value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= maxLength);
  }

  private static void optionalText(String value, int maxLength) {
    if (value != null) {
      text(value, maxLength);
    }
  }

  static RuntimeException classifyDataAccess(DataAccessException exception) {
    if (exception instanceof DataIntegrityViolationException) {
      return EventHandlingException.finalFailure("ORDER_PERSISTENCE_CONSTRAINT_VIOLATION");
    }
    if (exception instanceof TransientDataAccessException
        || exception instanceof RecoverableDataAccessException
        || exception instanceof DataAccessResourceFailureException) {
      return EventHandlingException.retryable("ORDER_STORAGE_UNAVAILABLE");
    }
    return exception;
  }

  private static void requiredInteger(JsonNode node, String field) {
    required(node.isObject() && node.hasNonNull(field) && node.path(field).isIntegralNumber());
  }

  private static void required(boolean valid) {
    if (!valid) {
      throw invalid();
    }
  }

  private static ContractViolation invalid() {
    return new ContractViolation();
  }

  private TradeOrder createOrder(
      TenantId tenantId,
      EventDelivery delivery,
      QuotationAcceptedV1.Payload payload,
      String snapshotHash,
      Instant now) {
    List<Line> lines =
        payload.lines().stream()
            .map(
                line ->
                    new Line(
                        UUID.randomUUID(),
                        line.quotationLineId(),
                        line.skuId(),
                        line.skuCode(),
                        line.description(),
                        new BigDecimal(line.quantity()),
                        line.unit(),
                        new BigDecimal(line.netUnitPrice()),
                        new BigDecimal(line.lineTotal()),
                        line.supplyPoolId(),
                        line.supplyType()))
            .toList();
    CommercialSnapshot snapshot =
        new CommercialSnapshot(
            1,
            new Customer(
                payload.customer().partnerId(),
                payload.customer().partnerNumber(),
                payload.customer().displayName(),
                payload.customer().sourceVersion()),
            payload.currency(),
            new BigDecimal(payload.totalAmount()),
            payload.paymentTermDays(),
            new Route(
                payload.route().code(),
                payload.route().policyVersion(),
                payload.route().estimatedDeliveryDate()),
            payload.acceptedTermsVersion(),
            payload.requestedDeliveryDate(),
            new DeliveryAddress(
                payload.deliveryAddress().countryCode(),
                payload.deliveryAddress().province(),
                payload.deliveryAddress().city(),
                payload.deliveryAddress().district(),
                payload.deliveryAddress().line1(),
                payload.deliveryAddress().postalCode()),
            lines);
    return TradeOrder.create(
        UUID.randomUUID(),
        tenantId,
        repository.nextNumber(tenantId, now),
        payload.quotationId(),
        payload.revisionId(),
        payload.quotationNumber(),
        payload.revision(),
        delivery.eventId(),
        payload.acceptanceId(),
        payload.acceptedAt(),
        payload.sourceOwnerId(),
        snapshot,
        snapshotHash,
        delivery.correlationId(),
        delivery.eventId(),
        UUID.randomUUID(),
        now);
  }

  private static String requireSameSource(
      TradeOrder existing, QuotationAcceptedV1.Payload payload, String snapshotHash) {
    String existingHash =
        normalizeStoredHash(existing.snapshotHash(), "ORDER_SOURCE_QUOTATION_CONFLICT");
    if (!existing.sourceRevisionId().equals(payload.revisionId())
        || existing.sourceRevision() != payload.revision()
        || !existing.sourceQuotationNumber().equals(payload.quotationNumber())
        || !existing.acceptanceId().equals(payload.acceptanceId())
        || !existingHash.equals(snapshotHash)) {
      throw EventHandlingException.finalFailure("ORDER_SOURCE_QUOTATION_CONFLICT");
    }
    return existingHash;
  }

  private static EventHandlingResult result(TradeOrder order, String snapshotHash) {
    return EventHandlingResult.processed(
        order.id().toString(), sha256(order.id() + "|" + snapshotHash));
  }

  private static String normalizeStoredHash(String snapshotHash, String failureCode) {
    try {
      return QuotationSnapshotHashV1.normalizeStoredHash(snapshotHash);
    } catch (QuotationSnapshotHashV1.InvalidSnapshotHashFormatException exception) {
      throw EventHandlingException.finalFailure(failureCode);
    }
  }

  private static TradeOrderCreatedV1 createdEvent(TradeOrder order) {
    CommercialSnapshot snapshot = order.commercialSnapshot();
    TradeOrderCreatedV1.Payload payload =
        new TradeOrderCreatedV1.Payload(
            order.id(),
            order.number(),
            order.sourceQuotationId(),
            order.sourceRevisionId(),
            order.sourceQuotationNumber(),
            order.sourceRevision(),
            order.sourceEventId(),
            order.acceptanceId(),
            order.acceptedAt(),
            order.sourceOwnerId(),
            new TradeOrderCreatedV1.Customer(
                snapshot.customer().partnerId(),
                snapshot.customer().partnerNumber(),
                snapshot.customer().displayName(),
                snapshot.customer().sourceVersion()),
            snapshot.currency(),
            amount(snapshot.totalAmount()),
            snapshot.paymentTermDays(),
            new TradeOrderCreatedV1.Route(
                snapshot.route().code(),
                snapshot.route().policyVersion(),
                snapshot.route().estimatedDeliveryDate()),
            snapshot.acceptedTermsVersion(),
            snapshot.requestedDeliveryDate(),
            new TradeOrderCreatedV1.DeliveryAddress(
                snapshot.deliveryAddress().countryCode(),
                snapshot.deliveryAddress().province(),
                snapshot.deliveryAddress().city(),
                snapshot.deliveryAddress().district(),
                snapshot.deliveryAddress().line1(),
                snapshot.deliveryAddress().postalCode()),
            order.snapshotHash(),
            snapshot.lines().stream()
                .map(
                    line ->
                        new TradeOrderCreatedV1.Line(
                            line.id(),
                            line.sourceQuotationLineId(),
                            line.skuId(),
                            line.skuCode(),
                            line.description(),
                            amount(line.quantity()),
                            line.unit(),
                            amount(line.netUnitPrice()),
                            amount(line.lineTotal()),
                            line.supplyPoolId(),
                            line.supplyType()))
                .toList(),
            order.createdAt());
    return new TradeOrderCreatedV1(
        order.createdEventId(),
        TradeOrderCreatedV1.TYPE,
        "1.0",
        order.createdAt(),
        order.tenantId().value(),
        "trade-order",
        new TradeOrderCreatedV1.Subject("TRADE_ORDER", order.id(), order.number()),
        order.correlationId(),
        order.sourceEventId(),
        payload,
        Map.of());
  }

  private static Instant max(Instant first, Instant second) {
    return first.isBefore(second) ? second : first;
  }

  private static String amount(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record AcceptedEvent(QuotationAcceptedV1.Payload payload, String snapshotHash) {}

  private static final class ContractViolation extends RuntimeException {}
}
