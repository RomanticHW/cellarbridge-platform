package com.rom.cellarbridge.platform;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality scheduler observations shared by module-owned jobs. */
@Component
public final class SchedulerTelemetry {

  private final ObservationRegistry observations;
  private final MeterRegistry meters;

  public SchedulerTelemetry(ObservationRegistry observations, MeterRegistry meters) {
    this.observations = observations;
    this.meters = meters;
  }

  public void run(String job, Runnable action) {
    Observation observation =
        Observation.start("cellarbridge.scheduler", observations)
            .lowCardinalityKeyValue("job", job);
    try (Observation.Scope ignored = observation.openScope()) {
      action.run();
      Counter.builder("cellarbridge.scheduler.runs")
          .tag("job", job)
          .tag("outcome", "success")
          .register(meters)
          .increment();
    } catch (RuntimeException failure) {
      observation.error(failure);
      Counter.builder("cellarbridge.scheduler.runs")
          .tag("job", job)
          .tag("outcome", "failure")
          .register(meters)
          .increment();
      throw failure;
    } finally {
      observation.stop();
    }
  }
}
