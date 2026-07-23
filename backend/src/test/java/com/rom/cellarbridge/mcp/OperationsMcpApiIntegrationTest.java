package com.rom.cellarbridge.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationsMcpApiIntegrationTest extends PostgresIntegrationTestSupport {

  private static final String NORTH_SALES = "mcp-north-sales";
  private static final String NORTH_BUYER = "mcp-north-buyer";
  private static final String NORTH_ADMIN = "mcp-north-admin";
  private static final String NORTH_WAREHOUSE = "mcp-north-warehouse";
  private static final String HARBOR_MANAGER = "mcp-harbor-manager";
  private static final String NORTH_SKU = "34000000-0000-4000-8000-000000000001";
  private static final String SUBJECT = "34000000-0000-4000-8000-000000000001";
  private static final List<String> EXPECTED_TOOLS =
      List.of(
          "cellarbridge_current_user",
          "cellarbridge_get_dashboard",
          "cellarbridge_get_timeline",
          "cellarbridge_list_work_items",
          "cellarbridge_search_audit",
          "cellarbridge_search_supply");

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JsonMapper json;
  @Autowired private RequestContextCleanupProbe cleanupProbe;

  @Test
  void requiresBearerAuthenticationAndRejectsInvalidOrigins() throws Exception {
    RpcResponse missing = rpc(null, "tools/list", Map.of());
    assertThat(missing.status()).isEqualTo(401);
    assertThat(missing.raw()).contains("\"code\":\"AUTHENTICATION_REQUIRED\"");

    for (String invalid : List.of("expired-token-marker", "wrong-audience-marker")) {
      RpcResponse response = rpc(invalid, "tools/list", Map.of());
      assertThat(response.status()).isEqualTo(401);
      assertThat(response.raw())
          .contains("\"code\":\"INVALID_ACCESS_TOKEN\"")
          .doesNotContain(invalid);
    }

    HttpRequest denied =
        request()
            .header("Authorization", "Bearer " + NORTH_SALES)
            .header("Origin", "https://origin.invalid")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(requestBody(1, "tools/list", Map.of()))))
            .build();
    HttpResponse<String> deniedResponse = http.send(denied, HttpResponse.BodyHandlers.ofString());
    assertThat(deniedResponse.statusCode()).isEqualTo(403);
    assertThat(deniedResponse.headers().firstValue("access-control-allow-origin")).isEmpty();

    HttpRequest allowedPreflight =
        HttpRequest.newBuilder(endpoint())
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST")
            .header(
                "Access-Control-Request-Headers", "authorization,content-type,mcp-protocol-version")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> allowedResponse =
        http.send(allowedPreflight, HttpResponse.BodyHandlers.ofString());
    assertThat(allowedResponse.statusCode()).isEqualTo(200);
    assertThat(allowedResponse.headers().firstValue("access-control-allow-origin"))
        .contains("http://localhost:5173");

    HttpRequest unsupportedVersion =
        HttpRequest.newBuilder(endpoint())
            .header("Authorization", "Bearer " + NORTH_SALES)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2099-12-31-secret-marker")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(requestBody(1, "tools/list", Map.of()))))
            .build();
    HttpResponse<String> unsupportedResponse =
        http.send(unsupportedVersion, HttpResponse.BodyHandlers.ofString());
    assertThat(unsupportedResponse.statusCode()).isEqualTo(400);
    assertThat(unsupportedResponse.headers().firstValue("cache-control")).contains("no-store");
    assertThat(unsupportedResponse.body())
        .contains("Unsupported MCP protocol version")
        .doesNotContain("2099-12-31-secret-marker");

    HttpRequest allowedGet =
        HttpRequest.newBuilder(endpoint())
            .header("Authorization", "Bearer " + NORTH_SALES)
            .header("Origin", "http://localhost:5173")
            .header("Accept", "application/json, text/event-stream")
            .GET()
            .build();
    HttpResponse<String> allowedGetResponse =
        http.send(allowedGet, HttpResponse.BodyHandlers.ofString());
    assertThat(allowedGetResponse.statusCode()).isEqualTo(405);
    assertThat(allowedGetResponse.headers().firstValue("access-control-allow-origin"))
        .contains("http://localhost:5173");
  }

  @Test
  void exposesExactlyTheApprovedProtocolSurface() throws Exception {
    RpcResponse initialized =
        rpc(
            NORTH_ADMIN,
            "initialize",
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "cellarbridge-test", "version", "1.0")));
    assertThat(initialized.status()).isEqualTo(200);
    assertThat(initialized.body().path("result").path("protocolVersion").asText())
        .isEqualTo("2025-11-25");
    assertThat(initialized.body().path("result").path("serverInfo").path("name").asText())
        .isEqualTo("cellarbridge-operations");
    assertThat(initialized.headers().firstValue("mcp-session-id")).isEmpty();
    assertThat(initialized.headers().firstValue("cache-control").orElse("")).contains("no-store");

    RpcResponse tools = rpc(NORTH_ADMIN, "tools/list", Map.of());
    List<String> names = new ArrayList<>();
    tools
        .body()
        .path("result")
        .path("tools")
        .forEach(tool -> names.add(tool.path("name").asText()));
    assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
    tools
        .body()
        .path("result")
        .path("tools")
        .forEach(
            tool -> {
              assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
              assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
              assertThat(tool.path("annotations").path("openWorldHint").asBoolean()).isFalse();
              assertThat(tool.path("inputSchema").path("additionalProperties").isBoolean())
                  .isTrue();
              assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean())
                  .isFalse();
              assertThat(tool.path("outputSchema").path("type").asText()).isEqualTo("object");
              assertThat(tool.path("outputSchema").path("additionalProperties").asBoolean())
                  .isFalse();
            });

    RpcResponse resources = rpc(NORTH_ADMIN, "resources/list", Map.of());
    assertThat(resourceUris(resources)).containsExactly("cellarbridge://session/me");
    RpcResponse templates = rpc(NORTH_ADMIN, "resources/templates/list", Map.of());
    assertThat(resourceTemplateUris(templates))
        .containsExactlyInAnyOrder(
            "cellarbridge://catalog/skus/{skuId}",
            "cellarbridge://timeline/{subjectType}/{subjectId}");

    RpcResponse session =
        rpc(NORTH_ADMIN, "resources/read", Map.of("uri", "cellarbridge://session/me"));
    JsonNode sessionEnvelope =
        json.readTree(session.body().path("result").path("contents").path(0).path("text").asText());
    assertThat(sessionEnvelope.path("sourceKind").asText()).isEqualTo("SESSION");
    assertThat(sessionEnvelope.path("projectionStatus").asText()).isEqualTo("NOT_APPLICABLE");
    assertThat(sessionEnvelope.path("data").path("tenant").path("name").asText())
        .isEqualTo("North Cellars");
    assertThat(session.raw()).doesNotContain("subjectHash", "tenantHash", NORTH_ADMIN);

    RpcResponse prompts = rpc(NORTH_ADMIN, "prompts/list", Map.of());
    List<String> promptNames = new ArrayList<>();
    prompts
        .body()
        .path("result")
        .path("prompts")
        .forEach(prompt -> promptNames.add(prompt.path("name").asText()));
    assertThat(promptNames)
        .containsExactlyInAnyOrder(
            "cellarbridge_daily_operations_brief",
            "cellarbridge_supply_search_brief",
            "cellarbridge_trace_business_history");
    RpcResponse prompt =
        rpc(
            NORTH_ADMIN,
            "prompts/get",
            Map.of("name", "cellarbridge_daily_operations_brief", "arguments", Map.of()));
    assertThat(prompt.body().path("result").path("messages").path(0).path("role").asText())
        .isEqualTo("user");
    assertThat(prompt.raw()).contains("cellarbridge_list_work_items").doesNotContain("Bearer");

    RpcResponse promptWithUnknownArgument =
        rpc(
            NORTH_ADMIN,
            "prompts/get",
            Map.of(
                "name",
                "cellarbridge_daily_operations_brief",
                "arguments",
                Map.of("tenantId", "other-tenant-secret-marker")));
    assertThat(promptWithUnknownArgument.body().path("error").path("code").asInt())
        .isEqualTo(-32602);
    assertThat(promptWithUnknownArgument.raw()).doesNotContain("other-tenant-secret-marker");
  }

  @Test
  void rejectsUnknownToolArgumentsAndKeepsTextAndStructuredEnvelopesEquivalent() throws Exception {
    RpcResponse currentUser =
        rpc(
            NORTH_SALES,
            "tools/call",
            Map.of("name", "cellarbridge_current_user", "arguments", Map.of()));
    JsonNode structured = structured(currentUser);
    JsonNode text =
        json.readTree(
            currentUser.body().path("result").path("content").path(0).path("text").asText());
    assertThat(text).isEqualTo(structured);

    RpcResponse unknownArgument =
        rpc(
            NORTH_SALES,
            "tools/call",
            Map.of(
                "name",
                "cellarbridge_current_user",
                "arguments",
                Map.of("tenantId", "other-tenant-secret-marker")));
    assertThat(
            unknownArgument.body().path("result").path("isError").asBoolean()
                || unknownArgument.body().path("error").isObject())
        .isTrue();
    assertThat(unknownArgument.raw()).doesNotContain("other-tenant-secret-marker");
  }

  @Test
  void reportsEmptySupplyAndMapsCatalogValidationToASafeToolError() throws Exception {
    JsonNode empty =
        structured(
            rpc(
                NORTH_SALES,
                "tools/call",
                Map.of(
                    "name",
                    "cellarbridge_search_supply",
                    "arguments",
                    Map.of("keyword", "Neverland Reserve"))));
    assertThat(empty.path("isError").asBoolean()).isFalse();
    assertThat(empty.path("projectionStatus").asText()).isEqualTo("EMPTY");
    assertThat(empty.path("warnings")).isNotEmpty();
    assertThat(empty.path("data").path("items")).isEmpty();

    JsonNode invalid =
        structured(
            rpc(
                NORTH_SALES,
                "tools/call",
                Map.of("name", "cellarbridge_search_supply", "arguments", Map.of("keyword", "*"))));
    assertThat(invalid.path("isError").asBoolean()).isTrue();
    assertThat(invalid.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(invalid.path("retryable").asBoolean()).isFalse();
  }

  @Test
  void preservesSupplyFieldPermissionsWarehouseScopeAndTenantIsolation() throws Exception {
    Map<String, Object> search =
        Map.of(
            "name",
            "cellarbridge_search_supply",
            "arguments",
            Map.of(
                "keyword",
                "Moonlit Terrace",
                "supplyTypes",
                List.of("DOMESTIC_ON_HAND"),
                "quantityUnits",
                List.of("CASE")));
    JsonNode sales = structured(rpc(NORTH_SALES, "tools/call", search));
    assertThat(sales.path("isError").asBoolean()).isFalse();
    assertThat(sales.path("sourceKind").asText()).isEqualTo("SUPPLY_PROJECTION");
    assertThat(
            sales
                .path("data")
                .path("items")
                .path(0)
                .path("supplies")
                .path(0)
                .path("exactLots")
                .size())
        .isZero();
    assertThat(
            sales
                .path("data")
                .path("items")
                .path(0)
                .path("supplies")
                .path(0)
                .path("displayedAvailableQuantity")
                .isMissingNode())
        .isTrue();

    JsonNode warehouse = structured(rpc(NORTH_WAREHOUSE, "tools/call", search));
    assertThat(
            warehouse
                .path("data")
                .path("items")
                .path(0)
                .path("supplies")
                .path(0)
                .path("exactLots")
                .size())
        .isPositive();

    Map<String, Object> unassigned =
        Map.of(
            "name",
            "cellarbridge_search_supply",
            "arguments",
            Map.of("supplyTypes", List.of("HONG_KONG_ON_HAND")));
    JsonNode hidden = structured(rpc(NORTH_WAREHOUSE, "tools/call", unassigned));
    assertThat(
            hidden
                .path("data")
                .path("items")
                .path(0)
                .path("supplies")
                .path(0)
                .path("exactLots")
                .size())
        .isZero();

    Map<String, Object> tenantSearch =
        Map.of(
            "name",
            "cellarbridge_search_supply",
            "arguments",
            Map.of("keyword", "Moonlit Terrace"));
    JsonNode north = structured(rpc(NORTH_SALES, "tools/call", tenantSearch));
    JsonNode harbor = structured(rpc(HARBOR_MANAGER, "tools/call", tenantSearch));
    assertThat(north.toString()).contains("CB-MTV-2019-750X6").doesNotContain("HB-MTV");
    assertThat(harbor.toString()).contains("HB-MTV-2019-750X6").doesNotContain("CB-MTV");

    RpcResponse crossTenantResource =
        rpc(
            HARBOR_MANAGER,
            "resources/read",
            Map.of("uri", "cellarbridge://catalog/skus/" + NORTH_SKU));
    JsonNode crossTenantEnvelope =
        json.readTree(
            crossTenantResource
                .body()
                .path("result")
                .path("contents")
                .path(0)
                .path("text")
                .asText());
    assertThat(crossTenantEnvelope.path("isError").asBoolean()).isTrue();
    assertThat(crossTenantEnvelope.path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(crossTenantEnvelope.path("data").isNull()).isTrue();
    assertThat(crossTenantResource.raw()).doesNotContain("CB-MTV").doesNotContain("north-cellars");

    JsonNode buyer = structured(rpc(NORTH_BUYER, "tools/call", search));
    assertThat(buyer.path("isError").asBoolean()).isTrue();
    assertThat(buyer.path("code").asText()).isEqualTo("ACCESS_DENIED");
  }

  @Test
  void enforcesReportingScopeTimelineGuardAndSafeErrors() throws Exception {
    JsonNode teamDenied =
        structured(
            rpc(
                NORTH_SALES,
                "tools/call",
                Map.of(
                    "name", "cellarbridge_list_work_items", "arguments", Map.of("scope", "team"))));
    assertThat(teamDenied.path("isError").asBoolean()).isTrue();
    assertThat(teamDenied.path("code").asText()).isEqualTo("ACCESS_DENIED");

    JsonNode timelineDenied =
        structured(
            rpc(
                NORTH_SALES,
                "tools/call",
                Map.of(
                    "name",
                    "cellarbridge_get_timeline",
                    "arguments",
                    Map.of("subjectType", "QUOTATION", "subjectId", SUBJECT))));
    assertThat(timelineDenied.path("isError").asBoolean()).isTrue();
    assertThat(timelineDenied.path("code").asText()).isEqualTo("ACCESS_DENIED");

    JsonNode dashboard =
        structured(
            rpc(
                NORTH_ADMIN,
                "tools/call",
                Map.of(
                    "name",
                    "cellarbridge_get_dashboard",
                    "arguments",
                    Map.of("from", "2026-07-01", "to", "2026-07-31"))));
    assertThat(dashboard.path("isError").asBoolean()).isFalse();
    assertThat(List.of("CURRENT", "STALE", "EMPTY"))
        .contains(dashboard.path("projectionStatus").asText());

    String hostileCursor =
        "SELECT * FROM audit_reporting.audit_entry JWT stacktrace internal.package";
    RpcResponse invalidAudit =
        rpc(
            NORTH_ADMIN,
            "tools/call",
            Map.of(
                "name", "cellarbridge_search_audit", "arguments", Map.of("cursor", hostileCursor)));
    JsonNode safe = structured(invalidAudit);
    assertThat(invalidAudit.body().path("result").path("isError").asBoolean()).isTrue();
    assertThat(safe.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(invalidAudit.raw())
        .doesNotContain(
            hostileCursor,
            "audit_reporting",
            "SELECT",
            "JWT",
            "stacktrace",
            "com.rom.cellarbridge");
  }

  @Test
  void keepsTenantContextInsideTheRequestAndCleansItAfterward() throws Exception {
    cleanupProbe.reset();
    JsonNode current =
        structured(
            rpc(
                NORTH_SALES,
                "tools/call",
                Map.of("name", "cellarbridge_current_user", "arguments", Map.of())));
    assertThat(current.path("data").path("displayName").asText()).isEqualTo("North Sales");
    assertThat(cleanupProbe.checked()).isTrue();
    assertThat(cleanupProbe.cleared()).isTrue();
  }

  private RpcResponse rpc(String token, String method, Map<String, ?> params) throws Exception {
    HttpRequest.Builder builder =
        request()
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(requestBody(1, method, params))));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode body =
        response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    return new RpcResponse(response.statusCode(), response.headers(), body, response.body());
  }

  private HttpRequest.Builder request() {
    return HttpRequest.newBuilder(endpoint())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25");
  }

  private URI endpoint() {
    return URI.create("http://127.0.0.1:" + port + "/mcp");
  }

  private static Map<String, Object> requestBody(int id, String method, Map<String, ?> params) {
    return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
  }

  private static JsonNode structured(RpcResponse response) {
    assertThat(response.status()).withFailMessage(response.raw()).isEqualTo(200);
    return response.body().path("result").path("structuredContent");
  }

  private static List<String> resourceUris(RpcResponse response) {
    List<String> values = new ArrayList<>();
    response
        .body()
        .path("result")
        .path("resources")
        .forEach(resource -> values.add(resource.path("uri").asText()));
    return values;
  }

  private static List<String> resourceTemplateUris(RpcResponse response) {
    List<String> values = new ArrayList<>();
    response
        .body()
        .path("result")
        .path("resourceTemplates")
        .forEach(resource -> values.add(resource.path("uriTemplate").asText()));
    return values;
  }

  record RpcResponse(int status, java.net.http.HttpHeaders headers, JsonNode body, String raw) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class McpTestConfiguration {

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
            case "expired-token-marker", "wrong-audience-marker" ->
                throw new BadJwtException("Token is invalid");
            default -> throw new BadJwtException("Token is invalid");
          };
    }

    @Bean
    RequestContextCleanupProbe requestContextCleanupProbe(TenantContextHolder contexts) {
      return new RequestContextCleanupProbe(contexts);
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

  @Order(Ordered.HIGHEST_PRECEDENCE + 1)
  static final class RequestContextCleanupProbe extends OncePerRequestFilter {

    private final TenantContextHolder contexts;
    private final AtomicBoolean checked = new AtomicBoolean();
    private final AtomicBoolean cleared = new AtomicBoolean();

    private RequestContextCleanupProbe(TenantContextHolder contexts) {
      this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      filterChain.doFilter(request, response);
      if ("/mcp".equals(request.getRequestURI())) {
        checked.set(true);
        cleared.set(contexts.current().isEmpty());
      }
    }

    void reset() {
      checked.set(false);
      cleared.set(false);
    }

    boolean checked() {
      return checked.get();
    }

    boolean cleared() {
      return cleared.get();
    }
  }
}
