package com.rom.cellarbridge.settlement.internal.application;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import com.rom.cellarbridge.tradeorder.TradeOrderCreatedV1;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class TradeOrderCreatedSettlementHandler implements LocalEventHandler {
  private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
  private final JsonMapper json;
  private final SettlementService settlement;

  TradeOrderCreatedSettlementHandler(JsonMapper json, SettlementService settlement) {
    this.json = json;
    this.settlement = settlement;
  }

  @Override
  public String consumerName() {
    return "settlement.order-snapshot.v1";
  }

  @Override
  public String eventType() {
    return TradeOrderCreatedV1.TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    try {
      TradeOrderCreatedV1.Payload payload =
          json.readValue(delivery.payloadJson(), TradeOrderCreatedV1.Payload.class);
      validate(delivery, payload);
      SettlementService.SnapshotResult result = settlement.captureOrderSnapshot(delivery, payload);
      return EventHandlingResult.processed(result.orderId().toString(), result.evidenceHash());
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("SETTLEMENT_ORDER_EVENT_INVALID");
    }
  }

  private static void validate(EventDelivery delivery, TradeOrderCreatedV1.Payload payload) {
    if (!"TRADE_ORDER".equals(delivery.subject().type())
        || !Objects.equals(delivery.subject().id(), payload.orderId())
        || !Objects.equals(delivery.subject().number(), payload.orderNumber())
        || payload.customer() == null
        || payload.customer().partnerId() == null
        || blank(payload.customer().partnerNumber())
        || blank(payload.customer().displayName())
        || payload.customer().sourceVersion() < 0
        || blank(payload.currency())
        || payload.currency().length() != 3
        || payload.totalAmount() == null
        || new BigDecimal(payload.totalAmount()).signum() < 0
        || payload.paymentTermDays() < 0
        || payload.paymentTermDays() > 180
        || payload.acceptedAt() == null
        || payload.snapshotHash() == null
        || !HASH.matcher(payload.snapshotHash()).matches()) {
      throw EventHandlingException.finalFailure("SETTLEMENT_ORDER_EVENT_INVALID");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
