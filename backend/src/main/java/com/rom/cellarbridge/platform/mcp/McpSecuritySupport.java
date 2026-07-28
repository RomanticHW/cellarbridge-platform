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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class McpSecuritySupport extends OncePerRequestFilter
    implements HealthIndicator, AuthenticationEntryPoint, AccessDeniedHandler {
  private static final String ENDPOINT = "/mcp";
  private static final String METADATA = "/.well-known/oauth-protected-resource/mcp";
  private static final String CAPABILITY = McpSecuritySupport.class.getName() + ".capability";
  private static final String PERMIT_LEASE = McpSecuritySupport.class.getName() + ".permitLease";
  private static final long HEALTH_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
  private static final Set<String> PROTOCOLS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
  private static final Logger LOGGER = LoggerFactory.getLogger(McpSecuritySupport.class);
  private static final Rejection INVALID_PATH =
      new Rejection(404, -32600, "MCP endpoint path is not canonical", "invalid_path", null);
  private static final Rejection MISCONFIGURED =
      unavailable("MCP security configuration unavailable", "misconfigured", "MISCONFIGURED");
  private static final Rejection INVALID_HOST =
      new Rejection(421, -32000, "MCP host is not allowed", "invalid_host", null);
  private static final Rejection INVALID_ORIGIN =
      new Rejection(403, -32000, "MCP origin is not allowed", "invalid_origin", null);
  private static final Rejection UNSUPPORTED_PROTOCOL =
      new Rejection(400, -32600, "Unsupported MCP protocol version", "unsupported_protocol", null);
  private static final Rejection BODY_TOO_LARGE =
      new Rejection(413, -32001, "Request body exceeds configured limit", "body_too_large", null);
  private static final Rejection GLOBAL_BULKHEAD =
      unavailable("MCP request capacity unavailable", "global_bulkhead", "BULKHEAD_SATURATED");
  private static final Rejection CLIENT_BULKHEAD =
      unavailable("MCP request capacity unavailable", "client_bulkhead", "BULKHEAD_SATURATED");
  private static final Rejection RATE_LIMITED =
      new Rejection(429, -32002, "Rate limit exceeded", "rate_limited", "RATE_LIMITED");
  private static final Rejection AUTHORIZATION_UNAVAILABLE =
      unavailable(
          "MCP authorization service unavailable",
          "authorization_server_unavailable",
          "AUTHORIZATION_SERVER_UNAVAILABLE");
  private static final Rejection AUTHENTICATION_BULKHEAD =
      unavailable(
          "MCP authentication capacity unavailable",
          "authentication_bulkhead",
          "BULKHEAD_SATURATED");
  private static final Rejection DEADLINE_EXCEEDED =
      new Rejection(
          504, -32004, "MCP request deadline exceeded", "downstream_timeout", "DOWNSTREAM_TIMEOUT");
  private static final String COMPLETION_LOG =
      "mcpRequestCompleted protocol={} clientClass={} capability={} outcome={} rejection={} responseSizeClass={}";

  private static Rejection unavailable(String message, String reason, String health) {
    return new Rejection(503, -32003, message, reason, health);
  }

  private final McpSecurityProperties properties;
  private final JsonMapper json;
  private final ProblemResponseWriter problems;
  private final MeterRegistry meters;
  private final Tracer tracer;
  private final boolean configured;
  private final boolean enabled;
  private final Semaphore global;
  private final Map<String, ClientGuard> clients = new ConcurrentHashMap<>();
  private volatile State state = new State("UP", 0);

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
    this.global = new Semaphore(Math.max(1, properties.maxConcurrency()));
    for (String client : properties.allowedClients()) {
      clients.put(
          client,
          new ClientGuard(
              new Semaphore(Math.max(1, properties.maxClientConcurrency())),
              new TokenBucket(
                  Math.max(1, properties.rateCapacity()), properties.rateRefillPerSecond())));
    }
  }

  public OncePerRequestFilter admissionFilter() {
    return new AdmissionFilter();
  }

  public static boolean isCanonicalPath(HttpServletRequest request) {
    return ENDPOINT.equals(applicationPath(request));
  }

  public static boolean isMcpNamespace(HttpServletRequest request) {
    String path = applicationPath(request);
    if (path == null || !path.startsWith(ENDPOINT))
      return ENDPOINT.equals(request.getServletPath());
    String suffix = path.substring(ENDPOINT.length()).toLowerCase(Locale.ROOT);
    return suffix.isEmpty()
        || List.of("/", ";", "\\", "%2f", "%3b", "%5c").stream().anyMatch(suffix::startsWith);
  }

  public static boolean hasUnsupportedProtocol(HttpServletRequest request) {
    String protocol = request.getHeader("MCP-Protocol-Version");
    return HttpMethod.POST.matches(request.getMethod())
        && isCanonicalPath(request)
        && protocol != null
        && !PROTOCOLS.contains(protocol);
  }

  private static String applicationPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    return uri.startsWith(context) ? uri.substring(context.length()) : null;
  }

  private static boolean isMetadata(HttpServletRequest request) {
    return METADATA.equals(applicationPath(request));
  }

  McpSafeException reportFailure(McpSafeException failure) {
    switch (failure.code()) {
      case "DOWNSTREAM_TIMEOUT" -> mark("DOWNSTREAM_TIMEOUT");
      case "DEPENDENCY_UNAVAILABLE" -> mark("DEPENDENCY_UNAVAILABLE");
      case "DEPENDENCY_OVERLOADED" -> mark("BULKHEAD_SATURATED");
      default -> {}
    }
    return failure;
  }

  @Override
  public Health health() {
    if (!enabled) return Health.status("DISABLED").build();
    if (!configured) return Health.status("MISCONFIGURED").build();
    State current = state;
    boolean fresh =
        current.recordedAtNanos() != 0
            && System.nanoTime() - current.recordedAtNanos() < HEALTH_TTL_NANOS;
    return Health.status(fresh ? current.name() : "UP").build();
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    if (failure instanceof AuthenticationServiceException) {
      reject(
          request,
          response,
          "unknown",
          causedBy(failure, RejectedExecutionException.class)
              ? AUTHENTICATION_BULKHEAD
              : AUTHORIZATION_UNAVAILABLE);
      return;
    }
    boolean presented = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
    String rejection = presented ? "invalid_token" : "authentication_required";
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(presented ? "invalid_token" : null));
    ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
    problems.write(
        wrapped,
        HttpStatus.UNAUTHORIZED,
        presented ? "INVALID_ACCESS_TOKEN" : "AUTHENTICATION_REQUIRED",
        "Authentication required",
        "A valid MCP access token is required.",
        false);
    record(request, "unknown", "rejected", rejection, 0, wrapped.getContentSize());
    wrapped.copyBodyToResponse();
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure)
      throws IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean hasScope = hasScope(authentication);
    if (!hasScope)
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge("insufficient_scope"));
    ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
    problems.write(
        wrapped,
        HttpStatus.FORBIDDEN,
        hasScope ? "ACCESS_DENIED" : "INSUFFICIENT_SCOPE",
        hasScope ? "Access denied" : "Insufficient scope",
        hasScope
            ? "The authenticated user is not allowed to access this operation."
            : "The access token does not grant the required MCP scope.",
        false);
    record(
        request,
        "registered",
        "rejected",
        hasScope ? "access_denied" : "insufficient_scope",
        0,
        wrapped.getContentSize());
    wrapped.copyBodyToResponse();
  }

  private String challenge(String error) {
    String value =
        "Bearer resource_metadata=\"%s\", scope=\"%s\""
            .formatted(properties.metadataUri(), properties.scope());
    return error == null ? value : value + ", error=\"" + error + "\"";
  }

  private boolean hasScope(Authentication authentication) {
    return authentication != null
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("SCOPE_" + properties.scope()));
  }

  private void mark(String name) {
    state = new State(name, System.nanoTime());
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
    String responseSizeClass = responseSizeClass(responseBytes);
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
          .tag("mcp.rejection", rejection)
          .tag("mcp.response.size.class", responseSizeClass);
    }
    LOGGER.info(
        COMPLETION_LOG, protocol, client, capability, outcome, rejection, responseSizeClass);
  }

  private static String responseSizeClass(int bytes) {
    if (bytes <= 0) return "empty";
    if (bytes <= 4 * 1024) return "small";
    if (bytes <= 64 * 1024) return "medium";
    return "large";
  }

  private static String protocol(String value) {
    return value == null ? "missing" : PROTOCOLS.contains(value) ? value : "unsupported";
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

  private int writeRpc(HttpServletResponse response, int status, int code, String message)
      throws IOException {
    response.resetBuffer();
    response.setStatus(status);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    byte[] body =
        "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":%d,\"message\":\"%s\"}}"
            .formatted(code, message)
            .getBytes(StandardCharsets.UTF_8);
    response.getOutputStream().write(body);
    return body.length;
  }

  private void reject(
      HttpServletRequest request, HttpServletResponse response, String client, Rejection rejection)
      throws IOException {
    int bytes = writeRpc(response, rejection.status(), rejection.code(), rejection.message());
    if (rejection.health() != null) mark(rejection.health());
    record(request, client, "rejected", rejection.reason(), 0, bytes);
  }

  private byte[] limitedBody(HttpServletRequest request, long deadline) throws IOException {
    int limit = properties.maxRequestBytes() + 1, offset = 0;
    if (request.getContentLengthLong() >= limit) return null;
    byte[] body = new byte[limit];
    ServletInputStream input = request.getInputStream();
    while (offset < limit && System.nanoTime() < deadline) {
      int count = input.read(body, offset, limit - offset);
      if (System.nanoTime() >= deadline) return null;
      if (count < 0) return java.util.Arrays.copyOf(body, offset);
      offset += count;
    }
    return null;
  }

  private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (type.isInstance(current)) return true;
    }
    return false;
  }

  PermitLease workerLease() {
    if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            instanceof org.springframework.web.context.request.ServletRequestAttributes attributes
        && attributes.getRequest().getAttribute(PERMIT_LEASE) instanceof PermitLease lease) {
      return lease.retain();
    }
    return PermitLease.NOOP;
  }

  private Rejection ingressRejection(HttpServletRequest request) {
    if (!isCanonicalPath(request)) return INVALID_PATH;
    if (!configured) return MISCONFIGURED;
    if (!allowed(request.getHeader(HttpHeaders.HOST), properties.allowedHosts()))
      return INVALID_HOST;
    String origin = request.getHeader(HttpHeaders.ORIGIN);
    if (origin != null && !allowed(origin, properties.allowedOrigins())) return INVALID_ORIGIN;
    return hasUnsupportedProtocol(request) ? UNSUPPORTED_PROTOCOL : null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!enabled) {
      chain.doFilter(request, response);
      return;
    }
    if (isMetadata(request)) {
      if (!configured) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        problems.write(
            response,
            HttpStatus.SERVICE_UNAVAILABLE,
            "MCP_MISCONFIGURED",
            "MCP unavailable",
            "MCP security configuration is unavailable.",
            false);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (!isMcpNamespace(request)) {
      chain.doFilter(request, response);
      return;
    }
    Rejection rejection = ingressRejection(request);
    if (rejection != null) {
      reject(request, response, "unknown", rejection);
      return;
    }
    long deadline = System.nanoTime() + properties.requestTimeout().toNanos();
    request.setAttribute(McpReadExecutor.DEADLINE, deadline);
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    HttpServletRequest active = request;
    if (HttpMethod.POST.matches(request.getMethod())) {
      byte[] body;
      try {
        body = limitedBody(request, deadline);
      } catch (IOException failure) {
        if (!causedBy(failure, java.net.SocketTimeoutException.class)) throw failure;
        reject(request, response, "unknown", DEADLINE_EXCEEDED);
        return;
      }
      if (body == null) {
        reject(
            request,
            response,
            "unknown",
            System.nanoTime() >= deadline ? DEADLINE_EXCEEDED : BODY_TOO_LARGE);
        return;
      }
      active = new ByteArrayRequest(request, body);
      active.setAttribute(CAPABILITY, capability(body));
    }
    try {
      chain.doFilter(active, response);
    } catch (AuthenticationServiceException failure) {
      commence(active, response, failure);
    }
  }

  private String capability(byte[] body) {
    try {
      JsonNode root = json.readTree(body);
      String method = root.path("method").asText();
      String tool = root.at("/params/name").asText();
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
        case "tools/call" ->
            switch (tool) {
              case "cellarbridge_current_user",
                  "cellarbridge_get_dashboard",
                  "cellarbridge_get_timeline",
                  "cellarbridge_list_work_items",
                  "cellarbridge_search_audit",
                  "cellarbridge_search_supply" ->
                  tool;
              default -> "unknown";
            };
        default -> "unknown";
      };
    } catch (RuntimeException ignored) {
      // The MCP transport returns the stable invalid-request response.
    }
    return "unknown";
  }

  private final class AdmissionFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      if (!isCanonicalPath(request) || HttpMethod.OPTIONS.matches(request.getMethod())) {
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
      if (!(request.getAttribute(McpReadExecutor.DEADLINE) instanceof Long deadline)
          || deadline <= System.nanoTime()) {
        reject(request, response, "registered", DEADLINE_EXCEEDED);
        return;
      }
      ClientGuard guard = clients.get(client);
      if (guard == null || !guard.rate().take()) {
        response.setHeader(
            HttpHeaders.RETRY_AFTER,
            Long.toString(Math.max(1L, (long) Math.ceil(1 / properties.rateRefillPerSecond()))));
        reject(request, response, "registered", RATE_LIMITED);
        return;
      }
      Semaphore clientLimit = guard.concurrency();
      if (!clientLimit.tryAcquire()) {
        reject(request, response, "registered", CLIENT_BULKHEAD);
        return;
      }
      if (!global.tryAcquire()) {
        clientLimit.release();
        reject(request, response, "registered", GLOBAL_BULKHEAD);
        return;
      }
      PermitLease lease = new PermitLease(global, clientLimit);
      request.setAttribute(PERMIT_LEASE, lease);
      try {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        long started = System.nanoTime();
        String rejection;
        try {
          chain.doFilter(request, wrapped);
          if (wrapped.getStatus() >= 500) writeRpc(wrapped, 500, -32603, "MCP request failed");
          rejection = responseRejection(wrapped);
          if (wrapped.getStatus() < 500
              && !Set.of("downstream_timeout", "downstream_unavailable", "executor_bulkhead")
                  .contains(rejection)) mark("UP");
        } catch (McpSafeException failure) {
          boolean timeout = "DOWNSTREAM_TIMEOUT".equals(failure.code());
          boolean unavailable = "DEPENDENCY_UNAVAILABLE".equals(failure.code());
          reportFailure(failure);
          writeRpc(wrapped, timeout ? 504 : 503, timeout ? -32004 : -32003, failure.safeMessage());
          rejection =
              timeout
                  ? "downstream_timeout"
                  : unavailable ? "downstream_unavailable" : "executor_bulkhead";
        } catch (AccessDeniedException failure) {
          problems.write(
              wrapped,
              HttpStatus.FORBIDDEN,
              "ACCESS_DENIED",
              "Access denied",
              "The authenticated user is not allowed to access this operation.",
              false);
          rejection = "access_denied";
        } catch (ServletException | IOException | RuntimeException failure) {
          writeRpc(wrapped, 500, -32603, "MCP request failed");
          rejection = "internal";
        }
        record(
            request,
            "registered",
            "none".equals(rejection) ? "success" : "rejected",
            rejection,
            System.nanoTime() - started,
            wrapped.getContentSize());
        wrapped.copyBodyToResponse();
      } finally {
        lease.close();
      }
    }

    private String responseRejection(ContentCachingResponseWrapper response) {
      try {
        JsonNode root = json.readTree(response.getContentAsByteArray());
        if (root.has("error")) return "protocol_error";
        JsonNode result = root.path("result");
        JsonNode envelope = result.path("structuredContent");
        if (!envelope.isObject() && result.path("isError").asBoolean()) return "application_error";
        String text = result.at("/content/0/text").asText(result.at("/contents/0/text").asText());
        if (!envelope.isObject() && !text.isBlank()) envelope = json.readTree(text);
        String code = envelope.path("code").asText();
        return switch (code) {
          case "ACCESS_DENIED" -> "access_denied";
          case "DOWNSTREAM_TIMEOUT" -> "downstream_timeout";
          case "DEPENDENCY_OVERLOADED" -> "executor_bulkhead";
          case "DEPENDENCY_UNAVAILABLE" -> "downstream_unavailable";
          case "INSUFFICIENT_SCOPE" -> "insufficient_scope";
          default ->
              envelope.path("isError").asBoolean()
                  ? "application_error"
                  : response.getStatus() >= 400 ? "transport_error" : "none";
        };
      } catch (RuntimeException ignored) {
        return response.getStatus() >= 400 ? "transport_error" : "application_error";
      }
    }
  }

  private record Rejection(int status, int code, String message, String reason, String health) {}

  private record State(String name, long recordedAtNanos) {}

  private record ClientGuard(Semaphore concurrency, TokenBucket rate) {}

  static final class PermitLease implements AutoCloseable {
    private static final PermitLease NOOP = new PermitLease(null, null);
    private final Semaphore global;
    private final Semaphore client;
    private int holders;

    private PermitLease(Semaphore global, Semaphore client) {
      this.global = global;
      this.client = client;
      this.holders = global == null ? 0 : 1;
    }

    private synchronized PermitLease retain() {
      if (holders == 0) return NOOP;
      holders++;
      return this;
    }

    @Override
    public synchronized void close() {
      if (holders == 0 || --holders != 0) return;
      client.release();
      global.release();
    }
  }

  private static final class TokenBucket {
    private final int capacity;
    private final double refillPerSecond;
    private double tokens;
    private long last = System.nanoTime();

    private TokenBucket(int capacity, double refillPerSecond) {
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
