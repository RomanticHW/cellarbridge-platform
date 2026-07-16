package com.rom.cellarbridge.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class QuotationSupplyDecisionV13MigrationIntegrationTest extends PostgresIntegrationTestSupport {

  private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000093");
  private static final UUID PARTNER = UUID.fromString("53000000-0000-4000-8000-000000000093");
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000093");
  private static final UUID UNDECIDED_QUOTATION =
      UUID.fromString("21000000-0000-4000-8000-000000000093");
  private static final UUID UNDECIDED_REVISION =
      UUID.fromString("22000000-0000-4000-8000-000000000093");
  private static final UUID LEGACY_QUOTATION =
      UUID.fromString("21000000-0000-4000-8000-000000000094");
  private static final UUID LEGACY_REVISION =
      UUID.fromString("22000000-0000-4000-8000-000000000094");
  private static final UUID LEGACY_EVALUATION =
      UUID.fromString("71000000-0000-4000-8000-000000000094");
  private static final UUID LEGACY_POOL = UUID.fromString("54000000-0000-4000-8000-000000000094");

  @Test
  void upgradesV12RevisionsWithoutFabricatingFrozenEvidenceOrRewritingLegacyLines() {
    String database = "quotation_v13_" + UUID.randomUUID().toString().replace("-", "");
    jdbc("postgres").execute("CREATE DATABASE " + database);
    migrate(database, "12");
    JdbcTemplate jdbc = jdbc(database);
    insertQuotation(jdbc, UNDECIDED_QUOTATION, UNDECIDED_REVISION, null);
    insertQuotation(jdbc, LEGACY_QUOTATION, LEGACY_REVISION, LEGACY_EVALUATION);
    insertLegacyLine(jdbc);

    migrate(database, "13");

    assertThat(
            jdbc.queryForObject(
                "SELECT supply_decision_status FROM quotation.quotation_revision WHERE id = ?",
                String.class,
                UNDECIDED_REVISION))
        .isEqualTo("UNDECIDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT supply_decision_status FROM quotation.quotation_revision WHERE id = ?",
                String.class,
                LEGACY_REVISION))
        .isEqualTo("LEGACY_REEVALUATION_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM quotation.quotation_revision
                 WHERE supply_decision_schema_version IS NULL
                   AND supply_decision_policy_version IS NULL
                   AND supply_decision_at IS NULL
                   AND supply_decision_hash IS NULL
                   AND supply_decision_snapshot IS NULL
                """,
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForMap(
                "SELECT allocation_mode, preferred_supply_pool_id, supply_type FROM quotation.quotation_line WHERE revision_id = ?",
                LEGACY_REVISION))
        .containsEntry("allocation_mode", null)
        .containsEntry("preferred_supply_pool_id", LEGACY_POOL)
        .containsEntry("supply_type", "DOMESTIC_ON_HAND");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE quotation.quotation_line SET allocation_mode = 'ROUTE_ELIGIBLE_AUTO', preferred_supply_pool_id = NULL, supply_type = 'UNKNOWN_CURRENT_TYPE' WHERE revision_id = ?",
                    LEGACY_REVISION))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE quotation.quotation_line SET allocation_mode = 'FIXED_POOL', supply_type = 'UNKNOWN_CURRENT_TYPE' WHERE revision_id = ?",
                    LEGACY_REVISION))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'quotation'
                   AND table_name = 'quotation_revision'
                   AND column_name = 'supply_decision_status'
                """,
                String.class))
        .isNull();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE quotation.quotation_revision SET supply_decision_status = 'FROZEN' WHERE id = ?",
                    LEGACY_REVISION))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static void insertQuotation(
      JdbcTemplate jdbc, UUID quotationId, UUID revisionId, UUID evaluationId) {
    jdbc.update(
        """
        INSERT INTO quotation.quotation
          (id, tenant_id, number, partner_id, status, current_revision_no,
           current_revision_id, owner_id, created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, 'DRAFT', 1, ?, ?, now(), ?, now(), ?, 0)
        """,
        quotationId,
        TENANT,
        "QUO-V13-" + quotationId.toString().substring(30),
        PARTNER,
        revisionId,
        ACTOR,
        ACTOR,
        ACTOR);
    boolean routed = evaluationId != null;
    jdbc.update(
        """
        INSERT INTO quotation.quotation_revision
          (id, tenant_id, quotation_id, revision_no, partner_number, partner_display_name,
           partner_payment_term_days, partner_source_version, partner_captured_at,
           currency, requested_delivery_date, expires_at, payment_term_days,
           delivery_country_code, delivery_province, delivery_city, delivery_line1,
           route_evaluation_id, route_policy_version, recommended_route_code, selected_route_code,
           price_policy_version, approval_policy_version, subtotal, total, total_cost,
           estimated_margin_rate, route_charges, created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, 1, 'PAR-V13', 'Historical Customer', 30, 1, now(),
                'CNY', current_date + 20, now() + interval '10 days', 30,
                'CN', 'Shanghai', 'Shanghai', '88 Harbor Avenue',
                ?, ?, ?, ?, 'PRICE-2026-01', 'APPROVAL-2026-01',
                100, 100, 80, 0.2000, 0, now(), ?, now(), ?, 0)
        """,
        revisionId,
        TENANT,
        quotationId,
        evaluationId,
        routed ? "ROUTE-2026-02" : null,
        routed ? "SH_GENERAL_TRADE" : null,
        routed ? "SH_GENERAL_TRADE" : null,
        ACTOR,
        ACTOR);
  }

  private static void insertLegacyLine(JdbcTemplate jdbc) {
    jdbc.update(
        """
        INSERT INTO quotation.quotation_line
          (id, tenant_id, revision_id, sku_id, sku_code, display_name, producer_name,
           region_name, country_code, category, vintage, volume_ml, units_per_case,
           package_type, sku_source_version, sku_captured_at, quantity, quantity_unit,
           preferred_supply_pool_id, supply_type, currency, list_unit_price, discount_rate,
           net_unit_price, allocated_charges, line_total, cost_unit_price, line_cost,
           estimated_margin_rate, manual_price, price_source_version,
           created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, 'SKU-V13', 'Historical Wine', 'Producer', 'Region', 'FR',
                'RED', '2020', 750, 6, 'CASE', 1, now(), 1, 'CASE', ?,
                'DOMESTIC_ON_HAND', 'CNY', 100, 0, 100, 0, 100, 80, 80, 0.2,
                false, 'PRICE-2026-01', now(), ?, now(), ?, 0)
        """,
        UUID.fromString("51000000-0000-4000-8000-000000000094"),
        TENANT,
        LEGACY_REVISION,
        UUID.fromString("34000000-0000-4000-8000-000000000094"),
        LEGACY_POOL,
        ACTOR,
        ACTOR);
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
}
