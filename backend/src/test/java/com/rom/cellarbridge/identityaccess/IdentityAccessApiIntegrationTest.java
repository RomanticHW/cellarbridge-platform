package com.rom.cellarbridge.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
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

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityAccessApiIntegrationTest extends PostgresIntegrationTestSupport {

  private static final String TENANT_B_USER_ID = "21200000-0000-4000-8000-000000000001";
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void requiresATokenWithAStableProblemContract() throws Exception {
    HttpResponse<String> response = send("/api/v1/me", null);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("content-type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(response.body())
        .contains("\"code\":\"AUTHENTICATION_REQUIRED\"")
        .contains("\"retryable\":false")
        .contains("\"traceId\"");
  }

  @Test
  void rejectsAnExpiredOrInvalidTokenWithoutEchoingIt() throws Exception {
    HttpResponse<String> response = send("/api/v1/me", "expired-token-marker");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body())
        .contains("\"code\":\"INVALID_ACCESS_TOKEN\"")
        .doesNotContain("expired-token-marker")
        .doesNotContain("Bearer");
  }

  @Test
  void returnsOnlyTheMappedCurrentUserProfile() throws Exception {
    HttpResponse<String> response = send("/api/v1/me", "north-valid");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("cache-control")).contains("no-store");
    assertThat(response.headers().firstValue("content-security-policy"))
        .contains("default-src 'none'; frame-ancestors 'none'");
    assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
    assertThat(response.headers().firstValue("referrer-policy")).contains("no-referrer");
    assertThat(response.body())
        .contains("11200000-0000-4000-8000-000000000001")
        .contains("North Sales")
        .contains("North Cellars")
        .contains("Sales Representative")
        .contains("partner:read")
        .doesNotContain("11000000-0000-4000-8000-000000000001")
        .doesNotContain("11100000-0000-4000-8000-000000000001")
        .doesNotContain("north-valid");
  }

  @Test
  void returnsTheServerMappedPartnerScopeForABuyer() throws Exception {
    HttpResponse<String> response = send("/api/v1/me", "north-buyer");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"partnerId\":\"53000000-0000-4000-8000-000000000001\"")
        .contains("Customer Buyer")
        .contains("order:read")
        .doesNotContain("11100000-0000-4000-8000-000000000002");
  }

  @Test
  void ignoresClientSuppliedTenantSelectors() throws Exception {
    HttpRequest request =
        request("/api/v1/me?tenantId=20000000-0000-4000-8000-000000000001")
            .header("Authorization", "Bearer north-valid")
            .header("X-Tenant-ID", "20000000-0000-4000-8000-000000000001")
            .GET()
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("North Cellars").doesNotContain("Harbor Cellars");
  }

  @Test
  void deniesUnmappedConflictingAndSuspendedUsers() throws Exception {
    assertAccessDenied("unmapped");
    assertAccessDenied("tenant-conflict");
    assertAccessDenied("suspended-user");
  }

  @Test
  void deniesAUserWhoseTenantIsSuspended() throws Exception {
    jdbc.update(
        "UPDATE identity_access.tenant SET status = 'SUSPENDED' WHERE code = 'harbor-cellars'");
    try {
      assertAccessDenied("harbor-valid");
    } finally {
      jdbc.update(
          "UPDATE identity_access.tenant SET status = 'ACTIVE' WHERE code = 'harbor-cellars'");
    }
  }

  @Test
  void defaultDenyBlocksAGuessedCrossTenantResource() throws Exception {
    HttpResponse<String> response =
        send("/api/v1/identity/users/" + TENANT_B_USER_ID, "north-valid");

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body())
        .contains("\"code\":\"ACCESS_DENIED\"")
        .doesNotContain(TENANT_B_USER_ID);
  }

  @Test
  void permitsOnlyConfiguredDevelopmentCorsOrigins() throws Exception {
    HttpRequest allowed =
        request("/api/v1/me")
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "GET")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build();
    HttpRequest denied =
        request("/api/v1/me")
            .header("Origin", "https://origin.invalid")
            .header("Access-Control-Request-Method", "GET")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build();

    HttpResponse<String> allowedResponse =
        httpClient.send(allowed, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> deniedResponse =
        httpClient.send(denied, HttpResponse.BodyHandlers.ofString());

    assertThat(allowedResponse.statusCode()).isEqualTo(200);
    assertThat(allowedResponse.headers().firstValue("access-control-allow-origin"))
        .contains("http://localhost:5173");
    assertThat(deniedResponse.statusCode()).isEqualTo(403);
    assertThat(deniedResponse.headers().firstValue("access-control-allow-origin")).isEmpty();
  }

  private void assertAccessDenied(String token) throws Exception {
    HttpResponse<String> response = send("/api/v1/me", token);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("\"code\":\"ACCESS_DENIED\"").doesNotContain(token);
  }

  private HttpResponse<String> send(String path, String token) throws Exception {
    HttpRequest.Builder request = request(path).GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Accept", "application/json");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestTokenConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
      return token -> {
        if (token.startsWith("expired")) {
          throw new BadJwtException("Token is expired");
        }
        return switch (token) {
          case "north-valid" -> jwt(token, "11000000-0000-4000-8000-000000000001", "north-cellars");
          case "north-buyer" -> jwt(token, "11000000-0000-4000-8000-000000000002", "north-cellars");
          case "harbor-valid" ->
              jwt(token, "22000000-0000-4000-8000-000000000001", "harbor-cellars");
          case "suspended-user" ->
              jwt(token, "11000000-0000-4000-8000-000000000099", "north-cellars");
          case "tenant-conflict" ->
              jwt(token, "11000000-0000-4000-8000-000000000001", "harbor-cellars");
          case "unmapped" -> jwt(token, "99000000-0000-4000-8000-000000000001", "north-cellars");
          default -> throw new BadJwtException("Token is invalid");
        };
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
