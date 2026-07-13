package com.rom.cellarbridge.quotation;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class V8LegacyPortalMigrationIntegrationTest extends PostgresIntegrationTestSupport {

  private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000091");
  private static final UUID QUOTATION = UUID.fromString("21000000-0000-4000-8000-000000000091");
  private static final UUID REVISION = UUID.fromString("22000000-0000-4000-8000-000000000091");
  private static final UUID ACCESS = UUID.fromString("23000000-0000-4000-8000-000000000091");
  private static final UUID PARTNER = UUID.fromString("53000000-0000-4000-8000-000000000091");
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000091");
  private static final Instant ISSUED_AT = Instant.parse("2026-07-01T00:00:00Z");
  private static final Instant LEGACY_EXPIRY = Instant.parse("2027-07-01T00:00:00Z");

  @Test
  void revokesLegacyPreviewWithoutExtendingOrEscalatingItsCapability() {
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + DATABASE;
    Flyway.configure()
        .dataSource(url, USERNAME, PASSWORD)
        .locations("classpath:db/migration")
        .target("7")
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, USERNAME, PASSWORD));
    insertLegacyPreview(jdbc);

    Flyway.configure()
        .dataSource(url, USERNAME, PASSWORD)
        .locations("classpath:db/migration")
        .target("8")
        .load()
        .migrate();

    Map<String, Object> migrated =
        jdbc.queryForMap(
            """
            SELECT revoked_at IS NOT NULL AS revoked,
                   purpose,
                   allowed_actions = ARRAY['VIEW']::text[] AS view_only,
                   expires_at,
                   terms_version,
                   supplier_public_id,
                   supplier_display_name
              FROM quotation.portal_access
             WHERE id = ?::uuid
            """,
            ACCESS);
    assertThat(migrated)
        .containsEntry("revoked", true)
        .containsEntry("purpose", "LEGACY_QUOTATION_PREVIEW")
        .containsEntry("view_only", true)
        .containsEntry("terms_version", null)
        .containsEntry("supplier_public_id", null)
        .containsEntry("supplier_display_name", null);
    assertThat(((Timestamp) migrated.get("expires_at")).toInstant()).isEqualTo(LEGACY_EXPIRY);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM quotation.portal_access
                 WHERE id = ?::uuid
                   AND allowed_actions && ARRAY['ACCEPT', 'REJECT']::text[]
                """,
                Integer.class,
                ACCESS))
        .isZero();
  }

  private static void insertLegacyPreview(JdbcTemplate jdbc) {
    jdbc.update(
        """
        INSERT INTO quotation.quotation
          (id, tenant_id, number, partner_id, status, current_revision_no,
           current_revision_id, owner_id, submitted_by_id, created_at, created_by,
           updated_at, updated_by, version)
        VALUES
          (?::uuid, ?::uuid, 'QUO-LEGACY-000091', ?::uuid, 'SENT', 1,
           ?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?, ?::uuid, 3)
        """,
        QUOTATION,
        TENANT,
        PARTNER,
        REVISION,
        ACTOR,
        ACTOR,
        Timestamp.from(ISSUED_AT),
        ACTOR,
        Timestamp.from(ISSUED_AT),
        ACTOR);
    jdbc.update(
        """
        INSERT INTO quotation.quotation_revision
          (id, tenant_id, quotation_id, revision_no,
           partner_number, partner_display_name, partner_payment_term_days,
           partner_source_version, partner_captured_at, currency,
           requested_delivery_date, expires_at, payment_term_days,
           delivery_country_code, delivery_province, delivery_city, delivery_district,
           delivery_line1, delivery_postal_code, route_evaluation_id, route_policy_version,
           recommended_route_code, selected_route_code, route_override_reason,
           price_policy_version, approval_policy_version, subtotal, total, total_cost,
           estimated_margin_rate, route_charges, frozen_at, created_at, created_by,
           updated_at, updated_by, version)
        VALUES
          (?::uuid, ?::uuid, ?::uuid, 1,
           'PAR-LEGACY-000091', 'Legacy Customer', 30,
           1, ?, 'CNY',
           DATE '2027-06-20', ?, 30,
           'CN', 'Shanghai', 'Shanghai', 'Pudong',
           '88 Harbor Avenue', '200120', ?::uuid, 'ROUTE-2026-01',
           'SH_GENERAL_TRADE', 'SH_GENERAL_TRADE', NULL,
           'PRICE-2026-01', 'APPROVAL-2026-01', 100.0000, 100.0000, 80.0000,
           0.2000, 0.0000, ?, ?, ?::uuid,
           ?, ?::uuid, 0)
        """,
        REVISION,
        TENANT,
        QUOTATION,
        Timestamp.from(ISSUED_AT),
        Timestamp.from(LEGACY_EXPIRY),
        UUID.fromString("61000000-0000-4000-8000-000000000091"),
        Timestamp.from(ISSUED_AT),
        Timestamp.from(ISSUED_AT),
        ACTOR,
        Timestamp.from(ISSUED_AT),
        ACTOR);
    jdbc.update(
        """
        INSERT INTO quotation.portal_access
          (id, tenant_id, quotation_id, revision_id, token_hash, expires_at, revoked_at,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, NULL, ?, ?::uuid, ?, ?::uuid, 0)
        """,
        ACCESS,
        TENANT,
        QUOTATION,
        REVISION,
        "9".repeat(64),
        Timestamp.from(LEGACY_EXPIRY),
        Timestamp.from(ISSUED_AT),
        ACTOR,
        Timestamp.from(ISSUED_AT),
        ACTOR);
  }
}
