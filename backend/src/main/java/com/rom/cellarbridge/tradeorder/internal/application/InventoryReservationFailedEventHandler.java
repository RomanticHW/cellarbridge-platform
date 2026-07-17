package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.inventory.InventoryReservationFailedV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import org.springframework.stereotype.Component;

/** Consumes the terminal business failure Inventory reservation fact. */
@Component
public final class InventoryReservationFailedEventHandler implements LocalEventHandler {

  static final String CONSUMER_NAME = "tradeorder.inventory-reservation-failed.v1";

  private final TradeOrderReservationOutcomeService service;

  InventoryReservationFailedEventHandler(TradeOrderReservationOutcomeService service) {
    this.service = service;
  }

  @Override
  public String consumerName() {
    return CONSUMER_NAME;
  }

  @Override
  public String eventType() {
    return InventoryReservationFailedV1.TYPE;
  }

  @Override
  public EventHandlingResult handle(EventDelivery delivery) {
    return service.failed(delivery);
  }
}
