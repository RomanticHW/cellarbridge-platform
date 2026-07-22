package com.rom.cellarbridge.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessEventMetricsTest {

  private static final Set<String> FORBIDDEN_TAG_KEYS =
      Set.of(
          "tenantId",
          "userId",
          "partnerId",
          "orderId",
          "subjectId",
          "eventId",
          "businessNumber",
          "correlationId");

  @Test
  void businessMetricsUseOnlyControlledLowCardinalityTags() {
    assertThat(BusinessEventMetrics.definitions().values())
        .allSatisfy(
            metric -> {
              assertThat(metric.tagKey()).isNotIn(FORBIDDEN_TAG_KEYS);
              assertThat(metric.tagValue()).matches("[a-z_]+");
            });
  }

  @Test
  void incrementsKnownEventsAndIgnoresUnknownEvents() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BusinessEventMetrics metrics = new BusinessEventMetrics(registry);

    metrics.published("cellarbridge.quotation.accepted.v1");
    metrics.published("cellarbridge.example.unknown.v1");

    assertThat(
            registry
                .get("cellarbridge.quotation.lifecycle")
                .tag("stage", "accepted")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.getMeters()).hasSize(1);
  }
}
