package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class LocalEventDeliveryService {

  private final JdbcEventInbox inbox;

  LocalEventDeliveryService(JdbcEventInbox inbox) {
    this.inbox = inbox;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  DeliveryOutcome deliver(LocalEventHandler handler, EventDelivery delivery) {
    if (!handler.eventType().equals(delivery.eventType())) {
      throw new IllegalArgumentException("Handler event type does not match the delivery");
    }
    JdbcEventInbox.BeginOutcome begin = inbox.begin(handler, delivery);
    if (begin != JdbcEventInbox.BeginOutcome.PROCESS) {
      return switch (begin) {
        case ALREADY_PROCESSED -> DeliveryOutcome.ALREADY_PROCESSED;
        case FAILED_FINAL -> DeliveryOutcome.FAILED_FINAL;
        case NOT_DUE -> DeliveryOutcome.NOT_DUE;
        case PROCESS -> throw new IllegalStateException("Unreachable inbox outcome");
      };
    }
    EventHandlingResult result =
        Objects.requireNonNull(handler.handle(delivery), "Local event handler result is required");
    inbox.complete(handler, delivery, result);
    return DeliveryOutcome.PROCESSED;
  }

  enum DeliveryOutcome {
    PROCESSED,
    ALREADY_PROCESSED,
    FAILED_FINAL,
    NOT_DUE
  }
}
