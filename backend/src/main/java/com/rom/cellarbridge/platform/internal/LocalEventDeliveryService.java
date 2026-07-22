package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class LocalEventDeliveryService {

  private final JdbcEventInbox inbox;
  private final OpenTelemetry openTelemetry;
  private final BusinessEventMetrics businessMetrics;

  LocalEventDeliveryService(
      JdbcEventInbox inbox,
      ObjectProvider<OpenTelemetry> openTelemetry,
      BusinessEventMetrics businessMetrics) {
    this.inbox = inbox;
    this.openTelemetry = openTelemetry.getIfAvailable(OpenTelemetry::noop);
    this.businessMetrics = businessMetrics;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  DeliveryOutcome deliver(LocalEventHandler handler, EventDelivery delivery) {
    if (!handler.eventType().equals(delivery.eventType())) {
      throw new IllegalArgumentException("Handler event type does not match the delivery");
    }
    Context producerContext =
        openTelemetry
            .getPropagators()
            .getTextMapPropagator()
            .extract(Context.root(), delivery, DeliveryGetter.INSTANCE);
    SpanBuilder builder =
        openTelemetry
            .getTracer("cellarbridge-local-events")
            .spanBuilder("consume " + delivery.eventType())
            .setSpanKind(SpanKind.CONSUMER)
            .setNoParent();
    if (Span.fromContext(producerContext).getSpanContext().isValid()) {
      builder.addLink(Span.fromContext(producerContext).getSpanContext());
    }
    Span span =
        builder
            .setAttribute("messaging.system", "cellarbridge-local")
            .setAttribute("messaging.operation.name", "process")
            .setAttribute("messaging.message.id", delivery.eventId().toString())
            .setAttribute("cellarbridge.correlation_id", delivery.correlationId().toString())
            .setAttribute("cellarbridge.causation_id", delivery.causationId().toString())
            .setAttribute(
                "cellarbridge.tenant_hash",
                SafeTelemetryIdentifiers.tenantHash(delivery.tenantId()))
            .startSpan();
    try (Scope ignored = span.makeCurrent();
        MDC.MDCCloseable correlation =
            MDC.putCloseable("correlationId", delivery.correlationId().toString());
        MDC.MDCCloseable event = MDC.putCloseable("eventId", delivery.eventId().toString());
        MDC.MDCCloseable module = MDC.putCloseable("module", handler.consumerName())) {
      JdbcEventInbox.BeginOutcome begin = inbox.begin(handler, delivery);
      if (begin != JdbcEventInbox.BeginOutcome.PROCESS) {
        if (begin == JdbcEventInbox.BeginOutcome.ALREADY_PROCESSED) {
          businessMetrics.deliveryOutcome(delivery.eventType(), "already_processed");
        }
        return switch (begin) {
          case ALREADY_PROCESSED -> DeliveryOutcome.ALREADY_PROCESSED;
          case FAILED_FINAL -> DeliveryOutcome.FAILED_FINAL;
          case NOT_DUE -> DeliveryOutcome.NOT_DUE;
          case PROCESS -> throw new IllegalStateException("Unreachable inbox outcome");
        };
      }
      EventHandlingResult result =
          Objects.requireNonNull(
              handler.handle(delivery), "Local event handler result is required");
      inbox.complete(handler, delivery, result);
      return DeliveryOutcome.PROCESSED;
    } catch (RuntimeException failure) {
      span.recordException(failure);
      span.setStatus(StatusCode.ERROR);
      throw failure;
    } finally {
      span.end();
    }
  }

  private enum DeliveryGetter implements TextMapGetter<EventDelivery> {
    INSTANCE;

    @Override
    public Iterable<String> keys(EventDelivery carrier) {
      return List.of("traceparent", "tracestate");
    }

    @Override
    public String get(EventDelivery carrier, String key) {
      if (carrier == null) return null;
      return switch (key) {
        case "traceparent" -> carrier.traceParent();
        case "tracestate" -> carrier.traceState();
        default -> null;
      };
    }
  }

  enum DeliveryOutcome {
    PROCESSED,
    ALREADY_PROCESSED,
    FAILED_FINAL,
    NOT_DUE
  }
}
