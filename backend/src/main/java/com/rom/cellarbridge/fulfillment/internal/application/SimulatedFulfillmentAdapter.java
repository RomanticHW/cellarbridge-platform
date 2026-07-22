package com.rom.cellarbridge.fulfillment.internal.application;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deterministic local adapter used only for observable demonstration scenarios. */
@Component
final class SimulatedFulfillmentAdapter {
  private static final Set<String> SCENARIOS = Set.of("SUCCESS", "FAILURE", "DELAY", "TIMEOUT");
  private final ObservationRegistry observations;
  private final boolean failureSimulationEnabled;

  SimulatedFulfillmentAdapter(
      ObservationRegistry observations,
      @Value("${cellarbridge.fulfillment.failure-simulation-enabled:false}")
          boolean failureSimulationEnabled) {
    this.observations = observations;
    this.failureSimulationEnabled = failureSimulationEnabled;
  }

  Result execute(String requestedScenario) {
    String scenario =
        requestedScenario == null || requestedScenario.isBlank()
            ? "SUCCESS"
            : requestedScenario.toUpperCase(Locale.ROOT);
    if (!SCENARIOS.contains(scenario)) {
      throw new FulfillmentProblem(
          "VALIDATION_FAILED", "Simulation scenario must be SUCCESS, FAILURE, DELAY or TIMEOUT");
    }
    if (!"SUCCESS".equals(scenario) && !failureSimulationEnabled) {
      throw new FulfillmentProblem(
          "VALIDATION_FAILED", "Failure simulation is disabled outside an approved demo profile");
    }
    return Observation.createNotStarted("cellarbridge.external.simulated-fulfillment", observations)
        .lowCardinalityKeyValue("adapter", "fulfillment")
        .lowCardinalityKeyValue("scenario", scenario.toLowerCase(Locale.ROOT))
        .observe(
            () ->
                switch (scenario) {
                  case "TIMEOUT" -> new Result(scenario, "FAILED", "SIMULATED_ADAPTER_TIMEOUT");
                  case "FAILURE" -> new Result(scenario, "FAILED", "SIMULATED_ADAPTER_FAILURE");
                  case "DELAY" -> new Result(scenario, "DELAYED", "SIMULATED_ADAPTER_DELAY");
                  default -> new Result(scenario, "CONFIRMED", null);
                });
  }

  record Result(String scenario, String outcome, String failureCode) {}
}
