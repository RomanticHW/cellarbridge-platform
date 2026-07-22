package com.rom.cellarbridge.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rom.cellarbridge.platform.PendingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class JdbcReliableEventPublisherTelemetryTest {

  @Test
  void writesCurrentW3cTraceContextIntoEventMetadata() throws Exception {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
    OpenTelemetry telemetry = OpenTelemetry.noop();
    @SuppressWarnings("unchecked")
    ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(telemetry);
    @SuppressWarnings("unchecked")
    ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
    Tracer tracer = mock(Tracer.class);
    Span currentSpan = mock(Span.class);
    TraceContext traceContext = mock(TraceContext.class);
    when(tracerProvider.getIfAvailable(any())).thenReturn(tracer);
    when(tracer.currentSpan()).thenReturn(currentSpan);
    when(currentSpan.isNoop()).thenReturn(false);
    when(currentSpan.context()).thenReturn(traceContext);
    when(traceContext.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
    when(traceContext.spanId()).thenReturn("0123456789abcdef");
    when(traceContext.sampled()).thenReturn(true);
    JdbcReliableEventPublisher publisher =
        new JdbcReliableEventPublisher(
            jdbc,
            JsonMapper.builder().build(),
            ObservationRegistry.create(),
            provider,
            tracerProvider,
            new BusinessEventMetrics(new SimpleMeterRegistry()));
    publisher.publish(event());

    ArgumentCaptor<MapSqlParameterSource> parameters =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    org.mockito.Mockito.verify(jdbc).update(anyString(), parameters.capture());
    String envelope = (String) parameters.getValue().getValue("payload");
    assertThat(
            JsonMapper.builder()
                .build()
                .readTree(envelope)
                .path("metadata")
                .path("traceparent")
                .asText())
        .isEqualTo("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
  }

  private static PendingEvent event() {
    UUID subjectId = UUID.randomUUID();
    return new PendingEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "cellarbridge.test.delivery.v1",
        1,
        Instant.parse("2026-07-22T12:00:00Z"),
        "platform",
        new PendingEvent.Subject("TEST", subjectId, "TEST-1"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of("subjectId", subjectId.toString()),
        Map.of("source", "test"));
  }
}
