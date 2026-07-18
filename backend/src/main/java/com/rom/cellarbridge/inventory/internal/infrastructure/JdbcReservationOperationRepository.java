package com.rom.cellarbridge.inventory.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.internal.application.ReservationOperationRepository;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReservationOperationRepository implements ReservationOperationRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcReservationOperationRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean lockReservation(TenantId tenantId, UUID reservationId) {
    return jdbc
        .query(
            """
            SELECT id
              FROM inventory.reservation
             WHERE tenant_id = :tenantId AND id = :reservationId
             FOR UPDATE
            """,
            ids(tenantId, reservationId),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
        .stream()
        .findFirst()
        .isPresent();
  }

  @Override
  public Command claim(
      TenantId tenantId,
      UUID reservationId,
      Action action,
      String keyHash,
      String requestHash,
      UUID actorId,
      Instant now) {
    UUID id = UUID.randomUUID();
    MapSqlParameterSource parameters =
        ids(tenantId, reservationId)
            .addValue("id", id)
            .addValue("operationType", action.name())
            .addValue("keyHash", keyHash)
            .addValue("requestHash", requestHash)
            .addValue("actorId", actorId)
            .addValue("now", timestamp(now));
    boolean created =
        jdbc.update(
                """
                INSERT INTO inventory.reservation_operation_command
                  (id, tenant_id, reservation_id, operation_type, business_key_hash,
                   request_hash, status, actor_id, created_at)
                VALUES
                  (:id, :tenantId, :reservationId, :operationType, :keyHash,
                   :requestHash, 'PROCESSING', :actorId, :now)
                ON CONFLICT DO NOTHING
                """,
                parameters)
            == 1;
    List<Command> rows =
        jdbc.query(
            """
            SELECT id, tenant_id, reservation_id, operation_type, business_key_hash,
                   request_hash, status, result_code, result_snapshot::text,
                   actor_id, created_at, completed_at
              FROM inventory.reservation_operation_command
             WHERE tenant_id = :tenantId
               AND reservation_id = :reservationId
               AND operation_type = :operationType
               AND business_key_hash = :keyHash
             FOR UPDATE
            """,
            parameters,
            (resultSet, rowNumber) -> mapCommand(resultSet, created));
    if (rows.size() != 1) {
      throw new IllegalStateException("Reservation operation command could not be claimed");
    }
    return rows.getFirst();
  }

  @Override
  public List<UUID> lockAllocations(
      TenantId tenantId, UUID reservationId, List<UUID> allocationIds) {
    if (allocationIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        """
        SELECT id
          FROM inventory.allocation
         WHERE tenant_id = :tenantId
           AND reservation_id = :reservationId
           AND id IN (:allocationIds)
         ORDER BY order_line_id, lot_id, id
         FOR UPDATE
        """,
        ids(tenantId, reservationId).addValue("allocationIds", allocationIds),
        (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
  }

  @Override
  public boolean updateAllocation(
      TenantId tenantId, Allocation before, Allocation after, Action action) {
    if (!before.tenantId().equals(tenantId)
        || !after.tenantId().equals(tenantId)
        || !before.id().equals(after.id())
        || !before.reservationId().equals(after.reservationId())) {
      throw new IllegalArgumentException("Allocation operation identity mismatch");
    }
    return jdbc.update(
            """
            UPDATE inventory.allocation
               SET released_quantity = :releasedAfter,
                   consumed_quantity = :consumedAfter,
                   remaining_reserved_quantity = :remainingAfter
             WHERE tenant_id = :tenantId
               AND reservation_id = :reservationId
               AND id = :allocationId
               AND quantity_unit = :quantityUnit
               AND released_quantity = :releasedBefore
               AND consumed_quantity = :consumedBefore
               AND remaining_reserved_quantity = :remainingBefore
               AND remaining_reserved_quantity >= :delta
               AND (:operationType = 'RELEASE' AND consumed_quantity = 0
                    OR :operationType = 'CONSUME' AND released_quantity = 0)
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("reservationId", before.reservationId())
                .addValue("allocationId", before.id())
                .addValue("quantityUnit", before.quantityUnit().name())
                .addValue("releasedBefore", before.releasedQuantity())
                .addValue("consumedBefore", before.consumedQuantity())
                .addValue("remainingBefore", before.remainingReservedQuantity())
                .addValue("releasedAfter", after.releasedQuantity())
                .addValue("consumedAfter", after.consumedQuantity())
                .addValue("remainingAfter", after.remainingReservedQuantity())
                .addValue(
                    "delta",
                    before.remainingReservedQuantity().subtract(after.remainingReservedQuantity()))
                .addValue("operationType", action.name()))
        == 1;
  }

  @Override
  public void complete(
      TenantId tenantId,
      UUID commandId,
      Status status,
      String resultCode,
      String resultSnapshot,
      Instant completedAt) {
    if (status == Status.PROCESSING) {
      throw new IllegalArgumentException("Completed command requires a terminal status");
    }
    int updated =
        jdbc.update(
            """
            UPDATE inventory.reservation_operation_command
               SET status = :status,
                   result_code = :resultCode,
                   result_schema_version = 1,
                   result_snapshot = CAST(:resultSnapshot AS jsonb),
                   completed_at = :completedAt
             WHERE tenant_id = :tenantId AND id = :commandId AND status = 'PROCESSING'
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("commandId", commandId)
                .addValue("status", status.name())
                .addValue("resultCode", resultCode)
                .addValue("resultSnapshot", resultSnapshot)
                .addValue("completedAt", timestamp(completedAt)));
    if (updated != 1) {
      throw new IllegalStateException("Reservation operation command did not complete once");
    }
  }

  @Override
  public void appendAudit(TenantId tenantId, Audit audit) {
    if (!Objects.requireNonNull(tenantId, "tenantId").equals(audit.tenantId())) {
      throw new IllegalArgumentException("Reservation operation audit tenant mismatch");
    }
    jdbc.update(
        """
        INSERT INTO inventory.reservation_operation_audit
          (id, tenant_id, reservation_id, command_id, operation_type, outcome,
           reason_code, actor_id, business_key_hash, previous_state, new_state, occurred_at)
        VALUES
          (:id, :tenantId, :reservationId, :commandId, :operationType, :outcome,
           :reasonCode, :actorId, :keyHash, :previousState, :newState, :occurredAt)
        """,
        new MapSqlParameterSource()
            .addValue("id", audit.id())
            .addValue("tenantId", audit.tenantId().value())
            .addValue("reservationId", audit.reservationId())
            .addValue("commandId", audit.commandId())
            .addValue("operationType", audit.action().name())
            .addValue("outcome", audit.outcome().name())
            .addValue("reasonCode", audit.reasonCode())
            .addValue("actorId", audit.actorId())
            .addValue("keyHash", audit.keyHash())
            .addValue("previousState", audit.previousState())
            .addValue("newState", audit.newState())
            .addValue("occurredAt", timestamp(audit.occurredAt())));
  }

  @Override
  public List<Audit> findAudits(TenantId tenantId, UUID reservationId) {
    return jdbc.query(
        """
        SELECT id, tenant_id, reservation_id, command_id, operation_type, outcome,
               reason_code, actor_id, business_key_hash, previous_state, new_state, occurred_at
          FROM inventory.reservation_operation_audit
         WHERE tenant_id = :tenantId AND reservation_id = :reservationId
         ORDER BY occurred_at, id
        """,
        ids(tenantId, reservationId),
        (resultSet, rowNumber) -> mapAudit(resultSet));
  }

  private static Command mapCommand(ResultSet resultSet, boolean created) throws SQLException {
    return new Command(
        resultSet.getObject("id", UUID.class),
        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
        resultSet.getObject("reservation_id", UUID.class),
        Action.valueOf(resultSet.getString("operation_type")),
        resultSet.getString("business_key_hash"),
        resultSet.getString("request_hash"),
        Status.valueOf(resultSet.getString("status")),
        resultSet.getString("result_code"),
        resultSet.getString("result_snapshot"),
        resultSet.getObject("actor_id", UUID.class),
        instant(resultSet, "created_at"),
        instant(resultSet, "completed_at"),
        created);
  }

  private static Audit mapAudit(ResultSet resultSet) throws SQLException {
    return new Audit(
        resultSet.getObject("id", UUID.class),
        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
        resultSet.getObject("reservation_id", UUID.class),
        resultSet.getObject("command_id", UUID.class),
        Action.valueOf(resultSet.getString("operation_type")),
        Status.valueOf(resultSet.getString("outcome")),
        resultSet.getString("reason_code"),
        resultSet.getObject("actor_id", UUID.class),
        resultSet.getString("business_key_hash"),
        resultSet.getString("previous_state"),
        resultSet.getString("new_state"),
        instant(resultSet, "occurred_at"));
  }

  private static MapSqlParameterSource ids(TenantId tenantId, UUID reservationId) {
    return new MapSqlParameterSource()
        .addValue("tenantId", Objects.requireNonNull(tenantId, "tenantId").value())
        .addValue("reservationId", Objects.requireNonNull(reservationId, "reservationId"));
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(Objects.requireNonNull(value, "instant"));
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
