package com.rom.cellarbridge.partner;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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
class PartnerApiIntegrationTest extends PostgresIntegrationTestSupport {

  private static final String NORTH_SALES = "north-sales-token";
  private static final String NORTH_MANAGER = "north-manager-token";
  private static final String NORTH_ADMIN = "north-admin-token";
  private static final String HARBOR_MANAGER = "harbor-manager-token";
  private static final String NORTH_SALES_USER_ID = "11200000-0000-4000-8000-000000000001";

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JsonMapper jsonMapper;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void salesSubmitsManagerActivatesAndEligibilityHistoryRemainsTraceable() throws Exception {
    ApiResponse created =
        create(NORTH_SALES, "Cedar Table Group 301", "Cedar Table 301", "REG-API-301", true, null);
    assertThat(created.status()).isEqualTo(201);
    String partnerId = created.body().path("id").asText();
    assertThat(created.body().path("registrationIdentifierMasked").asText()).endsWith("I301");
    assertThat(created.raw()).doesNotContain("REG-API-301");

    ApiResponse submitted =
        post("/api/v1/partners/" + partnerId + "/submission", NORTH_SALES, created.etag(), null);
    assertThat(submitted.status()).isEqualTo(200);
    assertThat(submitted.body().path("status").asText()).isEqualTo("PENDING_REVIEW");

    ApiResponse approved =
        post(
            "/api/v1/partners/" + partnerId + "/review",
            NORTH_MANAGER,
            submitted.etag(),
            """
            {
              "decision": "APPROVE",
              "reason": "Commercial profile verified",
              "approvedPaymentTermDays": 45,
              "approvedCreditLimit": {"amount": "50000.00", "currency": "CNY"},
              "approvedRouteCodes": ["SH_GENERAL_TRADE", "NB_BONDED_B2B"],
              "approvedServiceRegions": ["CN-SH", "CN-ZJ"],
              "approvedCurrencies": ["CNY"]
            }
            """);
    assertThat(approved.status()).isEqualTo(200);
    assertThat(approved.body().path("status").asText()).isEqualTo("ACTIVE");

    ApiResponse detail = get("/api/v1/partners/" + partnerId, NORTH_SALES);
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body().path("eligibility").path("version").asInt()).isEqualTo(1);
    assertThat(detail.body().path("eligibility").path("paymentTermDays").asInt()).isEqualTo(45);
    assertThat(detail.body().path("timeline").size()).isEqualTo(3);
    assertThat(detail.body().path("allowedActions").toString()).doesNotContain("SUBMIT");

    ApiResponse approvedRoute = get("/api/v1/partners?routeCode=NB_BONDED_B2B", NORTH_SALES);
    assertThat(approvedRoute.body().path("items").path(0).path("id").asText()).isEqualTo(partnerId);
    ApiResponse unapprovedRoute = get("/api/v1/partners?routeCode=HK_FREE_TRADE", NORTH_SALES);
    assertThat(unapprovedRoute.body().path("items").size()).isZero();

    UUID id = UUID.fromString(partnerId);
    assertThat(count("partner.eligibility_version", id)).isEqualTo(1);
    assertThat(count("partner.review_decision", id)).isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM partner.local_event_publication WHERE partner_id = ? ORDER BY occurred_at",
                String.class,
                id))
        .containsExactly("PartnerSubmittedForReviewV1", "PartnerActivatedV1");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM partner.review_work_item WHERE partner_id = ?",
                String.class,
                id))
        .isEqualTo("COMPLETED");
    assertThat(
            String.join(
                "|",
                jdbc.queryForList(
                    "SELECT changed_fields::text FROM partner.audit_entry WHERE partner_id = ?",
                    String.class,
                    id)))
        .doesNotContain("REG-API-301")
        .doesNotContain("partner301@example.test");
  }

  @Test
  void rejectsSelfReviewAndIncompleteSubmission() throws Exception {
    ApiResponse selfOwned =
        create(NORTH_ADMIN, "Juniper Kitchens 302", "Juniper 302", "REG-API-302", true, null);
    String selfOwnedId = selfOwned.body().path("id").asText();
    ApiResponse submitted =
        post(
            "/api/v1/partners/" + selfOwnedId + "/submission", NORTH_ADMIN, selfOwned.etag(), null);
    ApiResponse selfReview =
        post(
            "/api/v1/partners/" + selfOwnedId + "/review",
            NORTH_ADMIN,
            submitted.etag(),
            review("APPROVE", "Profile verified independently"));
    assertThat(selfReview.status()).isEqualTo(409);
    assertThat(selfReview.body().path("code").asText()).isEqualTo("PARTNER_REVIEWER_CONFLICT");

    ApiResponse incomplete =
        create(NORTH_SALES, "Maple Hospitality 302", "Maple 302", null, false, null);
    assertThat(incomplete.status()).withFailMessage(incomplete.raw()).isEqualTo(201);
    ApiResponse incompleteSubmission =
        post(
            "/api/v1/partners/" + incomplete.body().path("id").asText() + "/submission",
            NORTH_SALES,
            incomplete.etag(),
            null);
    assertThat(incompleteSubmission.status()).isEqualTo(422);
    assertThat(incompleteSubmission.body().path("code").asText())
        .isEqualTo("PARTNER_PROFILE_INCOMPLETE");
    assertThat(incompleteSubmission.body().path("errors").toString())
        .contains("registrationIdentifier", "requestedRouteCodes", "requestedServiceRegions");
  }

  @Test
  void persistsAnEmptyDraftAndReportsEveryMissingSubmissionField() throws Exception {
    ApiResponse created = post("/api/v1/partners", NORTH_SALES, null, "{}");

    assertThat(created.status()).withFailMessage(created.raw()).isEqualTo(201);
    assertThat(created.body().path("status").asText()).isEqualTo("DRAFT");
    assertThat(created.body().path("legalName").isNull()).isTrue();
    assertThat(created.body().path("displayName").isNull()).isTrue();
    assertThat(created.body().path("type").isNull()).isTrue();
    assertThat(created.body().path("defaultCurrency").isNull()).isTrue();
    assertThat(created.body().path("contacts").size()).isZero();
    assertThat(created.body().path("billingAddress").isNull()).isTrue();

    ApiResponse reloaded =
        get("/api/v1/partners/" + created.body().path("id").asText(), NORTH_SALES);
    assertThat(reloaded.status()).isEqualTo(200);
    assertThat(reloaded.body().path("legalName").isNull()).isTrue();

    ApiResponse submitted =
        post(
            "/api/v1/partners/" + created.body().path("id").asText() + "/submission",
            NORTH_SALES,
            created.etag(),
            null);
    assertThat(submitted.status()).isEqualTo(422);
    assertThat(submitted.body().path("code").asText()).isEqualTo("PARTNER_PROFILE_INCOMPLETE");
    assertThat(submitted.body().path("errors").toString())
        .contains(
            "legalName",
            "displayName",
            "registrationIdentifier",
            "type",
            "defaultCurrency",
            "contact",
            "billingAddress",
            "requestedPaymentTermDays",
            "requestedRouteCodes",
            "requestedServiceRegions",
            "requestedCurrencies");
  }

  @Test
  void suspensionAndReapprovalAppendAnEligibilityVersion() throws Exception {
    ApiResponse created =
        create(NORTH_SALES, "Aspen Hospitality 305", "Aspen 305", "REG-API-305", true, null);
    String partnerId = created.body().path("id").asText();
    ApiResponse submitted =
        post("/api/v1/partners/" + partnerId + "/submission", NORTH_SALES, created.etag(), null);
    ApiResponse firstApproval =
        post(
            "/api/v1/partners/" + partnerId + "/review",
            NORTH_MANAGER,
            submitted.etag(),
            review("APPROVE", "Initial commercial profile verified"));
    ApiResponse suspended =
        post(
            "/api/v1/partners/" + partnerId + "/suspension",
            NORTH_MANAGER,
            firstApproval.etag(),
            "{\"reason\":\"Commercial documents expired\"}");
    ApiResponse reactivation =
        post(
            "/api/v1/partners/" + partnerId + "/reactivation",
            NORTH_SALES,
            suspended.etag(),
            "{\"reason\":\"Commercial documents renewed\"}");
    ApiResponse secondApproval =
        post(
            "/api/v1/partners/" + partnerId + "/review",
            NORTH_MANAGER,
            reactivation.etag(),
            """
            {
              "decision": "APPROVE",
              "reason": "Renewed profile verified",
              "approvedPaymentTermDays": 60,
              "approvedRouteCodes": ["NB_BONDED_B2B"],
              "approvedServiceRegions": ["CN-ZJ"],
              "approvedCurrencies": ["CNY"]
            }
            """);

    assertThat(secondApproval.status()).isEqualTo(200);
    ApiResponse detail = get("/api/v1/partners/" + partnerId, NORTH_SALES);
    assertThat(detail.body().path("status").asText()).isEqualTo("ACTIVE");
    assertThat(detail.body().path("eligibility").path("version").asInt()).isEqualTo(2);
    assertThat(detail.body().path("eligibility").path("paymentTermDays").asInt()).isEqualTo(60);
    assertThat(
            jdbc.queryForList(
                """
                SELECT payment_term_days
                  FROM partner.eligibility_version
                 WHERE partner_id = ?
                 ORDER BY eligibility_version
                """,
                Integer.class,
                UUID.fromString(partnerId)))
        .containsExactly(30, 60);
    assertThat(
            jdbc.queryForList(
                """
                SELECT event_type
                  FROM partner.local_event_publication
                 WHERE partner_id = ?
                 ORDER BY occurred_at
                """,
                String.class,
                UUID.fromString(partnerId)))
        .containsExactly(
            "PartnerSubmittedForReviewV1",
            "PartnerActivatedV1",
            "PartnerSuspendedV1",
            "PartnerSubmittedForReviewV1",
            "PartnerActivatedV1");
  }

  @Test
  void enforcesDuplicateConcurrencyOwnershipAndTenantBoundaries() throws Exception {
    ApiResponse original =
        create(NORTH_ADMIN, "Willow Dining 303", "Willow 303", "REG-API-303", true, null);
    String partnerId = original.body().path("id").asText();

    ApiResponse duplicateIdentifier =
        create(NORTH_SALES, "Willow Dining East 303", "Willow East 303", "REG API 303", true, null);
    assertThat(duplicateIdentifier.status()).isEqualTo(409);
    assertThat(duplicateIdentifier.body().path("code").asText())
        .isEqualTo("PARTNER_DUPLICATE_IDENTIFIER");

    ApiResponse deniedEdit =
        patch(
            "/api/v1/partners/" + partnerId,
            NORTH_SALES,
            original.etag(),
            "{\"displayName\":\"Changed by another owner\"}");
    assertThat(deniedEdit.status()).isEqualTo(403);
    assertThat(deniedEdit.body().path("code").asText()).isEqualTo("ACCESS_DENIED");

    ApiResponse missingPrecondition =
        patch(
            "/api/v1/partners/" + partnerId,
            NORTH_ADMIN,
            null,
            "{\"displayName\":\"Willow Revised\"}");
    assertThat(missingPrecondition.status()).isEqualTo(428);
    assertThat(missingPrecondition.body().path("code").asText()).isEqualTo("PRECONDITION_REQUIRED");

    ApiResponse changed =
        patch(
            "/api/v1/partners/" + partnerId,
            NORTH_ADMIN,
            original.etag(),
            "{\"displayName\":\"Willow Revised\"}");
    assertThat(changed.status()).isEqualTo(200);
    ApiResponse stale =
        patch(
            "/api/v1/partners/" + partnerId,
            NORTH_ADMIN,
            original.etag(),
            "{\"displayName\":\"Willow Stale\"}");
    assertThat(stale.status()).isEqualTo(412);
    assertThat(stale.body().path("currentVersion").asLong()).isEqualTo(1);
    assertThat(stale.body().path("currentState").asText()).isEqualTo("DRAFT");

    ApiResponse crossTenant = get("/api/v1/partners/" + partnerId, HARBOR_MANAGER);
    assertThat(crossTenant.status()).isEqualTo(404);
    assertThat(crossTenant.raw()).doesNotContain(partnerId);
    ApiResponse harborList = get("/api/v1/partners?keyword=Willow303", HARBOR_MANAGER);
    assertThat(harborList.status()).isEqualTo(200);
    assertThat(harborList.body().path("items").size()).isZero();
  }

  @Test
  void requiresDuplicateNotesAndBindsOpaqueCursorsToFiltersAndTenants() throws Exception {
    ApiResponse firstDuplicate =
        create(NORTH_SALES, "Elm House Group 304", "Elm House North", "REG-API-304-A", true, null);
    ApiResponse firstSubmitted =
        post(
            "/api/v1/partners/" + firstDuplicate.body().path("id").asText() + "/submission",
            NORTH_SALES,
            firstDuplicate.etag(),
            null);
    assertThat(firstSubmitted.status()).isEqualTo(200);

    ApiResponse secondDuplicate =
        create(NORTH_SALES, "Elm House Group 304", "Elm House South", "REG-API-304-B", true, null);
    String secondId = secondDuplicate.body().path("id").asText();
    ApiResponse duplicateBlocked =
        post(
            "/api/v1/partners/" + secondId + "/submission",
            NORTH_SALES,
            secondDuplicate.etag(),
            null);
    assertThat(duplicateBlocked.status()).isEqualTo(409);
    assertThat(duplicateBlocked.body().path("code").asText())
        .isEqualTo("PARTNER_POTENTIAL_DUPLICATE");
    ApiResponse documented =
        patch(
            "/api/v1/partners/" + secondId,
            NORTH_SALES,
            secondDuplicate.etag(),
            "{\"duplicateResolutionNote\":\"Separate legal entity and service territory\"}");
    ApiResponse documentedSubmission =
        post("/api/v1/partners/" + secondId + "/submission", NORTH_SALES, documented.etag(), null);
    assertThat(documentedSubmission.status()).isEqualTo(200);

    create(NORTH_SALES, "River Group 304 One", "River One", "REG-API-304-C", true, null);
    create(NORTH_SALES, "River Group 304 Two", "River Two", "REG-API-304-D", true, null);
    String filter =
        "/api/v1/partners?keyword=River%20Group%20304&status=DRAFT&ownerId="
            + NORTH_SALES_USER_ID
            + "&pageSize=1";
    ApiResponse firstPage = get(filter, NORTH_SALES);
    assertThat(firstPage.status()).isEqualTo(200);
    assertThat(firstPage.body().path("items").size()).isEqualTo(1);
    assertThat(firstPage.body().path("pageInfo").path("hasNext").asBoolean()).isTrue();
    String cursor = firstPage.body().path("pageInfo").path("nextCursor").asText();
    ApiResponse secondPage =
        get(filter + "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8), NORTH_SALES);
    assertThat(secondPage.status()).isEqualTo(200);
    assertThat(secondPage.body().path("items").size()).isEqualTo(1);
    assertThat(secondPage.body().path("items").path(0).path("id").asText())
        .isNotEqualTo(firstPage.body().path("items").path(0).path("id").asText());

    ApiResponse reboundCursor =
        get(
            "/api/v1/partners?keyword=Different&pageSize=1&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8),
            NORTH_SALES);
    assertThat(reboundCursor.status()).isEqualTo(400);
    assertThat(reboundCursor.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");
    ApiResponse crossTenantCursor =
        get(
            "/api/v1/partners?keyword=River%20Group%20304&status=DRAFT&ownerId="
                + NORTH_SALES_USER_ID
                + "&pageSize=1&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8),
            HARBOR_MANAGER);
    assertThat(crossTenantCursor.status()).isEqualTo(400);
  }

  @Test
  void partnerEndpointsRequireAuthentication() throws Exception {
    ApiResponse response = get("/api/v1/partners", null);
    assertThat(response.status()).isEqualTo(401);
    assertThat(response.body().path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
  }

  @Test
  void rejectsUnsupportedRoutesAndMalformedFilterValuesAsValidationProblems() throws Exception {
    ApiResponse unsupportedRoute = get("/api/v1/partners?routeCode=DIRECT", NORTH_SALES);
    assertThat(unsupportedRoute.status()).isEqualTo(400);
    assertThat(unsupportedRoute.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");

    ApiResponse malformedOwner = get("/api/v1/partners?ownerId=not-a-uuid", NORTH_SALES);
    assertThat(malformedOwner.status()).isEqualTo(400);
    assertThat(malformedOwner.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");

    ApiResponse malformedBody =
        post("/api/v1/partners", NORTH_SALES, null, "{\"type\":\"UNKNOWN\"}");
    assertThat(malformedBody.status()).isEqualTo(400);
    assertThat(malformedBody.body().path("code").asText()).isEqualTo("MALFORMED_REQUEST");
  }

  private int count(String table, UUID partnerId) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE partner_id = ?", Integer.class, partnerId);
    return value == null ? 0 : value;
  }

  private ApiResponse create(
      String token,
      String legalName,
      String displayName,
      String registrationIdentifier,
      boolean complete,
      String duplicateNote)
      throws Exception {
    String registration =
        registrationIdentifier == null ? "null" : "\"" + registrationIdentifier + "\"";
    String eligibility =
        complete
            ? """
              "requestedPaymentTermDays": 30,
              "requestedRouteCodes": ["SH_GENERAL_TRADE"],
              "requestedServiceRegions": ["CN-SH"],
              "requestedCurrencies": ["CNY"],
              """
            : "";
    String note = duplicateNote == null ? "null" : "\"" + duplicateNote + "\"";
    String body =
        """
        {
          "legalName": "%s",
          "displayName": "%s",
          "registrationIdentifier": %s,
          "type": "RESTAURANT_GROUP",
          "defaultCurrency": "CNY",
          %s
          "contact": {
            "name": "Lin Wen",
            "email": "partner301@example.test",
            "phone": "13800003001",
            "primary": true
          },
          "billingAddress": {
            "countryCode": "CN",
            "province": "Shanghai",
            "city": "Shanghai",
            "district": "Xuhui",
            "line1": "301 Huaihai Road",
            "postalCode": "200030"
          },
          "duplicateResolutionNote": %s
        }
        """
            .formatted(legalName, displayName, registration, eligibility, note);
    return post("/api/v1/partners", token, null, body);
  }

  private static String review(String decision, String reason) {
    return """
        {"decision":"%s","reason":"%s"}
        """
        .formatted(decision, reason);
  }

  private ApiResponse get(String path, String token) throws Exception {
    return send("GET", path, token, null, null, MediaType.APPLICATION_JSON_VALUE);
  }

  private ApiResponse post(String path, String token, String etag, String body) throws Exception {
    return send("POST", path, token, etag, body, MediaType.APPLICATION_JSON_VALUE);
  }

  private ApiResponse patch(String path, String token, String etag, String body) throws Exception {
    return send("PATCH", path, token, etag, body, "application/merge-patch+json");
  }

  private ApiResponse send(
      String method, String path, String token, String etag, String body, String contentType)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", MediaType.APPLICATION_JSON_VALUE);
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (etag != null) request.header("If-Match", etag);
    if (body != null) request.header("Content-Type", contentType);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response =
        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new ApiResponse(
        response.statusCode(),
        response.headers(),
        response.body().isBlank()
            ? jsonMapper.createObjectNode()
            : jsonMapper.readTree(response.body()),
        response.body());
  }

  record ApiResponse(int status, HttpHeaders headers, JsonNode body, String raw) {
    String etag() {
      return headers.firstValue("etag").orElseThrow();
    }
  }

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
            case NORTH_ADMIN -> jwt(token, "11000000-0000-4000-8000-000000000004", "north-cellars");
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
