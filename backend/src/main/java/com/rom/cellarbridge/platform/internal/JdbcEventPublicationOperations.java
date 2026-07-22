package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.EventPublicationOperations;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcEventPublicationOperations implements EventPublicationOperations {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcEventPublicationOperations(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public List<FailedDelivery> listFailed(UUID tenantId, int offset, int limit) {
    return failed(
        """
        SELECT publication.event_id,
               publication.tenant_id,
               publication.event_type,
               publication.producer,
               publication.subject_type,
               publication.subject_id,
               publication.subject_number,
               publication.correlation_id,
               inbox.consumer_name,
               inbox.status,
               inbox.attempts,
               inbox.next_attempt_at,
               inbox.last_error_code,
               inbox.last_attempt_at,
               inbox.version
          FROM platform_event.event_inbox inbox
          JOIN platform_event.event_publication publication
            ON publication.tenant_id = inbox.tenant_id
           AND publication.event_id = inbox.event_id
         WHERE inbox.tenant_id = :tenantId
           AND inbox.status IN ('FAILED_RETRYABLE', 'FAILED_FINAL')
         ORDER BY inbox.last_attempt_at DESC, inbox.event_id, inbox.consumer_name
         LIMIT :limit OFFSET :offset
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("offset", offset)
            .addValue("limit", limit));
  }

  @Override
  @Transactional(readOnly = true)
  public List<FailedDelivery> listFinal(int offset, int limit) {
    return failed(
        """
        SELECT publication.event_id,
               publication.tenant_id,
               publication.event_type,
               publication.producer,
               publication.subject_type,
               publication.subject_id,
               publication.subject_number,
               publication.correlation_id,
               inbox.consumer_name,
               inbox.status,
               inbox.attempts,
               inbox.next_attempt_at,
               inbox.last_error_code,
               inbox.last_attempt_at,
               inbox.version
          FROM platform_event.event_inbox inbox
          JOIN platform_event.event_publication publication
            ON publication.tenant_id = inbox.tenant_id
           AND publication.event_id = inbox.event_id
         WHERE inbox.status = 'FAILED_FINAL'
         ORDER BY inbox.last_attempt_at, inbox.event_id, inbox.consumer_name
         LIMIT :limit OFFSET :offset
        """,
        new MapSqlParameterSource().addValue("offset", offset).addValue("limit", limit));
  }

  @Override
  @Transactional
  public ReplayResult replay(
      UUID tenantId, UUID eventId, String consumerName, long expectedVersion, Instant requestedAt) {
    DeliveryState state = lock(tenantId, eventId, consumerName);
    if (state.status().equals("FAILED_RETRYABLE")) {
      return new ReplayResult(eventId, consumerName, state.status(), state.version(), true);
    }
    if (state.version() != expectedVersion) {
      throw new EventPublicationOperationException(
          "RESOURCE_VERSION_CONFLICT", "The failed delivery changed; refresh before replay");
    }
    if (!state.status().equals("FAILED_FINAL")) {
      throw new EventPublicationOperationException(
          "EVENT_REPLAY_NOT_ALLOWED", "Only a final failed delivery can be replayed");
    }
    int updated =
        jdbc.update(
            """
            UPDATE platform_event.event_inbox
               SET status = 'FAILED_RETRYABLE',
                   next_attempt_at = :requestedAt,
                   updated_at = :requestedAt,
                   version = version + 1
             WHERE tenant_id = :tenantId
               AND event_id = :eventId
               AND consumer_name = :consumerName
               AND status = 'FAILED_FINAL'
               AND version = :expectedVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("eventId", eventId)
                .addValue("consumerName", consumerName)
                .addValue("requestedAt", Timestamp.from(requestedAt))
                .addValue("expectedVersion", expectedVersion));
    if (updated != 1) {
      throw new EventPublicationOperationException(
          "RESOURCE_VERSION_CONFLICT", "The failed delivery changed; refresh before replay");
    }
    return new ReplayResult(eventId, consumerName, "FAILED_RETRYABLE", expectedVersion + 1, false);
  }

  private DeliveryState lock(UUID tenantId, UUID eventId, String consumerName) {
    List<DeliveryState> rows =
        jdbc.query(
            """
            SELECT status, version
              FROM platform_event.event_inbox
             WHERE tenant_id = :tenantId
               AND event_id = :eventId
               AND consumer_name = :consumerName
             FOR UPDATE
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("eventId", eventId)
                .addValue("consumerName", consumerName),
            (resultSet, row) ->
                new DeliveryState(resultSet.getString("status"), resultSet.getLong("version")));
    if (rows.isEmpty()) {
      throw new EventPublicationOperationException(
          "RESOURCE_NOT_FOUND", "The failed delivery does not exist in this tenant");
    }
    if (rows.size() != 1)
      throw new IllegalStateException("Event inbox uniqueness was not preserved");
    return rows.getFirst();
  }

  private static FailedDelivery failed(ResultSet resultSet) throws SQLException {
    Timestamp nextRetryAt = resultSet.getTimestamp("next_attempt_at");
    return new FailedDelivery(
        resultSet.getObject("tenant_id", UUID.class),
        resultSet.getObject("event_id", UUID.class),
        resultSet.getString("event_type"),
        resultSet.getString("producer"),
        resultSet.getString("subject_type"),
        resultSet.getObject("subject_id", UUID.class),
        resultSet.getString("subject_number"),
        resultSet.getObject("correlation_id", UUID.class),
        resultSet.getString("consumer_name"),
        resultSet.getString("status"),
        resultSet.getInt("attempts"),
        nextRetryAt == null ? null : nextRetryAt.toInstant(),
        resultSet.getString("last_error_code"),
        resultSet.getTimestamp("last_attempt_at").toInstant(),
        resultSet.getLong("version"));
  }

  private List<FailedDelivery> failed(String sql, MapSqlParameterSource parameters) {
    return jdbc.query(sql, parameters, (resultSet, row) -> failed(resultSet));
  }

  private record DeliveryState(String status, long version) {}

  public static final class EventPublicationOperationException extends RuntimeException {
    private final String code;

    EventPublicationOperationException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
