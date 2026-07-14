package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEventInbox {

  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;

  JdbcEventInbox(NamedParameterJdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  BeginOutcome begin(LocalEventHandler handler, EventDelivery delivery) {
    Instant now = clock.instant();
    String payloadHash = EventPayloadHash.sha256(delivery.payloadJson());
    MapSqlParameterSource parameters = binding(handler, delivery, payloadHash, now);
    int inserted =
        jdbc.update(
            """
            INSERT INTO platform_event.event_inbox
              (tenant_id, consumer_name, event_id, event_type, payload_hash, status,
               attempts, duplicate_count, first_received_at, last_attempt_at,
               created_at, updated_at, version)
            VALUES
              (:tenantId, :consumerName, :eventId, :eventType, :payloadHash, 'PROCESSING',
               1, 0, :now, :now, :now, :now, 0)
            ON CONFLICT DO NOTHING
            """,
            parameters);
    if (inserted == 1) {
      return BeginOutcome.PROCESS;
    }

    InboxRow existing = lockExisting(handler, delivery);
    requireSameBinding(existing, delivery, payloadHash);
    return switch (existing.status()) {
      case "PROCESSED" -> {
        incrementDuplicate(parameters);
        yield BeginOutcome.ALREADY_PROCESSED;
      }
      case "FAILED_FINAL" -> BeginOutcome.FAILED_FINAL;
      case "FAILED_RETRYABLE" -> retry(existing, parameters, now);
      case "PROCESSING" ->
          throw EventHandlingException.finalFailure("EVENT_INBOX_ORPHANED_PROCESSING");
      default -> throw new IllegalStateException("Unsupported event inbox status");
    };
  }

  void complete(LocalEventHandler handler, EventDelivery delivery, EventHandlingResult result) {
    Instant now = clock.instant();
    int updated =
        jdbc.update(
            """
            UPDATE platform_event.event_inbox
               SET status = 'PROCESSED',
                   result_reference = :resultReference,
                   result_hash = :resultHash,
                   processed_at = :now,
                   updated_at = :now,
                   version = version + 1
             WHERE tenant_id = :tenantId
               AND consumer_name = :consumerName
               AND event_id = :eventId
               AND status = 'PROCESSING'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", delivery.tenantId())
                .addValue("consumerName", handler.consumerName())
                .addValue("eventId", delivery.eventId())
                .addValue("resultReference", result.resultReference())
                .addValue("resultHash", result.resultHash())
                .addValue("now", Timestamp.from(now)));
    if (updated != 1) {
      throw new IllegalStateException("Event inbox completion lost its processing claim");
    }
  }

  private BeginOutcome retry(InboxRow existing, MapSqlParameterSource parameters, Instant now) {
    if (existing.nextAttemptAt() != null && existing.nextAttemptAt().isAfter(now)) {
      return BeginOutcome.NOT_DUE;
    }
    int updated =
        jdbc.update(
            """
            UPDATE platform_event.event_inbox
               SET status = 'PROCESSING',
                   attempts = attempts + 1,
                   next_attempt_at = NULL,
                   last_error_code = NULL,
                   last_attempt_at = :now,
                   updated_at = :now,
                   version = version + 1
             WHERE tenant_id = :tenantId
               AND consumer_name = :consumerName
               AND event_id = :eventId
               AND status = 'FAILED_RETRYABLE'
            """,
            parameters);
    if (updated != 1) {
      throw new IllegalStateException("Event inbox retry state changed unexpectedly");
    }
    return BeginOutcome.PROCESS;
  }

  private void incrementDuplicate(MapSqlParameterSource parameters) {
    jdbc.update(
        """
        UPDATE platform_event.event_inbox
           SET duplicate_count = duplicate_count + 1,
               updated_at = GREATEST(updated_at, created_at, :now),
               version = version + 1
         WHERE tenant_id = :tenantId
           AND consumer_name = :consumerName
           AND event_id = :eventId
           AND status = 'PROCESSED'
        """,
        parameters);
  }

  private InboxRow lockExisting(LocalEventHandler handler, EventDelivery delivery) {
    List<InboxRow> rows =
        jdbc.query(
            """
            SELECT tenant_id, event_type, payload_hash, status, next_attempt_at
              FROM platform_event.event_inbox
             WHERE consumer_name = :consumerName
               AND event_id = :eventId
             FOR UPDATE
            """,
            new MapSqlParameterSource()
                .addValue("consumerName", handler.consumerName())
                .addValue("eventId", delivery.eventId()),
            (resultSet, rowNumber) ->
                new InboxRow(
                    resultSet.getObject("tenant_id", java.util.UUID.class),
                    resultSet.getString("event_type"),
                    resultSet.getString("payload_hash"),
                    resultSet.getString("status"),
                    resultSet.getTimestamp("next_attempt_at") == null
                        ? null
                        : resultSet.getTimestamp("next_attempt_at").toInstant()));
    if (rows.size() != 1) {
      throw new IllegalStateException("Event inbox uniqueness was not preserved");
    }
    return rows.getFirst();
  }

  private static void requireSameBinding(
      InboxRow existing, EventDelivery delivery, String payloadHash) {
    if (!existing.tenantId().equals(delivery.tenantId())
        || !existing.eventType().equals(delivery.eventType())
        || !existing.payloadHash().equals(payloadHash)) {
      throw EventHandlingException.finalFailure("EVENT_INBOX_BINDING_CONFLICT");
    }
  }

  private static MapSqlParameterSource binding(
      LocalEventHandler handler, EventDelivery delivery, String payloadHash, Instant now) {
    return new MapSqlParameterSource()
        .addValue("tenantId", delivery.tenantId())
        .addValue("consumerName", handler.consumerName())
        .addValue("eventId", delivery.eventId())
        .addValue("eventType", delivery.eventType())
        .addValue("payloadHash", payloadHash)
        .addValue("now", Timestamp.from(now));
  }

  enum BeginOutcome {
    PROCESS,
    ALREADY_PROCESSED,
    FAILED_FINAL,
    NOT_DUE
  }

  private record InboxRow(
      java.util.UUID tenantId,
      String eventType,
      String payloadHash,
      String status,
      Instant nextAttemptAt) {}
}
