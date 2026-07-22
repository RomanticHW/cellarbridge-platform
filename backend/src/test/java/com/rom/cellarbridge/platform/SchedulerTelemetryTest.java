package com.rom.cellarbridge.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

class SchedulerTelemetryTest {

  @Test
  void recordsBoundedSuccessAndFailureOutcomes() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SchedulerTelemetry telemetry = new SchedulerTelemetry(ObservationRegistry.create(), meters);

    telemetry.run("settlement-overdue", () -> {});
    assertThatThrownBy(
            () ->
                telemetry.run(
                    "settlement-overdue",
                    () -> {
                      throw new IllegalStateException();
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            meters
                .get("cellarbridge.scheduler.runs")
                .tags("job", "settlement-overdue", "outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meters
                .get("cellarbridge.scheduler.runs")
                .tags("job", "settlement-overdue", "outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }
}
