package com.rom.cellarbridge.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
class CatalogSearchApiIntegrationTest extends PostgresIntegrationTestSupport {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CatalogSearchApiIntegrationTest.class);
  private static final String NORTH_SALES = "catalog-north-sales-token";
  private static final String NORTH_BUYER = "catalog-north-buyer-token";
  private static final String NORTH_ADMIN = "catalog-north-admin-token";
  private static final String NORTH_WAREHOUSE = "catalog-north-warehouse-token";
  private static final String HARBOR_MANAGER = "catalog-harbor-manager-token";
  private static final String NORTH_SKU = "34000000-0000-4000-8000-000000000001";

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JsonMapper jsonMapper;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void searchesEnglishChineseAccentAndMisspelledTermsWithoutExposingExactStockToSales()
      throws Exception {
    ApiResponse misspelled =
        get(
            "/api/v1/catalog/skus?keyword=Silvr%20Vale&vintage=2019"
                + "&supplyType=DOMESTIC_ON_HAND&quantityUnit=CASE&pageSize=5",
            NORTH_SALES);

    assertThat(misspelled.status()).withFailMessage(misspelled.raw()).isEqualTo(200);
    assertThat(misspelled.body().path("items").path(0).path("sku").path("skuCode").asText())
        .isEqualTo("CB-MTV-2019-750X6");
    JsonNode summary = misspelled.body().path("items").path(0).path("supplies").path(0);
    assertThat(summary.path("displayQuantityBand").asText()).isEqualTo("HIGH");
    assertThat(summary.path("displayedAvailableQuantity").isNull()).isTrue();
    assertThat(summary.path("exactLots").size()).isZero();
    assertThat(misspelled.body().path("availabilityDisclaimer").asText())
        .contains("not an inventory commitment");
    assertThat(misspelled.body().path("dataAsOf").asText()).isNotBlank();
    ApiResponse empty = get("/api/v1/catalog/skus?keyword=Neverland%20Reserve", NORTH_SALES);
    assertThat(empty.body().path("items")).isEmpty();
    assertThat(empty.body().path("dataAsOf").asText()).isNotBlank();

    ApiResponse accent = get("/api/v1/catalog/skus?keyword=Etoile", NORTH_SALES);
    assertThat(accent.body().path("items").path(0).path("sku").path("displayName").asText())
        .isEqualTo("Étoile Blanche");
    ApiResponse chinese =
        get("/api/v1/catalog/skus?keyword=" + encoded("云岚") + "&countryCode=CN", NORTH_SALES);
    assertThat(chinese.body().path("items").path(0).path("sku").path("skuCode").asText())
        .isEqualTo("CB-YL-2020-750X6");
  }

  @Test
  void preservesMeasuredLexicalRelevanceAcrossTheCatalogGoldSet() throws Exception {
    List<String> expectedCorpus =
        List.of(
            "CB-ECR-2023-750X12",
            "CB-ETB-2022-750X6",
            "CB-MTV-2019-750X6",
            "CB-MTV-2021-1500X3",
            "CB-NFS-NV-750X6",
            "CB-YL-2020-750X6");
    ApiResponse corpus = get("/api/v1/catalog/skus?pageSize=100", NORTH_SALES);
    assertThat(corpus.status()).withFailMessage(corpus.raw()).isEqualTo(200);
    List<String> actualCorpus =
        corpus
            .body()
            .path("items")
            .valueStream()
            .map(item -> item.path("sku").path("skuCode").asText())
            .sorted()
            .toList();
    boolean corpusHasNext = corpus.body().path("pageInfo").path("hasNext").asBoolean();
    LOGGER.info(
        "catalogRelevanceCorpus expectedActive={} actualCodes={} hasNext={}",
        expectedCorpus.size(),
        actualCorpus,
        corpusHasNext);
    assertThat(actualCorpus)
        .as("permission-trimmed North Sales corpus")
        .containsExactlyElementsOf(expectedCorpus);
    assertThat(corpusHasNext).as("complete evaluated corpus").isFalse();

    List<GoldQuery> goldSet =
        List.of(
            new GoldQuery(
                "producer-vintage-ranking",
                "Silver Vale 2019",
                Map.of("CB-MTV-2019-750X6", 3, "CB-MTV-2021-1500X3", 1),
                "CB-MTV-2019-750X6"),
            new GoldQuery(
                "exact-dimensions-among-decoys",
                "2019 750x6",
                Map.of("CB-MTV-2019-750X6", 3),
                "CB-MTV-2019-750X6"),
            new GoldQuery(
                "accent-folding", "Etoile", Map.of("CB-ETB-2022-750X6", 3), "CB-ETB-2022-750X6"),
            new GoldQuery("chinese-name", "云岚", Map.of("CB-YL-2020-750X6", 3), "CB-YL-2020-750X6"),
            new GoldQuery(
                "producer-typo",
                "Silvr Vale",
                Map.of("CB-MTV-2019-750X6", 3, "CB-MTV-2021-1500X3", 3),
                null),
            new GoldQuery(
                "shared-producer-recall",
                "Maison Aurelie",
                Map.of("CB-ETB-2022-750X6", 2, "CB-NFS-NV-750X6", 2),
                null),
            new GoldQuery("negative-empty", "Neverland Reserve", Map.of(), null));

    double ndcgTotal = 0;
    double reciprocalRankTotal = 0;
    double recallTotal = 0;
    int positiveQueries = 0;
    int negativeQueries = 0;
    for (GoldQuery query : goldSet) {
      ApiResponse response =
          get(
              "/api/v1/catalog/skus?keyword=" + encoded(query.keyword()) + "&pageSize=5",
              NORTH_SALES);
      assertThat(response.status()).as(query.name()).withFailMessage(response.raw()).isEqualTo(200);
      String dataAsOf = response.body().path("dataAsOf").asText();
      assertThat(dataAsOf).as(query.name() + " dataAsOf").isNotBlank();
      List<String> rankedCodes =
          response
              .body()
              .path("items")
              .valueStream()
              .map(item -> item.path("sku").path("skuCode").asText())
              .toList();
      assertThat(rankedCodes).as(query.name() + " stable SKU set").doesNotHaveDuplicates();
      assertThat(rankedCodes.size()).as(query.name() + " top-five bound").isLessThanOrEqualTo(5);
      List<String> actualTopFive = rankedCodes.stream().limit(5).toList();

      boolean negativeQuery = query.relevance().isEmpty();
      boolean negativeEmpty = negativeQuery && actualTopFive.isEmpty();
      if (negativeQuery) {
        LOGGER.info(
            "catalogRelevanceQuery queryName={} keyword={} gradedExpected={} requiredFirst={} "
                + "actualTopFive={} ndcgAt5={} reciprocalRankAt5={} recallAt5={} "
                + "unjudgedEmpty={} negativeEmpty={} outcome={} dataAsOf={}",
            query.name(),
            query.keyword(),
            gradedExpected(query.relevance()),
            "NONE",
            actualTopFive,
            "N/A",
            "N/A",
            "N/A",
            negativeEmpty,
            negativeEmpty,
            negativeEmpty ? "PASS" : "FAIL",
            dataAsOf);
        assertThat(actualTopFive).as(query.name()).isEmpty();
        negativeQueries++;
        continue;
      }

      double ndcg = ndcgAtFive(actualTopFive, query.relevance());
      double recall =
          actualTopFive.stream().filter(query.relevance()::containsKey).distinct().count()
              / (double) query.relevance().size();
      int firstRelevantRank = firstRelevantRank(actualTopFive, query.relevance());
      double reciprocalRank = firstRelevantRank == 0 ? 0 : 1.0 / firstRelevantRank;
      boolean requiredFirstMatched =
          query.requiredFirst() == null
              || (!actualTopFive.isEmpty()
                  && query.requiredFirst().equals(actualTopFive.getFirst()));
      boolean unjudgedEmpty = actualTopFive.stream().allMatch(query.relevance()::containsKey);
      boolean passed =
          ndcg >= 0.85
              && recall == 1.0
              && reciprocalRank == 1.0
              && requiredFirstMatched
              && unjudgedEmpty;
      LOGGER.info(
          "catalogRelevanceQuery queryName={} keyword={} gradedExpected={} requiredFirst={} "
              + "actualTopFive={} ndcgAt5={} reciprocalRankAt5={} recallAt5={} "
              + "unjudgedEmpty={} negativeEmpty={} outcome={} dataAsOf={}",
          query.name(),
          query.keyword(),
          gradedExpected(query.relevance()),
          query.requiredFirst() == null ? "NONE" : query.requiredFirst(),
          actualTopFive,
          ndcg,
          reciprocalRank,
          recall,
          unjudgedEmpty,
          false,
          passed ? "PASS" : "FAIL",
          dataAsOf);
      assertThat(ndcg).as(query.name() + " nDCG@5").isGreaterThanOrEqualTo(0.85);
      assertThat(recall).as(query.name() + " Recall@5").isEqualTo(1.0);
      assertThat(reciprocalRank).as(query.name() + " RR@5").isEqualTo(1.0);
      assertThat(unjudgedEmpty).as(query.name() + " unjudged result set").isTrue();
      if (query.requiredFirst() != null) {
        assertThat(actualTopFive)
            .as(query.name() + " requiredFirst")
            .startsWith(query.requiredFirst());
      }
      ndcgTotal += ndcg;
      reciprocalRankTotal += reciprocalRank;
      recallTotal += recall;
      positiveQueries++;
    }
    assertThat(goldSet).as("gold-set query count").hasSize(7);
    assertThat(positiveQueries).as("gold-set positive query count").isEqualTo(6);
    assertThat(negativeQueries).as("gold-set negative query count").isEqualTo(1);
    double meanNdcg = ndcgTotal / positiveQueries;
    double meanReciprocalRank = reciprocalRankTotal / positiveQueries;
    double meanRecall = recallTotal / positiveQueries;
    LOGGER.info(
        "catalogRelevanceGoldSet queryCount={} positive={} negative={} "
            + "meanNdcgAt5={} mrrAt5={} meanRecallAt5={}",
        goldSet.size(),
        positiveQueries,
        negativeQueries,
        meanNdcg,
        meanReciprocalRank,
        meanRecall);
    assertThat(meanNdcg).as("gold-set mean nDCG@5").isGreaterThanOrEqualTo(0.95);
    assertThat(meanReciprocalRank).as("gold-set MRR@5").isEqualTo(1.0);
    assertThat(meanRecall).as("gold-set mean Recall@5").isEqualTo(1.0);
  }

  @Test
  void revealsExactAssignedLotsOnlyToExactInventoryReaders() throws Exception {
    ApiResponse exact =
        get(
            "/api/v1/catalog/skus?keyword=Moonlit%20Terrace&supplyType=DOMESTIC_ON_HAND"
                + "&quantityUnit=CASE",
            NORTH_ADMIN);

    assertThat(exact.status()).isEqualTo(200);
    JsonNode supply = exact.body().path("items").path(0).path("supplies").path(0);
    assertThat(supply.path("displayedAvailableQuantity").path("value").asText()).isEqualTo("56");
    assertThat(supply.path("exactLots").size()).isEqualTo(2);
    assertThat(supply.path("exactLots").path(0).path("onHandQuantity").path("value").asText())
        .isNotBlank();

    ApiResponse assigned = get("/api/v1/catalog/skus?supplyType=DOMESTIC_ON_HAND", NORTH_WAREHOUSE);
    assertThat(assigned.body().path("items").size()).isPositive();
    assertThat(
            assigned.body().path("items").path(0).path("supplies").path(0).path("exactLots").size())
        .isPositive();
    ApiResponse unassigned =
        get("/api/v1/catalog/skus?supplyType=HONG_KONG_ON_HAND", NORTH_WAREHOUSE);
    assertThat(unassigned.body().path("items").size()).isPositive();
    JsonNode unassignedSupply = unassigned.body().path("items").path(0).path("supplies").path(0);
    assertThat(unassignedSupply.path("displayQuantityBand").asText()).isNotBlank();
    assertThat(unassignedSupply.path("displayedAvailableQuantity").isNull()).isTrue();
    assertThat(unassignedSupply.path("exactLots").size()).isZero();

    ApiResponse requiresConfirmation =
        get("/api/v1/catalog/skus?supplyType=IN_TRANSIT_PRESALE", NORTH_ADMIN);
    JsonNode confirmationSupply =
        requiresConfirmation.body().path("items").path(0).path("supplies").path(0);
    assertThat(confirmationSupply.path("displayQuantityBand").asText())
        .isEqualTo("CONFIRMATION_REQUIRED");
    assertThat(confirmationSupply.path("displayedAvailableQuantity").isNull()).isTrue();
    assertThat(confirmationSupply.path("exactLots").size()).isZero();
  }

  @Test
  void separatesAndFiltersSamePoolSupplyByQuantityUnit() throws Exception {
    Instant priorityCorrectionTime = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
    jdbc.update(
        """
        UPDATE inventory.warehouse
           SET allocation_priority = 10, updated_at = ?::timestamptz, version = version + 1
         WHERE id = '35000000-0000-4000-8000-000000000001'
        """,
        priorityCorrectionTime.toString());
    String path = "/api/v1/catalog/skus?keyword=Moonlit%20Terrace&supplyType=DOMESTIC_ON_HAND";
    ApiResponse sales = get(path, NORTH_SALES);

    assertThat(sales.status()).isEqualTo(200);
    JsonNode salesSupplies = sales.body().path("items").path(0).path("supplies");
    assertThat(salesSupplies.size()).isEqualTo(2);
    assertThat(salesSupplies.path(0).path("quantityUnit").asText()).isEqualTo("BOTTLE");
    assertThat(salesSupplies.path(1).path("quantityUnit").asText()).isEqualTo("CASE");
    assertThat(sales.raw()).doesNotContain("warehouseAllocationPriority", "warehouseVersion");

    ApiResponse filtered = get(path + "&quantityUnit=BOTTLE", NORTH_SALES);
    assertThat(filtered.body().path("items").path(0).path("supplies").size()).isEqualTo(1);
    assertThat(
            filtered
                .body()
                .path("items")
                .path(0)
                .path("supplies")
                .path(0)
                .path("quantityUnit")
                .asText())
        .isEqualTo("BOTTLE");

    ApiResponse exact = get(path, NORTH_ADMIN);
    JsonNode exactSupplies = exact.body().path("items").path(0).path("supplies");
    assertThat(Instant.parse(exact.body().path("dataAsOf").asText()))
        .isAfterOrEqualTo(priorityCorrectionTime);
    assertThat(exactSupplies.path(0).path("displayedAvailableQuantity").path("value").asText())
        .isEqualTo("10");
    assertThat(exactSupplies.path(0).path("exactLots").size()).isEqualTo(1);
    assertThat(
            exactSupplies
                .path(0)
                .path("exactLots")
                .path(0)
                .path("warehouseAllocationPriority")
                .asInt())
        .isEqualTo(10);
    assertThat(exactSupplies.path(0).path("exactLots").path(0).path("warehouseVersion").asLong())
        .isEqualTo(1);
    assertThat(
            exactSupplies
                .path(0)
                .path("exactLots")
                .path(0)
                .path("availableQuantity")
                .path("unit")
                .asText())
        .isEqualTo(exactSupplies.path(0).path("quantityUnit").asText());
    assertThat(exactSupplies.path(1).path("displayedAvailableQuantity").path("value").asText())
        .isEqualTo("56");
    assertThat(exactSupplies.path(1).path("exactLots").size()).isEqualTo(2);
  }

  @Test
  void enforcesBuyerDenialTenantIsolationAndInactiveHistoricalLookup() throws Exception {
    ApiResponse denied = get("/api/v1/catalog/skus", NORTH_BUYER);
    assertThat(denied.status()).isEqualTo(403);
    assertThat(denied.body().path("code").asText()).isEqualTo("ACCESS_DENIED");

    ApiResponse harbor = get("/api/v1/catalog/skus?keyword=Moonlit%20Terrace", HARBOR_MANAGER);
    assertThat(harbor.status()).isEqualTo(200);
    assertThat(harbor.body().path("items").size()).isEqualTo(1);
    assertThat(harbor.body().path("items").path(0).path("sku").path("skuCode").asText())
        .isEqualTo("HB-MTV-2019-750X6");
    assertThat(harbor.raw()).doesNotContain("CB-MTV-2019-750X6");
    ApiResponse crossTenant = get("/api/v1/catalog/skus/" + NORTH_SKU, HARBOR_MANAGER);
    assertThat(crossTenant.status()).isEqualTo(404);
    assertThat(crossTenant.raw()).doesNotContain(NORTH_SKU);

    ApiResponse inactiveSearch = get("/api/v1/catalog/skus?keyword=Archive", NORTH_SALES);
    assertThat(inactiveSearch.body().path("items").size()).isZero();
    ApiResponse inactiveById =
        get("/api/v1/catalog/skus/34000000-0000-4000-8000-000000000007", NORTH_SALES);
    assertThat(inactiveById.status()).isEqualTo(200);
    assertThat(inactiveById.body().path("sku").path("status").asText()).isEqualTo("INACTIVE");
  }

  @Test
  void bindsStableCursorsToFiltersSortAndTenant() throws Exception {
    String firstPath = "/api/v1/catalog/skus?automaticallyReservable=true&sort=name&pageSize=1";
    ApiResponse first = get(firstPath, NORTH_SALES);
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().path("pageInfo").path("hasNext").asBoolean()).isTrue();
    String firstCode = first.body().path("items").path(0).path("sku").path("skuCode").asText();
    String cursor = first.body().path("pageInfo").path("nextCursor").asText();

    ApiResponse second = get(firstPath + "&cursor=" + encoded(cursor), NORTH_SALES);
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.body().path("items").path(0).path("sku").path("skuCode").asText())
        .isNotEqualTo(firstCode);

    ApiResponse rebound =
        get(
            "/api/v1/catalog/skus?automaticallyReservable=false&sort=name&pageSize=1&cursor="
                + encoded(cursor),
            NORTH_SALES);
    assertThat(rebound.status()).isEqualTo(400);
    assertThat(rebound.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");
    ApiResponse unknownSort = get("/api/v1/catalog/skus?sort=search_text", NORTH_SALES);
    assertThat(unknownSort.status()).isEqualTo(400);
  }

  @Test
  void rejectsAuthenticationAndWildcardSearchSyntax() throws Exception {
    ApiResponse unauthenticated = get("/api/v1/catalog/skus", null);
    assertThat(unauthenticated.status()).isEqualTo(401);
    ApiResponse wildcard = get("/api/v1/catalog/skus?keyword=Moon*", NORTH_SALES);
    assertThat(wildcard.status()).isEqualTo(400);
    assertThat(wildcard.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void enforcesNvAndDatabaseSkuDefinitionAndPositiveDimensionConstraints() {
    Integer nvCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM catalog.sku WHERE tenant_id = ? AND vintage_code = 'NV'",
            Integer.class,
            UUID.fromString("10000000-0000-4000-8000-000000000001"));
    assertThat(nvCount).isPositive();

    assertThatThrownBy(
            () ->
                insertSku(
                    "34000000-0000-4000-8000-000000000099",
                    "CB-DUPLICATE-DEFINITION",
                    "2019",
                    750,
                    6,
                    "CASE"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                insertSku(
                    "34000000-0000-4000-8000-000000000098",
                    "CB-INVALID-DIMENSION",
                    "2024",
                    0,
                    6,
                    "CASE"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private ApiResponse get(String path, String token) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", "application/json")
            .GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response =
        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new ApiResponse(
        response.statusCode(),
        response.body().isBlank()
            ? jsonMapper.createObjectNode()
            : jsonMapper.readTree(response.body()),
        response.body());
  }

  private void insertSku(
      String id, String code, String vintage, int volumeMl, int unitsPerCase, String packageType) {
    jdbc.update(
        """
        INSERT INTO catalog.sku
            (id, tenant_id, product_id, code, vintage_code, volume_ml, units_per_case,
             package_type, status, search_text, activated_at,
             created_at, created_by, updated_at, updated_by, version)
        VALUES (?::uuid, '10000000-0000-4000-8000-000000000001',
                '33000000-0000-4000-8000-000000000001', ?, ?, ?, ?, ?, 'ACTIVE', ?,
                '2026-07-13T00:00:00Z', '2026-07-13T00:00:00Z',
                '11200000-0000-4000-8000-000000000004', '2026-07-13T00:00:00Z',
                '11200000-0000-4000-8000-000000000004', 1)
        """,
        id,
        code,
        vintage,
        volumeMl,
        unitsPerCase,
        packageType,
        "constraint fixture " + code.toLowerCase(java.util.Locale.ROOT));
  }

  private static String encoded(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static double ndcgAtFive(List<String> rankedCodes, Map<String, Integer> relevance) {
    double actual = 0;
    for (int index = 0; index < Math.min(5, rankedCodes.size()); index++) {
      int grade = relevance.getOrDefault(rankedCodes.get(index), 0);
      actual += (Math.pow(2, grade) - 1) / logTwo(index + 2);
    }
    List<Integer> ideal =
        relevance.values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
    double best = 0;
    for (int index = 0; index < Math.min(5, ideal.size()); index++) {
      best += (Math.pow(2, ideal.get(index)) - 1) / logTwo(index + 2);
    }
    return best == 0 ? 1 : actual / best;
  }

  private static int firstRelevantRank(List<String> rankedCodes, Map<String, Integer> relevance) {
    for (int index = 0; index < Math.min(5, rankedCodes.size()); index++) {
      if (relevance.containsKey(rankedCodes.get(index))) return index + 1;
    }
    return 0;
  }

  private static List<String> gradedExpected(Map<String, Integer> relevance) {
    return relevance.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + ":" + entry.getValue())
        .toList();
  }

  private static double logTwo(int value) {
    return Math.log(value) / Math.log(2);
  }

  record GoldQuery(
      String name, String keyword, Map<String, Integer> relevance, String requiredFirst) {}

  record ApiResponse(int status, JsonNode body, String raw) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class TestTokenConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
      return token ->
          switch (token) {
            case NORTH_SALES -> jwt(token, "11000000-0000-4000-8000-000000000001", "north-cellars");
            case NORTH_BUYER -> jwt(token, "11000000-0000-4000-8000-000000000002", "north-cellars");
            case NORTH_ADMIN -> jwt(token, "11000000-0000-4000-8000-000000000004", "north-cellars");
            case NORTH_WAREHOUSE ->
                jwt(token, "11000000-0000-4000-8000-000000000006", "north-cellars");
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
