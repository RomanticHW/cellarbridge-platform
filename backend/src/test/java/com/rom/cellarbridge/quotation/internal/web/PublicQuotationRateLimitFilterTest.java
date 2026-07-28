package com.rom.cellarbridge.quotation.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class PublicQuotationRateLimitFilterTest {

  @Test
  void limitsDecisionBurstWithoutEchoingTheCapabilityToken() throws Exception {
    MutableTicker ticker = new MutableTicker();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PublicQuotationRateLimitFilter filter = filter(ticker, meters, 10_000);
    String token = token(1);

    for (int attempt = 0; attempt < 30; attempt++) {
      MockHttpServletResponse allowed = invoke(filter, token, "acceptance");
      assertThat(allowed.getStatus()).isEqualTo(200);
    }

    MockHttpServletResponse limited = invoke(filter, token, "acceptance");
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader("Retry-After")).isEqualTo("2");
    assertThat(limited.getContentAsString())
        .contains("\"code\":\"RATE_LIMITED\"")
        .doesNotContain(token);
    assertThat(
            meters
                .get("cellarbridge.quotation.portal.rate_limit.requests")
                .tags("scope", "decision", "key_class", "capability", "outcome", "limited")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void acceptanceAndRejectionShareOneDecisionBudgetWhileReadsRemainIndependent() throws Exception {
    PublicQuotationRateLimitFilter filter =
        filter(new MutableTicker(), new SimpleMeterRegistry(), 10_000);
    String token = token(2);

    for (int attempt = 0; attempt < 29; attempt++) {
      assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(200);
    }
    assertThat(invoke(filter, token, "rejection").getStatus()).isEqualTo(200);
    assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(429);

    for (int attempt = 0; attempt < 120; attempt++) {
      assertThat(invoke(filter, token, null).getStatus()).isEqualTo(200);
    }
    MockHttpServletResponse limitedRead = invoke(filter, token, null);
    assertThat(limitedRead.getStatus()).isEqualTo(429);
    assertThat(limitedRead.getHeader("Retry-After")).isEqualTo("1");
  }

  @Test
  void canonicalizesUrlEncodedCapabilityAliasesBeforeHashing() throws Exception {
    PublicQuotationRateLimitFilter filter =
        filter(new MutableTicker(), new SimpleMeterRegistry(), 10_000);
    String token = token(3);
    String encodedAlias = "%54" + token.substring(1);

    for (int attempt = 0; attempt < 29; attempt++) {
      assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(200);
    }
    assertThat(invoke(filter, encodedAlias, "rejection").getStatus()).isEqualTo(200);
    assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(429);
    assertThat(filter.trackedBucketCount()).isEqualTo(1);
  }

  @Test
  void preventsMinuteBoundaryDoubleBurstAndDoesNotAdvanceOnRejection() throws Exception {
    MutableTicker ticker = new MutableTicker();
    PublicQuotationRateLimitFilter filter = filter(ticker, new SimpleMeterRegistry(), 10_000);
    String token = token(4);
    assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(200);
    ticker.advance(Duration.ofSeconds(59));

    for (int attempt = 0; attempt < 30; attempt++) {
      assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(200);
    }
    for (int rejected = 0; rejected < 20; rejected++) {
      assertThat(invoke(filter, token, "acceptance").getHeader("Retry-After")).isEqualTo("2");
    }

    ticker.advance(Duration.ofSeconds(1));
    assertThat(invoke(filter, token, "acceptance").getHeader("Retry-After")).isEqualTo("1");
    ticker.advance(Duration.ofNanos(Duration.ofSeconds(1).toNanos() - 1));
    assertThat(invoke(filter, token, "acceptance").getHeader("Retry-After")).isEqualTo("1");
    ticker.advance(Duration.ofNanos(1));
    assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(200);
    assertThat(invoke(filter, token, "acceptance").getHeader("Retry-After")).isEqualTo("2");
  }

  @Test
  void admitsExactlyOneDecisionBurstUnderConcurrency() throws Exception {
    MutableTicker ticker = new MutableTicker();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PublicQuotationRateLimitFilter filter = filter(ticker, meters, 10_000);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(24);
    List<Future<Integer>> responses = new ArrayList<>();

    try {
      for (int request = 0; request < 96; request++) {
        responses.add(
            pool.submit(
                () -> {
                  start.await();
                  return invoke(filter, token(5), "acceptance").getStatus();
                }));
      }
      start.countDown();
      long allowed =
          responses.stream()
              .map(PublicQuotationRateLimitFilterTest::await)
              .filter(status -> status == 200)
              .count();
      assertThat(allowed).isEqualTo(30);
      assertThat(filter.trackedBucketCount()).isEqualTo(1);
      ticker.advance(Duration.ofSeconds(2));
      assertThat(invoke(filter, token(5), "acceptance").getStatus()).isEqualTo(200);
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void boundsMalformedTokensIntoLowCardinalityKeysAndMetrics() throws Exception {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PublicQuotationRateLimitFilter filter = filter(new MutableTicker(), meters, 10_000);

    for (int request = 0; request < 2_000; request++) {
      invoke(filter, "short-invalid-" + request, request % 2 == 0 ? null : "acceptance");
    }
    invoke(filter, "%ZZ", null);

    assertThat(filter.trackedBucketCount()).isLessThanOrEqualTo(128);
    assertThat(meters.getMeters()).hasSizeLessThanOrEqualTo(8);
    assertThat(meters.getMeters())
        .allSatisfy(
            meter -> {
              assertThat(meter.getId().getName())
                  .isEqualTo("cellarbridge.quotation.portal.rate_limit.requests");
              assertThat(meter.getId().getTags())
                  .extracting(Tag::getKey)
                  .containsExactlyInAnyOrder("scope", "key_class", "outcome");
              assertThat(meter.getId().getTags())
                  .extracting(Tag::getValue)
                  .allMatch(
                      value ->
                          List.of("read", "decision", "invalid", "allowed", "limited")
                              .contains(value));
            });
  }

  @Test
  void reservesCapacityAtomicallyAndReclaimsOnlyFullyRefilledBuckets() throws Exception {
    MutableTicker ticker = new MutableTicker();
    PublicQuotationRateLimitFilter filter = filter(ticker, new SimpleMeterRegistry(), 2);

    for (int request = 0; request < 3; request++) {
      assertThat(invoke(filter, token(10), null).getStatus()).isEqualTo(200);
    }
    assertThat(invoke(filter, token(11), null).getStatus()).isEqualTo(200);
    assertThat(invoke(filter, token(12), null).getStatus()).isEqualTo(429);
    assertThat(filter.trackedBucketCount()).isEqualTo(2);

    ticker.advance(Duration.ofSeconds(1));
    assertThat(invoke(filter, token(12), null).getStatus()).isEqualTo(200);
    assertThat(invoke(filter, token(13), null).getStatus()).isEqualTo(429);
    assertThat(filter.trackedBucketCount()).isEqualTo(2);

    ticker.advance(Duration.ofSeconds(1));
    assertThat(invoke(filter, token(13), null).getStatus()).isEqualTo(200);
    assertThat(filter.trackedBucketCount()).isEqualTo(1);
  }

  @Test
  void pathParametersCannotBypassOrSplitTheDecisionBudget() throws Exception {
    PublicQuotationRateLimitFilter filter =
        filter(new MutableTicker(), new SimpleMeterRegistry(), 10_000);
    String token = token(14);

    for (int attempt = 0; attempt < 29; attempt++) {
      assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(200);
    }
    assertThat(
            invokePath(
                    filter,
                    "/api/v1/portal/quotations/" + token + ";source=mail/rejection;intent=confirm")
                .getStatus())
        .isEqualTo(200);
    assertThat(invoke(filter, token, "acceptance").getStatus()).isEqualTo(429);
    assertThat(filter.trackedBucketCount()).isEqualTo(1);
  }

  @Test
  void neverExceedsTheTrackedBucketCapForConcurrentNewKeys() throws Exception {
    PublicQuotationRateLimitFilter filter =
        filter(new MutableTicker(), new SimpleMeterRegistry(), 8);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(24);
    List<Future<Integer>> responses = new ArrayList<>();

    try {
      for (int request = 0; request < 96; request++) {
        int tokenNumber = 100 + request;
        responses.add(
            pool.submit(
                () -> {
                  start.await();
                  return invoke(filter, token(tokenNumber), null).getStatus();
                }));
      }
      start.countDown();
      assertThat(responses.stream().map(PublicQuotationRateLimitFilterTest::await))
          .containsOnly(200, 429);
      assertThat(filter.trackedBucketCount()).isEqualTo(8);
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static PublicQuotationRateLimitFilter filter(
      LongSupplier ticker, SimpleMeterRegistry meters, int maxTrackedBuckets) {
    return new PublicQuotationRateLimitFilter(
        JsonMapper.builder().build(), meters, ticker, maxTrackedBuckets);
  }

  private static int await(Future<Integer> response) {
    try {
      return response.get(5, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static MockHttpServletResponse invoke(
      PublicQuotationRateLimitFilter filter, String token, String action) throws Exception {
    String suffix = action == null ? "" : "/" + action;
    return invokePath(filter, "/api/v1/portal/quotations/" + token + suffix);
  }

  private static MockHttpServletResponse invokePath(
      PublicQuotationRateLimitFilter filter, String requestUri) throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest(
            requestUri.contains("/acceptance") || requestUri.contains("/rejection")
                ? "POST"
                : "GET",
            requestUri);
    request.setRemoteAddr("192.0.2.10");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  private static String token(int sequence) {
    return "T%042d".formatted(sequence);
  }

  private static final class MutableTicker implements LongSupplier {
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long getAsLong() {
      return nanos.get();
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }
  }
}
