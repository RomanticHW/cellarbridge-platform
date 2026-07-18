package com.rom.cellarbridge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationRepository;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import com.rom.cellarbridge.inventory.internal.domain.InventoryMovement;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.inventory.internal.domain.ReservationAttempt;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
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
class InventoryReservationApiIntegrationTest extends PostgresIntegrationTestSupport {

  private static final String NORTH_ADMIN = "reservation-north-admin-token";
  private static final String NORTH_MANAGER = "reservation-north-manager-token";
  private static final String NORTH_BUYER = "reservation-north-buyer-token";
  private static final String HARBOR_MANAGER = "reservation-harbor-manager-token";
  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final UUID POOL = UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final Instant NOW = Instant.parse("2026-07-18T07:00:00Z");

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JsonMapper json;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InventoryReservationRepository reservations;

  @Test
  void projectsAssignedExactEvidenceAndReplaysReleaseWithoutASecondEffect() throws Exception {
    Fixture fixture = confirmed("2");
    String path = "/api/v1/inventory/reservations/by-order/" + fixture.reservation().orderId();

    ApiResponse before = request("GET", path, NORTH_ADMIN, null, null);

    assertThat(before.status()).withFailMessage(before.raw()).isEqualTo(200);
    assertThat(before.header("cache-control")).contains("no-store");
    assertThat(before.body().path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(before.body().path("allocations").path(0).path("supplyPoolId").asText())
        .isEqualTo(POOL.toString());
    assertThat(before.body().path("allocations").path(0).path("lotId").asText())
        .isEqualTo(fixture.allocation().lotId().toString());
    assertThat(before.body().path("allocations").path(0).path("warehouseLabel").asText())
        .isEqualTo("Eastbank Distribution Center");
    assertThat(before.body().path("allowedActions")).hasSize(2);
    assertThat(before.body().path("attempts")).hasSize(1);

    String operationPath =
        "/api/v1/inventory/reservations/" + fixture.reservation().id() + "/release";
    String body = operationBody(fixture.allocation(), "2");
    String key = "reservation-api-release-replay";
    ApiResponse released = request("POST", operationPath, NORTH_ADMIN, key, body);
    ApiResponse replayed = request("POST", operationPath, NORTH_ADMIN, key, body);

    assertThat(released.status()).isEqualTo(200);
    assertThat(released.header("idempotency-replayed")).contains("false");
    assertThat(released.body().path("reservationStatus").asText()).isEqualTo("RELEASED");
    assertThat(replayed.status()).isEqualTo(200);
    assertThat(replayed.header("idempotency-replayed")).contains("true");
    assertThat(replayed.body().path("commandId").asText())
        .isEqualTo(released.body().path("commandId").asText());

    ApiResponse after = request("GET", path, NORTH_ADMIN, null, null);
    assertThat(after.body().path("status").asText()).isEqualTo("RELEASED");
    assertThat(after.body().path("allowedActions")).isEmpty();
    assertThat(after.body().path("operations")).hasSize(1);
    assertThat(after.body().path("operations").path(0).path("outcome").asText())
        .isEqualTo("COMPLETED");
    assertThat(movementCount(fixture.reservation().id(), "RELEASE")).isEqualTo(1);
  }

  @Test
  void redactsUnassignedFieldsAndEnforcesPermissionTenantAndAuthenticationBoundaries()
      throws Exception {
    Fixture fixture = confirmed("1");
    String byOrder = "/api/v1/inventory/reservations/by-order/" + fixture.reservation().orderId();

    ApiResponse summary = request("GET", byOrder, NORTH_MANAGER, null, null);
    assertThat(summary.status()).withFailMessage(summary.raw()).isEqualTo(200);
    assertThat(summary.body().path("allocations").path(0).path("supplyPoolId").isNull()).isTrue();
    assertThat(summary.body().path("allocations").path(0).path("lotId").isNull()).isTrue();
    assertThat(summary.body().path("allocations").path(0).path("warehouseLabel").isNull()).isTrue();
    assertThat(summary.body().path("allocations").path(0).path("warehousePriority").isNull())
        .isTrue();
    assertThat(summary.body().path("allowedActions")).isEmpty();

    String release = "/api/v1/inventory/reservations/" + fixture.reservation().id() + "/release";
    assertThat(
            request(
                    "POST",
                    release,
                    NORTH_MANAGER,
                    "reservation-api-manager-denied",
                    operationBody(fixture.allocation(), "1"))
                .status())
        .isEqualTo(403);
    assertThat(request("GET", byOrder, NORTH_BUYER, null, null).status()).isEqualTo(403);
    ApiResponse crossTenant = request("GET", byOrder, HARBOR_MANAGER, null, null);
    assertThat(crossTenant.status()).isEqualTo(404);
    assertThat(crossTenant.raw()).doesNotContain(fixture.reservation().id().toString());
    assertThat(request("GET", byOrder, null, null, null).status()).isEqualTo(401);
    assertThat(movementCount(fixture.reservation().id(), "RELEASE")).isZero();
  }

  @Test
  void mapsValidationNotFoundAndIdempotencyConflictsToStableProblems() throws Exception {
    Fixture fixture = confirmed("2");
    String release = "/api/v1/inventory/reservations/" + fixture.reservation().id() + "/release";
    String one = operationBody(fixture.allocation(), "1");

    ApiResponse missingKey = request("POST", release, NORTH_ADMIN, null, one);
    assertThat(missingKey.status()).withFailMessage(missingKey.raw()).isEqualTo(400);
    assertThat(missingKey.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

    ApiResponse malformed = request("POST", release, NORTH_ADMIN, "reservation-api-malformed", "{");
    assertThat(malformed.status()).isEqualTo(400);
    assertThat(malformed.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");

    ApiResponse nullItem =
        request(
            "POST", release, NORTH_ADMIN, "reservation-api-null-item", "{\"allocations\":[null]}");
    assertThat(nullItem.status()).isEqualTo(400);
    assertThat(nullItem.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");

    String key = "reservation-api-payload-conflict";
    assertThat(request("POST", release, NORTH_ADMIN, key, one).status()).isEqualTo(200);
    ApiResponse conflict =
        request("POST", release, NORTH_ADMIN, key, operationBody(fixture.allocation(), "2"));
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    ApiResponse missing =
        request(
            "GET", "/api/v1/inventory/reservations/" + UUID.randomUUID(), NORTH_ADMIN, null, null);
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.body().path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
  }

  private Fixture confirmed(String reserved) {
    UUID reservationId = UUID.randomUUID();
    UUID orderLineId = UUID.randomUUID();
    UUID sourceLineId = UUID.randomUUID();
    UUID lotId = insertLot(reserved);
    BigDecimal quantity = quantity(reserved);
    Reservation pending =
        Reservation.pending(
            reservationId,
            TENANT,
            UUID.randomUUID(),
            UUID.randomUUID().toString().replace("-", "").repeat(2),
            "d".repeat(64),
            "SH_GENERAL_TRADE",
            List.of(
                new Reservation.Line(
                    orderLineId,
                    sourceLineId,
                    SKU,
                    quantity,
                    QuantityUnit.CASE,
                    AllocationMode.FIXED_POOL,
                    POOL,
                    SupplyType.DOMESTIC_ON_HAND)),
            NOW);
    reservations.create(TENANT, pending);
    reservations.appendAttempt(
        TENANT,
        new ReservationAttempt(
            UUID.randomUUID(),
            TENANT,
            reservationId,
            1,
            pending.requestHash(),
            ReservationAttempt.Trigger.EVENT,
            NOW,
            NOW.plusSeconds(1),
            ReservationAttempt.Outcome.CONFIRMED,
            null,
            UUID.randomUUID(),
            UUID.randomUUID()));
    Allocation allocation =
        new Allocation(
            UUID.randomUUID(),
            TENANT,
            reservationId,
            orderLineId,
            sourceLineId,
            SKU,
            QuantityUnit.CASE,
            SupplyType.DOMESTIC_ON_HAND,
            AllocationMode.FIXED_POOL,
            POOL,
            lotId,
            quantity,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            quantity,
            10,
            0);
    reservations.appendAllocations(TENANT, List.of(allocation));
    reservations.appendMovement(
        TENANT,
        new InventoryMovement(
            UUID.randomUUID(),
            TENANT,
            reservationId,
            allocation.id(),
            orderLineId,
            lotId,
            InventoryMovement.Type.RESERVE,
            quantity,
            QuantityUnit.CASE,
            "reserve:api:" + allocation.id(),
            NOW.plusSeconds(1)));
    Reservation confirmed =
        pending.transition(Reservation.Status.CONFIRMED, null, NOW.plusSeconds(2));
    reservations.updateState(TENANT, confirmed, 0);
    return new Fixture(confirmed, allocation);
  }

  private UUID insertLot(String reserved) {
    UUID lotId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory.inventory_lot
          (id, tenant_id, supply_pool_id, sku_id, lot_code, status, quantity_unit,
           on_hand_quantity, reserved_quantity, created_at, created_by,
           updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, ?, 'AVAILABLE', 'CASE', 10, ?, ?, ?, ?, ?, 0)
        """,
        lotId,
        TENANT.value(),
        POOL,
        SKU,
        "API-RESERVATION-" + lotId,
        quantity(reserved),
        Timestamp.from(NOW),
        ACTOR,
        Timestamp.from(NOW),
        ACTOR);
    return lotId;
  }

  private ApiResponse request(
      String method, String path, String token, String idempotencyKey, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Accept", "application/json");
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new ApiResponse(
        response.statusCode(),
        response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body()),
        response.body(),
        response.headers());
  }

  private static String operationBody(Allocation allocation, String amount) {
    return """
        {"allocations":[{"allocationId":"%s","quantity":"%s","quantityUnit":"CASE"}]}
        """
        .formatted(allocation.id(), amount)
        .trim();
  }

  private int movementCount(UUID reservationId, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM inventory.inventory_movement WHERE tenant_id = ? AND reservation_id = ? AND movement_type = ?",
        Integer.class,
        TENANT.value(),
        reservationId,
        type);
  }

  private static BigDecimal quantity(String value) {
    return new BigDecimal(value).setScale(6);
  }

  private record Fixture(Reservation reservation, Allocation allocation) {}

  private record ApiResponse(
      int status, JsonNode body, String raw, java.net.http.HttpHeaders headers) {
    java.util.Optional<String> header(String name) {
      return headers.firstValue(name);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TokenConfiguration {

    @Bean
    @Primary
    JwtDecoder inventoryReservationJwtDecoder() {
      return token ->
          switch (token) {
            case NORTH_ADMIN -> jwt(token, "11000000-0000-4000-8000-000000000004", "north-cellars");
            case NORTH_MANAGER ->
                jwt(token, "11000000-0000-4000-8000-000000000003", "north-cellars");
            case NORTH_BUYER -> jwt(token, "11000000-0000-4000-8000-000000000002", "north-cellars");
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
