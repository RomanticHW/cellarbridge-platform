package com.rom.cellarbridge.platform;

/** Persists an integration event atomically with the business transaction that produced it. */
public interface ReliableEventPublisher {

  void publish(PendingEvent event);
}
