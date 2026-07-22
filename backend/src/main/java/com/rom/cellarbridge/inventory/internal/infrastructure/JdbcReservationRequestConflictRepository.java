package com.rom.cellarbridge.inventory.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.internal.application.ReservationPersistenceException;
import com.rom.cellarbridge.inventory.internal.application.ReservationPersistenceException.Code;
import com.rom.cellarbridge.inventory.internal.application.ReservationRequestConflictRepository;
import com.rom.cellarbridge.inventory.internal.domain.ReservationRequestConflict;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReservationRequestConflictRepository
    implements ReservationRequestConflictRepository {

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final String SELECT =
      """
      SELECT id, tenant_id, order_id, reservation_id, existing_request_hash,
             conflicting_request_hash, source_event_id, correlation_id, observed_at,
             failure_code
        FROM inventory.reservation_request_conflict
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcReservationRequestConflictRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public RecordResult record(TenantId tenantId, ReservationRequestConflict conflict) {
    Objects.requireNonNull(conflict, "conflict");
    requireTenant(tenantId, conflict.tenantId());
    int inserted =
        jdbc.update(
            """
            INSERT INTO inventory.reservation_request_conflict
              (id, tenant_id, order_id, reservation_id, existing_request_hash,
               conflicting_request_hash, source_event_id, correlation_id, observed_at,
               failure_code)
            VALUES
              (:id, :tenantId, :orderId, :reservationId, :existingRequestHash,
               :conflictingRequestHash, :sourceEventId, :correlationId, :observedAt,
               :failureCode)
            ON CONFLICT (tenant_id, order_id, conflicting_request_hash) DO NOTHING
            """,
            parameters(conflict));
    ReservationRequestConflict persisted =
        findByOrderAndConflictingHash(
                tenantId, conflict.orderId(), conflict.conflictingRequestHash())
            .orElseThrow(
                () ->
                    integrity("Request conflict insert did not produce a readable immutable fact"));
    if (!sameBusinessIdentity(persisted, conflict)) {
      throw integrity("Request conflict business key resolved inconsistent canonical evidence");
    }
    return new RecordResult(persisted, inserted == 0);
  }

  @Override
  public Optional<ReservationRequestConflict> findByOrderAndConflictingHash(
      TenantId tenantId, UUID orderId, String conflictingRequestHash) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(orderId, "orderId");
    requireHash(conflictingRequestHash);
    return jdbc
        .query(
            SELECT
                + " WHERE tenant_id = :tenantId AND order_id = :orderId"
                + " AND conflicting_request_hash = :conflictingRequestHash",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("orderId", orderId)
                .addValue("conflictingRequestHash", conflictingRequestHash),
            (resultSet, rowNumber) ->
                new ReservationRequestConflict(
                    resultSet.getObject("id", UUID.class),
                    TenantId.of(resultSet.getObject("tenant_id", UUID.class)),
                    resultSet.getObject("order_id", UUID.class),
                    resultSet.getObject("reservation_id", UUID.class),
                    resultSet.getString("existing_request_hash"),
                    resultSet.getString("conflicting_request_hash"),
                    resultSet.getObject("source_event_id", UUID.class),
                    resultSet.getObject("correlation_id", UUID.class),
                    resultSet.getTimestamp("observed_at").toInstant(),
                    resultSet.getString("failure_code")))
        .stream()
        .findFirst();
  }

  private static MapSqlParameterSource parameters(ReservationRequestConflict conflict) {
    return new MapSqlParameterSource()
        .addValue("id", conflict.id())
        .addValue("tenantId", conflict.tenantId().value())
        .addValue("orderId", conflict.orderId())
        .addValue("reservationId", conflict.reservationId())
        .addValue("existingRequestHash", conflict.existingRequestHash())
        .addValue("conflictingRequestHash", conflict.conflictingRequestHash())
        .addValue("sourceEventId", conflict.sourceEventId())
        .addValue("correlationId", conflict.correlationId())
        .addValue("observedAt", Timestamp.from(conflict.observedAt()))
        .addValue("failureCode", conflict.failureCode());
  }

  private static boolean sameBusinessIdentity(
      ReservationRequestConflict persisted, ReservationRequestConflict requested) {
    return persisted.tenantId().equals(requested.tenantId())
        && persisted.orderId().equals(requested.orderId())
        && persisted.reservationId().equals(requested.reservationId())
        && persisted.existingRequestHash().equals(requested.existingRequestHash())
        && persisted.conflictingRequestHash().equals(requested.conflictingRequestHash())
        && persisted.failureCode().equals(requested.failureCode());
  }

  private static void requireTenant(TenantId expected, TenantId actual) {
    Objects.requireNonNull(expected, "tenantId");
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("Tenant does not own Reservation request conflict");
    }
  }

  private static void requireHash(String hash) {
    if (hash == null || !HASH.matcher(hash).matches()) {
      throw new IllegalArgumentException("conflictingRequestHash must be lowercase SHA-256 hex");
    }
  }

  private static ReservationPersistenceException integrity(String message) {
    return new ReservationPersistenceException(Code.PERSISTENCE_INTEGRITY_VIOLATION, message);
  }
}
