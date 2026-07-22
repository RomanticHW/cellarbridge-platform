package com.rom.cellarbridge.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class LocalEventDeliveryIntegrationTest extends PostgresIntegrationTestSupport {

  private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Autowired private LocalEventDeliveryService deliveryService;
  @Autowired private JdbcEventFailureRecorder failureRecorder;
  @Autowired private ReliableEventPublisher eventPublisher;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext applicationContext;

  @Test
  void testProfileHardDisablesTheDemoSchedulerForDeterministicIntegrationTests() {
    assertThat(applicationContext.getBeansOfType(LocalEventDispatcher.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(LocalEventSchedulingConfiguration.class))
        .isEmpty();
  }

  @Test
  void completesTheInboxAndNextPublicationInOneTransactionAndSkipsDuplicates() {
    EventDelivery delivery = delivery(UUID.randomUUID());
    RecordingHandler handler = new RecordingHandler("test.order-create.success", eventPublisher);

    assertThat(deliveryService.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertThat(deliveryService.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.ALREADY_PROCESSED);

    assertThat(handler.invocations()).isEqualTo(1);
    assertThat(inboxValue(handler, delivery, "status")).isEqualTo("PROCESSED");
    assertThat(inboxNumber(handler, delivery, "duplicate_count")).isEqualTo(1);
    assertThat(publicationCount(handler.nextEventId(delivery))).isEqualTo(1);
  }

  @Test
  void normalizesNanosecondEventTimeAcrossTheEnvelopeAndPostgresColumn() {
    Instant occurredAt = Instant.parse("2026-07-22T10:02:31.452170999Z");
    EventDelivery delivery = delivery(UUID.randomUUID(), occurredAt);
    RecordingHandler handler =
        new RecordingHandler("test.order-create.timestamp-precision", eventPublisher);

    assertThat(deliveryService.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);

    UUID publicationId = handler.nextEventId(delivery);
    Instant expected = occurredAt.truncatedTo(ChronoUnit.MICROS);
    assertThat(publicationOccurredAt(publicationId)).isEqualTo(expected);
    assertThat(publicationEnvelopeOccurredAt(publicationId)).isEqualTo(expected);
  }

  @Test
  void rollsBackTheHandlerTransactionThenRecordsAndRecoversABoundedRetry() {
    EventDelivery delivery = delivery(UUID.randomUUID());
    RecordingHandler handler = new RecordingHandler("test.order-create.retry", eventPublisher);
    handler.failRetryablyOnce();

    RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, delivery));
    assertThat(failureRecorder.record(handler, delivery, failure))
        .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.RETRY_SCHEDULED);
    assertThat(publicationCount(handler.nextEventId(delivery))).isZero();
    assertThat(inboxValue(handler, delivery, "status")).isEqualTo("FAILED_RETRYABLE");

    jdbc.update(
        """
        UPDATE platform_event.event_inbox
           SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
         WHERE consumer_name = ? AND event_id = ?
        """,
        handler.consumerName(),
        delivery.eventId());

    assertThat(deliveryService.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);
    assertThat(inboxNumber(handler, delivery, "attempts")).isEqualTo(2);
    assertThat(publicationCount(handler.nextEventId(delivery))).isEqualTo(1);
  }

  @Test
  void recordsAPoisonEventAsFinalAndNeverInvokesItAgain() {
    EventDelivery delivery = delivery(UUID.randomUUID());
    RecordingHandler handler = new RecordingHandler("test.order-create.final", eventPublisher);
    handler.failFinally();

    RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, delivery));
    assertThat(failureRecorder.record(handler, delivery, failure))
        .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.FAILED_FINAL);
    assertThat(deliveryService.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.FAILED_FINAL);

    assertThat(handler.invocations()).isEqualTo(1);
    assertThat(inboxValue(handler, delivery, "last_error_code"))
        .isEqualTo("EVENT_SCHEMA_UNSUPPORTED");
    assertThat(publicationCount(handler.nextEventId(delivery))).isZero();
  }

  @Test
  void countsEachActualInvocationExactlyOnceAndStopsAtTheConfiguredMaximum() {
    EventDelivery delivery = delivery(UUID.randomUUID());
    RecordingHandler handler = new RecordingHandler("test.order-create.max-retry", eventPublisher);
    handler.alwaysFailRetryably();

    for (int invocation = 1; invocation <= 5; invocation++) {
      RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, delivery));
      JdbcEventFailureRecorder.FailureOutcome outcome =
          failureRecorder.record(handler, delivery, failure);
      assertThat(inboxNumber(handler, delivery, "attempts")).isEqualTo(invocation);
      assertThat(outcome)
          .isEqualTo(
              invocation < 5
                  ? JdbcEventFailureRecorder.FailureOutcome.RETRY_SCHEDULED
                  : JdbcEventFailureRecorder.FailureOutcome.FAILED_FINAL);
      if (invocation < 5) {
        makeRetryDue(handler, delivery);
      }
    }

    assertThat(handler.invocations()).isEqualTo(5);
    assertThat(deliveryService.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.FAILED_FINAL);
    assertThat(handler.invocations()).isEqualTo(5);
    assertThat(publicationCount(handler.nextEventId(delivery))).isZero();
  }

  @Test
  void makesAnAbnormallyPersistedProcessingRowFinalWithoutInventingAnInvocation() {
    EventDelivery delivery = delivery(UUID.randomUUID());
    RecordingHandler handler =
        new RecordingHandler("test.order-create.orphaned-processing", eventPublisher);
    jdbc.update(
        """
        INSERT INTO platform_event.event_inbox
          (tenant_id, consumer_name, event_id, event_type, payload_hash, status,
           attempts, duplicate_count, first_received_at, last_attempt_at,
           created_at, updated_at, version)
        VALUES (?, ?, ?, ?, ?, 'PROCESSING', 3, 0,
                TIMESTAMPTZ '2000-01-01 00:00:00Z', TIMESTAMPTZ '2000-01-01 00:00:00Z',
                TIMESTAMPTZ '2000-01-01 00:00:00Z', TIMESTAMPTZ '2000-01-01 00:00:00Z', 0)
        """,
        delivery.tenantId(),
        handler.consumerName(),
        delivery.eventId(),
        delivery.eventType(),
        EventPayloadHash.sha256(delivery.payloadJson()));

    RuntimeException failure = catchFailure(() -> deliveryService.deliver(handler, delivery));
    assertThat(failureRecorder.record(handler, delivery, failure))
        .isEqualTo(JdbcEventFailureRecorder.FailureOutcome.FAILED_FINAL);

    assertThat(handler.invocations()).isZero();
    assertThat(inboxNumber(handler, delivery, "attempts")).isEqualTo(3);
    assertThat(inboxValue(handler, delivery, "last_error_code"))
        .isEqualTo("EVENT_INBOX_ORPHANED_PROCESSING");
  }

  @Test
  void rejectsConflictingPayloadReuseWithoutChangingTheCompletedEvidence() {
    EventDelivery delivery = delivery(UUID.randomUUID());
    RecordingHandler handler = new RecordingHandler("test.order-create.binding", eventPublisher);
    deliveryService.deliver(handler, delivery);
    EventDelivery conflicting =
        new EventDelivery(
            delivery.eventId(),
            delivery.tenantId(),
            delivery.eventType(),
            delivery.eventVersion(),
            delivery.occurredAt(),
            delivery.producer(),
            delivery.subject(),
            delivery.correlationId(),
            delivery.causationId(),
            "{\"quotationId\":\"64000000-0000-4000-8000-000000000099\"}");

    assertThatThrownBy(() -> deliveryService.deliver(handler, conflicting))
        .isInstanceOf(EventHandlingException.class)
        .extracting("failureCode")
        .isEqualTo("EVENT_INBOX_BINDING_CONFLICT");
    assertThat(inboxValue(handler, delivery, "status")).isEqualTo("PROCESSED");
  }

  private String inboxValue(
      LocalEventHandler handler, EventDelivery delivery, String selectedColumn) {
    if (!selectedColumn.equals("status") && !selectedColumn.equals("last_error_code")) {
      throw new IllegalArgumentException("Unsupported test column");
    }
    return jdbc.queryForObject(
        "SELECT "
            + selectedColumn
            + " FROM platform_event.event_inbox WHERE consumer_name = ? AND event_id = ?",
        String.class,
        handler.consumerName(),
        delivery.eventId());
  }

  private int inboxNumber(
      LocalEventHandler handler, EventDelivery delivery, String selectedColumn) {
    if (!selectedColumn.equals("attempts") && !selectedColumn.equals("duplicate_count")) {
      throw new IllegalArgumentException("Unsupported test column");
    }
    Integer value =
        jdbc.queryForObject(
            "SELECT "
                + selectedColumn
                + " FROM platform_event.event_inbox WHERE consumer_name = ? AND event_id = ?",
            Integer.class,
            handler.consumerName(),
            delivery.eventId());
    return value == null ? -1 : value;
  }

  private int publicationCount(UUID eventId) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform_event.event_publication WHERE event_id = ?",
            Integer.class,
            eventId);
    return value == null ? -1 : value;
  }

  private Instant publicationOccurredAt(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT occurred_at FROM platform_event.event_publication WHERE event_id = ?",
        (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
        eventId);
  }

  private Instant publicationEnvelopeOccurredAt(UUID eventId) {
    String value =
        jdbc.queryForObject(
            "SELECT payload ->> 'occurredAt' FROM platform_event.event_publication WHERE event_id = ?",
            String.class,
            eventId);
    return Instant.parse(value);
  }

  private void makeRetryDue(LocalEventHandler handler, EventDelivery delivery) {
    jdbc.update(
        """
        UPDATE platform_event.event_inbox
           SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
         WHERE consumer_name = ? AND event_id = ?
        """,
        handler.consumerName(),
        delivery.eventId());
  }

  private static RuntimeException catchFailure(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected handler failure");
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  private static EventDelivery delivery(UUID eventId) {
    return delivery(eventId, Instant.now());
  }

  private static EventDelivery delivery(UUID eventId, Instant occurredAt) {
    UUID quotationId =
        UUID.nameUUIDFromBytes(("quotation:" + eventId).getBytes(StandardCharsets.UTF_8));
    return new EventDelivery(
        eventId,
        TENANT_ID,
        "cellarbridge.quotation.accepted.v1",
        1,
        occurredAt,
        "quotation",
        new EventDelivery.Subject(
            "Quotation", quotationId, "Q-" + eventId.toString().substring(0, 8)),
        eventId,
        eventId,
        "{\"quotationId\":\"" + quotationId + "\"}");
  }

  private static final class RecordingHandler implements LocalEventHandler {

    private static final String RESULT_HASH = "b".repeat(64);
    private final String consumerName;
    private final ReliableEventPublisher publisher;
    private final AtomicInteger invocations = new AtomicInteger();
    private boolean retryOnce;
    private boolean alwaysRetry;
    private boolean finalFailure;

    private RecordingHandler(String consumerName, ReliableEventPublisher publisher) {
      this.consumerName = consumerName;
      this.publisher = publisher;
    }

    @Override
    public String consumerName() {
      return consumerName;
    }

    @Override
    public String eventType() {
      return "cellarbridge.quotation.accepted.v1";
    }

    @Override
    public EventHandlingResult handle(EventDelivery delivery) {
      int invocation = invocations.incrementAndGet();
      if (finalFailure) {
        throw EventHandlingException.finalFailure("EVENT_SCHEMA_UNSUPPORTED");
      }
      publishNext(delivery);
      if (alwaysRetry || (retryOnce && invocation == 1)) {
        throw EventHandlingException.retryable("ORDER_STORAGE_UNAVAILABLE");
      }
      return EventHandlingResult.processed("order:" + delivery.subject().id(), RESULT_HASH);
    }

    int invocations() {
      return invocations.get();
    }

    void failRetryablyOnce() {
      retryOnce = true;
    }

    void failFinally() {
      finalFailure = true;
    }

    void alwaysFailRetryably() {
      alwaysRetry = true;
    }

    UUID nextEventId(EventDelivery delivery) {
      return UUID.nameUUIDFromBytes(
          (consumerName + delivery.eventId()).getBytes(StandardCharsets.UTF_8));
    }

    private void publishNext(EventDelivery delivery) {
      UUID nextEventId = nextEventId(delivery);
      publisher.publish(
          new PendingEvent(
              nextEventId,
              delivery.tenantId(),
              "cellarbridge.order.created.v1",
              1,
              delivery.occurredAt(),
              "trade-order",
              new PendingEvent.Subject("TradeOrder", delivery.subject().id(), "ORD-001"),
              delivery.correlationId(),
              delivery.eventId(),
              Map.of("orderId", delivery.subject().id().toString()),
              Map.of()));
    }
  }
}
