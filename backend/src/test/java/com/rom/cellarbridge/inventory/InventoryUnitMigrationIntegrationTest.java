package com.rom.cellarbridge.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class InventoryUnitMigrationIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void migratesFreshDatabaseWithInventoryAndCatalogUnitConstraintsAndIndexes() {
    JdbcTemplate jdbc = migrateTo("11");

    assertThat(columnDefinition(jdbc, "inventory", "inventory_lot", "quantity_unit"))
        .containsExactly("character varying", "NO", null);
    assertThat(columnDefinition(jdbc, "inventory", "warehouse", "allocation_priority"))
        .containsExactly("integer", "NO", "100");
    assertThat(columnDefinition(jdbc, "catalog", "sku_supply_projection", "quantity_unit"))
        .containsExactly("character varying", "NO", null);
    assertThat(constraintNames(jdbc, "inventory"))
        .contains("ck_inventory_lot_quantity_unit", "ck_inventory_warehouse_allocation_priority");
    assertThat(constraintNames(jdbc, "catalog"))
        .contains("ck_catalog_supply_projection_quantity_unit");
    assertThat(indexColumns(jdbc, "ix_inventory_lot_tenant_sku_unit_available"))
        .containsExactly(
            "tenant_id",
            "sku_id",
            "quantity_unit",
            "status",
            "available_from",
            "supply_pool_id",
            "id");
    assertThat(primaryKeyColumns(jdbc, "catalog", "sku_supply_projection"))
        .containsExactly("tenant_id", "sku_id", "supply_pool_id", "quantity_unit");
    assertThat(indexColumns(jdbc, "catalog", "ix_catalog_supply_projection_unit_filter"))
        .containsExactly(
            "tenant_id",
            "quantity_unit",
            "supply_type",
            "availability_class",
            "automatically_reservable",
            "sku_id");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success",
                Integer.class))
        .isEqualTo(10);
  }

  @Test
  void backfillsV9RowsWithoutLeavingAUnitOrPriorityEscapeHatch() {
    String database = createDatabase();
    migrate(database, "9");
    JdbcTemplate jdbc = jdbc(database);
    insertLegacyInventory(jdbc);
    insertLegacyCatalog(jdbc);
    List<Map<String, Object>> warehouses =
        jdbc.queryForList("SELECT id, code, name, status FROM inventory.warehouse ORDER BY id");
    List<Map<String, Object>> lots =
        jdbc.queryForList(
            "SELECT id, lot_code, status, on_hand_quantity, reserved_quantity FROM inventory.inventory_lot ORDER BY id");
    List<Map<String, Object>> projections =
        jdbc.queryForList(
            "SELECT tenant_id, sku_id, supply_pool_id, supply_type, location_label, projection_version FROM catalog.sku_supply_projection ORDER BY tenant_id, sku_id, supply_pool_id");

    migrate(database, "11");

    assertThat(
            jdbc.queryForList("SELECT id, code, name, status FROM inventory.warehouse ORDER BY id"))
        .isEqualTo(warehouses);
    assertThat(
            jdbc.queryForList(
                "SELECT id, lot_code, status, on_hand_quantity, reserved_quantity FROM inventory.inventory_lot ORDER BY id"))
        .isEqualTo(lots);
    assertThat(
            jdbc.queryForList(
                "SELECT tenant_id, sku_id, supply_pool_id, supply_type, location_label, projection_version FROM catalog.sku_supply_projection ORDER BY tenant_id, sku_id, supply_pool_id"))
        .isEqualTo(projections);

    assertThat(
            jdbc.queryForObject("SELECT quantity_unit FROM inventory.inventory_lot", String.class))
        .isEqualTo("CASE");
    assertThat(
            jdbc.queryForObject(
                "SELECT allocation_priority FROM inventory.warehouse", Integer.class))
        .isEqualTo(100);
    assertThat(
            jdbc.queryForObject(
                "SELECT quantity_unit FROM catalog.sku_supply_projection", String.class))
        .isEqualTo("CASE");
    assertThatThrownBy(
            () -> jdbc.update("UPDATE inventory.inventory_lot SET quantity_unit = 'PALLET'"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE inventory.warehouse SET allocation_priority = -1"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE catalog.sku_supply_projection SET quantity_unit = 'PALLET'"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void repeatableDemoSeedVersionsOnlyRealWarehousePriorityCorrections() {
    String freshDatabase = createDatabase();
    migrateWithDemo(freshDatabase, "11");
    JdbcTemplate fresh = jdbc(freshDatabase);
    WarehouseState freshSeed = warehouseState(fresh);
    assertThat(freshSeed.version()).isZero();
    assertThat(
            fresh.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IS NULL AND success",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            fresh.queryForObject(
                "SELECT count(*) FROM inventory.inventory_lot WHERE quantity_unit IS NULL",
                Integer.class))
        .isZero();
    assertThat(
            fresh.queryForObject(
                "SELECT count(*) FROM catalog.sku_supply_projection WHERE quantity_unit IS NULL",
                Integer.class))
        .isZero();
    assertThat(
            fresh.queryForObject(
                "SELECT count(*) FROM inventory.warehouse WHERE allocation_priority < 0",
                Integer.class))
        .isZero();
    assertThat(dualUnitGroups(fresh, "catalog.sku_supply_projection")).isPositive();
    assertThat(dualUnitGroups(fresh, "inventory.inventory_lot")).isPositive();
    assertThatThrownBy(
            () -> fresh.update("UPDATE inventory.inventory_lot SET quantity_unit = NULL"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> fresh.update("UPDATE catalog.sku_supply_projection SET quantity_unit = NULL"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                fresh.update(
                    "UPDATE inventory.inventory_lot SET reserved_quantity = on_hand_quantity + 1"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                fresh.update(
                    "UPDATE catalog.sku_supply_projection SET quantity_unit = 'BOTTLE' WHERE sku_id = '34000000-0000-4000-8000-000000000001' AND supply_pool_id = '36000000-0000-4000-8000-000000000001' AND quantity_unit = 'CASE'"))
        .isInstanceOf(DataIntegrityViolationException.class);
    rerunCatalogSeed(fresh);
    assertThat(warehouseState(fresh)).isEqualTo(freshSeed);

    String legacyDatabase = createDatabase();
    migrate(legacyDatabase, "9");
    JdbcTemplate legacy = jdbc(legacyDatabase);
    legacy.update(
        """
        INSERT INTO inventory.warehouse
          (id, tenant_id, code, name, country_code, city, status,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('35000000-0000-4000-8000-000000000001',
           '10000000-0000-4000-8000-000000000001', 'WH-SH-01', 'Legacy Warehouse',
           'CN', 'Shanghai', 'ACTIVE', '2020-01-01T00:00:00Z',
           '11200000-0000-4000-8000-000000000099', '2020-01-01T00:00:00Z',
           '11200000-0000-4000-8000-000000000099', 0)
        """);
    migrate(legacyDatabase, "11");
    WarehouseState beforeCorrection = warehouseState(legacy);
    assertThat(beforeCorrection.priority()).isEqualTo(100);

    rerunCatalogSeed(legacy);
    WarehouseState corrected = warehouseState(legacy);
    assertThat(corrected.priority()).isEqualTo(10);
    assertThat(corrected.version()).isEqualTo(1);
    assertThat(corrected.updatedAt()).isAfter(beforeCorrection.updatedAt());
    assertThat(corrected.updatedBy())
        .isEqualTo(UUID.fromString("11200000-0000-4000-8000-000000000004"));
    rerunCatalogSeed(legacy);
    assertThat(warehouseState(legacy)).isEqualTo(corrected);
  }

  private JdbcTemplate migrateTo(String target) {
    String database = createDatabase();
    migrate(database, target);
    return jdbc(database);
  }

  private String createDatabase() {
    String database = "inventory_unit_" + UUID.randomUUID().toString().replace("-", "");
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

  private void migrateWithDemo(String database, String target) {
    Flyway.configure()
        .dataSource(url(database), USERNAME, PASSWORD)
        .locations("classpath:db/migration", "classpath:db/demo")
        .target(target)
        .load()
        .migrate();
  }

  private static void rerunCatalogSeed(JdbcTemplate jdbc) {
    new ResourceDatabasePopulator(
            new ClassPathResource("db/demo/R__catalog_inventory_demo_seed.sql"))
        .execute(jdbc.getDataSource());
  }

  private static WarehouseState warehouseState(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        """
        SELECT allocation_priority, version, updated_at, updated_by
          FROM inventory.warehouse
         WHERE id = '35000000-0000-4000-8000-000000000001'
        """,
        (resultSet, rowNumber) ->
            new WarehouseState(
                resultSet.getInt("allocation_priority"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("updated_at").toInstant(),
                resultSet.getObject("updated_by", UUID.class)));
  }

  private static int dualUnitGroups(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM (SELECT tenant_id, sku_id, supply_pool_id FROM "
            + table
            + " GROUP BY 1, 2, 3 HAVING count(DISTINCT quantity_unit) = 2) groups",
        Integer.class);
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

  private static List<Object> columnDefinition(
      JdbcTemplate jdbc, String schema, String table, String column) {
    return jdbc
        .queryForList(
            """
            SELECT data_type, is_nullable, column_default
              FROM information_schema.columns
             WHERE table_schema = ? AND table_name = ? AND column_name = ?
            """,
            schema,
            table,
            column)
        .getFirst()
        .values()
        .stream()
        .toList();
  }

  private static List<String> constraintNames(JdbcTemplate jdbc, String schema) {
    return jdbc.queryForList(
        """
        SELECT conname
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = ?
        """,
        String.class,
        schema);
  }

  private static List<String> indexColumns(JdbcTemplate jdbc, String indexName) {
    return indexColumns(jdbc, "inventory", indexName);
  }

  private static List<String> indexColumns(JdbcTemplate jdbc, String schema, String indexName) {
    return jdbc.queryForList(
        """
        SELECT a.attname
          FROM pg_class i
          JOIN pg_namespace n ON n.oid = i.relnamespace
          JOIN pg_index ix ON ix.indexrelid = i.oid
          JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, position) ON true
          JOIN pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum
         WHERE n.nspname = ? AND i.relname = ?
         ORDER BY k.position
        """,
        String.class,
        schema,
        indexName);
  }

  private static List<String> primaryKeyColumns(JdbcTemplate jdbc, String schema, String table) {
    return jdbc.queryForList(
        """
        SELECT a.attname
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
          JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, position) ON true
          JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
         WHERE c.contype = 'p' AND n.nspname = ? AND t.relname = ?
         ORDER BY k.position
        """,
        String.class,
        schema,
        table);
  }

  private static void insertLegacyInventory(JdbcTemplate jdbc) {
    jdbc.update(
        """
        INSERT INTO inventory.warehouse
          (id, tenant_id, code, name, country_code, city, status,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('35000000-0000-4000-8000-000000000099',
           '10000000-0000-4000-8000-000000000099', 'WH-LEGACY', 'Legacy Warehouse',
           'CN', 'Shanghai', 'ACTIVE', now(),
           '11200000-0000-4000-8000-000000000099', now(),
           '11200000-0000-4000-8000-000000000099', 0)
        """);
    jdbc.update(
        """
        INSERT INTO inventory.supply_pool
          (id, tenant_id, warehouse_id, code, supply_type, route_code, currency,
           confidence, policy_version, status, created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('36000000-0000-4000-8000-000000000099',
           '10000000-0000-4000-8000-000000000099',
           '35000000-0000-4000-8000-000000000099', 'POOL-LEGACY', 'DOMESTIC_ON_HAND',
           'SH_GENERAL_TRADE', 'CNY', 'HIGH', 'LEGACY', 'ACTIVE', now(),
           '11200000-0000-4000-8000-000000000099', now(),
           '11200000-0000-4000-8000-000000000099', 0)
        """);
    jdbc.update(
        """
        INSERT INTO inventory.inventory_lot
          (id, tenant_id, supply_pool_id, sku_id, lot_code, status,
           on_hand_quantity, reserved_quantity, created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('37000000-0000-4000-8000-000000000099',
           '10000000-0000-4000-8000-000000000099',
           '36000000-0000-4000-8000-000000000099',
           '34000000-0000-4000-8000-000000000099', 'LOT-LEGACY', 'AVAILABLE', 5, 1, now(),
           '11200000-0000-4000-8000-000000000099', now(),
           '11200000-0000-4000-8000-000000000099', 0)
        """);
  }

  private static void insertLegacyCatalog(JdbcTemplate jdbc) {
    jdbc.update(
        """
        INSERT INTO catalog.producer
          (id, tenant_id, name, normalized_name, country_code,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('31000000-0000-4000-8000-000000000099',
           '10000000-0000-4000-8000-000000000099', 'Legacy Producer', 'legacy producer', 'FR',
           now(), '11200000-0000-4000-8000-000000000099', now(),
           '11200000-0000-4000-8000-000000000099', 0)
        """);
    jdbc.update(
        """
        INSERT INTO catalog.region
          (id, tenant_id, name, normalized_name, country_code,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('32000000-0000-4000-8000-000000000099',
           '10000000-0000-4000-8000-000000000099', 'Legacy Region', 'legacy region', 'FR',
           now(), '11200000-0000-4000-8000-000000000099', now(),
           '11200000-0000-4000-8000-000000000099', 0)
        """);
    jdbc.update(
        """
        INSERT INTO catalog.wine_product
          (id, tenant_id, producer_id, region_id, name, normalized_name, category,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('33000000-0000-4000-8000-000000000099',
           '10000000-0000-4000-8000-000000000099',
           '31000000-0000-4000-8000-000000000099',
           '32000000-0000-4000-8000-000000000099', 'Legacy Product', 'legacy product', 'RED',
           now(), '11200000-0000-4000-8000-000000000099', now(),
           '11200000-0000-4000-8000-000000000099', 0)
        """);
    jdbc.update(
        """
        INSERT INTO catalog.sku
          (id, tenant_id, product_id, code, vintage_code, volume_ml, units_per_case,
           package_type, status, search_text, activated_at,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('34000000-0000-4000-8000-000000000099',
           '10000000-0000-4000-8000-000000000099',
           '33000000-0000-4000-8000-000000000099', 'LEGACY-SKU', '2019', 750, 6,
           'CASE', 'ACTIVE', 'legacy product', now(), now(),
           '11200000-0000-4000-8000-000000000099', now(),
           '11200000-0000-4000-8000-000000000099', 0)
        """);
    jdbc.update(
        """
        INSERT INTO catalog.sku_supply_projection
          (tenant_id, sku_id, supply_pool_id, supply_type, location_label,
           availability_class, display_quantity_band, automatically_reservable,
           data_as_of, projection_version, created_at, created_by, updated_at, updated_by, version)
        VALUES
          ('10000000-0000-4000-8000-000000000099',
           '34000000-0000-4000-8000-000000000099',
           '36000000-0000-4000-8000-000000000099', 'DOMESTIC_ON_HAND', 'Legacy Warehouse',
           'AVAILABLE', 'LOW', true, now(), 1, now(),
           '11200000-0000-4000-8000-000000000099', now(),
           '11200000-0000-4000-8000-000000000099', 0)
        """);
  }

  private record WarehouseState(int priority, long version, Instant updatedAt, UUID updatedBy) {}
}
