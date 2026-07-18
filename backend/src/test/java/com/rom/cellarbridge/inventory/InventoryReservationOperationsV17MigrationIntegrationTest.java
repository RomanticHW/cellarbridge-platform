package com.rom.cellarbridge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
class InventoryReservationOperationsV17MigrationIntegrationTest
    extends PostgresIntegrationTestSupport {

  @Test
  void migratesFreshDatabaseToOwnedOperationAndAuditFacts()
      throws IOException, NoSuchAlgorithmException {
    JdbcTemplate jdbc = migrateTo("17", false);

    assertThat(
            jdbc.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'inventory'
                   AND table_name IN ('reservation_operation_command',
                                      'reservation_operation_audit')
                 ORDER BY table_name
                """,
                String.class))
        .containsExactly("reservation_operation_audit", "reservation_operation_command");
    assertThat(
            jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE connamespace = 'inventory'::regnamespace",
                String.class))
        .contains(
            "uq_inventory_operation_command_business_key",
            "ck_inventory_operation_command_result",
            "uq_inventory_operation_audit_command",
            "fk_inventory_operation_audit_reservation");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success",
                Integer.class))
        .isEqualTo(16);
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
        new ClassPathResource("db/migration/V17__inventory_reservation_operations.sql")
            .getInputStream()
            .readAllBytes();
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration));
    String ownership =
        new String(
            new ClassPathResource("db/migration-ownership.csv").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    assertThat(ownership)
        .contains("17,V17__inventory_reservation_operations.sql,inventory,," + sha);
  }

  @Test
  void upgradesV16WithoutRewritingExistingInventoryFacts() {
    String database = createDatabase();
    migrate(database, "16", true);
    JdbcTemplate jdbc = jdbc(database);
    List<Map<String, Object>> before =
        jdbc.queryForList(
            """
            SELECT id, tenant_id, supply_pool_id, sku_id, lot_code, status,
                   on_hand_quantity, reserved_quantity, quantity_unit, version
              FROM inventory.inventory_lot
             ORDER BY tenant_id, id
            """);

    migrate(database, "17", true);

    assertThat(
            jdbc.queryForList(
                """
                SELECT id, tenant_id, supply_pool_id, sku_id, lot_code, status,
                       on_hand_quantity, reserved_quantity, quantity_unit, version
                  FROM inventory.inventory_lot
                 ORDER BY tenant_id, id
                """))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class))
        .isEqualTo("17");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory.reservation_operation_command", Integer.class))
        .isZero();
  }

  private JdbcTemplate migrateTo(String target, boolean demo) {
    String database = createDatabase();
    migrate(database, target, demo);
    return jdbc(database);
  }

  private String createDatabase() {
    String database = "reservation_v17_" + UUID.randomUUID().toString().replace("-", "");
    jdbc("postgres").execute("CREATE DATABASE " + database);
    return database;
  }

  private void migrate(String database, String target, boolean demo) {
    Flyway.configure()
        .dataSource(url(database), USERNAME, PASSWORD)
        .locations(
            demo
                ? new String[] {"classpath:db/migration", "classpath:db/demo"}
                : new String[] {"classpath:db/migration"})
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
}
