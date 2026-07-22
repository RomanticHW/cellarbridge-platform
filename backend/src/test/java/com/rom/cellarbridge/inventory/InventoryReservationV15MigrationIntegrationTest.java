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
class InventoryReservationV15MigrationIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void migratesFreshDatabaseToOwnedConstrainedInventoryFoundation()
      throws IOException, NoSuchAlgorithmException {
    JdbcTemplate jdbc = migrateTo("15", false);

    assertThat(
            jdbc.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'inventory'
                   AND table_name IN ('reservation', 'reservation_attempt', 'allocation',
                                      'inventory_movement', 'shortage_snapshot')
                 ORDER BY table_name
                """,
                String.class))
        .containsExactly(
            "allocation",
            "inventory_movement",
            "reservation",
            "reservation_attempt",
            "shortage_snapshot");
    assertThat(numericDefinition(jdbc, "allocation", "allocated_quantity"))
        .containsEntry("numeric_precision", 19)
        .containsEntry("numeric_scale", 6);
    assertThat(numericDefinition(jdbc, "shortage_snapshot", "shortage_quantity"))
        .containsEntry("numeric_precision", 19)
        .containsEntry("numeric_scale", 6);
    assertThat(constraints(jdbc))
        .contains(
            "uq_inventory_reservation_tenant_order",
            "uq_inventory_attempt_number",
            "ck_inventory_allocation_conservation",
            "uq_inventory_movement_business_key",
            "ck_inventory_shortage_quantity");
    assertThat(indexes(jdbc))
        .contains(
            "ix_inventory_reservation_tenant_status",
            "ix_inventory_attempt_reservation",
            "ix_inventory_allocation_reservation",
            "ix_inventory_movement_reservation",
            "ix_inventory_shortage_reservation");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success",
                Integer.class))
        .isEqualTo(14);
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
        new ClassPathResource("db/migration/V15__inventory_reservation_foundation.sql")
            .getInputStream()
            .readAllBytes();
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration));
    String ownership =
        new String(
            new ClassPathResource("db/migration-ownership.csv").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    assertThat(ownership)
        .contains("15,V15__inventory_reservation_foundation.sql,inventory,," + sha);
  }

  @Test
  void upgradesV14DataWithoutRewritingExistingInventory() {
    String database = createDatabase();
    migrate(database, "14", true);
    JdbcTemplate jdbc = jdbc(database);
    List<Map<String, Object>> before =
        jdbc.queryForList(
            """
            SELECT id, tenant_id, supply_pool_id, sku_id, lot_code, status,
                   on_hand_quantity, reserved_quantity, quantity_unit, version
              FROM inventory.inventory_lot
             ORDER BY tenant_id, id
            """);

    migrate(database, "15", true);

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
        .isEqualTo("15");
  }

  private JdbcTemplate migrateTo(String target, boolean demo) {
    String database = createDatabase();
    migrate(database, target, demo);
    return jdbc(database);
  }

  private String createDatabase() {
    String database = "reservation_v15_" + UUID.randomUUID().toString().replace("-", "");
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

  private static Map<String, Object> numericDefinition(
      JdbcTemplate jdbc, String table, String column) {
    return jdbc.queryForMap(
        """
        SELECT numeric_precision, numeric_scale
          FROM information_schema.columns
         WHERE table_schema = 'inventory' AND table_name = ? AND column_name = ?
        """,
        table,
        column);
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
