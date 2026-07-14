package com.rom.cellarbridge.platform;

/**
 * Transactional local integration-event consumer.
 *
 * <p>The platform invokes {@link #handle(EventDelivery)} in the same transaction that records the
 * inbox result. A handler may persist its module-owned business effect and use {@link
 * ReliableEventPublisher}; it must not acknowledge an external broker or perform network I/O.
 */
public interface LocalEventHandler {

  /** Stable consumer identity used as part of the inbox idempotency key. */
  String consumerName();

  /** Exact versioned integration-event type handled by this consumer. */
  String eventType();

  /** Applies at most one business effect and returns only safe, deterministic result evidence. */
  EventHandlingResult handle(EventDelivery delivery);
}
