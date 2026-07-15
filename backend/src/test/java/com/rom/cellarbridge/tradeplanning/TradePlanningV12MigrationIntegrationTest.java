package com.rom.cellarbridge.tradeplanning;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import com.rom.cellarbridge.tradeplanning.internal.infrastructure.JdbcTradePlanningRepository;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class TradePlanningV12MigrationIntegrationTest extends PostgresIntegrationTestSupport {

  private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000099");
  private static final UUID PARTNER = UUID.fromString("53000000-0000-4000-8000-000000000099");
  private static final UUID ACTOR = UUID.fromString("11000000-0000-4000-8000-000000000099");
  private static final List<UUID> EVALUATIONS =
      List.of(
          UUID.fromString("71000000-0000-4000-8000-000000000001"),
          UUID.fromString("71000000-0000-4000-8000-000000000002"));

  @Test
  void upgradesV11HistoricalEvaluationsWithoutFabricatingSupplyDecisions() {
    String database = "trade_planning_v12_" + UUID.randomUUID().toString().replace("-", "");
    jdbc("postgres").execute("CREATE DATABASE " + database);
    migrate(database, "11");
    JdbcTemplate jdbc = jdbc(database);
    insertHistorical(jdbc, EVALUATIONS.get(0), "ROUTE-2026-01");
    insertHistorical(jdbc, EVALUATIONS.get(1), "ROUTE-2026-02");
    List<java.util.Map<String, Object>> roots =
        jdbc.queryForList(
            "SELECT id, policy_version, input_hash, selected_route_code FROM trade_planning.evaluation ORDER BY id");

    migrate(database, "12");

    assertThat(
            jdbc.queryForList(
                "SELECT id, policy_version, input_hash, selected_route_code FROM trade_planning.evaluation ORDER BY id"))
        .isEqualTo(roots);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM trade_planning.evaluation WHERE supply_decision_schema_version IS NULL AND supply_decision_policy_version IS NULL AND supply_decision_at IS NULL AND supply_decision_hash IS NULL AND supply_decision_summary IS NULL",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM trade_planning.candidate_result", Integer.class))
        .isEqualTo(2);
    JdbcTradePlanningRepository repository =
        new JdbcTradePlanningRepository(
            new NamedParameterJdbcTemplate(jdbc.getDataSource()), JsonMapper.builder().build());
    assertThat(
            EVALUATIONS.stream()
                .map(id -> repository.find(TenantId.of(TENANT), id).orElseThrow())
                .map(TradePlanningService.RouteEvaluation::supplyDecision))
        .containsOnlyNulls();
  }

  private static void insertHistorical(JdbcTemplate jdbc, UUID id, String policyVersion) {
    jdbc.update(
        """
        INSERT INTO trade_planning.evaluation
          (id, tenant_id, partner_id, policy_version, input_hash, input_summary,
           recommended_route_code, selected_route_code, evaluated_at,
           created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), 'SH_GENERAL_TRADE', 'SH_GENERAL_TRADE',
                '2026-07-14T12:00:00Z', '2026-07-14T12:00:00Z', ?,
                '2026-07-14T12:00:00Z', ?, 0)
        """,
        id,
        TENANT,
        PARTNER,
        policyVersion,
        policyVersion.endsWith("01") ? "1".repeat(64) : "2".repeat(64),
        "{\"schemaVersion\":1}",
        ACTOR,
        ACTOR);
    jdbc.update(
        """
        INSERT INTO trade_planning.candidate_result
          (id, tenant_id, evaluation_id, route_code, eligibility,
           cost_score, lead_time_score, supply_confidence_score, simplicity_score, total_score,
           estimated_delivery_date, estimated_charges, currency, rejections,
           created_at, created_by, updated_at, updated_by, version)
        VALUES (?, ?, ?, 'SH_GENERAL_TRADE', 'ELIGIBLE', 72, 92, 95, 92, 84.60,
                '2026-07-19', 90, 'CNY', '[]'::jsonb,
                '2026-07-14T12:00:00Z', ?, '2026-07-14T12:00:00Z', ?, 0)
        """,
        UUID.randomUUID(),
        TENANT,
        id,
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
