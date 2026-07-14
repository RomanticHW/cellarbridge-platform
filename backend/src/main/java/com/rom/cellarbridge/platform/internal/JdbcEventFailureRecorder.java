package com.rom.cellarbridge.platform.internal;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.platform.LocalEventHandler;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcEventFailureRecorder {

  private static final String UNEXPECTED_FAILURE = "UNEXPECTED_HANDLER_FAILURE";

  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;
  private final int maxAttempts;
  private final long baseDelaySeconds;
  private final long maxDelaySeconds;

  JdbcEventFailureRecorder(
      NamedParameterJdbcTemplate jdbc,
      Clock clock,
      @Value("${cellarbridge.platform.local-events.max-attempts:5}") int maxAttempts,
      @Value("${cellarbridge.platform.local-events.base-delay-seconds:5}") long baseDelaySeconds,
      @Value("${cellarbridge.platform.local-events.max-delay-seconds:300}") long maxDelaySeconds) {
    if (maxAttempts < 1 || baseDelaySeconds < 1 || maxDelaySeconds < baseDelaySeconds) {
      throw new IllegalArgumentException("Invalid local event retry configuration");
    }
    this.jdbc = jdbc;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
    this.baseDelaySeconds = baseDelaySeconds;
    this.maxDelaySeconds = maxDelaySeconds;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  FailureOutcome record(
      LocalEventHandler handler, EventDelivery delivery, RuntimeException failure) {
    Instant now = clock.instant();
    String payloadHash = EventPayloadHash.sha256(delivery.payloadJson());
    Failure failureClassification = classify(failure);
    boolean firstAttemptRetryable = failureClassification.retryable() && maxAttempts > 1;
    if (insertFailure(
            handler,
            delivery,
            payloadHash,
            failureClassification.code(),
            firstAttemptRetryable,
            now)
        == 1) {
      boolean retryable = firstAttemptRetryable;
      return retryable ? FailureOutcome.RETRY_SCHEDULED : FailureOutcome.FAILED_FINAL;
    }

    List<InboxRow> existing = lockExisting(handler, delivery);
    if (existing.size() != 1) {
      throw new IllegalStateException("Event inbox uniqueness was not preserved");
    }
    InboxRow row = existing.getFirst();
    if (!row.tenantId().equals(delivery.tenantId())
        || !row.eventType().equals(delivery.eventType())
        || !row.payloadHash().equals(payloadHash)) {
      return FailureOutcome.BINDING_CONFLICT_PRESERVED;
    }
    if (row.status().equals("PROCESSED")) {
      return FailureOutcome.ALREADY_PROCESSED;
    }
    if (row.status().equals("FAILED_FINAL")) {
      return FailureOutcome.FAILED_FINAL;
    }

    boolean orphanedProcessing =
        row.status().equals("PROCESSING")
            && failureClassification.code().equals("EVENT_INBOX_ORPHANED_PROCESSING");
    int attempts = orphanedProcessing ? row.attempts() : row.attempts() + 1;
    boolean retryable = failureClassification.retryable() && attempts < maxAttempts;
    updateFailure(handler, delivery, failureClassification.code(), attempts, retryable, now);
    return retryable ? FailureOutcome.RETRY_SCHEDULED : FailureOutcome.FAILED_FINAL;
  }

  private int insertFailure(
      LocalEventHandler handler,
      EventDelivery delivery,
      String payloadHash,
      String failureCode,
      boolean retryable,
      Instant now) {
    return jdbc.update(
        """
        INSERT INTO platform_event.event_inbox
          (tenant_id, consumer_name, event_id, event_type, payload_hash, status,
           attempts, duplicate_count, next_attempt_at, last_error_code,
           first_received_at, last_attempt_at, created_at, updated_at, version)
        VALUES
          (:tenantId, :consumerName, :eventId, :eventType, :payloadHash, :status,
           1, 0, :nextAttemptAt, :failureCode, :now, :now, :now, :now, 0)
        ON CONFLICT DO NOTHING
        """,
        failureParameters(
                handler, delivery, failureCode, retryable, now, retryable ? backoff(now, 1) : null)
            .addValue("payloadHash", payloadHash));
  }

  private void updateFailure(
      LocalEventHandler handler,
      EventDelivery delivery,
      String failureCode,
      int attempts,
      boolean retryable,
      Instant now) {
    int updated =
        jdbc.update(
            """
            UPDATE platform_event.event_inbox
               SET status = :status,
                   attempts = :attempts,
                   next_attempt_at = :nextAttemptAt,
                   result_reference = NULL,
                   result_hash = NULL,
                   last_error_code = :failureCode,
                   processed_at = NULL,
                   last_attempt_at = :now,
                   updated_at = :now,
                   version = version + 1
             WHERE tenant_id = :tenantId
               AND consumer_name = :consumerName
               AND event_id = :eventId
               AND status IN ('PROCESSING', 'FAILED_RETRYABLE')
            """,
            failureParameters(
                    handler,
                    delivery,
                    failureCode,
                    retryable,
                    now,
                    retryable ? backoff(now, attempts) : null)
                .addValue("attempts", attempts));
    if (updated != 1) {
      throw new IllegalStateException("Event inbox failure state changed unexpectedly");
    }
  }

  private List<InboxRow> lockExisting(LocalEventHandler handler, EventDelivery delivery) {
    return jdbc.query(
        """
        SELECT tenant_id, event_type, payload_hash, status, attempts
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
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("payload_hash"),
                resultSet.getString("status"),
                resultSet.getInt("attempts")));
  }

  private MapSqlParameterSource failureParameters(
      LocalEventHandler handler,
      EventDelivery delivery,
      String failureCode,
      boolean retryable,
      Instant now,
      Instant nextAttemptAt) {
    return new MapSqlParameterSource()
        .addValue("tenantId", delivery.tenantId())
        .addValue("consumerName", handler.consumerName())
        .addValue("eventId", delivery.eventId())
        .addValue("eventType", delivery.eventType())
        .addValue("status", retryable ? "FAILED_RETRYABLE" : "FAILED_FINAL")
        .addValue("nextAttemptAt", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
        .addValue("failureCode", failureCode)
        .addValue("now", Timestamp.from(now));
  }

  private Instant backoff(Instant now, int attempts) {
    long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
    long delay =
        baseDelaySeconds > maxDelaySeconds / multiplier
            ? maxDelaySeconds
            : Math.min(maxDelaySeconds, baseDelaySeconds * multiplier);
    return now.plus(delay, ChronoUnit.SECONDS);
  }

  private static Failure classify(RuntimeException failure) {
    if (failure instanceof EventHandlingException classified) {
      return new Failure(classified.failureCode(), classified.retryable());
    }
    return new Failure(UNEXPECTED_FAILURE, true);
  }

  enum FailureOutcome {
    RETRY_SCHEDULED,
    FAILED_FINAL,
    ALREADY_PROCESSED,
    BINDING_CONFLICT_PRESERVED
  }

  private record Failure(String code, boolean retryable) {}

  private record InboxRow(
      UUID tenantId, String eventType, String payloadHash, String status, int attempts) {}
}
