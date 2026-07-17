package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.inventory.InventoryReservationConfirmedV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import org.springframework.stereotype.Component;

/** Consumes the successful Inventory reservation fact. */
@Component
public final class InventoryReservationConfirmedEventHandler implements LocalEventHandler {

  static final String CONSUMER_NAME = "tradeorder.inventory-reservation-confirmed.v1";

  private final TradeOrderReservationOutcomeService service;

  InventoryReservationConfirmedEventHandler(TradeOrderReservationOutcomeService service) {
    this.service = service;
  }

  @Override
  public String consumerName() {
    return CONSUMER_NAME;
  }

  @Override
  public String eventType() {
    return InventoryReservationConfirmedV1.TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    return service.confirmed(delivery);
  }
}
