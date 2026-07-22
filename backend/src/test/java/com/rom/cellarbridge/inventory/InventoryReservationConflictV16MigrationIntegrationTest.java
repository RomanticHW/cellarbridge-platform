package com.rom.cellarbridge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class InventoryReservationConflictV16MigrationIntegrationTest
    extends PostgresIntegrationTestSupport {

  private static final Instant NOW = Instant.parse("2026-07-16T14:00:00Z");

  @Test
  void migratesFreshDatabaseToOwnedImmutableRequestConflictEvidence()
      throws IOException, NoSuchAlgorithmException {
    JdbcTemplate jdbc = migrateTo("16");

    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'inventory'
                   AND table_name = 'reservation_request_conflict'
                """,
                Integer.class))
        .isEqualTo(1);
    assertThat(constraints(jdbc))
        .contains(
            "uq_inventory_request_conflict_business_key",
            "uq_inventory_request_conflict_source_event",
            "fk_inventory_request_conflict_reservation_identity",
            "fk_inventory_request_conflict_reservation_order",
            "ck_inventory_request_conflict_distinct_hash",
            "ck_inventory_request_conflict_failure");
    assertThat(indexes(jdbc)).contains("ix_inventory_request_conflict_reservation");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success",
                Integer.class))
        .isEqualTo(15);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_constraint c
                  JOIN pg_namespace source ON source.oid = c.connamespace
                  JOIN pg_class target_table ON target_table.oid = c.confrelid
                  JOIN pg_namespace target ON target.oid = target_table.relnamespace
                 WHERE c.contype = 'f' AND source.nspname = 'inventory'
                   AND target.nspname <> 'inventory'
                """,
                Integer.class))
        .isZero();

    byte[] migration =
        new ClassPathResource(
                "db/migration/V16__inventory_reservation_request_conflict_evidence.sql")
            .getInputStream()
            .readAllBytes();
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration));
    String ownership =
        new String(
            new ClassPathResource("db/migration-ownership.csv").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    assertThat(ownership)
        .contains("16,V16__inventory_reservation_request_conflict_evidence.sql,inventory,," + sha);
  }

  @Test
  void upgradesV15ReservationWithoutRewritingCanonicalEvidence() {
    String database = createDatabase();
    migrate(database, "15");
    JdbcTemplate jdbc = jdbc(database);
    UUID tenantId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    String existingHash = "a".repeat(64);
    insertReservation(jdbc, tenantId, reservationId, orderId, existingHash);
    List<Map<String, Object>> before =
        jdbc.queryForList(
            """
            SELECT id, tenant_id, order_id, request_hash, status, version, created_at, updated_at
              FROM inventory.reservation
             WHERE tenant_id = ? AND order_id = ?
            """,
            tenantId,
            orderId);

    migrate(database, "16");

    assertThat(
            jdbc.queryForList(
                """
                SELECT id, tenant_id, order_id, request_hash, status, version,
                       created_at, updated_at
                  FROM inventory.reservation
                 WHERE tenant_id = ? AND order_id = ?
                """,
                tenantId,
                orderId))
        .isEqualTo(before);
    UUID conflictId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory.reservation_request_conflict
          (id, tenant_id, order_id, reservation_id, existing_request_hash,
           conflicting_request_hash, source_event_id, correlation_id, observed_at, failure_code)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVATION_REQUEST_CONFLICT')
        """,
        conflictId,
        tenantId,
        orderId,
        reservationId,
        existingHash,
        "b".repeat(64),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(NOW));
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM inventory.reservation_request_conflict
                 WHERE tenant_id = ? AND id = ?
                """,
                Integer.class,
                tenantId,
                conflictId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class))
        .isEqualTo("16");
  }

  private JdbcTemplate migrateTo(String target) {
    String database = createDatabase();
    migrate(database, target);
    return jdbc(database);
  }

  private String createDatabase() {
    String database = "reservation_v16_" + UUID.randomUUID().toString().replace("-", "");
    jdbc("postgres").execute("CREATE DATABASE " + database);
    return database;
  }

  private void migrate(String database, String target) {
    Flyway.configure()
        .dataSource(url(database), USERNAME, PASSWORD)
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private JdbcTemplate jdbc(String database) {
    return new JdbcTemplate(new DriverManagerDataSource(url(database), USERNAME, PASSWORD));
  }

  private String url(String database) {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getMappedPort(5432)
        + "/"
        + database;
  }

  private static void insertReservation(
      JdbcTemplate jdbc, UUID tenantId, UUID reservationId, UUID orderId, String requestHash) {
    jdbc.update(
        """
        INSERT INTO inventory.reservation
          (id, tenant_id, order_id, request_hash, supply_decision_hash, route_code,
           status, failure_code, request_schema_version, request_lines, version,
           created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 'SH_GENERAL_TRADE', 'PENDING', NULL, 1,
                CAST(? AS jsonb), 0, ?, ?)
        """,
        reservationId,
        tenantId,
        orderId,
        requestHash,
        "d".repeat(64),
        "[{}]",
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private static List<String> constraints(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT conname FROM pg_constraint WHERE connamespace = 'inventory'::regnamespace",
        String.class);
  }

  private static List<String> indexes(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT indexname FROM pg_indexes WHERE schemaname = 'inventory'", String.class);
  }
}
