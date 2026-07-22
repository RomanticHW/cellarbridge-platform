package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcReliableEventPublisher implements ReliableEventPublisher {

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper jsonMapper;
  private final ObservationRegistry observations;
  private final OpenTelemetry openTelemetry;
  private final Tracer tracer;
  private final BusinessEventMetrics businessMetrics;

  public JdbcReliableEventPublisher(
      NamedParameterJdbcTemplate jdbc,
      JsonMapper jsonMapper,
      ObservationRegistry observations,
      ObjectProvider<OpenTelemetry> openTelemetry,
      ObjectProvider<Tracer> tracer,
      BusinessEventMetrics businessMetrics) {
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
    this.observations = observations;
    this.openTelemetry = openTelemetry.getIfAvailable(OpenTelemetry::noop);
    this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    this.businessMetrics = businessMetrics;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(PendingEvent event) {
    Observation observation =
        Observation.start("cellarbridge.event.publish", observations)
            .lowCardinalityKeyValue("event.type", event.eventType())
            .lowCardinalityKeyValue("module", event.producer())
            .highCardinalityKeyValue("event.id", event.eventId().toString())
            .highCardinalityKeyValue("correlation.id", event.correlationId().toString())
            .highCardinalityKeyValue(
                "tenant.hash", SafeTelemetryIdentifiers.tenantHash(event.tenantId()));
    try (Observation.Scope ignored = observation.openScope()) {
      Instant occurredAt = event.occurredAt().truncatedTo(ChronoUnit.MICROS);
      String envelope = serialize(event, occurredAt, propagatedMetadata(event.metadata()));
      MapSqlParameterSource parameters = parameters(event, envelope, occurredAt);
      int inserted =
          Observation.createNotStarted("cellarbridge.event.publication.database", observations)
              .lowCardinalityKeyValue("db.operation", "insert")
              .observe(
                  () ->
                      jdbc.update(
                          """
            INSERT INTO platform_event.event_publication
              (event_id, tenant_id, event_type, event_version, spec_version, producer,
               subject_type, subject_id, subject_number, payload, status, attempts,
               next_attempt_at, occurred_at, correlation_id, causation_id,
               created_at, updated_at, version)
            VALUES
              (:eventId, :tenantId, :eventType, :eventVersion, :specVersion, :producer,
               :subjectType, :subjectId, :subjectNumber, CAST(:payload AS jsonb),
               'PENDING', 0, :occurredAt, :occurredAt, :correlationId, :causationId,
               :occurredAt, :occurredAt, 0)
            ON CONFLICT DO NOTHING
            """,
                          parameters));
      if (inserted == 0 && !matchesExistingPublication(parameters)) {
        throw new IllegalStateException(
            "Conflicting reliable event publication for event " + event.eventId());
      }
      if (inserted == 1) {
        businessMetrics.published(event.eventType());
      }
    } catch (RuntimeException failure) {
      observation.error(failure);
      throw failure;
    } finally {
      observation.stop();
    }
  }

  private Map<String, ?> propagatedMetadata(Map<String, ?> source) {
    Map<String, Object> metadata = new LinkedHashMap<>(source);
    TextMapSetter<Map<String, Object>> setter = Map::put;
    openTelemetry
        .getPropagators()
        .getTextMapPropagator()
        .inject(Context.current(), metadata, setter);
    Span currentSpan = tracer.currentSpan();
    if (!metadata.containsKey("traceparent") && currentSpan != null && !currentSpan.isNoop()) {
      TraceContext context = currentSpan.context();
      metadata.put(
          "traceparent",
          "00-"
              + context.traceId()
              + "-"
              + context.spanId()
              + (Boolean.TRUE.equals(context.sampled()) ? "-01" : "-00"));
    }
    return Collections.unmodifiableMap(metadata);
  }

  private boolean matchesExistingPublication(MapSqlParameterSource parameters) {
    Integer matches =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM platform_event.event_publication
             WHERE event_id = :eventId
               AND tenant_id = :tenantId
               AND event_type = :eventType
               AND event_version = :eventVersion
               AND spec_version = :specVersion
               AND producer = :producer
               AND subject_type = :subjectType
               AND subject_id = :subjectId
               AND subject_number = :subjectNumber
               AND payload = CAST(:payload AS jsonb)
               AND occurred_at = :occurredAt
               AND correlation_id = :correlationId
               AND causation_id = :causationId
            """,
            parameters,
            Integer.class);
    return matches != null && matches == 1;
  }

  private String serialize(PendingEvent event, Instant occurredAt, Map<String, ?> metadata) {
    try {
      return jsonMapper.writeValueAsString(
          new EventEnvelope(
              event.eventId(),
              event.eventType(),
              PendingEvent.SPEC_VERSION,
              occurredAt,
              event.tenantId(),
              event.producer(),
              new EventSubject(
                  event.subject().type(), event.subject().id(), event.subject().number()),
              event.correlationId(),
              event.causationId(),
              event.payload(),
              metadata));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize reliable event envelope", exception);
    }
  }

  private static MapSqlParameterSource parameters(
      PendingEvent event, String envelope, Instant occurredAt) {
    return new MapSqlParameterSource()
        .addValue("eventId", event.eventId())
        .addValue("tenantId", event.tenantId())
        .addValue("eventType", event.eventType())
        .addValue("eventVersion", event.eventVersion())
        .addValue("specVersion", PendingEvent.SPEC_VERSION)
        .addValue("producer", event.producer())
        .addValue("subjectType", event.subject().type())
        .addValue("subjectId", event.subject().id())
        .addValue("subjectNumber", event.subject().number())
        .addValue("payload", envelope)
        .addValue("occurredAt", Timestamp.from(occurredAt))
        .addValue("correlationId", event.correlationId())
        .addValue("causationId", event.causationId());
  }

  private record EventEnvelope(
      UUID id,
      String type,
      String specVersion,
      Instant occurredAt,
      UUID tenantId,
      String producer,
      EventSubject subject,
      UUID correlationId,
      UUID causationId,
      Object payload,
      Map<String, ?> metadata) {}

  private record EventSubject(String type, UUID id, String number) {}
}
