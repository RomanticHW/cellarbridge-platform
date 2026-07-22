package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationContextTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void preservesUuidRequestCorrelation() {
    UUID correlationId = UUID.randomUUID();
    MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());

    assertThat(CorrelationContext.currentOrCreate()).isEqualTo(correlationId);
  }

  @Test
  void deterministicallyMapsSafeOpaqueCorrelation() {
    MDC.put(CorrelationIdFilter.MDC_KEY, "browser-journey-42");

    assertThat(CorrelationContext.currentOrCreate())
        .isEqualTo(CorrelationContext.currentOrCreate());
  }
}
