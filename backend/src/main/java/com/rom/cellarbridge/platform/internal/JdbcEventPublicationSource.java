package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEventPublicationSource {

  private final NamedParameterJdbcTemplate jdbc;

  JdbcEventPublicationSource(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<EventDelivery> findReady(LocalEventHandler handler, int batchSize) {
    return jdbc.query(
        """
        SELECT publication.event_id,
               publication.tenant_id,
               publication.event_type,
               publication.event_version,
               publication.occurred_at,
               publication.producer,
               publication.subject_type,
               publication.subject_id,
               publication.subject_number,
               publication.correlation_id,
               publication.causation_id,
               (publication.payload -> 'payload')::text AS business_payload,
               publication.payload -> 'metadata' ->> 'traceparent' AS trace_parent,
               publication.payload -> 'metadata' ->> 'tracestate' AS trace_state
          FROM platform_event.event_publication AS publication
          LEFT JOIN platform_event.event_inbox AS inbox
            ON inbox.tenant_id = publication.tenant_id
           AND inbox.consumer_name = :consumerName
           AND inbox.event_id = publication.event_id
         WHERE publication.event_type = :eventType
           AND (inbox.event_id IS NULL
                OR (inbox.status = 'FAILED_RETRYABLE'
                    AND inbox.next_attempt_at <= CURRENT_TIMESTAMP))
         ORDER BY publication.occurred_at, publication.event_id
         LIMIT :batchSize
        """,
        new MapSqlParameterSource()
            .addValue("consumerName", handler.consumerName())
            .addValue("eventType", handler.eventType())
            .addValue("batchSize", batchSize),
        (resultSet, rowNumber) ->
            new EventDelivery(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("event_version"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getString("producer"),
                new EventDelivery.Subject(
                    resultSet.getString("subject_type"),
                    resultSet.getObject("subject_id", UUID.class),
                    resultSet.getString("subject_number")),
                resultSet.getObject("correlation_id", UUID.class),
                resultSet.getObject("causation_id", UUID.class),
                resultSet.getString("business_payload"),
                resultSet.getString("trace_parent"),
                resultSet.getString("trace_state")));
  }
}
