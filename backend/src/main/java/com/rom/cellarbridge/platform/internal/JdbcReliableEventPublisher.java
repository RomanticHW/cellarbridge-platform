package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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

  public JdbcReliableEventPublisher(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(PendingEvent event) {
    String envelope = serialize(event);
    MapSqlParameterSource parameters = parameters(event, envelope);
    int inserted =
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
            parameters);
    if (inserted == 0 && !matchesExistingPublication(parameters)) {
      throw new IllegalStateException(
          "Conflicting reliable event publication for event " + event.eventId());
    }
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

  private String serialize(PendingEvent event) {
    try {
      return jsonMapper.writeValueAsString(
          new EventEnvelope(
              event.eventId(),
              event.eventType(),
              PendingEvent.SPEC_VERSION,
              event.occurredAt(),
              event.tenantId(),
              event.producer(),
              new EventSubject(
                  event.subject().type(), event.subject().id(), event.subject().number()),
              event.correlationId(),
              event.causationId(),
              event.payload(),
              event.metadata()));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize reliable event envelope", exception);
    }
  }

  private static MapSqlParameterSource parameters(PendingEvent event, String envelope) {
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
        .addValue("occurredAt", Timestamp.from(event.occurredAt()))
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
