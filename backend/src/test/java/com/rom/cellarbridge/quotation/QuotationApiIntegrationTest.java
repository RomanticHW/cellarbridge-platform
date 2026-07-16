package com.rom.cellarbridge.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import com.rom.cellarbridge.tradeplanning.TradePlanningException;
import com.rom.cellarbridge.tradeplanning.TradePlanningService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuotationApiIntegrationTest extends PostgresIntegrationTestSupport {

  private static final String NORTH_SALES = "quotation-north-sales-token";
  private static final String NORTH_MANAGER = "quotation-north-manager-token";
  private static final String HARBOR_MANAGER = "quotation-harbor-manager-token";
  private static final String PARTNER = "53000000-0000-4000-8000-000000000001";
  private static final String SKU = "34000000-0000-4000-8000-000000000001";

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JsonMapper jsonMapper;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private TradePlanningService tradePlanningService;

  @Autowired private QuotationRepository quotationRepository;

  @Test
  void createsEvaluatesApprovesIssuesAndReturnsCustomerSafeSnapshot() throws Exception {
    Instant now = Instant.now();
    String request =
        """
        {
          "partnerId": "%s",
          "currency": "CNY",
          "requestedDeliveryDate": "%s",
          "expiresAt": "%s",
          "paymentTermDays": 30,
          "deliveryAddress": {
            "countryCode": "CN",
            "province": "Shanghai",
            "city": "Shanghai",
            "district": "Pudong",
            "line1": "88 Harbor Avenue",
            "postalCode": "200120"
          },
          "lines": [{
            "skuId": "%s",
            "quantity": {"value": "6", "unit": "CASE"},
            "discountRate": "0.0900"
          }]
        }
        """
            .formatted(
                PARTNER,
                LocalDate.now(ZoneOffset.UTC).plusDays(20),
                now.plusSeconds(10 * 86_400L),
                SKU);

    ApiResponse created = request("POST", "/api/v1/quotations", NORTH_SALES, null, request);
    assertThat(created.status()).withFailMessage(created.raw()).isEqualTo(201);
    assertThat(created.body().path("status").asText()).isEqualTo("DRAFT");
    assertThat(created.body().path("estimatedMarginRate").isNull()).isTrue();
    assertThat(created.body().path("supplyDecisionStatus").asText()).isEqualTo("UNDECIDED");
    assertThat(created.body().path("supplyDecision").isNull()).isTrue();
    assertThat(created.body().path("lines").path(0).path("allocationMode").isNull()).isTrue();
    assertThat(created.body().path("lines").path(0).path("supplyType").isNull()).isTrue();
    assertThat(created.body().path("lines").path(0).path("sku").path("skuCode").asText())
        .isEqualTo("CB-MTV-2019-750X6");
    String quotationId = created.body().path("id").asText();
    assertThat(created.body().path("allowedActions").toString()).doesNotContain("SUBMIT");
    ApiResponse undecidedSubmit =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/submission",
            NORTH_SALES,
            "\"0\"",
            null);
    assertThat(undecidedSubmit.status()).isEqualTo(422);
    assertThat(undecidedSubmit.body().path("code").asText())
        .isEqualTo("QUOTE_SUPPLY_DECISION_REQUIRED");

    Integer evaluationsBeforeOverride =
        jdbc.queryForObject("SELECT count(*) FROM trade_planning.evaluation", Integer.class);
    ApiResponse unauthorizedOverride =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/route-evaluations",
            NORTH_SALES,
            "\"0\"",
            """
            {"requestedRouteCode":"NB_BONDED_B2B","overrideReason":"Use bonded delivery"}
            """);
    assertThat(unauthorizedOverride.status()).isEqualTo(403);
    assertThat(unauthorizedOverride.body().path("code").asText()).isEqualTo("ACCESS_DENIED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_planning.evaluation", Integer.class))
        .isEqualTo(evaluationsBeforeOverride);

    ApiResponse evaluated =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/route-evaluations",
            NORTH_MANAGER,
            "\"0\"",
            """
            {"requestedRouteCode":"NB_BONDED_B2B","overrideReason":"Use bonded delivery"}
            """);
    assertThat(evaluated.status()).withFailMessage(evaluated.raw()).isEqualTo(200);
    assertThat(evaluated.body().path("policyVersion").asText()).isEqualTo("ROUTE-2026-03");
    assertThat(evaluated.body().path("recommendedRouteCode").asText())
        .isEqualTo("SH_GENERAL_TRADE");
    assertThat(evaluated.body().path("selectedRouteCode").asText()).isEqualTo("NB_BONDED_B2B");
    assertThat(evaluated.body().path("override").path("originalRecommendation").asText())
        .isEqualTo("SH_GENERAL_TRADE");
    assertThat(evaluated.body().path("candidates").size()).isEqualTo(3);
    assertThat(evaluated.body().path("supplyDecision").path("policyVersion").asText())
        .isEqualTo("SUPPLY-DECISION-2026-01");
    assertThat(evaluated.body().path("supplyDecision").path("selectedRouteCode").asText())
        .isEqualTo("NB_BONDED_B2B");
    String evaluationId = evaluated.body().path("evaluationId").asText();
    assertThat(
            jdbc.queryForObject(
                "SELECT input_hash FROM trade_planning.evaluation WHERE id = ?::uuid",
                String.class,
                evaluationId))
        .isEqualTo(evaluated.body().path("inputHash").asText());
    String storedInput =
        jdbc.queryForObject(
            "SELECT input_summary::text FROM trade_planning.evaluation WHERE id = ?::uuid",
            String.class,
            evaluationId);
    assertThat(storedInput)
        .contains("\"schemaVersion\": 3")
        .contains("\"routePolicyVersion\": \"ROUTE-2026-03\"")
        .contains("\"supplyDecisionPolicyVersion\": \"SUPPLY-DECISION-2026-01\"")
        .contains("\"requestedQuantity\": \"6.000000\"")
        .contains("\"quantityUnit\": \"CASE\"")
        .contains("\"moqCaseEquivalentQuantity\": \"6.000000\"")
        .contains("\"preferredSupplyPoolId\": null")
        .contains("\"availability\"");
    var internalEvaluation =
        tradePlanningService.get(
            TenantId.of(java.util.UUID.fromString("10000000-0000-4000-8000-000000000001")),
            java.util.UUID.fromString(evaluationId));
    assertThat(internalEvaluation.supplyDecision()).isNotNull();
    assertThat(internalEvaluation.supplyDecision().sourceRouteEvaluationId().toString())
        .isEqualTo(evaluationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT supply_decision_hash FROM trade_planning.evaluation WHERE id = ?::uuid",
                String.class,
                evaluationId))
        .isEqualTo(internalEvaluation.supplyDecision().decisionHash());

    ApiResponse routed = get("/api/v1/quotations/" + quotationId, NORTH_SALES);
    assertThat(routed.body().path("supplyDecisionStatus").asText()).isEqualTo("FROZEN");
    assertThat(routed.body().path("supplyDecision").path("sourceRouteEvaluationId").asText())
        .isEqualTo(evaluationId);
    assertThat(routed.body().path("lines").path(0).path("allocationMode").asText())
        .isEqualTo("ROUTE_ELIGIBLE_AUTO");
    assertThat(routed.body().path("lines").path(0).path("supplyPoolId").isNull()).isTrue();
    assertThat(routed.body().path("version").asLong()).isEqualTo(1);
    ApiResponse submitted =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/submission",
            NORTH_SALES,
            "\"1\"",
            null);
    assertThat(submitted.status()).withFailMessage(submitted.raw()).isEqualTo(200);
    assertThat(submitted.body().path("status").asText()).isEqualTo("PENDING_APPROVAL");
    assertThat(submitted.body().path("version").asLong()).isEqualTo(2);

    ApiResponse selfApproval =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/approval",
            NORTH_SALES,
            "\"2\"",
            "{\"decision\":\"APPROVE\",\"reason\":\"Approve commercial terms\"}");
    assertThat(selfApproval.status()).isEqualTo(403);
    assertThat(selfApproval.body().path("code").asText()).isEqualTo("ACCESS_DENIED");

    ApiResponse managerView = get("/api/v1/quotations/" + quotationId, NORTH_MANAGER);
    assertThat(managerView.status()).isEqualTo(200);
    assertThat(managerView.body().path("estimatedMarginRate").asText()).isNotBlank();
    assertThat(managerView.body().path("approvalRequirements").path(0).path("code").asText())
        .isEqualTo("DISCOUNT_THRESHOLD_EXCEEDED");
    jdbc.update(
        """
        UPDATE catalog.wine_product
           SET name = 'Moonlit Terrace Updated', version = version + 1
         WHERE tenant_id = '10000000-0000-4000-8000-000000000001'
           AND id = '33000000-0000-4000-8000-000000000001'
        """);
    ApiResponse frozenSnapshot = get("/api/v1/quotations/" + quotationId, NORTH_MANAGER);
    assertThat(frozenSnapshot.body().path("lines").path(0).path("sku").path("displayName").asText())
        .isEqualTo("Moonlit Terrace");
    CompletableFuture<ApiResponse> firstApproval =
        CompletableFuture.supplyAsync(() -> approve(quotationId));
    CompletableFuture<ApiResponse> duplicateApproval =
        CompletableFuture.supplyAsync(() -> approve(quotationId));
    List<ApiResponse> approvals = List.of(firstApproval.join(), duplicateApproval.join());
    assertThat(approvals).extracting(ApiResponse::status).containsOnly(200);
    assertThat(approvals)
        .extracting(response -> response.body().path("status").asText())
        .containsOnly("APPROVED");
    Integer decisionCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM quotation.approval_decision WHERE quotation_id = ?::uuid",
            Integer.class,
            quotationId);
    assertThat(decisionCount).isEqualTo(1);

    Integer evaluationsBeforeIssue =
        jdbc.queryForObject("SELECT count(*) FROM trade_planning.evaluation", Integer.class);
    ApiResponse issued =
        request("POST", "/api/v1/quotations/" + quotationId + "/issue", NORTH_SALES, "\"3\"", null);
    assertThat(issued.status()).withFailMessage(issued.raw()).isEqualTo(200);
    assertThat(issued.body().path("status").asText()).isEqualTo("SENT");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_planning.evaluation", Integer.class))
        .isEqualTo(evaluationsBeforeIssue);
    String portalUrl = issued.body().path("portalUrl").asText();

    ApiResponse publicView = get("/api/v1" + portalUrl, null);
    assertThat(publicView.status()).withFailMessage(publicView.raw()).isEqualTo(200);
    assertThat(publicView.body().path("customerDisplayName").asText())
        .isEqualTo("Aurora Market Services");
    assertThat(publicView.raw())
        .doesNotContain(
            "cost",
            "margin",
            "score",
            "inputHash",
            "policyVersion",
            "supplyDecision",
            "allocationMode",
            "supplyPoolId",
            "supplyType");
    assertThat(publicView.headers().firstValue("cache-control").orElse("")).contains("no-store");

    ApiResponse crossTenant = get("/api/v1/quotations/" + quotationId, HARBOR_MANAGER);
    assertThat(crossTenant.status()).isEqualTo(404);
    assertThat(crossTenant.raw()).doesNotContain("Aurora Market Services");
  }

  @Test
  void rejectsUnauthenticatedInternalAccessAndMalformedPublicTokens() throws Exception {
    ApiResponse internal = get("/api/v1/quotations", null);
    assertThat(internal.status()).isEqualTo(401);

    ApiResponse publicView = get("/api/v1/portal/quotations/not-a-token", null);
    assertThat(publicView.status()).isEqualTo(404);
    assertThat(publicView.body().path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
  }

  @Test
  void evaluatesDemoSupplyCoverageWithoutMixingCaseAndBottleQuantities() throws Exception {
    Integer evaluationsBeforeRejection =
        jdbc.queryForObject("SELECT count(*) FROM trade_planning.evaluation", Integer.class);
    ApiResponse sixtyCases = createAndEvaluate("60", "CASE");
    assertThat(sixtyCases.status()).withFailMessage(sixtyCases.raw()).isEqualTo(422);
    assertThat(sixtyCases.body().path("code").asText()).isEqualTo("QUOTE_HAS_NO_ELIGIBLE_ROUTE");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_planning.evaluation", Integer.class))
        .isEqualTo(evaluationsBeforeRejection);

    ApiResponse fiftySixCases = createAndEvaluate("56", "CASE");
    assertThat(fiftySixCases.status()).withFailMessage(fiftySixCases.raw()).isEqualTo(200);
    assertThat(fiftySixCases.body().path("recommendedRouteCode").asText())
        .isEqualTo("SH_GENERAL_TRADE");

    ApiResponse tenBottles = createAndEvaluate("10", "BOTTLE");
    assertThat(tenBottles.status()).withFailMessage(tenBottles.raw()).isEqualTo(200);
    assertThat(tenBottles.body().path("recommendedRouteCode").asText())
        .isEqualTo("SH_GENERAL_TRADE");

    ApiResponse elevenBottles = createAndEvaluate("11", "BOTTLE");
    assertThat(elevenBottles.status()).withFailMessage(elevenBottles.raw()).isEqualTo(422);
    assertThat(elevenBottles.body().path("code").asText()).isEqualTo("QUOTE_HAS_NO_ELIGIBLE_ROUTE");
  }

  @Test
  void freezesTheRequestedFixedPoolWithoutAutomaticFallback() throws Exception {
    String poolId = "36000000-0000-4000-8000-000000000001";
    ApiResponse created = createQuotation("6", "CASE", poolId);
    ApiResponse evaluated =
        request(
            "POST",
            "/api/v1/quotations/" + created.body().path("id").asText() + "/route-evaluations",
            NORTH_MANAGER,
            "\"0\"",
            "{}");
    assertThat(evaluated.status()).withFailMessage(evaluated.raw()).isEqualTo(200);
    JsonNode line = evaluated.body().path("supplyDecision").path("lineDecisions").path(0);
    assertThat(line.path("allocationMode").asText()).isEqualTo("FIXED_POOL");
    assertThat(line.path("supplyPoolId").asText()).isEqualTo(poolId);
    assertThat(line.path("supplyType").asText()).isEqualTo("DOMESTIC_ON_HAND");
  }

  @Test
  void reevaluatesDraftLegacyEvidenceWithoutExposingUnverifiedLineSupply() throws Exception {
    ApiResponse created = createQuotation("6", "CASE");
    String quotationId = created.body().path("id").asText();
    ApiResponse frozen =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/route-evaluations",
            NORTH_MANAGER,
            "\"0\"",
            "{}");
    assertThat(frozen.status()).withFailMessage(frozen.raw()).isEqualTo(200);
    jdbc.update(
        """
        UPDATE quotation.quotation_revision
           SET supply_decision_status = 'LEGACY_REEVALUATION_REQUIRED',
               supply_decision_schema_version = NULL,
               supply_decision_policy_version = NULL,
               supply_decision_at = NULL,
               supply_decision_hash = NULL,
               supply_decision_snapshot = NULL
         WHERE quotation_id = ?::uuid
        """,
        quotationId);

    ApiResponse legacy = get("/api/v1/quotations/" + quotationId, NORTH_MANAGER);
    assertThat(legacy.status()).withFailMessage(legacy.raw()).isEqualTo(200);
    assertThat(legacy.body().path("supplyDecisionStatus").asText())
        .isEqualTo("LEGACY_REEVALUATION_REQUIRED");
    assertThat(legacy.body().path("supplyDecision").isNull()).isTrue();
    assertThat(legacy.body().path("lines").path(0).path("allocationMode").isNull()).isTrue();
    assertThat(legacy.body().path("lines").path(0).path("supplyType").isNull()).isTrue();
    assertThat(legacy.body().path("allowedActions"))
        .extracting(JsonNode::asText)
        .contains("EVALUATE_ROUTE");

    ApiResponse reevaluated =
        request(
            "POST",
            "/api/v1/quotations/" + quotationId + "/route-evaluations",
            NORTH_MANAGER,
            "\"1\"",
            "{}");
    assertThat(reevaluated.status()).withFailMessage(reevaluated.raw()).isEqualTo(200);
    assertThat(reevaluated.body().path("supplyDecision").path("decisionHash").asText()).hasSize(64);
  }

  @Test
  void rollsBackPlanningEvaluationWhenQuotationFreezePersistenceFails() throws Exception {
    ApiResponse created = createQuotation("6", "CASE");
    String quotationId = created.body().path("id").asText();
    Integer evaluationsBefore =
        jdbc.queryForObject("SELECT count(*) FROM trade_planning.evaluation", Integer.class);
    jdbc.execute(
        "ALTER TABLE quotation.quotation_revision ADD CONSTRAINT ck_test_reject_route_freeze CHECK (route_evaluation_id IS NULL) NOT VALID");
    try {
      ApiResponse failed =
          request(
              "POST",
              "/api/v1/quotations/" + quotationId + "/route-evaluations",
              NORTH_MANAGER,
              "\"0\"",
              "{}");
      assertThat(failed.status()).isEqualTo(500);
    } finally {
      jdbc.execute(
          "ALTER TABLE quotation.quotation_revision DROP CONSTRAINT ck_test_reject_route_freeze");
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_planning.evaluation", Integer.class))
        .isEqualTo(evaluationsBefore);
    ApiResponse unchanged = get("/api/v1/quotations/" + quotationId, NORTH_SALES);
    assertThat(unchanged.body().path("version").asLong()).isZero();
    assertThat(unchanged.body().path("supplyDecisionStatus").asText()).isEqualTo("UNDECIDED");
  }

  @Test
  void rejectsInvalidDecisionColumnsAndDetectsJsonTamperingAcrossTenantBoundaries()
      throws Exception {
    ApiResponse evaluated = createAndEvaluate("6", "CASE");
    assertThat(evaluated.status()).withFailMessage(evaluated.raw()).isEqualTo(200);
    UUID evaluationId = UUID.fromString(evaluated.body().path("evaluationId").asText());
    UUID quotationId =
        jdbc.queryForObject(
            "SELECT quotation_id FROM quotation.quotation_revision WHERE route_evaluation_id = ?",
            UUID.class,
            evaluationId);
    assertThatThrownBy(
            () ->
                tradePlanningService.get(
                    TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001")),
                    evaluationId))
        .isInstanceOfSatisfying(
            TradePlanningException.class,
            exception -> assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trade_planning.evaluation SET supply_decision_hash = NULL WHERE id = ?",
                    evaluationId))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE quotation.quotation_revision SET supply_decision_snapshot = '{\"schemaVersion\":1}'::jsonb WHERE id = (SELECT current_revision_id FROM quotation.quotation WHERE id = ?)",
                    quotationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE quotation.quotation_revision SET supply_decision_snapshot = jsonb_set(supply_decision_snapshot, '{decisionHash}', 'null'::jsonb) WHERE quotation_id = ?",
                    quotationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE quotation.quotation_revision SET supply_decision_snapshot = jsonb_set(supply_decision_snapshot, '{schemaVersion}', to_jsonb('1'::text)) WHERE quotation_id = ?",
                    quotationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE quotation.quotation_revision SET supply_decision_snapshot = jsonb_set(supply_decision_snapshot, '{lineDecisions}', '[]'::jsonb) WHERE quotation_id = ?",
                    quotationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE quotation.quotation_revision SET supply_decision_policy_version = '   ', supply_decision_snapshot = jsonb_set(supply_decision_snapshot, '{policyVersion}', to_jsonb('   '::text)) WHERE quotation_id = ?",
                    quotationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(
        "UPDATE quotation.quotation_revision SET supply_decision_snapshot = jsonb_set(supply_decision_snapshot, '{inventoryDataAsOf}', to_jsonb('2000-01-01T00:00:00Z'::text)) WHERE quotation_id = ?",
        quotationId);
    assertThatThrownBy(
            () ->
                quotationRepository.find(
                    TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001")),
                    quotationId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Quotation supply decision snapshot is invalid");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trade_planning.evaluation SET supply_decision_hash = ? WHERE id = ?",
                    "A".repeat(64),
                    evaluationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trade_planning.evaluation SET supply_decision_summary = '{\"schemaVersion\":1}'::jsonb WHERE id = ?",
                    evaluationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trade_planning.evaluation SET supply_decision_summary = jsonb_set(supply_decision_summary, '{sourceRouteInputHash}', 'null'::jsonb) WHERE id = ?",
                    evaluationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trade_planning.evaluation SET supply_decision_summary = jsonb_set(supply_decision_summary, '{schemaVersion}', to_jsonb('1'::text)) WHERE id = ?",
                    evaluationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trade_planning.evaluation SET supply_decision_summary = jsonb_set(supply_decision_summary, '{lineDecisions}', '[]'::jsonb) WHERE id = ?",
                    evaluationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trade_planning.evaluation SET supply_decision_policy_version = '   ', supply_decision_summary = jsonb_set(supply_decision_summary, '{policyVersion}', to_jsonb('   '::text)) WHERE id = ?",
                    evaluationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trade_planning.evaluation SET supply_decision_at = evaluated_at + interval '1 microsecond' WHERE id = ?",
                    evaluationId))
        .isInstanceOf(DataIntegrityViolationException.class);

    jdbc.update(
        "UPDATE trade_planning.evaluation SET supply_decision_summary = jsonb_set(supply_decision_summary, '{inventoryDataAsOf}', to_jsonb('2000-01-01T00:00:00Z'::text)) WHERE id = ?",
        evaluationId);
    assertThatThrownBy(
            () ->
                tradePlanningService.get(
                    TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001")),
                    evaluationId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Could not read valid supply decision evidence");
  }

  private ApiResponse get(String path, String token) throws Exception {
    return request("GET", path, token, null, null);
  }

  private ApiResponse approve(String quotationId) {
    try {
      return request(
          "POST",
          "/api/v1/quotations/" + quotationId + "/approval",
          NORTH_MANAGER,
          "\"2\"",
          "{\"decision\":\"APPROVE\",\"reason\":\"Approved within demo policy\"}");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private ApiResponse createAndEvaluate(String quantity, String quantityUnit) throws Exception {
    ApiResponse created = createQuotation(quantity, quantityUnit);
    return request(
        "POST",
        "/api/v1/quotations/" + created.body().path("id").asText() + "/route-evaluations",
        NORTH_MANAGER,
        "\"0\"",
        "{}");
  }

  private ApiResponse createQuotation(String quantity, String quantityUnit) throws Exception {
    return createQuotation(quantity, quantityUnit, null);
  }

  private ApiResponse createQuotation(
      String quantity, String quantityUnit, String preferredSupplyPoolId) throws Exception {
    Instant now = Instant.now();
    String createRequest =
        """
        {
          "partnerId": "%s",
          "currency": "CNY",
          "requestedDeliveryDate": "%s",
          "expiresAt": "%s",
          "paymentTermDays": 30,
          "deliveryAddress": {
            "countryCode": "CN",
            "province": "Shanghai",
            "city": "Shanghai",
            "district": "Pudong",
            "line1": "88 Harbor Avenue",
            "postalCode": "200120"
          },
          "lines": [{
            "skuId": "%s",
            "quantity": {"value": "%s", "unit": "%s"}%s,
            "discountRate": "0.0000"
          }]
        }
        """
            .formatted(
                PARTNER,
                LocalDate.now(ZoneOffset.UTC).plusDays(20),
                now.plusSeconds(10 * 86_400L),
                SKU,
                quantity,
                quantityUnit,
                preferredSupplyPoolId == null
                    ? ""
                    : ", \"preferredSupplyPoolId\": \"" + preferredSupplyPoolId + "\"");
    ApiResponse created = request("POST", "/api/v1/quotations", NORTH_SALES, null, createRequest);
    assertThat(created.status()).withFailMessage(created.raw()).isEqualTo(201);
    return created;
  }

  private ApiResponse request(String method, String path, String token, String ifMatch, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", "application/json");
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response =
        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new ApiResponse(
        response.statusCode(),
        response.body().isBlank()
            ? jsonMapper.createObjectNode()
            : jsonMapper.readTree(response.body()),
        response.body(),
        response.headers());
  }

  record ApiResponse(int status, JsonNode body, String raw, java.net.http.HttpHeaders headers) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class TestTokenConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
      return token ->
          switch (token) {
            case NORTH_SALES -> jwt(token, "11000000-0000-4000-8000-000000000001", "north-cellars");
            case NORTH_MANAGER ->
                jwt(token, "11000000-0000-4000-8000-000000000003", "north-cellars");
            case HARBOR_MANAGER ->
                jwt(token, "22000000-0000-4000-8000-000000000001", "harbor-cellars");
            default -> throw new BadJwtException("Token is invalid");
          };
    }

    private static Jwt jwt(String token, String subject, String tenantCode) {
      Instant now = Instant.now();
      return Jwt.withTokenValue(token)
          .header("alg", "RS256")
          .issuer("http://localhost:8081/realms/cellarbridge")
          .subject(subject)
          .audience(List.of("cellarbridge-api"))
          .issuedAt(now.minusSeconds(30))
          .expiresAt(now.plusSeconds(300))
          .claim("tenant_code", tenantCode)
          .build();
    }
  }
}
