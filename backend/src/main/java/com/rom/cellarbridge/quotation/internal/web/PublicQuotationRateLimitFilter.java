package com.rom.cellarbridge.quotation.internal.web;

import com.rom.cellarbridge.platform.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class PublicQuotationRateLimitFilter extends OncePerRequestFilter {

  private static final Pattern PORTAL_PATH =
      Pattern.compile("^/api/v1/portal/quotations/([^/]+)(?:/(acceptance|rejection))?/?$");
  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final int CAPABILITY_READ_LIMIT = 120;
  private static final int CAPABILITY_DECISION_LIMIT = 30;
  private static final int MAX_TRACKED_WINDOWS = 10_000;

  private final Clock clock;
  private final JsonMapper jsonMapper;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final AtomicLong requestCounter = new AtomicLong();

  PublicQuotationRateLimitFilter(Clock clock, JsonMapper jsonMapper) {
    this.clock = clock;
    this.jsonMapper = jsonMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Matcher path = PORTAL_PATH.matcher(request.getRequestURI());
    if (!path.matches() || "OPTIONS".equals(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }

    Instant now = clock.instant();
    cleanupExpiredWindows(now);
    String capabilityDigest = sha256(path.group(1));
    boolean decision = path.group(2) != null;
    boolean allowed =
        consume(
            (decision ? "decision:" : "read:") + capabilityDigest,
            decision ? CAPABILITY_DECISION_LIMIT : CAPABILITY_READ_LIMIT,
            now);
    if (allowed) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(429);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(WINDOW.toSeconds()));
    String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
    jsonMapper.writeValue(
        response.getOutputStream(),
        new RateLimitProblem(
            "about:blank",
            "Too many requests",
            429,
            "The secure quotation request limit was exceeded. Retry later.",
            "RATE_LIMITED",
            traceId == null ? "unavailable" : traceId,
            true));
  }

  private boolean consume(String key, int limit, Instant now) {
    if (windows.size() >= MAX_TRACKED_WINDOWS && !windows.containsKey(key)) {
      windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().resetAt()));
      if (windows.size() >= MAX_TRACKED_WINDOWS) {
        return false;
      }
    }
    AtomicBoolean allowed = new AtomicBoolean();
    windows.compute(
        key,
        (ignored, current) -> {
          Window active =
              current == null || !now.isBefore(current.resetAt())
                  ? new Window(now.plus(WINDOW), 0)
                  : current;
          int nextCount = active.count() + 1;
          allowed.set(nextCount <= limit);
          return new Window(active.resetAt(), nextCount);
        });
    return allowed.get();
  }

  private void cleanupExpiredWindows(Instant now) {
    if ((requestCounter.incrementAndGet() & 255L) == 0L) {
      windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().resetAt()));
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record Window(Instant resetAt, int count) {}

  private record RateLimitProblem(
      String type,
      String title,
      int status,
      String detail,
      String code,
      String traceId,
      boolean retryable) {}
}
