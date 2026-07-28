package com.rom.cellarbridge.quotation.internal.web;

import com.rom.cellarbridge.platform.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final Pattern CAPABILITY_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
  private static final Policy READ_POLICY = Policy.every(Duration.ofMillis(500), 120);
  private static final Policy DECISION_POLICY = Policy.every(Duration.ofSeconds(2), 30);
  private static final long CLEANUP_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
  private static final int INVALID_BUCKETS = 64;
  private static final int MAX_TRACKED_BUCKETS = 10_000;
  private static final String REQUEST_METRIC = "cellarbridge.quotation.portal.rate_limit.requests";

  private final JsonMapper jsonMapper;
  private final MeterRegistry meters;
  private final LongSupplier ticker;
  private final int maxTrackedBuckets;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final AtomicInteger slots = new AtomicInteger();
  private final AtomicLong lastCleanupNanos = new AtomicLong(Long.MIN_VALUE);

  @Autowired
  PublicQuotationRateLimitFilter(JsonMapper jsonMapper, MeterRegistry meters) {
    this(jsonMapper, meters, System::nanoTime, MAX_TRACKED_BUCKETS);
  }

  PublicQuotationRateLimitFilter(
      JsonMapper jsonMapper, MeterRegistry meters, LongSupplier ticker, int maxTrackedBuckets) {
    this.jsonMapper = jsonMapper;
    this.meters = meters;
    this.ticker = ticker;
    this.maxTrackedBuckets = maxTrackedBuckets;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Matcher path = PORTAL_PATH.matcher(withoutPathParameters(request.getRequestURI()));
    if (!path.matches() || "OPTIONS".equals(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }

    long now = ticker.getAsLong();
    cleanupExpiredBuckets(now);
    Scope scope = path.group(2) == null ? Scope.READ : Scope.DECISION;
    KeyMaterial material = keyMaterial(path.group(1));
    Admission admission = consume(scope.keyPrefix + material.key(), scope.policy, now);
    recordRequest(scope, material.keyClass(), admission.allowed() ? "allowed" : "limited");
    if (admission.allowed()) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(429);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(admission.retryAfterSeconds()));
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

  private Admission consume(String key, Policy policy, long now) {
    AtomicReference<Admission> result = new AtomicReference<>();
    buckets.compute(
        key,
        (ignored, current) -> {
          if (current == null && !reserveSlot()) {
            result.set(Admission.limited(1));
            return null;
          }
          long theoreticalArrival =
              current == null || now - current.theoreticalArrivalNanos() >= 0
                  ? now
                  : current.theoreticalArrivalNanos();
          long allowedAt = theoreticalArrival - policy.toleranceNanos();
          if (now - allowedAt < 0) {
            result.set(Admission.limited(ceilSeconds(allowedAt - now)));
            return current;
          }
          result.set(Admission.admitted());
          return new Bucket(theoreticalArrival + policy.intervalNanos());
        });
    return result.get();
  }

  private boolean reserveSlot() {
    int current;
    do {
      current = slots.get();
      if (current >= maxTrackedBuckets) {
        return false;
      }
    } while (!slots.compareAndSet(current, current + 1));
    return true;
  }

  private void cleanupExpiredBuckets(long now) {
    long previous = lastCleanupNanos.get();
    if (previous != Long.MIN_VALUE && now - previous < CLEANUP_INTERVAL_NANOS) {
      return;
    }
    if (!lastCleanupNanos.compareAndSet(previous, now)) {
      return;
    }
    buckets.forEach(
        (key, bucket) -> {
          if (now - bucket.theoreticalArrivalNanos() >= 0 && buckets.remove(key, bucket)) {
            slots.decrementAndGet();
          }
        });
  }

  private void recordRequest(Scope scope, String keyClass, String outcome) {
    meters
        .counter(
            REQUEST_METRIC, "scope", scope.metricTag, "key_class", keyClass, "outcome", outcome)
        .increment();
  }

  private static KeyMaterial keyMaterial(String rawToken) {
    String decoded;
    try {
      decoded = URLDecoder.decode(rawToken, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException invalidEncoding) {
      return invalidKey(rawToken);
    }
    if (CAPABILITY_TOKEN.matcher(decoded).matches()) {
      return new KeyMaterial(sha256(decoded), "capability");
    }
    return invalidKey(rawToken);
  }

  private static String withoutPathParameters(String requestUri) {
    if (requestUri.indexOf(';') < 0) {
      return requestUri;
    }
    StringBuilder normalized = new StringBuilder(requestUri.length());
    boolean parameter = false;
    for (int index = 0; index < requestUri.length(); index++) {
      char character = requestUri.charAt(index);
      if (character == ';') {
        parameter = true;
      } else if (character == '/') {
        parameter = false;
        normalized.append(character);
      } else if (!parameter) {
        normalized.append(character);
      }
    }
    return normalized.toString();
  }

  private static KeyMaterial invalidKey(String rawToken) {
    int bucket = Byte.toUnsignedInt(digest(rawToken)[0]) & (INVALID_BUCKETS - 1);
    return new KeyMaterial("invalid-" + bucket, "invalid");
  }

  private static String sha256(String value) {
    return HexFormat.of().formatHex(digest(value));
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static long ceilSeconds(long nanos) {
    return Math.max(1, (nanos + CLEANUP_INTERVAL_NANOS - 1) / CLEANUP_INTERVAL_NANOS);
  }

  int trackedBucketCount() {
    return slots.get();
  }

  private enum Scope {
    READ("read:", "read", READ_POLICY),
    DECISION("decision:", "decision", DECISION_POLICY);

    private final String keyPrefix;
    private final String metricTag;
    private final Policy policy;

    Scope(String keyPrefix, String metricTag, Policy policy) {
      this.keyPrefix = keyPrefix;
      this.metricTag = metricTag;
      this.policy = policy;
    }
  }

  private record Policy(long intervalNanos, long toleranceNanos) {
    static Policy every(Duration interval, int burst) {
      return new Policy(interval.toNanos(), Math.multiplyExact(burst - 1L, interval.toNanos()));
    }
  }

  private record Bucket(long theoreticalArrivalNanos) {}

  private record KeyMaterial(String key, String keyClass) {}

  private record Admission(boolean allowed, long retryAfterSeconds) {
    static Admission admitted() {
      return new Admission(true, 0);
    }

    static Admission limited(long retryAfterSeconds) {
      return new Admission(false, retryAfterSeconds);
    }
  }

  private record RateLimitProblem(
      String type,
      String title,
      int status,
      String detail,
      String code,
      String traceId,
      boolean retryable) {}
}
