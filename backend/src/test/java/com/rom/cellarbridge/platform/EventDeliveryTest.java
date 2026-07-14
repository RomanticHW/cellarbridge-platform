package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventDeliveryTest {

  @Test
  void acceptsAnExactVersionedDeliveryAndSafeResultEvidence() {
    EventDelivery delivery = delivery("cellarbridge.quotation.accepted.v1", 1);
    EventHandlingResult result =
        EventHandlingResult.processed("order:70000000-0000-4000-8000-000000000001", "a".repeat(64));

    assertThat(delivery.eventType()).isEqualTo("cellarbridge.quotation.accepted.v1");
    assertThat(result.resultHash()).hasSize(64);
  }

  @Test
  void rejectsMismatchedVersionsAndPartialResultEvidence() {
    assertThatThrownBy(() -> delivery("cellarbridge.quotation.accepted.v1", 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventHandlingResult.processed("order:one", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventHandlingResult.processed("order:one", "sha256:" + "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void classifiesFailuresWithoutAcceptingFreeFormMessages() {
    EventHandlingException failure =
        EventHandlingException.finalFailure("ORDER_SOURCE_QUOTE_CONFLICT");

    assertThat(failure.failureCode()).isEqualTo("ORDER_SOURCE_QUOTE_CONFLICT");
    assertThat(failure.retryable()).isFalse();
    assertThatThrownBy(() -> EventHandlingException.retryable("unsafe failure text"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static EventDelivery delivery(String eventType, int version) {
    UUID eventId = UUID.fromString("61000000-0000-4000-8000-000000000001");
    return new EventDelivery(
        eventId,
        UUID.fromString("10000000-0000-4000-8000-000000000001"),
        eventType,
        version,
        Instant.parse("2026-07-14T00:00:00Z"),
        "quotation",
        new EventDelivery.Subject(
            "Quotation", UUID.fromString("62000000-0000-4000-8000-000000000001"), "Q-001"),
        eventId,
        eventId,
        "{\"quotationId\":\"62000000-0000-4000-8000-000000000001\"}");
  }
}
