package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import com.rom.cellarbridge.tradeorder.TradeOrderCreatedV1;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.CommercialSnapshot;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Customer;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.DeliveryAddress;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Line;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Route;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Creates at most one immutable order from a self-contained accepted-quotation fact. */
@Component
public class QuotationAcceptedEventHandler implements LocalEventHandler {

  static final String CONSUMER_NAME = "tradeorder.quotation-accepted.v1";
  static final String EVENT_TYPE = "cellarbridge.quotation.accepted.v1";
  private static final UUID SYSTEM_ACTOR_ID =
      UUID.nameUUIDFromBytes(CONSUMER_NAME.getBytes(StandardCharsets.UTF_8));

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
      AcceptedPayload payload = parse(delivery);
      TenantId tenantId = new TenantId(delivery.tenantId());
      TradeOrder existing =
          repository.findBySourceQuotation(tenantId, payload.quotationId()).orElse(null);
      if (existing != null) {
        requireSameSource(existing, payload);
        return result(existing);
      }

      Instant now = max(clock.instant(), payload.acceptedAt());
      TradeOrder order = createOrder(tenantId, delivery, payload, now);
      if (!repository.insertIfAbsent(tenantId, order, SYSTEM_ACTOR_ID)) {
        TradeOrder winner =
            repository
                .findBySourceQuotation(tenantId, payload.quotationId())
                .orElseThrow(
                    () -> EventHandlingException.finalFailure("ORDER_SOURCE_EVENT_REUSED"));
        requireSameSource(winner, payload);
        return result(winner);
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
      return result(order);
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("QUOTATION_ACCEPTED_EVENT_INVALID");
    } catch (DataAccessException exception) {
      throw EventHandlingException.retryable("ORDER_STORAGE_UNAVAILABLE");
    }
  }

  private AcceptedPayload parse(EventDelivery delivery) throws JacksonException {
    if (!EVENT_TYPE.equals(delivery.eventType()) || delivery.eventVersion() != 1) {
      throw new IllegalArgumentException("Unsupported quotation-accepted event version");
    }
    AcceptedPayload payload = jsonMapper.readValue(delivery.payloadJson(), AcceptedPayload.class);
    if (!"QUOTATION".equals(delivery.subject().type())
        || !delivery.subject().id().equals(payload.quotationId())
        || !delivery.subject().number().equals(payload.quotationNumber())) {
      throw new IllegalArgumentException("Quotation-accepted subject does not match its payload");
    }
    return payload;
  }

  private TradeOrder createOrder(
      TenantId tenantId, EventDelivery delivery, AcceptedPayload payload, Instant now) {
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
        payload.snapshotHash(),
        delivery.correlationId(),
        delivery.eventId(),
        UUID.randomUUID(),
        now);
  }

  private static void requireSameSource(TradeOrder existing, AcceptedPayload payload) {
    if (!existing.sourceRevisionId().equals(payload.revisionId())
        || existing.sourceRevision() != payload.revision()
        || !existing.sourceQuotationNumber().equals(payload.quotationNumber())
        || !existing.acceptanceId().equals(payload.acceptanceId())
        || !existing.snapshotHash().equals(payload.snapshotHash())) {
      throw EventHandlingException.finalFailure("ORDER_SOURCE_QUOTATION_CONFLICT");
    }
  }

  private static EventHandlingResult result(TradeOrder order) {
    return EventHandlingResult.processed(
        order.id().toString(), sha256(order.id() + "|" + order.snapshotHash()));
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

  private record AcceptedPayload(
      UUID quotationId,
      UUID revisionId,
      String quotationNumber,
      int revision,
      UUID acceptanceId,
      Instant acceptedAt,
      UUID sourceOwnerId,
      AcceptedCustomer customer,
      String currency,
      String totalAmount,
      int paymentTermDays,
      AcceptedRoute route,
      String acceptedTermsVersion,
      LocalDate requestedDeliveryDate,
      AcceptedAddress deliveryAddress,
      String snapshotHash,
      List<AcceptedLine> lines) {}

  private record AcceptedCustomer(
      UUID partnerId, String partnerNumber, String displayName, int sourceVersion) {}

  private record AcceptedRoute(
      String code, String policyVersion, LocalDate estimatedDeliveryDate) {}

  private record AcceptedAddress(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {}

  private record AcceptedLine(
      UUID quotationLineId,
      UUID skuId,
      String skuCode,
      String description,
      String quantity,
      String unit,
      String netUnitPrice,
      String lineTotal,
      UUID supplyPoolId,
      String supplyType) {}
}
