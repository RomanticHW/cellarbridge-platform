package com.rom.cellarbridge.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.platform.mcp.McpReadExecutor;
import com.rom.cellarbridge.platform.mcp.McpSafeException;
import com.rom.cellarbridge.platform.mcp.McpSecuritySupport;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "cellarbridge.mcp.security.allowed-hosts=127.0.0.1:*,localhost:*",
      "cellarbridge.mcp.security.allowed-clients=cellarbridge-mcp-host,scope-order-probe",
      "cellarbridge.mcp.security.rate-capacity=150",
      "cellarbridge.mcp.security.rate-refill-per-second=0.01"
    })
class OperationsMcpApiIntegrationTest extends PostgresIntegrationTestSupport {

  private static final String NORTH_SALES = "mcp-north-sales";
  private static final String NORTH_BUYER = "mcp-north-buyer";
  private static final String NORTH_ADMIN = "mcp-north-admin";
  private static final String NORTH_WAREHOUSE = "mcp-north-warehouse";
  private static final String HARBOR_MANAGER = "mcp-harbor-manager";
  private static final String NORTH_SKU = "34000000-0000-4000-8000-000000000001";
  private static final String SUBJECT = "34000000-0000-4000-8000-000000000001";
  private static final String IDENTITY_CORRELATION = "11000000-0000-4000-8000-000000000001";
  private static final String CLEANUP_PROBE_HEADER = "X-CellarBridge-Cleanup-Probe";
  private static final List<String> EXPECTED_TOOLS =
      List.of(
          "cellarbridge_current_user",
          "cellarbridge_get_dashboard",
          "cellarbridge_get_timeline",
          "cellarbridge_list_work_items",
          "cellarbridge_search_audit",
          "cellarbridge_search_supply");
  private static final String EXPECTED_SCHEMA_SNAPSHOT =
      "21d638e7c30ac9cb0d42d399e566bf8a39336855b1c5d0d6efc28521f81baf73";

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JsonMapper json;
  @Autowired private MeterRegistry meters;
  @Autowired private RequestContextCleanupProbe cleanupProbe;
  @Autowired private McpSecuritySupport mcpRuntime;
  @Autowired private McpReadExecutor mcpReads;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void requiresBearerAuthenticationAndRejectsInvalidOrigins() throws Exception {
    Map<?, ?> clients = (Map<?, ?>) ReflectionTestUtils.getField(mcpRuntime, "clients");
    Object rate = ReflectionTestUtils.invokeMethod(clients.get("cellarbridge-mcp-host"), "rate");
    assertThat(ReflectionTestUtils.getField(rate, "refillPerSecond")).isEqualTo(0.01d);
    RpcResponse missing = rpc(null, "tools/list", Map.of());
    assertThat(missing.status()).isEqualTo(401);
    assertThat(missing.raw()).contains("\"code\":\"AUTHENTICATION_REQUIRED\"");
    assertThat(
            meters
                .find("cellarbridge.mcp.response.bytes")
                .tag("rejection", "authentication_required")
                .summary()
                .totalAmount())
        .isEqualTo(missing.raw().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    assertThat(missing.headers().firstValue("www-authenticate").orElse(""))
        .contains(
            "resource_metadata=\"https://mcp.cellarbridge.example/.well-known/oauth-protected-resource/mcp\"")
        .contains("scope=\"mcp:read\"");

    HttpResponse<String> metadata =
        send(
            HttpRequest.newBuilder(uri("/.well-known/oauth-protected-resource/mcp"))
                .header("X-Correlation-ID", IDENTITY_CORRELATION)
                .GET());
    assertThat(metadata.statusCode()).isEqualTo(200);
    assertThat(metadata.headers().firstValue("X-Correlation-ID")).contains(IDENTITY_CORRELATION);
    assertThat(json.readTree(metadata.body()))
        .isEqualTo(
            json.readTree(
                """
                {"resource":"https://mcp.cellarbridge.example/mcp",
                 "authorization_servers":["http://localhost:8081/realms/cellarbridge"],
                 "bearer_methods_supported":["header"],"scopes_supported":["mcp:read"]}
                """));

    for (String invalid : List.of("expired-token-marker", "wrong-audience-marker")) {
      RpcResponse response = rpc(invalid, "tools/list", Map.of());
      assertThat(response.status()).isEqualTo(401);
      assertThat(response.raw())
          .contains("\"code\":\"INVALID_ACCESS_TOKEN\"")
          .doesNotContain(invalid);
    }
    for (String unavailable :
        List.of("authorization-unavailable-marker", "authentication-capacity-marker")) {
      RpcResponse response = rpc(unavailable, "tools/list", Map.of());
      assertThat(response.status()).isEqualTo(503);
      assertThat(response.headers().firstValue("www-authenticate")).isEmpty();
      assertThat(response.raw()).contains("\"code\":-32003").doesNotContain(unavailable);
    }
    RpcResponse unmapped = rpc("mcp-unmapped", "tools/list", Map.of());
    assertThat(unmapped.status()).isEqualTo(403);
    assertThat(unmapped.raw())
        .contains("\"code\":\"ACCESS_DENIED\"")
        .doesNotContain("insufficient_scope", "subjectHash", "tenantHash");
    for (int attempt = 0; attempt < 170; attempt++)
      assertThat(rpc("mcp-scope-missing", "tools/list", Map.of()).status()).isEqualTo(403);
    assertThat(rpc("mcp-scope-probe", "tools/list", Map.of()).status()).isEqualTo(200);

    RpcResponse denied =
        rpc(
            "expired-token-marker",
            "tools/list",
            Map.of(),
            Map.of("Origin", "https://origin.invalid"));
    assertThat(denied.status()).isEqualTo(403);
    assertThat(denied.headers().firstValue("access-control-allow-origin")).isEmpty();

    HttpResponse<String> allowedResponse =
        send(
            HttpRequest.newBuilder(uri("/mcp"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header(
                    "Access-Control-Request-Headers",
                    "authorization,content-type,mcp-protocol-version")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
    assertThat(allowedResponse.statusCode()).isEqualTo(200);
    assertThat(allowedResponse.headers().firstValue("access-control-allow-origin"))
        .contains("http://localhost:5173");

    RpcResponse unsupported =
        rpc(
            "expired-token-marker",
            "tools/list",
            Map.of(),
            Map.of("MCP-Protocol-Version", "2099-12-31-secret-marker"));
    assertThat(unsupported.status()).isEqualTo(400);
    assertThat(unsupported.headers().firstValue("cache-control")).contains("no-store");
    assertThat(unsupported.raw())
        .contains("Unsupported MCP protocol version")
        .doesNotContain("2099-12-31-secret-marker");

    HttpResponse<String> allowedGetResponse =
        send(
            HttpRequest.newBuilder(uri("/mcp"))
                .header("Authorization", "Bearer " + NORTH_SALES)
                .header("Origin", "http://localhost:5173")
                .header("Accept", "application/json, text/event-stream")
                .GET());
    assertThat(allowedGetResponse.statusCode()).isEqualTo(405);
    assertThat(allowedGetResponse.headers().firstValue("access-control-allow-origin"))
        .contains("http://localhost:5173");
  }

  @Test
  void rejectsNonCanonicalMcpAliasesBeforeAuthenticationOrDispatch() throws Exception {
    MockHttpServletRequest canonical = new MockHttpServletRequest("POST", "/gateway/mcp");
    canonical.setContextPath("/gateway");
    assertThat(McpSecuritySupport.isCanonicalPath(canonical)).isTrue();
    MockHttpServletRequest alias = new MockHttpServletRequest("POST", "/gateway/mcp%3Bmatrix");
    alias.setContextPath("/gateway");
    assertThat(McpSecuritySupport.isCanonicalPath(alias)).isFalse();
    assertThat(McpSecuritySupport.isMcpNamespace(alias)).isTrue();
    Map<String, String> inbound = Map.of("X-Correlation-ID", IDENTITY_CORRELATION);
    for (String path : List.of("/mcp;matrix", "/mcp%3Bmatrix", "/mcp/")) {
      RpcResponse response = rpc(path, "expired-token-marker", "tools/list", Map.of(), inbound);
      assertThat(response.status()).as(path).isEqualTo(404);
      assertThat(response.headers().firstValue("X-Correlation-ID").orElseThrow())
          .isNotEqualTo(IDENTITY_CORRELATION);
      assertThat(response.raw())
          .contains("MCP endpoint path is not canonical")
          .doesNotContain(
              path, "INVALID_ACCESS_TOKEN", "expired-token-marker", IDENTITY_CORRELATION);
    }
  }

  @Test
  void keepsClientPermitsUntilTimedOutWorkersActuallyExit() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<String> statementTimeout = new AtomicReference<>();
    Semaphore global = new Semaphore(0);
    Semaphore client = new Semaphore(0);
    Object permit = permitLease(global, client);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
    request.setAttribute(McpSecuritySupport.class.getName() + ".permitLease", permit);
    request.setAttribute(
        McpReadExecutor.class.getName() + ".deadline",
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      assertThatThrownBy(
              () -> mcpReads.executeIdentity(() -> blockedWorker(release, statementTimeout)))
          .isInstanceOf(McpSafeException.class)
          .hasMessage("DOWNSTREAM_TIMEOUT");
      ReflectionTestUtils.invokeMethod(permit, "close");
      assertThat(Long.parseLong(statementTimeout.get().replace("ms", ""))).isBetween(1L, 239L);
      assertThat(global.tryAcquire()).isFalse();
      assertThat(client.tryAcquire()).isFalse();
    } finally {
      release.countDown();
      RequestContextHolder.resetRequestAttributes();
    }
    assertThat(global.tryAcquire(2, TimeUnit.SECONDS)).isTrue();
    assertThat(client.tryAcquire(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void mapsEveryDatabaseAccessFailureToUnavailableHealth() {
    assertThatThrownBy(
            () ->
                mcpReads.executeIdentity(
                    () -> {
                      throw new org.springframework.dao.RecoverableDataAccessException("private");
                    }))
        .isInstanceOf(McpSafeException.class)
        .hasMessage("DEPENDENCY_UNAVAILABLE");
    assertThat(mcpRuntime.health().getStatus().getCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
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
    assertThat(listedValues(tools, "tools", "name"))
        .containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
    List<String> schemaHashes = new ArrayList<>();
    tools
        .body()
        .at("/result/tools")
        .forEach(
            tool -> {
              String name = tool.path("name").asText();
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
              JsonNode dataSchema = tool.at("/outputSchema/properties/data/anyOf/0");
              assertThat(dataSchema.path("type").asText()).isEqualTo("object");
              assertThat(dataSchema.path("properties").size()).isPositive();
              assertStrictObjectSchemas(tool.path("outputSchema"));
              schemaHashes.add(name + "=" + schemaHash(tool.path("outputSchema")));
            });
    String snapshot = String.join("\n", schemaHashes.stream().sorted().toList());
    assertThat(sha256(snapshot)).isEqualTo(EXPECTED_SCHEMA_SNAPSHOT);

    RpcResponse resources = rpc(NORTH_ADMIN, "resources/list", Map.of());
    assertThat(listedValues(resources, "resources", "uri"))
        .containsExactly("cellarbridge://session/me");
    RpcResponse templates = rpc(NORTH_ADMIN, "resources/templates/list", Map.of());
    assertThat(listedValues(templates, "resourceTemplates", "uriTemplate"))
        .containsExactlyInAnyOrder(
            "cellarbridge://catalog/skus/{skuId}",
            "cellarbridge://timeline/{subjectType}/{subjectId}");
    assertThat(templates.raw()).contains("cellarbridge_get_timeline", "nextCursor");

    RpcResponse session =
        rpc(NORTH_ADMIN, "resources/read", Map.of("uri", "cellarbridge://session/me"));
    JsonNode sessionEnvelope = resourceEnvelope(session);
    assertThat(sessionEnvelope.path("sourceKind").asText()).isEqualTo("SESSION");
    assertThat(sessionEnvelope.path("projectionStatus").asText()).isEqualTo("NOT_APPLICABLE");
    assertThat(sessionEnvelope.path("data").path("tenant").path("name").asText())
        .isEqualTo("North Cellars");
    assertThat(session.raw()).doesNotContain("subjectHash", "tenantHash", NORTH_ADMIN);

    RpcResponse timeline =
        rpc(
            NORTH_ADMIN,
            "resources/read",
            Map.of("uri", "cellarbridge://timeline/QUOTATION/" + SUBJECT));
    JsonNode timelineEnvelope = resourceEnvelope(timeline);
    assertThat(timelineEnvelope.at("/data/pageInfo/pageSize").asInt()).isEqualTo(25);
    assertThat(timelineEnvelope.at("/data/pageInfo/hasNext").isBoolean()).isTrue();

    RpcResponse prompts = rpc(NORTH_ADMIN, "prompts/list", Map.of());
    assertThat(listedValues(prompts, "prompts", "name"))
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
  void validatesEverySuccessfulToolResponseAgainstItsDeclaredDraft202012Schema() throws Exception {
    Map<String, JsonNode> schemas = listedToolSchemas();
    Map<String, Map<String, Object>> calls =
        Map.of(
            "cellarbridge_current_user", Map.of(),
            "cellarbridge_get_dashboard", Map.of("from", "2026-07-01", "to", "2026-07-31"),
            "cellarbridge_get_timeline",
                Map.of("subjectType", "QUOTATION", "subjectId", SUBJECT, "pageSize", 1),
            "cellarbridge_list_work_items", Map.of("scope", "personal", "pageSize", 1),
            "cellarbridge_search_audit",
                Map.of(
                    "from", "2026-01-01T00:00:00Z",
                    "to", "2026-07-24T00:00:00Z",
                    "pageSize", 1),
            "cellarbridge_search_supply", Map.of("keyword", "Moonlit Terrace", "pageSize", 1));
    for (Map.Entry<String, Map<String, Object>> call : calls.entrySet()) {
      RpcResponse response =
          callTool(
              NORTH_ADMIN,
              call.getKey(),
              call.getValue(),
              "cellarbridge_current_user".equals(call.getKey())
                  ? Map.of("X-Correlation-ID", IDENTITY_CORRELATION)
                  : Map.of());
      assertThat(response.status()).withFailMessage(response.raw()).isEqualTo(200);
      JsonNode structured = response.body().at("/result/structuredContent");
      JsonNode text = textEnvelope(response);
      assertThat(response.body().at("/result/isError").asBoolean()).isFalse();
      assertThat(text).as(call.getKey() + " text/structured equivalence").isEqualTo(structured);
      assertThat(structured.path("schemaVersion").asText()).isEqualTo("2.0");
      if ("cellarbridge_current_user".equals(call.getKey())) {
        String serverCorrelation = structured.path("correlationId").asText();
        assertThat(serverCorrelation).matches("[0-9a-f-]{36}").isNotEqualTo(IDENTITY_CORRELATION);
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(serverCorrelation);
        assertThat(response.raw()).doesNotContain(IDENTITY_CORRELATION);
      }
      assertValid(schemas.get(call.getKey()), structured, call.getKey());
    }
  }

  @Test
  void rejectsUnknownToolArgumentsAndKeepsTextAndStructuredEnvelopesEquivalent() throws Exception {
    RpcResponse currentUser = callTool(NORTH_SALES, "cellarbridge_current_user", Map.of());
    JsonNode structured = structured(currentUser);
    assertThat(textEnvelope(currentUser)).isEqualTo(structured);

    double applicationErrors = requestCount("application_error");
    RpcResponse unknownArgument =
        callTool(
            NORTH_SALES,
            "cellarbridge_current_user",
            Map.of("tenantId", "other-tenant-secret-marker"));
    assertThat(
            unknownArgument.body().path("result").path("isError").asBoolean()
                || unknownArgument.body().path("error").isObject())
        .isTrue();
    assertThat(unknownArgument.raw()).doesNotContain("other-tenant-secret-marker");
    assertThat(requestCount("application_error")).isEqualTo(applicationErrors + 1);
  }

  @Test
  void reportsEmptySupplyAndMapsCatalogValidationToASafeToolError() throws Exception {
    JsonNode empty =
        tool(NORTH_SALES, "cellarbridge_search_supply", Map.of("keyword", "Neverland Reserve"));
    assertThat(empty.path("isError").asBoolean()).isFalse();
    assertThat(empty.path("projectionStatus").asText()).isEqualTo("EMPTY");
    assertThat(empty.path("dataAsOf").isNull()).isTrue();
    assertThat(empty.at("/freshness/lagSeconds").isNull()).isTrue();
    assertThat(empty.at("/freshness/lastSuccessfulRefreshAt").isNull()).isTrue();
    assertThat(empty.path("warnings")).isNotEmpty();
    assertThat(empty.path("data").path("items")).isEmpty();
    assertThat(empty.path("data").path("dataAsOf").isNull()).isTrue();

    JsonNode invalid = tool(NORTH_SALES, "cellarbridge_search_supply", Map.of("keyword", "*"));
    assertThat(invalid.path("isError").asBoolean()).isTrue();
    assertThat(invalid.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(invalid.path("retryable").asBoolean()).isFalse();

    JsonNode firstPage =
        tool(NORTH_SALES, "cellarbridge_search_supply", Map.of("pageSize", 1, "sort", "name"));
    String firstCursor = firstPage.path("data").path("pageInfo").path("nextCursor").asText();
    assertThat(firstCursor).isNotBlank();
    List<String> skuIds = new ArrayList<>();
    List<String> skuNames = new ArrayList<>();
    JsonNode page = firstPage;
    while (true) {
      page.path("data")
          .path("items")
          .forEach(
              item -> {
                skuIds.add(item.path("sku").path("skuId").asText());
                skuNames.add(item.path("sku").path("displayName").asText());
              });
      if (!page.path("data").path("pageInfo").path("hasNext").asBoolean()) break;
      String next = page.path("data").path("pageInfo").path("nextCursor").asText();
      page =
          tool(
              NORTH_SALES,
              "cellarbridge_search_supply",
              Map.of("pageSize", 1, "sort", "name", "cursor", next));
    }
    assertThat(skuIds).hasSizeGreaterThan(1).doesNotHaveDuplicates();
    assertThat(skuNames).contains("Moonlit Terrace", "Moonlit Terrace");
    JsonNode schema = listedToolSchemas().get("cellarbridge_search_supply");
    for (Map<String, ?> arguments :
        List.of(
            Map.of("pageSize", 1, "sort", "name", "cursor", tamper(firstCursor)),
            Map.of("pageSize", 1, "keyword", "Moonlit Terrace", "cursor", firstCursor))) {
      RpcResponse response = callTool(NORTH_SALES, "cellarbridge_search_supply", arguments);
      JsonNode error = structured(response);
      assertThat(error.path("schemaVersion").asText()).isEqualTo("2.0");
      assertThat(error.path("code").asText()).isEqualTo("CURSOR_INVALID");
      assertThat(error.path("data").isNull()).isTrue();
      assertThat(textEnvelope(response)).isEqualTo(error);
      assertValid(schema, error, "cursor error");
    }
    for (String token : List.of(HARBOR_MANAGER, NORTH_WAREHOUSE)) {
      JsonNode error =
          tool(
              token,
              "cellarbridge_search_supply",
              Map.of("pageSize", 1, "sort", "name", "cursor", firstCursor));
      assertThat(error.path("code").asText()).isEqualTo("CURSOR_INVALID");
    }
  }

  @Test
  void preservesSupplyFieldPermissionsWarehouseScopeAndTenantIsolation() throws Exception {
    Map<String, Object> search =
        Map.of(
            "keyword",
            "Moonlit Terrace",
            "supplyTypes",
            List.of("DOMESTIC_ON_HAND"),
            "quantityUnits",
            List.of("CASE"));
    JsonNode sales = tool(NORTH_SALES, "cellarbridge_search_supply", search);
    assertThat(sales.path("isError").asBoolean()).isFalse();
    assertThat(sales.path("sourceKind").asText()).isEqualTo("SUPPLY_PROJECTION");
    assertThat(sales.path("projectionStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(sales.at("/freshness/mode").asText()).isEqualTo("OBSERVATION_AGE");
    assertThat(sales.at("/freshness/projectorWatermark").isNull()).isTrue();
    assertThat(sales.path("warnings").toString()).contains("FRESHNESS_NOT_SOURCE_VERIFIED");
    assertThat(exactLots(sales)).isEmpty();
    assertThat(sales.at("/data/items/0/supplies/0/displayedAvailableQuantity").isMissingNode())
        .isTrue();

    JsonNode warehouse = tool(NORTH_WAREHOUSE, "cellarbridge_search_supply", search);
    assertThat(exactLots(warehouse)).isNotEmpty();

    Map<String, Object> unassigned = Map.of("supplyTypes", List.of("HONG_KONG_ON_HAND"));
    JsonNode hidden = tool(NORTH_WAREHOUSE, "cellarbridge_search_supply", unassigned);
    assertThat(exactLots(hidden)).isEmpty();

    Map<String, Object> tenantSearch = Map.of("keyword", "Moonlit Terrace");
    JsonNode north = tool(NORTH_SALES, "cellarbridge_search_supply", tenantSearch);
    JsonNode harbor = tool(HARBOR_MANAGER, "cellarbridge_search_supply", tenantSearch);
    assertThat(north.toString()).contains("CB-MTV-2019-750X6").doesNotContain("HB-MTV");
    assertThat(harbor.toString()).contains("HB-MTV-2019-750X6").doesNotContain("CB-MTV");

    RpcResponse crossTenantResource =
        rpc(
            HARBOR_MANAGER,
            "resources/read",
            Map.of("uri", "cellarbridge://catalog/skus/" + NORTH_SKU));
    JsonNode crossTenantEnvelope = resourceEnvelope(crossTenantResource);
    assertThat(crossTenantEnvelope.path("isError").asBoolean()).isTrue();
    assertThat(crossTenantEnvelope.path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(crossTenantEnvelope.path("data").isNull()).isTrue();
    assertThat(crossTenantResource.raw()).doesNotContain("CB-MTV").doesNotContain("north-cellars");

    JsonNode buyer = tool(NORTH_BUYER, "cellarbridge_search_supply", search);
    assertThat(buyer.path("isError").asBoolean()).isTrue();
    assertThat(buyer.path("code").asText()).isEqualTo("ACCESS_DENIED");
  }

  @Test
  void enforcesReportingScopeTimelineGuardAndSafeErrors() throws Exception {
    JsonNode teamDenied =
        tool(NORTH_SALES, "cellarbridge_list_work_items", Map.of("scope", "team"));
    assertThat(teamDenied.path("isError").asBoolean()).isTrue();
    assertThat(teamDenied.path("code").asText()).isEqualTo("ACCESS_DENIED");

    JsonNode timelineDenied =
        tool(
            NORTH_SALES,
            "cellarbridge_get_timeline",
            Map.of("subjectType", "QUOTATION", "subjectId", SUBJECT));
    assertThat(timelineDenied.path("isError").asBoolean()).isTrue();
    assertThat(timelineDenied.path("code").asText()).isEqualTo("ACCESS_DENIED");

    JsonNode dashboard =
        tool(
            NORTH_ADMIN,
            "cellarbridge_get_dashboard",
            Map.of("from", "2026-07-01", "to", "2026-07-31"));
    assertThat(dashboard.path("isError").asBoolean()).isFalse();
    assertThat(List.of("CURRENT", "STALE", "EMPTY"))
        .contains(dashboard.path("projectionStatus").asText());

    String hostileCursor =
        "SELECT * FROM audit_reporting.audit_entry JWT stacktrace internal.package";
    RpcResponse invalidAudit =
        callTool(NORTH_ADMIN, "cellarbridge_search_audit", Map.of("cursor", hostileCursor));
    JsonNode safe = structured(invalidAudit);
    assertThat(invalidAudit.body().path("result").path("isError").asBoolean()).isTrue();
    assertThat(safe.path("code").asText()).isEqualTo("CURSOR_INVALID");
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
  void enforcesPageSizeBoundariesForEveryPaginatedTool() throws Exception {
    Map<String, Map<String, Object>> tools =
        Map.of(
            "cellarbridge_search_supply", Map.of(),
            "cellarbridge_list_work_items", Map.of("scope", "personal"),
            "cellarbridge_get_timeline", Map.of("subjectType", "QUOTATION", "subjectId", SUBJECT),
            "cellarbridge_search_audit", Map.of());
    for (Map.Entry<String, Map<String, Object>> entry : tools.entrySet()) {
      for (int pageSize : List.of(1, 100)) {
        assertThat(
                tool(NORTH_ADMIN, entry.getKey(), withPageSize(entry.getValue(), pageSize))
                    .path("isError")
                    .asBoolean())
            .as(entry.getKey() + " pageSize=" + pageSize)
            .isFalse();
      }
      for (int pageSize : List.of(0, 101)) {
        RpcResponse invalid =
            callTool(NORTH_ADMIN, entry.getKey(), withPageSize(entry.getValue(), pageSize));
        assertThat(isInputRejected(invalid))
            .as(entry.getKey() + " pageSize=" + pageSize)
            .withFailMessage(invalid.raw())
            .isTrue();
      }
    }
  }

  @Test
  void keepsTenantContextInsideTheRequestAndCleansItAfterward() throws Exception {
    String probeId = cleanupProbe.reset();
    JsonNode current =
        structured(
            callTool(
                NORTH_SALES,
                "cellarbridge_current_user",
                Map.of(),
                Map.of(CLEANUP_PROBE_HEADER, probeId)));
    assertThat(current.path("data").path("displayName").asText()).isEqualTo("North Sales");
    assertThat(cleanupProbe.awaitCompletion()).isTrue();
    assertThat(cleanupProbe.checked()).isTrue();
    assertThat(cleanupProbe.cleared()).isTrue();
  }

  private RpcResponse rpc(String token, String method, Map<String, ?> params) throws Exception {
    return rpc(token, method, params, Map.of());
  }

  private RpcResponse callTool(String token, String name, Map<String, ?> arguments)
      throws Exception {
    return callTool(token, name, arguments, Map.of());
  }

  private RpcResponse callTool(
      String token, String name, Map<String, ?> arguments, Map<String, String> headers)
      throws Exception {
    return rpc(token, "tools/call", Map.of("name", name, "arguments", arguments), headers);
  }

  private RpcResponse rpc(
      String token, String method, Map<String, ?> params, Map<String, String> headers)
      throws Exception {
    return rpc("/mcp", token, method, params, headers);
  }

  private RpcResponse rpc(
      String path, String token, String method, Map<String, ?> params, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder builder =
        request(path)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(requestBody(1, method, params))));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    headers.forEach(builder::setHeader);
    HttpResponse<String> response = send(builder);
    JsonNode body =
        response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    return new RpcResponse(response.statusCode(), response.headers(), body, response.body());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(uri(path))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25");
  }

  private HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static Object permitLease(Semaphore global, Semaphore client) throws Exception {
    var constructor =
        Class.forName(McpSecuritySupport.class.getName() + "$PermitLease")
            .getDeclaredConstructor(Semaphore.class, Semaphore.class);
    constructor.setAccessible(true);
    return constructor.newInstance(global, client);
  }

  private String blockedWorker(CountDownLatch release, AtomicReference<String> timeout) {
    timeout.set(jdbc.queryForObject("SHOW statement_timeout", String.class));
    while (release.getCount() != 0) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
      Thread.interrupted();
    }
    return "complete";
  }

  private static Map<String, Object> requestBody(int id, String method, Map<String, ?> params) {
    return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
  }

  private double requestCount(String rejection) {
    return meters.find("cellarbridge.mcp.requests").tag("rejection", rejection).counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  private static JsonNode structured(RpcResponse response) {
    assertThat(response.status()).withFailMessage(response.raw()).isEqualTo(200);
    return response.body().at("/result/structuredContent");
  }

  private JsonNode textEnvelope(RpcResponse response) {
    return json.readTree(response.body().at("/result/content/0/text").asText());
  }

  private JsonNode resourceEnvelope(RpcResponse response) {
    return json.readTree(response.body().at("/result/contents/0/text").asText());
  }

  private static JsonNode exactLots(JsonNode envelope) {
    return envelope.at("/data/items/0/supplies/0/exactLots");
  }

  private JsonNode tool(String token, String name, Map<String, ?> arguments) throws Exception {
    return structured(callTool(token, name, arguments));
  }

  private static Map<String, Object> withPageSize(Map<String, Object> arguments, int pageSize) {
    Map<String, Object> result = new LinkedHashMap<>(arguments);
    result.put("pageSize", pageSize);
    return result;
  }

  private Map<String, JsonNode> listedToolSchemas() throws Exception {
    RpcResponse response = rpc(NORTH_ADMIN, "tools/list", Map.of());
    assertThat(response.status()).withFailMessage(response.raw()).isEqualTo(200);
    Map<String, JsonNode> schemas = new LinkedHashMap<>();
    response
        .body()
        .at("/result/tools")
        .forEach(tool -> schemas.put(tool.path("name").asText(), tool.path("outputSchema")));
    assertThat(schemas.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
    return schemas;
  }

  private static void assertValid(JsonNode schema, JsonNode value, String label) {
    assertThat(schema).as(label + " schema").isNotNull();
    var errors =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schema)
            .validate(value);
    assertThat(errors).as(label + " schema violations: " + errors).isEmpty();
  }

  private static void assertStrictObjectSchemas(JsonNode schema) {
    JsonNode type = schema.path("type");
    if (type.isTextual() && "object".equals(type.asText())) {
      assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
      assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }
    schema.forEach(OperationsMcpApiIntegrationTest::assertStrictObjectSchemas);
  }

  private static boolean isInputRejected(RpcResponse response) {
    JsonNode body = response.body();
    String text = body.at("/result/content/0/text").asText();
    return "-32602".equals(body.at("/error/code").asText())
        || "VALIDATION_FAILED".equals(body.at("/result/structuredContent/code").asText())
        || (body.at("/result/isError").asBoolean()
            && "text".equals(body.at("/result/content/0/type").asText())
            && text.startsWith("Tool (cellarbridge_")
            && text.contains(") input validation failed:"));
  }

  private static String schemaHash(JsonNode schema) {
    return sha256(canonical(schema).toString());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      var result = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      node.propertyNames().stream()
          .sorted()
          .forEach(name -> result.set(name, canonical(node.get(name))));
      return result;
    }
    if (node.isArray()) {
      var result = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
      node.forEach(value -> result.add(canonical(value)));
      return result;
    }
    return node;
  }

  private static String tamper(String cursor) {
    int signature = cursor.lastIndexOf('.') + 1;
    char original = cursor.charAt(signature);
    char replacement = original == 'A' ? 'B' : 'A';
    return cursor.substring(0, signature) + replacement + cursor.substring(signature + 1);
  }

  private static List<String> listedValues(RpcResponse response, String collection, String field) {
    List<String> values = new ArrayList<>();
    response
        .body()
        .at("/result/" + collection)
        .forEach(item -> values.add(item.path(field).asText()));
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
            case "mcp-unmapped" ->
                jwt(token, "99000000-0000-4000-8000-000000000001", "north-cellars");
            case "mcp-scope-missing", "mcp-scope-probe" ->
                jwt(token, "11000000-0000-4000-8000-000000000001", "north-cellars");
            case "expired-token-marker", "wrong-audience-marker" ->
                throw new BadJwtException("Token is invalid");
            case "authorization-unavailable-marker" ->
                throw new org.springframework.security.oauth2.jwt.JwtException(
                    "Authorization unavailable", new TimeoutException());
            case "authentication-capacity-marker" ->
                throw new org.springframework.security.oauth2.jwt.JwtException(
                    "Authentication capacity unavailable", new RejectedExecutionException());
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
          .audience(List.of("https://mcp.cellarbridge.example/mcp"))
          .issuedAt(now.minusSeconds(30))
          .expiresAt(now.plusSeconds(300))
          .claim("resource", "https://mcp.cellarbridge.example/mcp")
          .claim(
              "azp", token.startsWith("mcp-scope") ? "scope-order-probe" : "cellarbridge-mcp-host")
          .claim("scope", "mcp-scope-missing".equals(token) ? "openid" : "mcp:read")
          .claim("tenant_code", tenantCode)
          .build();
    }
  }

  @Order(Ordered.HIGHEST_PRECEDENCE + 1)
  static final class RequestContextCleanupProbe extends OncePerRequestFilter {

    private final TenantContextHolder contexts;
    private final AtomicReference<ProbeState> state = new AtomicReference<>();

    private RequestContextCleanupProbe(TenantContextHolder contexts) {
      this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      ProbeState probe = state.get();
      try {
        filterChain.doFilter(request, response);
      } finally {
        if (probe != null
            && probe.id().equals(request.getHeader(CLEANUP_PROBE_HEADER))
            && "/mcp".equals(request.getRequestURI())) {
          probe.checked().set(true);
          probe.cleared().set(contexts.current().isEmpty());
          probe.completed().countDown();
        }
      }
    }

    String reset() {
      String id = UUID.randomUUID().toString();
      state.set(
          new ProbeState(id, new CountDownLatch(1), new AtomicBoolean(), new AtomicBoolean()));
      return id;
    }

    boolean awaitCompletion() throws InterruptedException {
      ProbeState probe = state.get();
      return probe != null && probe.completed().await(5, TimeUnit.SECONDS);
    }

    boolean checked() {
      ProbeState probe = state.get();
      return probe != null && probe.checked().get();
    }

    boolean cleared() {
      ProbeState probe = state.get();
      return probe != null && probe.cleared().get();
    }

    private record ProbeState(
        String id, CountDownLatch completed, AtomicBoolean checked, AtomicBoolean cleared) {}
  }
}
