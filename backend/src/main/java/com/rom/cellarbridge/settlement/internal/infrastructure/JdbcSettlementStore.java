package com.rom.cellarbridge.settlement.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.settlement.PaymentMethod;
import com.rom.cellarbridge.settlement.ReceivableStatus;
import com.rom.cellarbridge.settlement.internal.application.SettlementStore;
import com.rom.cellarbridge.settlement.internal.domain.Receivable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
public class JdbcSettlementStore implements SettlementStore {
  private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcSettlementStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public TriggerPolicy activePolicy() {
    List<TriggerPolicy> rows =
        jdbc.query(
            """
            SELECT code, version, trigger_type
              FROM settlement.receivable_trigger_policy
             WHERE active = true
            """,
            new MapSqlParameterSource(),
            (rs, row) ->
                new TriggerPolicy(
                    rs.getString("code"), rs.getInt("version"), rs.getString("trigger_type")));
    if (rows.size() != 1) {
      throw new IllegalStateException("Exactly one Settlement trigger policy must be active");
    }
    return rows.getFirst();
  }

  @Override
  public Optional<OrderSnapshot> orderSnapshot(TenantId tenantId, UUID orderId) {
    List<OrderSnapshot> rows =
        jdbc.query(
            """
            SELECT tenant_id, order_id, order_number, partner_id, partner_number, partner_name,
                   partner_version, currency, original_amount, payment_term_days,
                   trigger_policy_code, trigger_policy_version, trigger_type, source_event_id,
                   source_snapshot_hash, accepted_at, captured_at
              FROM settlement.order_snapshot
             WHERE tenant_id = :tenantId AND order_id = :orderId
            """,
            params(tenantId).addValue("orderId", orderId),
            (rs, row) -> orderSnapshot(rs));
    return single(rows, "Settlement order snapshot identity is ambiguous");
  }

  @Override
  public boolean insertOrderSnapshot(OrderSnapshot snapshot) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO settlement.order_snapshot
              (tenant_id, order_id, order_number, partner_id, partner_number, partner_name,
               partner_version, currency, original_amount, payment_term_days,
               trigger_policy_code, trigger_policy_version, trigger_type, source_event_id,
               source_snapshot_hash, accepted_at, captured_at)
            VALUES
              (:tenantId, :orderId, :orderNumber, :partnerId, :partnerNumber, :partnerName,
               :partnerVersion, :currency, :originalAmount, :paymentTermDays,
               :triggerPolicyCode, :triggerPolicyVersion, :triggerType, :sourceEventId,
               :sourceSnapshotHash, :acceptedAt, :capturedAt)
            ON CONFLICT DO NOTHING
            """,
            orderSnapshotParams(snapshot));
    return inserted == 1;
  }

  @Override
  public String nextNumber(TenantId tenantId, Instant at) {
    String period = PERIOD.format(LocalDate.ofInstant(at, ZoneOffset.UTC));
    Long next =
        jdbc.queryForObject(
            """
            INSERT INTO settlement.receivable_number_sequence (tenant_id, period, last_value)
            VALUES (:tenantId, :period, 1)
            ON CONFLICT (tenant_id, period)
            DO UPDATE SET last_value = settlement.receivable_number_sequence.last_value + 1
            RETURNING last_value
            """,
            params(tenantId).addValue("period", period),
            Long.class);
    if (next == null)
      throw new IllegalStateException("Receivable number sequence returned no value");
    return "REC-" + period + "-" + String.format("%06d", next);
  }

  @Override
  public Optional<ReceivableRecord> findByTrigger(
      TenantId tenantId, String triggerType, UUID triggerId, boolean lock) {
    return queryReceivable(
        """
        SELECT * FROM settlement.receivable
         WHERE tenant_id = :tenantId AND trigger_type = :triggerType AND trigger_id = :triggerId
        """
            + (lock ? " FOR UPDATE" : ""),
        params(tenantId).addValue("triggerType", triggerType).addValue("triggerId", triggerId));
  }

  @Override
  public Optional<ReceivableRecord> find(
      TenantId tenantId, UUID receivableId, UUID partnerScope, boolean lock) {
    String sql =
        "SELECT * FROM settlement.receivable WHERE tenant_id = :tenantId AND id = :receivableId";
    MapSqlParameterSource parameters = params(tenantId).addValue("receivableId", receivableId);
    if (partnerScope != null) {
      sql += " AND partner_id = :partnerId";
      parameters.addValue("partnerId", partnerScope);
    }
    if (lock) sql += " FOR UPDATE";
    return queryReceivable(sql, parameters);
  }

  @Override
  public boolean insertReceivable(ReceivableRecord value) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO settlement.receivable
              (id, tenant_id, number, order_id, order_number, partner_id, partner_number,
               partner_name, partner_version, currency, original_amount, paid_net_amount,
               outstanding_amount, due_date, status, trigger_policy_code,
               trigger_policy_version, trigger_type, trigger_id, correlation_id, causation_id,
               created_at, created_by, updated_at, updated_by, version)
            VALUES
              (:id, :tenantId, :number, :orderId, :orderNumber, :partnerId, :partnerNumber,
               :partnerName, :partnerVersion, :currency, :originalAmount, :paidNetAmount,
               :outstandingAmount, :dueDate, :status, :triggerPolicyCode,
               :triggerPolicyVersion, :triggerType, :triggerId, :correlationId, :causationId,
               :createdAt, :createdBy, :updatedAt, :updatedBy, :version)
            ON CONFLICT DO NOTHING
            """,
            receivableParams(value));
    return inserted == 1;
  }

  @Override
  public ReceivablePage list(
      TenantId tenantId,
      UUID partnerScope,
      Set<ReceivableStatus> statuses,
      CursorPosition after,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT * FROM settlement.receivable WHERE tenant_id = :tenantId");
    MapSqlParameterSource parameters = params(tenantId).addValue("limit", limit + 1);
    if (partnerScope != null) {
      sql.append(" AND partner_id = :partnerId");
      parameters.addValue("partnerId", partnerScope);
    }
    if (statuses != null && !statuses.isEmpty()) {
      sql.append(" AND status IN (:statuses)");
      parameters.addValue("statuses", statuses.stream().map(Enum::name).toList());
    }
    if (after != null) {
      sql.append(" AND (created_at < :afterAt OR (created_at = :afterAt AND id < :afterId))");
      parameters.addValue("afterAt", timestamp(after.createdAt())).addValue("afterId", after.id());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
    List<ReceivableRecord> rows =
        jdbc.query(sql.toString(), parameters, (rs, row) -> receivable(rs));
    boolean hasNext = rows.size() > limit;
    List<ReceivableRecord> items =
        hasNext ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
    CursorPosition next =
        hasNext ? new CursorPosition(items.getLast().createdAt(), items.getLast().id()) : null;
    return new ReceivablePage(items, next, hasNext);
  }

  @Override
  public List<PaymentRecord> payments(TenantId tenantId, UUID receivableId) {
    return jdbc.query(
        """
        SELECT id, tenant_id, receivable_id, amount, currency, method, external_reference,
               occurred_on, note, actor_id, idempotency_key_hash, request_hash,
               correlation_id, recorded_at
          FROM settlement.payment_record
         WHERE tenant_id = :tenantId AND receivable_id = :receivableId
         ORDER BY recorded_at, id
        """,
        params(tenantId).addValue("receivableId", receivableId),
        (rs, row) -> payment(rs));
  }

  @Override
  public List<ReversalRecord> reversals(TenantId tenantId, UUID receivableId) {
    return jdbc.query(
        """
        SELECT id, tenant_id, receivable_id, payment_id, amount, currency, reason, actor_id,
               idempotency_key_hash, request_hash, correlation_id, reversed_at
          FROM settlement.payment_reversal
         WHERE tenant_id = :tenantId AND receivable_id = :receivableId
         ORDER BY reversed_at, id
        """,
        params(tenantId).addValue("receivableId", receivableId),
        (rs, row) -> reversal(rs));
  }

  @Override
  public List<HistoryRecord> history(TenantId tenantId, UUID receivableId) {
    return jdbc.query(
        """
        SELECT id, tenant_id, receivable_id, action, previous_status, new_status, amount,
               currency, actor_id, safe_reason, source_event_id, occurred_at
          FROM settlement.receivable_history
         WHERE tenant_id = :tenantId AND receivable_id = :receivableId
         ORDER BY occurred_at, id
        """,
        params(tenantId).addValue("receivableId", receivableId),
        (rs, row) -> history(rs));
  }

  @Override
  public Optional<PaymentRecord> paymentByReference(TenantId tenantId, String externalReference) {
    return queryPayment(
        "SELECT * FROM settlement.payment_record WHERE tenant_id = :tenantId AND external_reference = :externalReference",
        params(tenantId).addValue("externalReference", externalReference));
  }

  @Override
  public Optional<PaymentRecord> paymentByIdempotency(TenantId tenantId, String keyHash) {
    return queryPayment(
        "SELECT * FROM settlement.payment_record WHERE tenant_id = :tenantId AND idempotency_key_hash = :keyHash",
        params(tenantId).addValue("keyHash", keyHash));
  }

  @Override
  public void lockPaymentRequest(TenantId tenantId, String keyHash, String externalReference) {
    advisoryLock(tenantId, "payment-key:" + keyHash);
    advisoryLock(tenantId, "payment-reference:" + externalReference);
  }

  @Override
  public Optional<PaymentRecord> payment(
      TenantId tenantId, UUID receivableId, UUID paymentId, boolean lock) {
    return queryPayment(
        """
        SELECT * FROM settlement.payment_record
         WHERE tenant_id = :tenantId AND receivable_id = :receivableId AND id = :paymentId
        """
            + (lock ? " FOR UPDATE" : ""),
        params(tenantId).addValue("receivableId", receivableId).addValue("paymentId", paymentId));
  }

  @Override
  public void insertPayment(PaymentRecord value) {
    jdbc.update(
        """
        INSERT INTO settlement.payment_record
          (id, tenant_id, receivable_id, amount, currency, method, external_reference,
           occurred_on, note, actor_id, idempotency_key_hash, request_hash,
           correlation_id, recorded_at)
        VALUES
          (:id, :tenantId, :receivableId, :amount, :currency, :method, :externalReference,
           :occurredOn, :note, :actorId, :keyHash, :requestHash, :correlationId, :recordedAt)
        """,
        paymentParams(value));
  }

  @Override
  public Optional<ReversalRecord> reversalByIdempotency(TenantId tenantId, String keyHash) {
    List<ReversalRecord> rows =
        jdbc.query(
            "SELECT * FROM settlement.payment_reversal WHERE tenant_id = :tenantId AND idempotency_key_hash = :keyHash",
            params(tenantId).addValue("keyHash", keyHash),
            (rs, row) -> reversal(rs));
    return single(rows, "Payment reversal identity is ambiguous");
  }

  @Override
  public void lockReversalRequest(TenantId tenantId, String keyHash) {
    advisoryLock(tenantId, "reversal-key:" + keyHash);
  }

  @Override
  public java.math.BigDecimal reversedAmount(TenantId tenantId, UUID paymentId) {
    java.math.BigDecimal value =
        jdbc.queryForObject(
            "SELECT COALESCE(sum(amount), 0) FROM settlement.payment_reversal WHERE tenant_id = :tenantId AND payment_id = :paymentId",
            params(tenantId).addValue("paymentId", paymentId),
            java.math.BigDecimal.class);
    return value == null ? java.math.BigDecimal.ZERO.setScale(4) : value;
  }

  @Override
  public void insertReversal(ReversalRecord value) {
    jdbc.update(
        """
        INSERT INTO settlement.payment_reversal
          (id, tenant_id, receivable_id, payment_id, amount, currency, reason, actor_id,
           idempotency_key_hash, request_hash, correlation_id, reversed_at)
        VALUES
          (:id, :tenantId, :receivableId, :paymentId, :amount, :currency, :reason, :actorId,
           :keyHash, :requestHash, :correlationId, :reversedAt)
        """,
        reversalParams(value));
  }

  @Override
  public void updateReceivable(
      TenantId tenantId, ReceivableRecord before, Receivable after, UUID actorId, Instant at) {
    int updated =
        jdbc.update(
            """
            UPDATE settlement.receivable
               SET paid_net_amount = :paidNetAmount,
                   outstanding_amount = :outstandingAmount,
                   status = :status,
                   updated_at = :updatedAt,
                   updated_by = :updatedBy,
                   version = :nextVersion
             WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
            """,
            params(tenantId)
                .addValue("id", before.id())
                .addValue("paidNetAmount", after.paidNet().amount())
                .addValue("outstandingAmount", after.outstanding().amount())
                .addValue("status", after.status().name())
                .addValue("updatedAt", timestamp(at))
                .addValue("updatedBy", actorId)
                .addValue("nextVersion", after.version())
                .addValue("expectedVersion", before.version()));
    if (updated != 1) throw new IllegalStateException("Receivable version changed unexpectedly");
  }

  @Override
  public void insertHistory(HistoryRecord value) {
    jdbc.update(
        """
        INSERT INTO settlement.receivable_history
          (id, tenant_id, receivable_id, action, previous_status, new_status, amount, currency,
           actor_id, safe_reason, source_event_id, occurred_at)
        VALUES
          (:id, :tenantId, :receivableId, :action, :previousStatus, :newStatus, :amount,
           :currency, :actorId, :safeReason, :sourceEventId, :occurredAt)
        """,
        historyParams(value));
  }

  @Override
  public List<ReceivableRecord> lockOverdueCandidates(LocalDate today, int limit) {
    return jdbc.query(
        """
        SELECT * FROM settlement.receivable
         WHERE status IN ('OPEN', 'PARTIALLY_PAID')
           AND outstanding_amount > 0
           AND due_date < :today
         ORDER BY due_date, id
         FOR UPDATE SKIP LOCKED
         LIMIT :limit
        """,
        new MapSqlParameterSource().addValue("today", today).addValue("limit", limit),
        (rs, row) -> receivable(rs));
  }

  private Optional<ReceivableRecord> queryReceivable(String sql, MapSqlParameterSource parameters) {
    return single(
        jdbc.query(sql, parameters, (rs, row) -> receivable(rs)),
        "Receivable identity is ambiguous");
  }

  private void advisoryLock(TenantId tenantId, String identity) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(:identity, 0))",
        new MapSqlParameterSource().addValue("identity", tenantId.value() + "|" + identity),
        Object.class);
  }

  private Optional<PaymentRecord> queryPayment(String sql, MapSqlParameterSource parameters) {
    return single(
        jdbc.query(sql, parameters, (rs, row) -> payment(rs)), "Payment identity is ambiguous");
  }

  private static ReceivableRecord receivable(ResultSet rs) throws SQLException {
    return new ReceivableRecord(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getString("number"),
        rs.getObject("order_id", UUID.class),
        rs.getString("order_number"),
        rs.getObject("partner_id", UUID.class),
        rs.getString("partner_number"),
        rs.getString("partner_name"),
        rs.getInt("partner_version"),
        rs.getBigDecimal("original_amount"),
        rs.getBigDecimal("paid_net_amount"),
        rs.getBigDecimal("outstanding_amount"),
        rs.getString("currency"),
        rs.getObject("due_date", LocalDate.class),
        ReceivableStatus.valueOf(rs.getString("status")),
        rs.getString("trigger_policy_code"),
        rs.getInt("trigger_policy_version"),
        rs.getString("trigger_type"),
        rs.getObject("trigger_id", UUID.class),
        rs.getObject("correlation_id", UUID.class),
        rs.getObject("causation_id", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("created_by", UUID.class),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getObject("updated_by", UUID.class),
        rs.getLong("version"));
  }

  private static OrderSnapshot orderSnapshot(ResultSet rs) throws SQLException {
    return new OrderSnapshot(
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("order_id", UUID.class),
        rs.getString("order_number"),
        rs.getObject("partner_id", UUID.class),
        rs.getString("partner_number"),
        rs.getString("partner_name"),
        rs.getInt("partner_version"),
        rs.getString("currency"),
        rs.getBigDecimal("original_amount"),
        rs.getInt("payment_term_days"),
        rs.getString("trigger_policy_code"),
        rs.getInt("trigger_policy_version"),
        rs.getString("trigger_type"),
        rs.getObject("source_event_id", UUID.class),
        rs.getString("source_snapshot_hash"),
        rs.getTimestamp("accepted_at").toInstant(),
        rs.getTimestamp("captured_at").toInstant());
  }

  private static PaymentRecord payment(ResultSet rs) throws SQLException {
    return new PaymentRecord(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("receivable_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        PaymentMethod.valueOf(rs.getString("method")),
        rs.getString("external_reference"),
        rs.getObject("occurred_on", LocalDate.class),
        rs.getString("note"),
        rs.getObject("actor_id", UUID.class),
        rs.getString("idempotency_key_hash"),
        rs.getString("request_hash"),
        rs.getObject("correlation_id", UUID.class),
        rs.getTimestamp("recorded_at").toInstant());
  }

  private static ReversalRecord reversal(ResultSet rs) throws SQLException {
    return new ReversalRecord(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("receivable_id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("reason"),
        rs.getObject("actor_id", UUID.class),
        rs.getString("idempotency_key_hash"),
        rs.getString("request_hash"),
        rs.getObject("correlation_id", UUID.class),
        rs.getTimestamp("reversed_at").toInstant());
  }

  private static HistoryRecord history(ResultSet rs) throws SQLException {
    String previous = rs.getString("previous_status");
    return new HistoryRecord(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("receivable_id", UUID.class),
        rs.getString("action"),
        previous == null ? null : ReceivableStatus.valueOf(previous),
        ReceivableStatus.valueOf(rs.getString("new_status")),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getObject("actor_id", UUID.class),
        rs.getString("safe_reason"),
        rs.getObject("source_event_id", UUID.class),
        rs.getTimestamp("occurred_at").toInstant());
  }

  private static MapSqlParameterSource orderSnapshotParams(OrderSnapshot value) {
    return params(value.tenantId())
        .addValue("orderId", value.orderId())
        .addValue("orderNumber", value.orderNumber())
        .addValue("partnerId", value.partnerId())
        .addValue("partnerNumber", value.partnerNumber())
        .addValue("partnerName", value.partnerName())
        .addValue("partnerVersion", value.partnerVersion())
        .addValue("currency", value.currency())
        .addValue("originalAmount", value.originalAmount())
        .addValue("paymentTermDays", value.paymentTermDays())
        .addValue("triggerPolicyCode", value.triggerPolicyCode())
        .addValue("triggerPolicyVersion", value.triggerPolicyVersion())
        .addValue("triggerType", value.triggerType())
        .addValue("sourceEventId", value.sourceEventId())
        .addValue("sourceSnapshotHash", value.sourceSnapshotHash())
        .addValue("acceptedAt", timestamp(value.acceptedAt()))
        .addValue("capturedAt", timestamp(value.capturedAt()));
  }

  private static MapSqlParameterSource receivableParams(ReceivableRecord value) {
    return params(value.tenantId())
        .addValue("id", value.id())
        .addValue("number", value.number())
        .addValue("orderId", value.orderId())
        .addValue("orderNumber", value.orderNumber())
        .addValue("partnerId", value.partnerId())
        .addValue("partnerNumber", value.partnerNumber())
        .addValue("partnerName", value.partnerName())
        .addValue("partnerVersion", value.partnerVersion())
        .addValue("currency", value.currency())
        .addValue("originalAmount", value.originalAmount())
        .addValue("paidNetAmount", value.paidNetAmount())
        .addValue("outstandingAmount", value.outstandingAmount())
        .addValue("dueDate", value.dueDate())
        .addValue("status", value.status().name())
        .addValue("triggerPolicyCode", value.triggerPolicyCode())
        .addValue("triggerPolicyVersion", value.triggerPolicyVersion())
        .addValue("triggerType", value.triggerType())
        .addValue("triggerId", value.triggerId())
        .addValue("correlationId", value.correlationId())
        .addValue("causationId", value.causationId())
        .addValue("createdAt", timestamp(value.createdAt()))
        .addValue("createdBy", value.createdBy())
        .addValue("updatedAt", timestamp(value.updatedAt()))
        .addValue("updatedBy", value.updatedBy())
        .addValue("version", value.version());
  }

  private static MapSqlParameterSource paymentParams(PaymentRecord value) {
    return params(value.tenantId())
        .addValue("id", value.id())
        .addValue("receivableId", value.receivableId())
        .addValue("amount", value.amount())
        .addValue("currency", value.currency())
        .addValue("method", value.method().name())
        .addValue("externalReference", value.externalReference())
        .addValue("occurredOn", value.occurredOn())
        .addValue("note", value.note())
        .addValue("actorId", value.actorId())
        .addValue("keyHash", value.idempotencyKeyHash())
        .addValue("requestHash", value.requestHash())
        .addValue("correlationId", value.correlationId())
        .addValue("recordedAt", timestamp(value.recordedAt()));
  }

  private static MapSqlParameterSource reversalParams(ReversalRecord value) {
    return params(value.tenantId())
        .addValue("id", value.id())
        .addValue("receivableId", value.receivableId())
        .addValue("paymentId", value.paymentId())
        .addValue("amount", value.amount())
        .addValue("currency", value.currency())
        .addValue("reason", value.reason())
        .addValue("actorId", value.actorId())
        .addValue("keyHash", value.idempotencyKeyHash())
        .addValue("requestHash", value.requestHash())
        .addValue("correlationId", value.correlationId())
        .addValue("reversedAt", timestamp(value.reversedAt()));
  }

  private static MapSqlParameterSource historyParams(HistoryRecord value) {
    return params(value.tenantId())
        .addValue("id", value.id())
        .addValue("receivableId", value.receivableId())
        .addValue("action", value.action())
        .addValue(
            "previousStatus", value.previousStatus() == null ? null : value.previousStatus().name())
        .addValue("newStatus", value.newStatus().name())
        .addValue("amount", value.amount())
        .addValue("currency", value.currency())
        .addValue("actorId", value.actorId())
        .addValue("safeReason", value.safeReason())
        .addValue("sourceEventId", value.sourceEventId())
        .addValue("occurredAt", timestamp(value.occurredAt()));
  }

  private static MapSqlParameterSource params(TenantId tenantId) {
    return new MapSqlParameterSource("tenantId", tenantId.value());
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(value);
  }

  private static <T> Optional<T> single(List<T> rows, String message) {
    if (rows.size() > 1) throw new IllegalStateException(message);
    return rows.stream().findFirst();
  }
}
