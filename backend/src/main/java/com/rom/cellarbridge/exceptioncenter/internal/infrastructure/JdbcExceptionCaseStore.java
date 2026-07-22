package com.rom.cellarbridge.exceptioncenter.internal.infrastructure;

import com.rom.cellarbridge.exceptioncenter.ExceptionCategory;
import com.rom.cellarbridge.exceptioncenter.ExceptionSeverity;
import com.rom.cellarbridge.exceptioncenter.ExceptionStatus;
import com.rom.cellarbridge.exceptioncenter.RecoveryAction;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExceptionCaseStore implements ExceptionCaseStore {

  private static final DateTimeFormatter NUMBER_PERIOD =
      DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
  private static final String SELECT_CASE =
      """
      SELECT id, tenant_id, number, source_type, source_id, source_number, category,
             dedup_key, severity, status, assignee_id, primary_case_id, due_at, summary,
             safe_details::text AS safe_details, correlation_id, causation_id,
             opened_at, resolved_at, closed_at, updated_at, version
        FROM exception_center.exception_case ec
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcExceptionCaseStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String nextNumber(Instant at) {
    Long value =
        jdbc.getJdbcTemplate()
            .queryForObject("SELECT nextval('exception_center.case_number_seq')", Long.class);
    if (value == null) throw new IllegalStateException("Exception case sequence returned no value");
    return "EXC-" + NUMBER_PERIOD.format(at) + "-" + "%06d".formatted(value);
  }

  @Override
  public OpenResult open(Detection detection, UUID caseId, String number, Instant at) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO exception_center.exception_case
              (id, tenant_id, number, source_type, source_id, source_number, category,
               dedup_key, severity, status, due_at, summary, safe_details,
               correlation_id, causation_id, opened_at, created_at, updated_at, version)
            VALUES
              (:id, :tenantId, :number, :sourceType, :sourceId, :sourceNumber, :category,
               :dedupKey, :severity, 'OPEN', :dueAt, :summary, CAST(:safeDetails AS jsonb),
               :correlationId, :causationId, :openedAt, :at, :at, 0)
            ON CONFLICT DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("id", caseId)
                .addValue("tenantId", detection.tenantId().value())
                .addValue("number", number)
                .addValue("sourceType", detection.sourceType())
                .addValue("sourceId", detection.sourceId())
                .addValue("sourceNumber", detection.sourceNumber())
                .addValue("category", detection.category().name())
                .addValue("dedupKey", detection.dedupKey())
                .addValue("severity", detection.severity().name())
                .addValue("dueAt", timestamp(detection.dueAt()))
                .addValue("summary", detection.summary())
                .addValue("safeDetails", detection.safeDetails())
                .addValue("correlationId", detection.correlationId())
                .addValue("causationId", detection.causationId())
                .addValue("openedAt", Timestamp.from(detection.detectedAt()))
                .addValue("at", Timestamp.from(at)));
    CaseRecord result =
        inserted == 1
            ? find(detection.tenantId(), caseId, false).orElseThrow()
            : activeByDedup(detection.tenantId(), detection.dedupKey()).orElseThrow();
    int occurrence =
        jdbc.update(
            """
            INSERT INTO exception_center.case_occurrence
              (id, tenant_id, case_id, source_event_id, event_type, detected_at, evidence, created_at)
            VALUES
              (:id, :tenantId, :caseId, :sourceEventId, :eventType, :detectedAt,
               CAST(:evidence AS jsonb), :at)
            ON CONFLICT DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("tenantId", detection.tenantId().value())
                .addValue("caseId", result.id())
                .addValue("sourceEventId", detection.sourceEventId())
                .addValue("eventType", detection.eventType())
                .addValue("detectedAt", Timestamp.from(detection.detectedAt()))
                .addValue("evidence", detection.evidence())
                .addValue("at", Timestamp.from(at)));
    if (inserted == 1) {
      appendHistory(
          result,
          "OPEN",
          "SYSTEM",
          null,
          null,
          ExceptionStatus.OPEN,
          "SOURCE_DETECTED",
          "A source failure or delay opened this case.",
          detection.correlationId(),
          at);
      jdbc.update(
          """
          INSERT INTO exception_center.work_item
            (id, tenant_id, case_id, status, due_at, created_at, updated_at, version)
          VALUES (:id, :tenantId, :caseId, 'OPEN', :dueAt, :at, :at, 0)
          """,
          new MapSqlParameterSource()
              .addValue("id", UUID.randomUUID())
              .addValue("tenantId", detection.tenantId().value())
              .addValue("caseId", result.id())
              .addValue("dueAt", timestamp(detection.dueAt()))
              .addValue("at", Timestamp.from(at)));
      appendNotification(
          detection.tenantId(),
          result.id(),
          "case-opened:" + result.id(),
          "TRADE_OPERATOR",
          "A new exception case requires review.",
          at);
    }
    return new OpenResult(result, inserted == 1, occurrence == 1);
  }

  @Override
  public Optional<CaseRecord> find(TenantId tenantId, UUID caseId, boolean forUpdate) {
    List<CaseRecord> rows =
        jdbc.query(
            SELECT_CASE
                + " WHERE tenant_id = :tenantId AND id = :caseId"
                + (forUpdate ? " FOR UPDATE" : ""),
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("caseId", caseId),
            (resultSet, row) -> exceptionCase(resultSet));
    return rows.stream().findFirst();
  }

  private Optional<CaseRecord> activeByDedup(TenantId tenantId, String dedupKey) {
    List<CaseRecord> rows =
        jdbc.query(
            SELECT_CASE
                + " WHERE tenant_id = :tenantId AND dedup_key = :dedupKey AND status <> 'CLOSED'",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("dedupKey", dedupKey),
            (resultSet, row) -> exceptionCase(resultSet));
    return rows.stream().findFirst();
  }

  @Override
  public List<CaseRecord> list(
      TenantId tenantId,
      Set<ExceptionStatus> statuses,
      ExceptionSeverity severity,
      UUID assigneeId,
      String sourceType,
      Boolean overdue,
      boolean technicalOnly,
      int offset,
      int limit) {
    String sql = SELECT_CASE + " WHERE tenant_id = :tenantId";
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("offset", offset)
            .addValue("limit", limit);
    if (statuses != null && !statuses.isEmpty()) {
      sql += " AND status IN (:statuses)";
      parameters.addValue("statuses", statuses.stream().map(Enum::name).toList());
    }
    if (severity != null) {
      sql += " AND severity = :severity";
      parameters.addValue("severity", severity.name());
    }
    if (assigneeId != null) {
      sql += " AND assignee_id = :assigneeId";
      parameters.addValue("assigneeId", assigneeId);
    }
    if (sourceType != null && !sourceType.isBlank()) {
      sql += " AND source_type = :sourceType";
      parameters.addValue("sourceType", sourceType);
    }
    if (Boolean.TRUE.equals(overdue)) sql += " AND due_at < now() AND status <> 'CLOSED'";
    if (Boolean.FALSE.equals(overdue)) sql += " AND (due_at IS NULL OR due_at >= now())";
    if (technicalOnly) sql += " AND category = 'EVENT_DELIVERY_FAILED'";
    sql +=
        " ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, due_at NULLS LAST, updated_at DESC, id LIMIT :limit OFFSET :offset";
    return jdbc.query(sql, parameters, (resultSet, row) -> exceptionCase(resultSet));
  }

  @Override
  public void update(
      CaseRecord before,
      ExceptionStatus status,
      ExceptionSeverity severity,
      UUID assigneeId,
      UUID primaryCaseId,
      Instant resolvedAt,
      Instant closedAt,
      Instant at) {
    int updated =
        jdbc.update(
            """
            UPDATE exception_center.exception_case
               SET status = :status,
                   severity = :severity,
                   assignee_id = :assigneeId,
                   primary_case_id = :primaryCaseId,
                   resolved_at = :resolvedAt,
                   closed_at = :closedAt,
                   updated_at = :at,
                   version = version + 1
             WHERE tenant_id = :tenantId
               AND id = :caseId
               AND version = :version
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", before.tenantId().value())
                .addValue("caseId", before.id())
                .addValue("status", status.name())
                .addValue("severity", severity.name())
                .addValue("assigneeId", assigneeId)
                .addValue("primaryCaseId", primaryCaseId)
                .addValue("resolvedAt", timestamp(resolvedAt))
                .addValue("closedAt", timestamp(closedAt))
                .addValue("at", Timestamp.from(at))
                .addValue("version", before.version()));
    if (updated != 1)
      throw new IllegalStateException("Exception case version changed unexpectedly");
    jdbc.update(
        """
        UPDATE exception_center.work_item
           SET assignee_id = :assigneeId, updated_at = :at, version = version + 1
         WHERE tenant_id = :tenantId AND case_id = :caseId AND status = 'OPEN'
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", before.tenantId().value())
            .addValue("caseId", before.id())
            .addValue("assigneeId", assigneeId)
            .addValue("at", Timestamp.from(at)));
  }

  @Override
  public void appendHistory(
      CaseRecord exceptionCase,
      String action,
      String actorType,
      UUID actorId,
      ExceptionStatus previousStatus,
      ExceptionStatus newStatus,
      String reasonCode,
      String safeReason,
      UUID correlationId,
      Instant at) {
    jdbc.update(
        """
        INSERT INTO exception_center.case_history
          (id, tenant_id, case_id, action, actor_type, actor_id, previous_status,
           new_status, reason_code, safe_reason, correlation_id, occurred_at, created_at)
        VALUES
          (:id, :tenantId, :caseId, :action, :actorType, :actorId, :previousStatus,
           :newStatus, :reasonCode, :safeReason, :correlationId, :at, :at)
        """,
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("tenantId", exceptionCase.tenantId().value())
            .addValue("caseId", exceptionCase.id())
            .addValue("action", action)
            .addValue("actorType", actorType)
            .addValue("actorId", actorId)
            .addValue("previousStatus", previousStatus == null ? null : previousStatus.name())
            .addValue("newStatus", newStatus.name())
            .addValue("reasonCode", reasonCode)
            .addValue("safeReason", safeReason)
            .addValue("correlationId", correlationId)
            .addValue("at", Timestamp.from(at)));
  }

  @Override
  public List<Occurrence> occurrences(TenantId tenantId, UUID caseId) {
    return jdbc.query(
        """
        SELECT id, source_event_id, event_type, detected_at, evidence::text AS evidence
          FROM exception_center.case_occurrence
         WHERE tenant_id = :tenantId AND case_id = :caseId
         ORDER BY detected_at, id
        """,
        ids(tenantId, caseId),
        (rs, row) ->
            new Occurrence(
                rs.getObject("id", UUID.class),
                rs.getObject("source_event_id", UUID.class),
                rs.getString("event_type"),
                instant(rs, "detected_at"),
                rs.getString("evidence")));
  }

  @Override
  public List<History> history(TenantId tenantId, UUID caseId) {
    return jdbc.query(
        """
        SELECT id, action, actor_type, actor_id, previous_status, new_status,
               reason_code, safe_reason, correlation_id, occurred_at
          FROM exception_center.case_history
         WHERE tenant_id = :tenantId AND case_id = :caseId
         ORDER BY occurred_at, id
        """,
        ids(tenantId, caseId),
        (rs, row) ->
            new History(
                rs.getObject("id", UUID.class),
                rs.getString("action"),
                rs.getString("actor_type"),
                rs.getObject("actor_id", UUID.class),
                status(rs.getString("previous_status")),
                ExceptionStatus.valueOf(rs.getString("new_status")),
                rs.getString("reason_code"),
                rs.getString("safe_reason"),
                rs.getObject("correlation_id", UUID.class),
                instant(rs, "occurred_at")));
  }

  @Override
  public RecoveryClaim claimRecovery(
      CaseRecord exceptionCase,
      UUID attemptId,
      RecoveryAction action,
      UUID requesterId,
      String idempotencyKeyHash,
      String requestHash,
      String inputSummary,
      Instant at) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO exception_center.recovery_attempt
              (id, tenant_id, case_id, action, requester_id, idempotency_key_hash,
               request_hash, input_summary, requested_at, created_at)
            VALUES
              (:id, :tenantId, :caseId, :action, :requesterId, :keyHash,
               :requestHash, CAST(:inputSummary AS jsonb), :at, :at)
            ON CONFLICT DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("id", attemptId)
                .addValue("tenantId", exceptionCase.tenantId().value())
                .addValue("caseId", exceptionCase.id())
                .addValue("action", action.name())
                .addValue("requesterId", requesterId)
                .addValue("keyHash", idempotencyKeyHash)
                .addValue("requestHash", requestHash)
                .addValue("inputSummary", inputSummary)
                .addValue("at", Timestamp.from(at)));
    List<RecoveryClaim> rows =
        jdbc.query(
            """
            SELECT id, action, requester_id, request_hash, input_summary::text AS input_summary,
                   requested_at
              FROM exception_center.recovery_attempt
             WHERE tenant_id = :tenantId AND case_id = :caseId
               AND idempotency_key_hash = :keyHash
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", exceptionCase.tenantId().value())
                .addValue("caseId", exceptionCase.id())
                .addValue("keyHash", idempotencyKeyHash),
            (rs, row) ->
                new RecoveryClaim(
                    rs.getObject("id", UUID.class),
                    RecoveryAction.valueOf(rs.getString("action")),
                    rs.getObject("requester_id", UUID.class),
                    rs.getString("request_hash"),
                    rs.getString("input_summary"),
                    instant(rs, "requested_at"),
                    inserted == 1));
    if (rows.size() != 1) throw new IllegalStateException("Recovery idempotency was not preserved");
    return rows.getFirst();
  }

  @Override
  public boolean appendRecoveryOutcome(
      TenantId tenantId,
      UUID attemptId,
      String status,
      String resultCode,
      String safeResult,
      String sourceState,
      Instant at) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO exception_center.recovery_outcome
              (id, tenant_id, attempt_id, status, result_code, safe_result,
               source_state, completed_at, created_at)
            VALUES
              (:id, :tenantId, :attemptId, :status, :resultCode, :safeResult,
               :sourceState, :at, :at)
            ON CONFLICT DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("tenantId", tenantId.value())
                .addValue("attemptId", attemptId)
                .addValue("status", status)
                .addValue("resultCode", resultCode)
                .addValue("safeResult", safeResult)
                .addValue("sourceState", sourceState)
                .addValue("at", Timestamp.from(at)));
    return inserted == 1;
  }

  @Override
  public Optional<RecoveryOutcome> recoveryOutcome(TenantId tenantId, UUID attemptId) {
    List<RecoveryOutcome> rows =
        jdbc.query(
            """
            SELECT status AS outcome_status, result_code, safe_result, source_state, completed_at
              FROM exception_center.recovery_outcome
             WHERE tenant_id = :tenantId AND attempt_id = :attemptId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("attemptId", attemptId),
            (rs, row) -> outcome(rs));
    return rows.stream().findFirst();
  }

  @Override
  public List<RecoveryView> recoveries(TenantId tenantId, UUID caseId) {
    return jdbc.query(
        """
        SELECT attempt.id, attempt.action, attempt.requester_id,
               attempt.input_summary::text AS input_summary, attempt.requested_at,
               outcome.status AS outcome_status, outcome.result_code, outcome.safe_result,
               outcome.source_state, outcome.completed_at
          FROM exception_center.recovery_attempt attempt
          LEFT JOIN exception_center.recovery_outcome outcome
            ON outcome.tenant_id = attempt.tenant_id AND outcome.attempt_id = attempt.id
         WHERE attempt.tenant_id = :tenantId AND attempt.case_id = :caseId
         ORDER BY attempt.requested_at, attempt.id
        """,
        ids(tenantId, caseId),
        (rs, row) ->
            new RecoveryView(
                rs.getObject("id", UUID.class),
                RecoveryAction.valueOf(rs.getString("action")),
                rs.getObject("requester_id", UUID.class),
                rs.getString("input_summary"),
                instant(rs, "requested_at"),
                rs.getString("outcome_status") == null ? null : outcome(rs)));
  }

  @Override
  public int recoveryAttempts(TenantId tenantId, UUID caseId, RecoveryAction action) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM exception_center.recovery_attempt
             WHERE tenant_id = :tenantId AND case_id = :caseId AND action = :action
            """,
            ids(tenantId, caseId).addValue("action", action.name()),
            Integer.class);
    return count == null ? 0 : count;
  }

  @Override
  public void notifyRecoveryThreshold(
      TenantId tenantId, UUID caseId, RecoveryAction action, Instant at) {
    appendNotification(
        tenantId,
        caseId,
        "recovery-threshold:" + caseId + ":" + action.name(),
        "EXCEPTION_OWNER",
        "A bounded recovery threshold was reached and the exception severity was escalated.",
        at);
  }

  @Override
  public void completeWorkItem(TenantId tenantId, UUID caseId, Instant at) {
    jdbc.update(
        """
        UPDATE exception_center.work_item
           SET status = 'COMPLETED', completed_at = :at, updated_at = :at, version = version + 1
         WHERE tenant_id = :tenantId AND case_id = :caseId AND status = 'OPEN'
        """,
        ids(tenantId, caseId).addValue("at", Timestamp.from(at)));
  }

  private void appendNotification(
      TenantId tenantId, UUID caseId, String key, String audience, String message, Instant at) {
    jdbc.update(
        """
        INSERT INTO exception_center.notification_fact
          (id, tenant_id, case_id, notification_key, audience, safe_message, created_at)
        VALUES (:id, :tenantId, :caseId, :key, :audience, :message, :at)
        ON CONFLICT DO NOTHING
        """,
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("tenantId", tenantId.value())
            .addValue("caseId", caseId)
            .addValue("key", key)
            .addValue("audience", audience)
            .addValue("message", message)
            .addValue("at", Timestamp.from(at)));
  }

  private static CaseRecord exceptionCase(ResultSet rs) throws SQLException {
    return new CaseRecord(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getString("number"),
        rs.getString("source_type"),
        rs.getObject("source_id", UUID.class),
        rs.getString("source_number"),
        ExceptionCategory.valueOf(rs.getString("category")),
        rs.getString("dedup_key"),
        ExceptionSeverity.valueOf(rs.getString("severity")),
        ExceptionStatus.valueOf(rs.getString("status")),
        rs.getObject("assignee_id", UUID.class),
        rs.getObject("primary_case_id", UUID.class),
        nullableInstant(rs, "due_at"),
        rs.getString("summary"),
        rs.getString("safe_details"),
        rs.getObject("correlation_id", UUID.class),
        rs.getObject("causation_id", UUID.class),
        instant(rs, "opened_at"),
        nullableInstant(rs, "resolved_at"),
        nullableInstant(rs, "closed_at"),
        instant(rs, "updated_at"),
        rs.getLong("version"));
  }

  private static RecoveryOutcome outcome(ResultSet rs) throws SQLException {
    return new RecoveryOutcome(
        rs.getString("outcome_status") == null
            ? rs.getString("status")
            : rs.getString("outcome_status"),
        rs.getString("result_code"),
        rs.getString("safe_result"),
        rs.getString("source_state"),
        instant(rs, "completed_at"));
  }

  private static MapSqlParameterSource ids(TenantId tenantId, UUID caseId) {
    return new MapSqlParameterSource()
        .addValue("tenantId", tenantId.value())
        .addValue("caseId", caseId);
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static ExceptionStatus status(String value) {
    return value == null ? null : ExceptionStatus.valueOf(value);
  }
}
