package com.rom.cellarbridge.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

class LocalEventDeliveryTelemetryTest {

  @Test
  void createsConsumerSpanLinkedToProducerAndCleansMdc() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetry telemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    @SuppressWarnings("unchecked")
    ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(telemetry);
    JdbcEventInbox inbox = mock(JdbcEventInbox.class);
    when(inbox.begin(any(), any())).thenReturn(JdbcEventInbox.BeginOutcome.PROCESS);
    BusinessEventMetrics metrics = new BusinessEventMetrics(new SimpleMeterRegistry());
    LocalEventDeliveryService service = new LocalEventDeliveryService(inbox, provider, metrics);
    LocalEventHandler handler = handler();
    EventDelivery delivery = delivery();

    assertThat(service.deliver(handler, delivery))
        .isEqualTo(LocalEventDeliveryService.DeliveryOutcome.PROCESSED);

    verify(inbox).complete(handler, delivery, EventHandlingResult.processed());
    assertThat(exporter.getFinishedSpanItems())
        .singleElement()
        .satisfies(
            span -> {
              assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
              assertThat(span.getLinks())
                  .singleElement()
                  .satisfies(
                      link ->
                          assertThat(link.getSpanContext().getTraceId())
                              .isEqualTo("0123456789abcdef0123456789abcdef"));
            });
    assertThat(MDC.get("correlationId")).isNull();
    assertThat(MDC.get("eventId")).isNull();
    tracerProvider.close();
  }

  private static LocalEventHandler handler() {
    return new LocalEventHandler() {
      @Override
      public String consumerName() {
        return "test.consumer.v1";
      }

      @Override
      public String eventType() {
        return "cellarbridge.test.delivery.v1";
      }

      @Override
      public EventHandlingResult handle(EventDelivery delivery) {
        return EventHandlingResult.processed();
      }
    };
  }

  private static EventDelivery delivery() {
    return new EventDelivery(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "cellarbridge.test.delivery.v1",
        1,
        Instant.parse("2026-07-22T12:00:00Z"),
        "platform",
        new EventDelivery.Subject("TEST", UUID.randomUUID(), "TEST-1"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "{}",
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        null);
  }
}
