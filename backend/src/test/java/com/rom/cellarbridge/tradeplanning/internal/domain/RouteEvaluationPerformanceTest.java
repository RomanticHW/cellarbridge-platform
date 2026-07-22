package com.rom.cellarbridge.tradeplanning.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningService.RouteCandidate;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import com.rom.cellarbridge.tradeplanning.internal.domain.RouteEvaluationPolicy.Input;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.AvailabilityInput;
import com.rom.cellarbridge.tradeplanning.internal.domain.SupplyDecisionPolicy.LineInput;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RouteEvaluationPerformanceTest {

  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final UUID LINE = UUID.fromString("74000000-0000-4000-8000-000000000001");
  private static final UUID PRIMARY_POOL = UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final UUID SECONDARY_POOL =
      UUID.fromString("36000000-0000-4000-8000-000000000002");

  @Test
  void measuresDeterministicRouteEvaluation() throws Exception {
    int warmups = Integer.getInteger("cellarbridge.performance.route-warmups", 2_000);
    int iterations = Integer.getInteger("cellarbridge.performance.route-iterations", 10_000);
    assertThat(warmups).isPositive();
    assertThat(iterations).isPositive();

    Input input = input();
    List<RouteCandidate> expected = RouteEvaluationPolicy.evaluate(input).candidates();
    assertThat(RouteEvaluationPolicy.recommend(expected))
        .isEqualTo(TradeRouteCode.SH_GENERAL_TRADE);

    for (int iteration = 0; iteration < warmups; iteration++) {
      RouteEvaluationPolicy.evaluate(input);
    }

    List<Long> durations = new ArrayList<>(iterations);
    long startedAt = System.nanoTime();
    for (int iteration = 0; iteration < iterations; iteration++) {
      long operationStartedAt = System.nanoTime();
      List<RouteCandidate> actual = RouteEvaluationPolicy.evaluate(input).candidates();
      durations.add(System.nanoTime() - operationStartedAt);
      assertThat(actual).isEqualTo(expected);
    }
    long elapsedNanos = System.nanoTime() - startedAt;
    durations.sort(Long::compareTo);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("scenario", "deterministic-route-evaluation");
    result.put("policyVersion", RouteEvaluationPolicy.VERSION);
    result.put("warmups", warmups);
    result.put("iterations", iterations);
    result.put("p50Micros", percentileMicros(durations, 0.50));
    result.put("p95Micros", percentileMicros(durations, 0.95));
    result.put("p99Micros", percentileMicros(durations, 0.99));
    result.put("throughputPerSecond", iterations * 1_000_000_000.0 / elapsedNanos);
    result.put("errorRate", 0.0);
    result.put("recommendedRoute", TradeRouteCode.SH_GENERAL_TRADE.name());
    result.put("candidateCount", expected.size());

    String output = System.getProperty("cellarbridge.performance.route-output");
    if (output != null && !output.isBlank()) {
      Path outputPath = Path.of(output);
      if (outputPath.getParent() != null) {
        Files.createDirectories(outputPath.getParent());
      }
      JsonMapper.builder()
          .build()
          .writerWithDefaultPrettyPrinter()
          .writeValue(outputPath.toFile(), result);
    }

    System.out.printf(
        "Route evaluation: iterations=%d p50=%.3f us p95=%.3f us p99=%.3f us throughput=%.2f/s%n",
        iterations,
        result.get("p50Micros"),
        result.get("p95Micros"),
        result.get("p99Micros"),
        result.get("throughputPerSecond"));
  }

  private static double percentileMicros(List<Long> sortedDurations, double percentile) {
    int index = (int) Math.ceil(sortedDurations.size() * percentile) - 1;
    return sortedDurations.get(Math.max(index, 0)) / 1_000.0;
  }

  private static Input input() {
    return new Input(
        Set.of(TradeRouteCode.SH_GENERAL_TRADE, TradeRouteCode.NB_BONDED_B2B),
        "CNY",
        "CN",
        LocalDate.of(2026, 7, 30),
        30,
        LocalDate.of(2026, 7, 14),
        List.of(
            new LineInput(
                LINE,
                SKU,
                new BigDecimal("6"),
                TradePlanningQuantityUnit.CASE,
                new BigDecimal("6"),
                null)),
        List.of(
            availability(PRIMARY_POOL, TradeRouteCode.SH_GENERAL_TRADE, "56"),
            availability(SECONDARY_POOL, TradeRouteCode.NB_BONDED_B2B, "7")));
  }

  private static AvailabilityInput availability(
      UUID pool, TradeRouteCode route, String availableQuantity) {
    return new AvailabilityInput(
        pool,
        SKU,
        route,
        TradePlanningSupplyType.DOMESTIC_ON_HAND,
        TradePlanningQuantityUnit.CASE,
        new BigDecimal(availableQuantity),
        null,
        "HIGH",
        "INV-READY-2026-01",
        Instant.parse("2026-07-14T12:00:00Z"));
  }
}
