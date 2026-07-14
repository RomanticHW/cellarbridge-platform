package com.rom.cellarbridge.quotation.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import com.rom.cellarbridge.quotation.QuotationSnapshotHashV1;
import com.rom.cellarbridge.quotation.QuotationStatus;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.AcceptedOrderSource;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.OrderLink;
import com.rom.cellarbridge.quotation.internal.domain.QuotationAggregate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Eventually links a created order back to its accepted quotation without a module cycle. */
@Component
public class TradeOrderCreatedEventHandler implements LocalEventHandler {

  static final String CONSUMER_NAME = "quotation.trade-order-created.v1";
  static final String EVENT_TYPE = "cellarbridge.order.created.v1";
  private static final UUID SYSTEM_ACTOR_ID =
      UUID.nameUUIDFromBytes(CONSUMER_NAME.getBytes(StandardCharsets.UTF_8));

  private final QuotationRepository repository;
  private final JsonMapper jsonMapper;

  public TradeOrderCreatedEventHandler(QuotationRepository repository, JsonMapper jsonMapper) {
    this.repository = repository;
    this.jsonMapper = jsonMapper;
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
      OrderCreatedPayload payload = parse(delivery);
      TenantId tenantId = new TenantId(delivery.tenantId());
      OrderLink existing = repository.findOrderLink(tenantId, payload.quotationId()).orElse(null);
      if (existing != null) {
        requireSameLink(existing, payload, delivery.eventId());
        return processed(
            existing.orderId(),
            QuotationSnapshotHashV1.normalizeStoredHash(existing.snapshotHash()));
      }

      QuotationAggregate before =
          repository
              .findForUpdate(tenantId, payload.quotationId())
              .orElseThrow(() -> EventHandlingException.finalFailure("ORDER_QUOTATION_NOT_FOUND"));
      if (before.status() == QuotationStatus.CONVERTED) {
        throw EventHandlingException.finalFailure("ORDER_LINK_MISSING");
      }
      AcceptedOrderSource source =
          repository
              .findAcceptedOrderSource(tenantId, payload.quotationId())
              .orElseThrow(() -> EventHandlingException.finalFailure("ORDER_ACCEPTANCE_NOT_FOUND"));
      if (!source.revisionId().equals(payload.revisionId())
          || (payload.acceptanceId() != null
              && !source.acceptanceId().equals(payload.acceptanceId()))
          || !QuotationSnapshotHashV1.normalizeStoredHash(source.snapshotHash())
              .equals(payload.snapshotHash())) {
        throw EventHandlingException.finalFailure("ORDER_ACCEPTANCE_CONFLICT");
      }
      QuotationAggregate after = before.convert(payload.revisionId(), delivery.occurredAt());
      OrderLink link =
          new OrderLink(
              payload.quotationId(),
              payload.revisionId(),
              source.acceptanceId(),
              payload.orderId(),
              payload.orderNumber(),
              payload.snapshotHash(),
              delivery.eventId(),
              delivery.occurredAt());
      repository.saveOrderConversion(tenantId, before, after, link, SYSTEM_ACTOR_ID);
      return processed(payload.orderId(), payload.snapshotHash());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("ORDER_CREATED_EVENT_INVALID");
    } catch (DataAccessException exception) {
      throw EventHandlingException.retryable("ORDER_LINK_STORAGE_UNAVAILABLE");
    }
  }

  private OrderCreatedPayload parse(EventDelivery delivery) throws JacksonException {
    if (!EVENT_TYPE.equals(delivery.eventType()) || delivery.eventVersion() != 1) {
      throw new IllegalArgumentException("Unsupported order-created event version");
    }
    JsonNode payload = jsonMapper.readTree(delivery.payloadJson());
    UUID orderId = uuid(payload, "orderId");
    String orderNumber = text(payload, "orderNumber");
    if (!delivery.subject().id().equals(orderId)
        || !delivery.subject().number().equals(orderNumber)
        || !"TRADE_ORDER".equals(delivery.subject().type())) {
      throw new IllegalArgumentException("Order-created subject does not match its payload");
    }
    return new OrderCreatedPayload(
        orderId,
        orderNumber,
        uuid(payload, "sourceQuotationId"),
        uuid(payload, "sourceRevisionId"),
        optionalUuid(payload, "acceptanceId"),
        QuotationSnapshotHashV1.normalizeIncomingHash(text(payload, "snapshotHash")));
  }

  private static UUID uuid(JsonNode node, String field) {
    return UUID.fromString(text(node, field));
  }

  private static UUID optionalUuid(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : UUID.fromString(text(node, field));
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).asText();
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static void requireSameLink(
      OrderLink existing, OrderCreatedPayload payload, UUID sourceEventId) {
    if (!existing.revisionId().equals(payload.revisionId())
        || (payload.acceptanceId() != null
            && !existing.acceptanceId().equals(payload.acceptanceId()))
        || !existing.orderId().equals(payload.orderId())
        || !existing.orderNumber().equals(payload.orderNumber())
        || !QuotationSnapshotHashV1.normalizeStoredHash(existing.snapshotHash())
            .equals(payload.snapshotHash())
        || !existing.sourceEventId().equals(sourceEventId)) {
      throw EventHandlingException.finalFailure("ORDER_LINK_CONFLICT");
    }
  }

  private static EventHandlingResult processed(UUID orderId, String snapshotHash) {
    try {
      String material = orderId + "\u0000" + snapshotHash;
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(material.getBytes(StandardCharsets.UTF_8)));
      return EventHandlingResult.processed(orderId.toString(), digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record OrderCreatedPayload(
      UUID orderId,
      String orderNumber,
      UUID quotationId,
      UUID revisionId,
      UUID acceptanceId,
      String snapshotHash) {}
}
