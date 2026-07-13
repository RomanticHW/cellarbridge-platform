package com.rom.cellarbridge.tradeplanning.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.Eligibility;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteEvaluation;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteOverride;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteRejection;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteScore;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import com.rom.cellarbridge.tradeplanning.internal.application.TradePlanningRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcTradePlanningRepository implements TradePlanningRepository {

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper jsonMapper;

  public JdbcTradePlanningRepository(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void save(
      TenantId tenantId,
      UUID partnerId,
      UUID actorId,
      String inputSummary,
      RouteEvaluation evaluation) {
    jdbc.update(
        """
        INSERT INTO trade_planning.evaluation
          (id, tenant_id, partner_id, policy_version, input_hash, input_summary,
           recommended_route_code, selected_route_code, override_reason, override_actor_id,
           override_at, original_recommendation, evaluated_at, created_at, created_by,
           updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :partnerId, :policyVersion, :inputHash, CAST(:inputSummary AS jsonb),
           :recommendedRoute, :selectedRoute, :overrideReason, :overrideActorId,
           :overrideAt, :originalRecommendation, :evaluatedAt, :evaluatedAt, :actorId,
           :evaluatedAt, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("id", evaluation.evaluationId())
            .addValue("tenantId", tenantId.value())
            .addValue("partnerId", partnerId)
            .addValue("policyVersion", evaluation.policyVersion())
            .addValue("inputHash", evaluation.inputHash())
            .addValue("inputSummary", inputSummary)
            .addValue(
                "recommendedRoute",
                evaluation.recommendedRouteCode() == null
                    ? null
                    : evaluation.recommendedRouteCode().name())
            .addValue(
                "selectedRoute",
                evaluation.selectedRouteCode() == null
                    ? null
                    : evaluation.selectedRouteCode().name())
            .addValue(
                "overrideReason",
                evaluation.override() == null ? null : evaluation.override().reason())
            .addValue(
                "overrideActorId",
                evaluation.override() == null ? null : evaluation.override().actorId())
            .addValue(
                "overrideAt",
                evaluation.override() == null
                    ? null
                    : Timestamp.from(evaluation.override().occurredAt()))
            .addValue(
                "originalRecommendation",
                evaluation.override() == null
                    ? null
                    : evaluation.override().originalRecommendation().name())
            .addValue("evaluatedAt", Timestamp.from(evaluation.evaluatedAt()))
            .addValue("actorId", actorId));
    for (RouteCandidate candidate : evaluation.candidates()) {
      saveCandidate(tenantId, evaluation, candidate, actorId);
    }
  }

  @Override
  public Optional<RouteEvaluation> find(TenantId tenantId, UUID evaluationId) {
    return jdbc
        .query(
            """
            SELECT id, policy_version, input_hash, recommended_route_code,
                   selected_route_code, override_reason, override_actor_id,
                   override_at, original_recommendation, evaluated_at
              FROM trade_planning.evaluation
             WHERE tenant_id = :tenantId AND id = :evaluationId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("evaluationId", evaluationId),
            (resultSet, rowNumber) -> {
              String overrideReason = resultSet.getString("override_reason");
              RouteOverride override =
                  overrideReason == null
                      ? null
                      : new RouteOverride(
                          overrideReason,
                          resultSet.getObject("override_actor_id", UUID.class),
                          resultSet.getTimestamp("override_at").toInstant(),
                          TradeRouteCode.valueOf(resultSet.getString("original_recommendation")));
              return new RouteEvaluation(
                  resultSet.getObject("id", UUID.class),
                  resultSet.getString("policy_version"),
                  resultSet.getTimestamp("evaluated_at").toInstant(),
                  resultSet.getString("input_hash"),
                  candidates(tenantId, evaluationId),
                  route(resultSet.getString("recommended_route_code")),
                  route(resultSet.getString("selected_route_code")),
                  override);
            })
        .stream()
        .findFirst();
  }

  private void saveCandidate(
      TenantId tenantId, RouteEvaluation evaluation, RouteCandidate candidate, UUID actorId) {
    try {
      jdbc.update(
          """
          INSERT INTO trade_planning.candidate_result
            (id, tenant_id, evaluation_id, route_code, eligibility,
             cost_score, lead_time_score, supply_confidence_score, simplicity_score, total_score,
             estimated_delivery_date, estimated_charges, currency, rejections,
             created_at, created_by, updated_at, updated_by, version)
          VALUES
            (:id, :tenantId, :evaluationId, :routeCode, :eligibility,
             :costScore, :leadScore, :confidenceScore, :simplicityScore, :totalScore,
             :estimatedDeliveryDate, :estimatedCharges, :currency, CAST(:rejections AS jsonb),
             :evaluatedAt, :actorId, :evaluatedAt, :actorId, 0)
          """,
          new MapSqlParameterSource()
              .addValue("id", UUID.randomUUID())
              .addValue("tenantId", tenantId.value())
              .addValue("evaluationId", evaluation.evaluationId())
              .addValue("routeCode", candidate.routeCode().name())
              .addValue("eligibility", candidate.eligibility().name())
              .addValue("costScore", candidate.score() == null ? null : candidate.score().cost())
              .addValue(
                  "leadScore", candidate.score() == null ? null : candidate.score().leadTime())
              .addValue(
                  "confidenceScore",
                  candidate.score() == null ? null : candidate.score().supplyConfidence())
              .addValue(
                  "simplicityScore",
                  candidate.score() == null ? null : candidate.score().operationalSimplicity())
              .addValue("totalScore", candidate.score() == null ? null : candidate.score().total())
              .addValue("estimatedDeliveryDate", candidate.estimatedDeliveryDate())
              .addValue("estimatedCharges", candidate.estimatedCharges())
              .addValue("currency", candidate.currency())
              .addValue("rejections", jsonMapper.writeValueAsString(candidate.rejections()))
              .addValue("evaluatedAt", Timestamp.from(evaluation.evaluatedAt()))
              .addValue("actorId", actorId));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize route rejections", exception);
    }
  }

  private List<RouteCandidate> candidates(TenantId tenantId, UUID evaluationId) {
    return jdbc.query(
        """
        SELECT route_code, eligibility, cost_score, lead_time_score,
               supply_confidence_score, simplicity_score, total_score,
               estimated_delivery_date, estimated_charges, currency, rejections
          FROM trade_planning.candidate_result
         WHERE tenant_id = :tenantId AND evaluation_id = :evaluationId
         ORDER BY CASE route_code
                    WHEN 'SH_GENERAL_TRADE' THEN 1
                    WHEN 'NB_BONDED_B2B' THEN 2
                    ELSE 3
                  END
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("evaluationId", evaluationId),
        (resultSet, rowNumber) -> {
          RouteScore score =
              resultSet.getBigDecimal("total_score") == null
                  ? null
                  : new RouteScore(
                      resultSet.getBigDecimal("cost_score"),
                      resultSet.getBigDecimal("lead_time_score"),
                      resultSet.getBigDecimal("supply_confidence_score"),
                      resultSet.getBigDecimal("simplicity_score"),
                      resultSet.getBigDecimal("total_score"));
          return new RouteCandidate(
              TradeRouteCode.valueOf(resultSet.getString("route_code")),
              Eligibility.valueOf(resultSet.getString("eligibility")),
              score,
              resultSet.getObject("estimated_delivery_date", java.time.LocalDate.class),
              resultSet.getBigDecimal("estimated_charges"),
              resultSet.getString("currency"),
              rejections(resultSet.getString("rejections")));
        });
  }

  private List<RouteRejection> rejections(String json) {
    try {
      return jsonMapper.readValue(json, new TypeReference<List<RouteRejection>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not read route rejections", exception);
    }
  }

  private static TradeRouteCode route(String value) {
    return value == null ? null : TradeRouteCode.valueOf(value);
  }
}
