package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(
    prefix = "cellarbridge.platform.local-events",
    name = "enabled",
    havingValue = "true")
final class LocalEventDispatcher {

  private static final Logger LOGGER = LoggerFactory.getLogger(LocalEventDispatcher.class);
  private static final Pattern CONSUMER_NAME = Pattern.compile("^[a-z0-9][a-z0-9.-]{2,119}$");
  private static final Pattern EVENT_TYPE =
      Pattern.compile("^cellarbridge\\.[a-z0-9.-]+\\.v[0-9]+$");

  private final List<LocalEventHandler> handlers;
  private final JdbcEventPublicationSource publicationSource;
  private final LocalEventDeliveryService deliveryService;
  private final JdbcEventFailureRecorder failureRecorder;
  private final BusinessEventMetrics businessMetrics;
  private final int batchSize;

  LocalEventDispatcher(
      List<LocalEventHandler> handlers,
      JdbcEventPublicationSource publicationSource,
      LocalEventDeliveryService deliveryService,
      JdbcEventFailureRecorder failureRecorder,
      BusinessEventMetrics businessMetrics,
      @Value("${cellarbridge.platform.local-events.batch-size:50}") int batchSize) {
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("Local event batch size must be between 1 and 500");
    }
    this.handlers = validateAndSort(handlers);
    this.publicationSource = publicationSource;
    this.deliveryService = deliveryService;
    this.failureRecorder = failureRecorder;
    this.businessMetrics = businessMetrics;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${cellarbridge.platform.local-events.initial-delay:PT2S}",
      fixedDelayString = "${cellarbridge.platform.local-events.fixed-delay:PT2S}")
  void dispatchAvailable() {
    handlers.forEach(this::dispatchFor);
  }

  private void dispatchFor(LocalEventHandler handler) {
    publicationSource.findReady(handler, batchSize).forEach(event -> deliver(handler, event));
  }

  private void deliver(LocalEventHandler handler, EventDelivery event) {
    try {
      deliveryService.deliver(handler, event);
    } catch (RuntimeException failure) {
      try {
        JdbcEventFailureRecorder.FailureOutcome outcome =
            failureRecorder.record(handler, event, failure);
        if (outcome == JdbcEventFailureRecorder.FailureOutcome.RETRY_SCHEDULED) {
          businessMetrics.deliveryOutcome(event.eventType(), "retry_scheduled");
        }
        LOGGER.warn(
            "localEventDeliveryFailed consumer={} eventId={} outcome={} errorType={}",
            handler.consumerName(),
            event.eventId(),
            outcome,
            failure.getClass().getSimpleName());
      } catch (RuntimeException recordingFailure) {
        LOGGER.error(
            "localEventFailureRecordingFailed consumer={} eventId={} errorType={}",
            handler.consumerName(),
            event.eventId(),
            recordingFailure.getClass().getSimpleName());
      }
    }
  }

  private static List<LocalEventHandler> validateAndSort(List<LocalEventHandler> handlers) {
    Set<String> registrations = new HashSet<>();
    for (LocalEventHandler handler : handlers) {
      if (handler == null
          || handler.consumerName() == null
          || !CONSUMER_NAME.matcher(handler.consumerName()).matches()) {
        throw new IllegalStateException("Invalid local event consumer name");
      }
      if (handler.eventType() == null || !EVENT_TYPE.matcher(handler.eventType()).matches()) {
        throw new IllegalStateException("Invalid local event type registration");
      }
      String registration = handler.consumerName() + "\u0000" + handler.eventType();
      if (!registrations.add(registration)) {
        throw new IllegalStateException("Duplicate local event handler registration");
      }
    }
    return handlers.stream()
        .sorted(
            Comparator.comparing(LocalEventHandler::consumerName)
                .thenComparing(LocalEventHandler::eventType))
        .toList();
  }
}
