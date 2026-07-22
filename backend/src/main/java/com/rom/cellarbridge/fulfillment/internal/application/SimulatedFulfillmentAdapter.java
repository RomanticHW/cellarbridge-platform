package com.rom.cellarbridge.fulfillment.internal.application;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Deterministic local adapter used only for observable demonstration scenarios. */
@Component
final class SimulatedFulfillmentAdapter {
  private static final Set<String> SCENARIOS = Set.of("SUCCESS", "FAILURE", "DELAY");

  Result execute(String requestedScenario) {
    String scenario =
        requestedScenario == null || requestedScenario.isBlank()
            ? "SUCCESS"
            : requestedScenario.toUpperCase(Locale.ROOT);
    if (!SCENARIOS.contains(scenario)) {
      throw new FulfillmentProblem(
          "VALIDATION_FAILED", "Simulation scenario must be SUCCESS, FAILURE or DELAY");
    }
    return switch (scenario) {
      case "FAILURE" -> new Result(scenario, "FAILED", "SIMULATED_ADAPTER_FAILURE");
      case "DELAY" -> new Result(scenario, "DELAYED", "SIMULATED_ADAPTER_DELAY");
      default -> new Result(scenario, "CONFIRMED", null);
    };
  }

  record Result(String scenario, String outcome, String failureCode) {}
}
