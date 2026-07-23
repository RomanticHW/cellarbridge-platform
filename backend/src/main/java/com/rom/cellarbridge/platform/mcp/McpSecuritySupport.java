package com.rom.cellarbridge.platform.mcp;

import com.rom.cellarbridge.platform.ProblemResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component("mcpRuntime")
public final class McpSecuritySupport implements HealthIndicator {
  private static final String CAPABILITY = McpSecuritySupport.class.getName() + ".capability";
  private static final long HEALTH_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

  private final McpSecurityProperties properties;
  private final JsonMapper json;
  private final ProblemResponseWriter problems;
  private final MeterRegistry meters;
  private final Tracer tracer;
  private final boolean configured;
  private final boolean enabled;
  private final Semaphore global;
  private final Map<String, Semaphore> clientConcurrency = new ConcurrentHashMap<>();
  private final Map<String, TokenBucket> rates = new ConcurrentHashMap<>();
  private final AtomicReference<State> state = new AtomicReference<>(new State("UP", 0));

  McpSecuritySupport(
      McpSecurityProperties properties,
      JsonMapper json,
      ProblemResponseWriter problems,
      MeterRegistry meters,
      Tracer tracer,
      @Value("${spring.datasource.hikari.maximum-pool-size:10}") int databasePoolSize,
      @Value("${spring.ai.mcp.server.enabled:false}") boolean enabled) {
    this.properties = properties;
    this.json = json;
    this.problems = problems;
    this.meters = meters;
    this.tracer = tracer;
    this.configured = properties.configured(databasePoolSize);
    this.enabled = enabled;
    this.global = new Semaphore(properties.maxConcurrency());
    for (String client : properties.allowedClients()) {
      clientConcurrency.put(client, new Semaphore(properties.maxClientConcurrency()));
      rates.put(
          client, new TokenBucket(properties.rateCapacity(), properties.rateRefillPerSecond()));
    }
  }

  public OncePerRequestFilter ingressFilter() {
    return new IngressFilter();
  }

  public OncePerRequestFilter admissionFilter() {
    return new AdmissionFilter();
  }

  public AuthenticationEntryPoint authenticationEntryPoint() {
    return this::authenticate;
  }

  public AccessDeniedHandler accessDeniedHandler() {
    return this::denyScope;
  }

  public void timeout() {
    mark("DOWNSTREAM_TIMEOUT");
  }

  public void overloaded() {
    mark("BULKHEAD_SATURATED");
  }

  @Override
  public Health health() {
    if (!enabled) return Health.status("DISABLED").build();
    if (!configured) return Health.status("MISCONFIGURED").build();
    State current = state.get();
    boolean fresh =
        current.recordedAtNanos() != 0
            && System.nanoTime() - current.recordedAtNanos() < HEALTH_TTL_NANOS;
    return Health.status(fresh ? current.name() : "UP").build();
  }

  private void authenticate(
      HttpServletRequest request, HttpServletResponse response, Throwable failure)
      throws IOException {
    boolean presented = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
    String rejection = presented ? "invalid_token" : "authentication_required";
    if (authorizationServerUnavailable(failure)) mark("AUTHORIZATION_SERVER_UNAVAILABLE");
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(presented ? "invalid_token" : null));
    record(request, "unknown", "rejected", rejection, 0, 0);
    problems.write(
        response,
        HttpStatus.UNAUTHORIZED,
        presented ? "INVALID_ACCESS_TOKEN" : "AUTHENTICATION_REQUIRED",
        "Authentication required",
        "A valid MCP access token is required.",
        false);
  }

  private void denyScope(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure)
      throws IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean hasScope =
        authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(
                    authority -> authority.getAuthority().equals("SCOPE_" + properties.scope()));
    if (!hasScope)
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge("insufficient_scope"));
    problems.write(
        response,
        HttpStatus.FORBIDDEN,
        hasScope ? "ACCESS_DENIED" : "INSUFFICIENT_SCOPE",
        hasScope ? "Access denied" : "Insufficient scope",
        hasScope
            ? "The authenticated user is not allowed to access this operation."
            : "The access token does not grant the required MCP scope.",
        false);
  }

  private String challenge(String error) {
    String value =
        "Bearer resource_metadata=\"%s\", scope=\"%s\""
            .formatted(properties.metadataUri(), properties.scope());
    return error == null ? value : value + ", error=\"" + error + "\"";
  }

  private void mark(String name) {
    state.set(new State(name, System.nanoTime()));
  }

  private void record(
      HttpServletRequest request,
      String client,
      String outcome,
      String rejection,
      long elapsedNanos,
      int responseBytes) {
    String protocol = protocol(request.getHeader("MCP-Protocol-Version"));
    String capability = String.valueOf(request.getAttribute(CAPABILITY));
    if ("null".equals(capability)) capability = "unknown";
    String[] tags = {
      "protocol", protocol,
      "client", client,
      "capability", capability,
      "outcome", outcome,
      "rejection", rejection
    };
    meters.counter("cellarbridge.mcp.requests", tags).increment();
    meters.timer("cellarbridge.mcp.latency", tags).record(elapsedNanos, TimeUnit.NANOSECONDS);
    meters.summary("cellarbridge.mcp.response.bytes", tags).record(responseBytes);
    Span span = tracer.currentSpan();
    if (span != null && !span.isNoop()) {
      span.tag("mcp.protocol.version", protocol)
          .tag("mcp.client.class", client)
          .tag("mcp.capability", capability)
          .tag("mcp.outcome", outcome)
          .tag("mcp.rejection", rejection);
    }
  }

  private static String protocol(String value) {
    return value == null
        ? "missing"
        : Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(value) ? value : "unsupported";
  }

  private boolean allowed(String candidate, List<String> allowlist) {
    if (candidate == null) return false;
    return allowlist.stream()
        .anyMatch(
            allowed ->
                allowed.equals(candidate)
                    || (allowed.endsWith(":*")
                        && (candidate.equals(allowed.substring(0, allowed.length() - 2))
                            || candidate.startsWith(allowed.substring(0, allowed.length() - 1)))));
  }

  private void writeRpc(HttpServletResponse response, int status, int code, String message)
      throws IOException {
    response.resetBuffer();
    response.setStatus(status);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":%d,\"message\":\"%s\"}}"
            .formatted(code, message);
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }

  private void rejectIngress(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      int code,
      String message,
      String reason)
      throws IOException {
    writeRpc(response, status, code, message);
    record(request, "unknown", "rejected", reason, 0, 0);
  }

  private void rejectOversized(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    rejectIngress(
        request, response, 413, -32001, "Request body exceeds configured limit", "body_too_large");
  }

  private byte[] limitedBody(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > properties.maxRequestBytes()) return null;
    byte[] body = request.getInputStream().readNBytes(properties.maxRequestBytes() + 1);
    return body.length > properties.maxRequestBytes() ? null : body;
  }

  private static boolean authorizationServerUnavailable(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof IOException
          || current.getClass().getSimpleName().contains("RemoteKeySource")) return true;
    }
    return false;
  }

  private final class IngressFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      if (!"/mcp".equals(request.getRequestURI())) {
        chain.doFilter(request, response);
        return;
      }
      request.setAttribute(
          McpReadExecutor.DEADLINE, System.nanoTime() + properties.requestTimeout().toNanos());
      if (!configured) {
        rejectIngress(
            request,
            response,
            503,
            -32003,
            "MCP security configuration unavailable",
            "misconfigured");
        mark("MISCONFIGURED");
        return;
      }
      if (!allowed(request.getHeader(HttpHeaders.HOST), properties.allowedHosts())) {
        rejectIngress(request, response, 421, -32000, "MCP host is not allowed", "invalid_host");
        return;
      }
      String origin = request.getHeader(HttpHeaders.ORIGIN);
      if (origin != null && !allowed(origin, properties.allowedOrigins())) {
        rejectIngress(
            request, response, 403, -32000, "MCP origin is not allowed", "invalid_origin");
        return;
      }
      if (!HttpMethod.POST.matches(request.getMethod())) {
        chain.doFilter(request, response);
        return;
      }
      byte[] body = limitedBody(request);
      if (body == null) {
        rejectOversized(request, response);
        return;
      }
      ByteArrayRequest wrapped = new ByteArrayRequest(request, body);
      wrapped.setAttribute(CAPABILITY, capability(body));
      try {
        chain.doFilter(wrapped, response);
      } catch (AuthenticationException failure) {
        authenticate(wrapped, response, failure);
      }
    }

    private String capability(byte[] body) {
      try {
        JsonNode root = json.readTree(body);
        String method = root.path("method").asText();
        return switch (method) {
          case "initialize",
              "ping",
              "tools/list",
              "resources/list",
              "resources/templates/list",
              "resources/read",
              "prompts/list",
              "prompts/get" ->
              method.replace('/', '.');
          case "tools/call" -> {
            String tool = root.at("/params/name").asText();
            yield switch (tool) {
              case "cellarbridge_current_user",
                  "cellarbridge_get_dashboard",
                  "cellarbridge_get_timeline",
                  "cellarbridge_list_work_items",
                  "cellarbridge_search_audit",
                  "cellarbridge_search_supply" ->
                  tool;
              default -> "unknown";
            };
          }
          default -> "unknown";
        };
      } catch (RuntimeException ignored) {
        // The MCP transport returns the stable invalid-request response.
      }
      return "unknown";
    }
  }

  private final class AdmissionFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      if (!"/mcp".equals(request.getRequestURI())
          || HttpMethod.OPTIONS.matches(request.getMethod())) {
        chain.doFilter(request, response);
        return;
      }
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (!(authentication instanceof JwtAuthenticationToken jwt)) {
        chain.doFilter(request, response);
        return;
      }
      String client = jwt.getToken().getClaimAsString("azp");
      if (client == null) client = jwt.getToken().getClaimAsString("client_id");
      TokenBucket bucket = rates.get(client);
      if (bucket == null || !bucket.take()) {
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        writeRpc(response, 429, -32002, "Rate limit exceeded");
        mark("RATE_LIMITED");
        record(request, "registered", "rejected", "rate_limited", 0, 0);
        return;
      }
      Semaphore clientLimit = clientConcurrency.get(client);
      boolean globalAcquired = global.tryAcquire();
      boolean clientAcquired = globalAcquired && clientLimit != null && clientLimit.tryAcquire();
      if (!clientAcquired) {
        if (globalAcquired) global.release();
        writeRpc(response, 503, -32003, "MCP request capacity unavailable");
        overloaded();
        record(request, "registered", "rejected", "bulkhead", 0, 0);
        return;
      }
      ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
      long started = System.nanoTime();
      try {
        String rejection;
        int responseBytes;
        try {
          chain.doFilter(request, wrapped);
          if (wrapped.getStatus() >= 500) writeRpc(wrapped, 500, -32603, "MCP request failed");
          rejection = responseRejection(wrapped);
          if (!"downstream_timeout".equals(rejection) && !"overloaded".equals(rejection))
            mark("UP");
          responseBytes = wrapped.getContentSize();
        } catch (McpSafeException failure) {
          boolean timeout = "DOWNSTREAM_TIMEOUT".equals(failure.code());
          writeRpc(wrapped, timeout ? 504 : 503, timeout ? -32004 : -32003, failure.safeMessage());
          rejection = timeout ? "downstream_timeout" : "overloaded";
          responseBytes = wrapped.getContentSize();
        } catch (ServletException | IOException | RuntimeException failure) {
          writeRpc(wrapped, 500, -32603, "MCP request failed");
          rejection = "internal";
          responseBytes = 0;
        }
        record(
            request,
            "registered",
            "none".equals(rejection) ? "success" : "rejected",
            rejection,
            System.nanoTime() - started,
            responseBytes);
        wrapped.copyBodyToResponse();
      } finally {
        clientLimit.release();
        global.release();
      }
    }

    private String responseRejection(ContentCachingResponseWrapper response) {
      try {
        JsonNode root = json.readTree(response.getContentAsByteArray());
        JsonNode envelope = root.at("/result/structuredContent");
        String text = root.at("/result/contents/0/text").asText();
        if (envelope.isMissingNode() && !text.isBlank()) envelope = json.readTree(text);
        String code = root.path("code").asText(envelope.path("code").asText());
        if (root.has("error")) return "protocol_error";
        return switch (code) {
          case "ACCESS_DENIED" -> "access_denied";
          case "DOWNSTREAM_TIMEOUT" -> "downstream_timeout";
          case "DEPENDENCY_OVERLOADED" -> "overloaded";
          case "DEPENDENCY_UNAVAILABLE" -> "downstream_unavailable";
          case "INSUFFICIENT_SCOPE" -> "insufficient_scope";
          default ->
              envelope.path("isError").asBoolean()
                  ? "application_error"
                  : response.getStatus() >= 400 ? "transport_error" : "none";
        };
      } catch (RuntimeException ignored) {
        return response.getStatus() >= 400 ? "transport_error" : "none";
      }
    }
  }

  private record State(String name, long recordedAtNanos) {}

  private static final class TokenBucket {
    private final int capacity, refillPerSecond;
    private double tokens;
    private long last = System.nanoTime();

    private TokenBucket(int capacity, int refillPerSecond) {
      this.capacity = capacity;
      this.refillPerSecond = refillPerSecond;
      this.tokens = capacity;
    }

    private synchronized boolean take() {
      long now = System.nanoTime();
      tokens = Math.min(capacity, tokens + (now - last) / 1_000_000_000d * refillPerSecond);
      last = now;
      if (tokens < 1) return false;
      tokens--;
      return true;
    }
  }

  private static final class ByteArrayRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    private ByteArrayRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {}
      };
    }
  }
}
