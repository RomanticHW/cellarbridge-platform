package com.rom.cellarbridge.fulfillment.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

class SimulatedFulfillmentAdapterTest {

  @Test
  void productionDefaultAllowsSuccessButRejectsFailureInjection() {
    SimulatedFulfillmentAdapter adapter =
        new SimulatedFulfillmentAdapter(ObservationRegistry.create(), false);

    assertThat(adapter.execute("SUCCESS"))
        .isEqualTo(new SimulatedFulfillmentAdapter.Result("SUCCESS", "CONFIRMED", null));
    assertThatThrownBy(() -> adapter.execute("TIMEOUT"))
        .isInstanceOfSatisfying(
            FulfillmentProblem.class,
            failure -> assertThat(failure.code()).isEqualTo("VALIDATION_FAILED"));
  }

  @Test
  void approvedDemoProfileExposesDeterministicTimeoutEvidence() {
    SimulatedFulfillmentAdapter adapter =
        new SimulatedFulfillmentAdapter(ObservationRegistry.create(), true);

    assertThat(adapter.execute("TIMEOUT"))
        .isEqualTo(
            new SimulatedFulfillmentAdapter.Result(
                "TIMEOUT", "FAILED", "SIMULATED_ADAPTER_TIMEOUT"));
  }
}
