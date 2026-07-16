package com.rom.cellarbridge.quotation;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.quotation.internal.application.QuotationExpirationService;
import com.rom.cellarbridge.quotation.internal.application.QuotationRepository.ExpirationWorkItem;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "cellarbridge.quotation.expiration.enabled=false")
class CustomerQuotationApiIntegrationTest extends PostgresIntegrationTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-14T12:00:00Z");
  private static final String NORTH_SALES = "customer-quotation-north-sales-token";
  private static final String PARTNER = "53000000-0000-4000-8000-000000000001";
  private static final String SKU = "34000000-0000-4000-8000-000000000001";
  private static final String ACCEPTED_EVENT = "cellarbridge.quotation.accepted.v1";
  private static final Set<String> PUBLIC_QUOTATION_FIELDS =
      Set.of(
          "number",
          "revision",
          "supplierPublicId",
          "supplierDisplayName",
          "customerPublicId",
          "customerDisplayName",
          "status",
          "expiresAt",
          "lines",
          "subtotal",
          "fees",
          "total",
          "deliveryOption",
          "paymentTermDays",
          "termsVersion",
          "termsSummary",
          "allowedActions",
          "orderId",
          "orderNumber",
          "orderCreationStatus",
          "decisionReceipt");
  private static final Set<String> INTERNAL_FIELDS =
      Set.of(
          "id",
          "tenantId",
          "partnerId",
          "revisionId",
          "ownerId",
          "submittedById",
          "version",
          "createdAt",
          "updatedAt",
          "costUnitPrice",
          "totalCost",
          "estimatedMarginRate",
          "score",
          "inputHash",
          "policyVersion",
          "routeEvaluationId",
          "approvalRequirements",
          "approvals",
          "actorId",
          "lotId",
          "tokenHash");

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JsonMapper jsonMapper;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private DataSource dataSource;

  @Autowired private MutableClock clock;

  @Autowired private QuotationExpirationService expirationService;

  @BeforeEach
  void resetClock() {
    clock.set(BASE);
  }

  @Test
  void acceptsWithReplayConflictCustomerSafeDtoAndTerminalReceipt() throws Exception {
    IssuedQuotation quotation = issue(BASE.plusSeconds(20 * 86_400L));
    ApiResponse initial = get(quotation.publicPath());
    assertThat(initial.status()).withFailMessage(initial.raw()).isEqualTo(200);
    assertThat(initial.body().propertyNames())
        .containsExactlyInAnyOrderElementsOf(PUBLIC_QUOTATION_FIELDS);
    assertThat(initial.body().path("lines").path(0).propertyNames())
        .containsExactlyInAnyOrder(
            "skuCode", "description", "vintage", "package", "quantity", "unitPrice", "lineTotal");
    assertThat(initial.body().path("lines").path(0).path("quantity").propertyNames())
        .containsExactlyInAnyOrder("value", "unit");
    assertThat(initial.body().path("subtotal").propertyNames())
        .containsExactlyInAnyOrder("amount", "currency");
    assertThat(initial.body().path("deliveryOption").propertyNames())
        .containsExactlyInAnyOrder("label", "estimatedWindow");
    assertThat(initial.body().path("allowedActions"))
        .extracting(JsonNode::asText)
        .containsExactly("ACCEPT", "REJECT");
    assertThat(allPropertyNames(initial.body())).doesNotContainAnyElementsOf(INTERNAL_FIELDS);
    assertThat(initial.headers().firstValue("cache-control").orElse("")).contains("no-store");

    String termsVersion = initial.body().path("termsVersion").asText();
    ApiResponse unknownField =
        decision(
            quotation.publicPath() + "/acceptance",
            "accept-unknown-field-0001",
            """
            {"acceptedTermsVersion":"%s","unexpected":"value"}
            """
                .formatted(termsVersion)
                .strip());
    assertThat(unknownField.status()).isEqualTo(400);
    assertThat(unknownField.body().path("code").asText()).isEqualTo("MALFORMED_REQUEST");

    ApiResponse wrongTerms =
        decision(
            quotation.publicPath() + "/acceptance",
            "accept-wrong-terms-version-0001",
            acceptanceBody("TERMS-OUTDATED", "PO-2026-0714-00"));
    assertThat(wrongTerms.status()).isEqualTo(409);
    assertThat(wrongTerms.body().path("code").asText()).isEqualTo("QUOTE_TERMS_VERSION_MISMATCH");
    assertDecisionAndAcceptedEventCounts(quotation.id(), 0, 0);

    String firstBody = acceptanceBody(termsVersion, "PO-2026-0714-01");
    ApiResponse accepted =
        decision(quotation.publicPath() + "/acceptance", "accept-terminal-replay-0001", firstBody);
    assertThat(accepted.status()).withFailMessage(accepted.raw()).isEqualTo(201);
    assertThat(accepted.body().path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(accepted.body().path("replayed").asBoolean()).isFalse();
    assertThat(accepted.headers().firstValue("idempotency-replayed").orElse("")).isEqualTo("false");

    ApiResponse replay =
        decision(quotation.publicPath() + "/acceptance", "accept-terminal-replay-0001", firstBody);
    assertThat(replay.status()).withFailMessage(replay.raw()).isEqualTo(200);
    assertThat(replay.body().path("acceptanceId").asText())
        .isEqualTo(accepted.body().path("acceptanceId").asText());
    assertThat(replay.body().path("acceptedAt").asText())
        .isEqualTo(accepted.body().path("acceptedAt").asText());
    assertThat(replay.body().path("replayed").asBoolean()).isTrue();
    assertThat(replay.headers().firstValue("idempotency-replayed").orElse("")).isEqualTo("true");

    ApiResponse reusedForAnotherPayload =
        decision(
            quotation.publicPath() + "/acceptance",
            "accept-terminal-replay-0001",
            acceptanceBody(termsVersion, "PO-2026-0714-02"));
    assertThat(reusedForAnotherPayload.status()).isEqualTo(409);
    assertThat(reusedForAnotherPayload.body().path("code").asText())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");

    ApiResponse terminal = get(quotation.publicPath());
    assertThat(terminal.status()).isEqualTo(200);
    assertThat(terminal.body().path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(terminal.body().path("allowedActions")).isEmpty();
    assertThat(terminal.body().path("decisionReceipt").propertyNames())
        .containsExactlyInAnyOrder("decisionId", "decision", "decidedAt", "reference");
    assertThat(terminal.body().path("decisionReceipt").path("decisionId").asText())
        .isEqualTo(accepted.body().path("acceptanceId").asText());
    assertThat(terminal.body().path("decisionReceipt").path("decision").asText())
        .isEqualTo("ACCEPTED");
    assertThat(terminal.body().path("decisionReceipt").path("reference").asText())
        .isEqualTo("PO-2026-0714-01");
    assertDecisionAndAcceptedEventCounts(quotation.id(), 1, 1);
  }

  @Test
  void rejectsWithReplayThenPreventsAcceptanceAndReturnsTerminalReceipt() throws Exception {
    IssuedQuotation quotation = issue(BASE.plusSeconds(20 * 86_400L));
    String termsVersion = get(quotation.publicPath()).body().path("termsVersion").asText();
    String rejectionBody = "{\"reasonCategory\":\"DELIVERY_TIMING\"}";

    ApiResponse rejected =
        decision(
            quotation.publicPath() + "/rejection", "reject-terminal-replay-0001", rejectionBody);
    assertThat(rejected.status()).withFailMessage(rejected.raw()).isEqualTo(201);
    assertThat(rejected.body().path("status").asText()).isEqualTo("REJECTED_BY_CUSTOMER");
    assertThat(rejected.body().path("reasonCategory").asText()).isEqualTo("DELIVERY_TIMING");

    ApiResponse replay =
        decision(
            quotation.publicPath() + "/rejection", "reject-terminal-replay-0001", rejectionBody);
    assertThat(replay.status()).isEqualTo(200);
    assertThat(replay.body().path("rejectionId").asText())
        .isEqualTo(rejected.body().path("rejectionId").asText());
    assertThat(replay.body().path("replayed").asBoolean()).isTrue();

    ApiResponse acceptAfterReject =
        decision(
            quotation.publicPath() + "/acceptance",
            "accept-after-rejection-0001",
            acceptanceBody(termsVersion, "PO-2026-0714-05"));
    assertThat(acceptAfterReject.status()).isEqualTo(409);
    assertThat(acceptAfterReject.body().path("code").asText()).isEqualTo("QUOTE_ALREADY_DECIDED");

    ApiResponse terminal = get(quotation.publicPath());
    assertThat(terminal.body().path("status").asText()).isEqualTo("REJECTED_BY_CUSTOMER");
    assertThat(terminal.body().path("allowedActions")).isEmpty();
    assertThat(terminal.body().path("decisionReceipt").path("decisionId").asText())
        .isEqualTo(rejected.body().path("rejectionId").asText());
    assertThat(terminal.body().path("decisionReceipt").path("decision").asText())
        .isEqualTo("REJECTED_BY_CUSTOMER");
    assertThat(terminal.body().path("decisionReceipt").path("reference").asText())
        .isEqualTo("DELIVERY_TIMING");
    assertDecisionAndAcceptedEventCounts(quotation.id(), 1, 0);
  }

  @Test
  void omitsAnAbsentOptionalReferenceFromThePublicReceipt() throws Exception {
    IssuedQuotation quotation = issue(BASE.plusSeconds(20 * 86_400L));
    ApiResponse rejected =
        decision(
            quotation.publicPath() + "/rejection", "reject-without-optional-reason-0001", "{}");
    assertThat(rejected.status()).withFailMessage(rejected.raw()).isEqualTo(201);
    assertThat(rejected.body().path("reasonCategory").isNull()).isTrue();

    ApiResponse terminal = get(quotation.publicPath());
    assertThat(terminal.body().path("decisionReceipt").propertyNames())
        .containsExactlyInAnyOrder("decisionId", "decision", "decidedAt");
  }

  @Test
  void keepsLegacyIssuedRevisionsViewOnlyAndRejectsBothCustomerDecisions() throws Exception {
    IssuedQuotation quotation = issue(BASE.plusSeconds(20 * 86_400L));
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
        quotation.id());

    ApiResponse view = get(quotation.publicPath());
    assertThat(view.status()).withFailMessage(view.raw()).isEqualTo(200);
    assertThat(view.body().path("allowedActions")).isEmpty();
    assertThat(allPropertyNames(view.body())).doesNotContainAnyElementsOf(INTERNAL_FIELDS);

    ApiResponse acceptance =
        decision(
            quotation.publicPath() + "/acceptance",
            "accept-legacy-view-only-0001",
            acceptanceBody(view.body().path("termsVersion").asText(), "PO-LEGACY-001"));
    assertThat(acceptance.status()).withFailMessage(acceptance.raw()).isEqualTo(409);
    assertThat(acceptance.body().path("code").asText()).isEqualTo("QUOTE_SUPPLY_DECISION_REQUIRED");

    ApiResponse rejection =
        decision(
            quotation.publicPath() + "/rejection",
            "reject-legacy-view-only-0001",
            "{\"reasonCategory\":\"OTHER\"}");
    assertThat(rejection.status()).withFailMessage(rejection.raw()).isEqualTo(409);
    assertThat(rejection.body().path("code").asText()).isEqualTo("QUOTE_SUPPLY_DECISION_REQUIRED");
    assertDecisionAndAcceptedEventCounts(quotation.id(), 0, 0);
  }

  @Test
  void rollsBackTheDecisionWhenReliablePublicationFailsThenAllowsOneRetry() throws Exception {
    IssuedQuotation quotation = issue(BASE.plusSeconds(20 * 86_400L));
    String termsVersion = get(quotation.publicPath()).body().path("termsVersion").asText();
    String body = acceptanceBody(termsVersion, "PO-2026-0714-06");
    String key = "accept-after-publisher-recovery-0001";
    jdbc.execute(
        """
        CREATE FUNCTION platform_event.reject_test_publication()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          IF NEW.event_type = 'cellarbridge.quotation.accepted.v1' THEN
            RAISE EXCEPTION 'test publication failure';
          END IF;
          RETURN NEW;
        END;
        $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER reject_test_publication
        BEFORE INSERT ON platform_event.event_publication
        FOR EACH ROW EXECUTE FUNCTION platform_event.reject_test_publication()
        """);
    try {
      ApiResponse failed = decision(quotation.publicPath() + "/acceptance", key, body);
      assertThat(failed.status()).isEqualTo(503);
      assertThat(failed.body().path("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    } finally {
      jdbc.execute(
          "DROP TRIGGER IF EXISTS reject_test_publication ON platform_event.event_publication");
      jdbc.execute("DROP FUNCTION IF EXISTS platform_event.reject_test_publication()");
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM quotation.quotation WHERE id = ?::uuid",
                String.class,
                quotation.id()))
        .isEqualTo("SENT");
    assertDecisionAndAcceptedEventCounts(quotation.id(), 0, 0);
    assertThat(idempotencyCount(quotation.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM quotation.audit_entry
                 WHERE quotation_id = ?::uuid
                   AND action = 'QUOTATION_ACCEPTED_BY_CUSTOMER'
                """,
                Integer.class,
                quotation.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                SELECT status
                  FROM quotation.expiration_work_item
                 WHERE quotation_id = ?::uuid
                """,
                String.class,
                quotation.id()))
        .isEqualTo("PENDING");

    ApiResponse recovered = decision(quotation.publicPath() + "/acceptance", key, body);
    assertThat(recovered.status()).withFailMessage(recovered.raw()).isEqualTo(201);
    assertDecisionAndAcceptedEventCounts(quotation.id(), 1, 1);
    assertThat(idempotencyCount(quotation.id())).isEqualTo(1);
  }

  @Test
  void serializesTwentyConcurrentAcceptancesForSharedAndDistinctKeys() throws Exception {
    IssuedQuotation sharedKeyQuote = issue(BASE.plusSeconds(20 * 86_400L));
    String sharedTerms = get(sharedKeyQuote.publicPath()).body().path("termsVersion").asText();
    List<ApiResponse> sharedKeyResults =
        concurrentAcceptances(
            sharedKeyQuote,
            sharedTerms,
            index -> "accept-concurrent-shared-0001",
            "PO-2026-0714-03");
    assertConcurrentDecisionResults(sharedKeyResults);
    assertDecisionAndAcceptedEventCounts(sharedKeyQuote.id(), 1, 1);
    assertThat(idempotencyCount(sharedKeyQuote.id())).isEqualTo(1);

    IssuedQuotation distinctKeyQuote = issue(BASE.plusSeconds(20 * 86_400L));
    String distinctTerms = get(distinctKeyQuote.publicPath()).body().path("termsVersion").asText();
    List<ApiResponse> distinctKeyResults =
        concurrentAcceptances(
            distinctKeyQuote,
            distinctTerms,
            index -> "accept-concurrent-distinct-%02d-key".formatted(index),
            "PO-2026-0714-04");
    assertConcurrentDecisionResults(distinctKeyResults);
    assertDecisionAndAcceptedEventCounts(distinctKeyQuote.id(), 1, 1);
    assertThat(idempotencyCount(distinctKeyQuote.id())).isEqualTo(20);
  }

  @Test
  void enforcesTokenHashRevocationExpiryAndQuotationBinding() throws Exception {
    IssuedQuotation hashedQuote = issue(BASE.plusSeconds(20 * 86_400L));
    String storedHash =
        jdbc.queryForObject(
            "SELECT token_hash FROM quotation.portal_access WHERE quotation_id = ?::uuid",
            String.class,
            hashedQuote.id());
    assertThat(storedHash).matches("^[0-9a-f]{64}$").isNotEqualTo(hashedQuote.token());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM quotation.portal_access WHERE token_hash = ?",
                Integer.class,
                hashedQuote.token()))
        .isZero();

    IssuedQuotation revokedQuote = issue(BASE.plusSeconds(20 * 86_400L));
    assertThat(
            jdbc.update(
                "UPDATE quotation.portal_access SET revoked_at = ? WHERE quotation_id = ?::uuid",
                Timestamp.from(clock.instant()),
                revokedQuote.id()))
        .isEqualTo(1);
    assertUnavailable(revokedQuote.publicPath());

    IssuedQuotation bindingQuote = issue(BASE.plusSeconds(20 * 86_400L));
    assertThat(
            jdbc.update(
                "UPDATE quotation.portal_access SET partner_id = ?::uuid WHERE quotation_id = ?::uuid",
                UUID.randomUUID(),
                bindingQuote.id()))
        .isEqualTo(1);
    assertUnavailable(bindingQuote.publicPath());

    Instant quotationDeadline =
        timestamp(
            "SELECT quotation_expires_at FROM quotation.portal_access WHERE quotation_id = ?::uuid",
            hashedQuote.id());
    Instant tokenDeadline =
        timestamp(
            "SELECT expires_at FROM quotation.portal_access WHERE quotation_id = ?::uuid",
            hashedQuote.id());
    assertThat(tokenDeadline).isAfter(quotationDeadline);
    clock.set(quotationDeadline);
    ApiResponse readableExpiredQuotation = get(hashedQuote.publicPath());
    assertThat(readableExpiredQuotation.status()).isEqualTo(200);
    assertThat(readableExpiredQuotation.body().path("status").asText()).isEqualTo("EXPIRED");
    assertThat(readableExpiredQuotation.body().path("allowedActions")).isEmpty();
    clock.set(tokenDeadline);
    assertUnavailable(hashedQuote.publicPath());
  }

  @Test
  void rechecksTheDeadlineAfterWaitingForTheQuotationLock() throws Exception {
    Instant deadline = BASE.plusSeconds(2 * 86_400L);
    IssuedQuotation quotation = issue(deadline);
    String termsVersion = get(quotation.publicPath()).body().path("termsVersion").asText();
    clock.set(deadline.minusMillis(1));
    CompletableFuture<ApiResponse> acceptance;

    try (Connection connection = dataSource.getConnection();
        PreparedStatement lock =
            connection.prepareStatement(
                "SELECT id FROM quotation.quotation WHERE id = ?::uuid FOR UPDATE")) {
      connection.setAutoCommit(false);
      int blockerPid = backendPid(connection);
      lock.setObject(1, quotation.id());
      lock.executeQuery().close();
      acceptance =
          CompletableFuture.supplyAsync(
              () ->
                  uncheckedDecision(
                      quotation.publicPath() + "/acceptance",
                      "accept-crossing-deadline-0001",
                      acceptanceBody(termsVersion, "PO-2026-0714-07")));
      try {
        awaitDatabaseLockWait(blockerPid);
        clock.set(deadline);
      } finally {
        connection.commit();
      }
    }

    ApiResponse result = acceptance.get(10, SECONDS);
    assertThat(result.status()).isEqualTo(409);
    assertThat(result.body().path("code").asText()).isEqualTo("QUOTE_EXPIRED");
    assertDecisionAndAcceptedEventCounts(quotation.id(), 0, 0);
    assertThat(idempotencyCount(quotation.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM quotation.quotation WHERE id = ?::uuid",
                String.class,
                quotation.id()))
        .isEqualTo("SENT");
  }

  @Test
  void cannotAcceptWhenRevocationCommitsWhileTheWriteLookupIsBlocked() throws Exception {
    IssuedQuotation quotation = issue(BASE.plusSeconds(20 * 86_400L));
    String termsVersion = get(quotation.publicPath()).body().path("termsVersion").asText();
    CompletableFuture<ApiResponse> acceptance;

    try (Connection connection = dataSource.getConnection();
        PreparedStatement lock =
            connection.prepareStatement(
                """
                SELECT id
                  FROM quotation.portal_access
                 WHERE quotation_id = ?::uuid
                 FOR UPDATE
                """);
        PreparedStatement revoke =
            connection.prepareStatement(
                """
                UPDATE quotation.portal_access
                   SET revoked_at = ?, updated_at = ?, version = version + 1
                 WHERE quotation_id = ?::uuid
                """)) {
      connection.setAutoCommit(false);
      int blockerPid = backendPid(connection);
      lock.setObject(1, quotation.id());
      lock.executeQuery().close();
      acceptance =
          CompletableFuture.supplyAsync(
              () ->
                  uncheckedDecision(
                      quotation.publicPath() + "/acceptance",
                      "accept-during-revocation-0001",
                      acceptanceBody(termsVersion, "PO-2026-0714-08")));
      try {
        awaitDatabaseLockWait(blockerPid);
        Timestamp revokedAt = Timestamp.from(clock.instant());
        revoke.setTimestamp(1, revokedAt);
        revoke.setTimestamp(2, revokedAt);
        revoke.setObject(3, quotation.id());
        assertThat(revoke.executeUpdate()).isEqualTo(1);
      } finally {
        connection.commit();
      }
    }

    ApiResponse result = acceptance.get(10, SECONDS);
    assertThat(result.status()).isEqualTo(404);
    assertThat(result.body().path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    assertDecisionAndAcceptedEventCounts(quotation.id(), 0, 0);
    assertThat(idempotencyCount(quotation.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM quotation.quotation WHERE id = ?::uuid",
                String.class,
                quotation.id()))
        .isEqualTo("SENT");
  }

  @Test
  void reclaimsExpiredLeasesAndProcessesExpirationExactlyOnce() throws Exception {
    Instant deadline = BASE.plusSeconds(2 * 86_400L);
    IssuedQuotation quotation = issue(deadline);
    UUID firstOwner = UUID.randomUUID();
    UUID secondOwner = UUID.randomUUID();

    clock.set(deadline);
    List<ExpirationWorkItem> firstClaim = expirationService.claim(firstOwner, 10);
    ExpirationWorkItem workItem = workItem(firstClaim, quotation.id());
    assertThat(expirationService.claim(secondOwner, 10))
        .noneMatch(candidate -> candidate.quotationId().equals(quotation.id()));

    clock.set(deadline.plusSeconds(31));
    ExpirationWorkItem reclaimed =
        workItem(expirationService.claim(secondOwner, 10), quotation.id());
    assertThat(reclaimed.id()).isEqualTo(workItem.id());
    expirationService.expire(reclaimed);
    expirationService.expire(reclaimed);

    assertThat(expirationService.claim(firstOwner, 10))
        .noneMatch(candidate -> candidate.quotationId().equals(quotation.id()));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM quotation.quotation WHERE id = ?::uuid",
                String.class,
                quotation.id()))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForMap(
                "SELECT status, attempts, completion_outcome FROM quotation.expiration_work_item WHERE quotation_id = ?::uuid",
                quotation.id()))
        .containsEntry("status", "COMPLETED")
        .containsEntry("attempts", 2)
        .containsEntry("completion_outcome", "EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM quotation.audit_entry WHERE quotation_id = ?::uuid AND action = 'QUOTATION_EXPIRED'",
                Integer.class,
                quotation.id()))
        .isEqualTo(1);
  }

  private IssuedQuotation issue(Instant expiresAt) throws Exception {
    String body =
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
            "discountRate": "0.0000"
          }]
        }
        """
            .formatted(
                PARTNER,
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(9),
                expiresAt,
                SKU);
    ApiResponse created = internal("POST", "/api/v1/quotations", null, body);
    assertThat(created.status()).withFailMessage(created.raw()).isEqualTo(201);
    UUID quotationId = UUID.fromString(created.body().path("id").asText());
    ApiResponse evaluated =
        internal("POST", "/api/v1/quotations/" + quotationId + "/route-evaluations", "\"0\"", null);
    assertThat(evaluated.status()).withFailMessage(evaluated.raw()).isEqualTo(200);
    ApiResponse submitted =
        internal("POST", "/api/v1/quotations/" + quotationId + "/submission", "\"1\"", null);
    assertThat(submitted.status()).withFailMessage(submitted.raw()).isEqualTo(200);
    assertThat(submitted.body().path("status").asText()).isEqualTo("APPROVED");
    ApiResponse issued =
        internal("POST", "/api/v1/quotations/" + quotationId + "/issue", "\"2\"", null);
    assertThat(issued.status()).withFailMessage(issued.raw()).isEqualTo(200);
    String portalUrl = issued.body().path("portalUrl").asText();
    String token = portalUrl.substring(portalUrl.lastIndexOf('/') + 1);
    return new IssuedQuotation(quotationId, "/api/v1" + portalUrl, token);
  }

  private List<ApiResponse> concurrentAcceptances(
      IssuedQuotation quotation,
      String termsVersion,
      java.util.function.IntFunction<String> key,
      String buyerReference)
      throws Exception {
    int requestCount = 20;
    CountDownLatch ready = new CountDownLatch(requestCount);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(requestCount);
    try {
      List<CompletableFuture<ApiResponse>> requests =
          IntStream.range(0, requestCount)
              .mapToObj(
                  index ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            ready.countDown();
                            await(start);
                            return uncheckedDecision(
                                quotation.publicPath() + "/acceptance",
                                key.apply(index),
                                acceptanceBody(termsVersion, buyerReference));
                          },
                          executor))
              .toList();
      boolean allReady = ready.await(10, SECONDS);
      start.countDown();
      assertThat(allReady).isTrue();
      return requests.stream().map(CompletableFuture::join).toList();
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
    }
  }

  private void assertConcurrentDecisionResults(List<ApiResponse> results) {
    assertThat(results).hasSize(20);
    assertThat(results).allMatch(result -> result.status() == 200 || result.status() == 201);
    assertThat(results).filteredOn(result -> result.status() == 201).hasSize(1);
    assertThat(results).filteredOn(result -> result.status() == 200).hasSize(19);
    String acceptanceId = results.getFirst().body().path("acceptanceId").asText();
    assertThat(results)
        .extracting(result -> result.body().path("acceptanceId").asText())
        .containsOnly(acceptanceId);
  }

  private void assertDecisionAndAcceptedEventCounts(
      UUID quotationId, int decisions, int acceptedEvents) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM quotation.customer_decision WHERE quotation_id = ?::uuid",
                Integer.class,
                quotationId))
        .isEqualTo(decisions);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform_event.event_publication WHERE subject_id = ?::uuid AND event_type = ?",
                Integer.class,
                quotationId,
                ACCEPTED_EVENT))
        .isEqualTo(acceptedEvents);
  }

  private int idempotencyCount(UUID quotationId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM quotation.http_idempotency idempotency
              JOIN quotation.customer_decision decision
                ON decision.tenant_id = idempotency.tenant_id
               AND decision.id = idempotency.decision_id
             WHERE decision.quotation_id = ?::uuid
            """,
            Integer.class,
            quotationId);
    return count == null ? 0 : count;
  }

  private Instant timestamp(String sql, UUID quotationId) {
    Timestamp value = jdbc.queryForObject(sql, Timestamp.class, quotationId);
    return value.toInstant();
  }

  private void awaitDatabaseLockWait(int blockerPid) throws Exception {
    for (int attempt = 0; attempt < 250; attempt++) {
      Integer waiting =
          jdbc.queryForObject(
              """
              SELECT count(*)
                FROM pg_stat_activity activity
               WHERE datname = current_database()
                 AND activity.pid <> pg_backend_pid()
                 AND ? = ANY(pg_blocking_pids(activity.pid))
              """,
              Integer.class,
              blockerPid);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Expected a blocked database write");
  }

  private static int backendPid(Connection connection) throws Exception {
    try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()");
        ResultSet result = query.executeQuery()) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private static ExpirationWorkItem workItem(List<ExpirationWorkItem> workItems, UUID quotationId) {
    return workItems.stream()
        .filter(candidate -> candidate.quotationId().equals(quotationId))
        .findFirst()
        .orElseThrow();
  }

  private static Set<String> allPropertyNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    if (node.isObject()) {
      names.addAll(node.propertyNames());
    }
    node.forEach(child -> names.addAll(allPropertyNames(child)));
    return names;
  }

  private static String acceptanceBody(String termsVersion, String buyerReference) {
    return """
        {"acceptedTermsVersion":"%s","buyerReference":"%s"}
        """
        .formatted(termsVersion, buyerReference)
        .strip();
  }

  private ApiResponse get(String path) throws Exception {
    return request("GET", path, null, null, null, null);
  }

  private ApiResponse internal(String method, String path, String ifMatch, String body)
      throws Exception {
    return request(method, path, NORTH_SALES, ifMatch, null, body);
  }

  private ApiResponse decision(String path, String idempotencyKey, String body) throws Exception {
    return request("POST", path, null, null, idempotencyKey, body);
  }

  private ApiResponse uncheckedDecision(String path, String idempotencyKey, String body) {
    try {
      return decision(path, idempotencyKey, body);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void assertUnavailable(String publicPath) throws Exception {
    ApiResponse response = get(publicPath);
    assertThat(response.status()).isEqualTo(404);
    assertThat(response.body().path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
  }

  private ApiResponse request(
      String method,
      String path,
      String bearerToken,
      String ifMatch,
      String idempotencyKey,
      String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", "application/json");
    if (bearerToken != null) {
      request.header("Authorization", "Bearer " + bearerToken);
    }
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
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

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  record ApiResponse(int status, JsonNode body, String raw, java.net.http.HttpHeaders headers) {}

  record IssuedQuotation(UUID id, String publicPath, String token) {}

  static final class MutableClock extends Clock {

    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    MutableClock(Instant initial, ZoneId zone) {
      this(new AtomicReference<>(initial), zone);
    }

    private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
      this.current = current;
      this.zone = zone;
    }

    void set(Instant instant) {
      current.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
      return zone.equals(requestedZone) ? this : new MutableClock(current, requestedZone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CustomerQuotationTestConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(BASE, ZoneOffset.UTC);
    }

    @Bean
    @Primary
    JwtDecoder customerQuotationJwtDecoder() {
      return token -> {
        if (!NORTH_SALES.equals(token)) {
          throw new BadJwtException("Token is invalid");
        }
        Instant now = BASE;
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .issuer("http://localhost:8081/realms/cellarbridge")
            .subject("11000000-0000-4000-8000-000000000001")
            .audience(List.of("cellarbridge-api"))
            .issuedAt(now.minusSeconds(30))
            .expiresAt(now.plusSeconds(300))
            .claim("tenant_code", "north-cellars")
            .build();
      };
    }
  }
}
