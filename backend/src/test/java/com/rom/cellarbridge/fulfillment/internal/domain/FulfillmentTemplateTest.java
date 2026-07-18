package com.rom.cellarbridge.fulfillment.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FulfillmentTemplateTest {

  @Test
  void acceptsAnAcyclicDependencyGraphAndItsEffectivePeriod() {
    FulfillmentTemplate template =
        template(
            step("CONFIRM", 1, List.of()),
            step("PICK", 2, List.of("CONFIRM")),
            step("DISPATCH", 3, List.of("PICK")));

    assertThat(template.effectiveAt(Instant.parse("2026-07-18T00:00:00Z"))).isTrue();
    assertThat(template.steps())
        .extracting(FulfillmentTemplate.Step::code)
        .containsExactly("CONFIRM", "PICK", "DISPATCH");
  }

  @Test
  void rejectsCyclesMissingDependenciesAndInvalidSkips() {
    assertThatThrownBy(
            () ->
                template(
                    step("CONFIRM", 1, List.of("DISPATCH")),
                    step("DISPATCH", 2, List.of("CONFIRM"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
    assertThatThrownBy(() -> template(step("CONFIRM", 1, List.of("MISSING"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dependency");
    assertThatThrownBy(
            () ->
                new FulfillmentTemplate.Step(
                    UUID.randomUUID(),
                    "OPTION",
                    "Optional",
                    1,
                    "TRADE_OPERATOR",
                    10,
                    false,
                    false,
                    true,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("optional");
  }

  @Test
  void schedulesDependenciesTopologicallyAndUsesTheParallelCriticalPath() {
    Instant start = Instant.parse("2026-07-18T10:00:00Z");
    FulfillmentTemplate template =
        template(
            step("B", 1, 30, List.of("A")),
            step("A", 2, 60, List.of()),
            step("C", 3, 120, List.of()),
            step("D", 4, 15, List.of("B", "C")));

    var schedule = template.scheduleFrom(start);

    assertThat(schedule.get("A").plannedStartAt()).isEqualTo(start);
    assertThat(schedule.get("B").plannedStartAt()).isEqualTo(start.plus(60, ChronoUnit.MINUTES));
    assertThat(schedule.get("C").plannedStartAt()).isEqualTo(start);
    assertThat(schedule.get("D").plannedStartAt()).isEqualTo(start.plus(120, ChronoUnit.MINUTES));
    assertThat(schedule.get("D").dueAt()).isEqualTo(start.plus(135, ChronoUnit.MINUTES));
  }

  private static FulfillmentTemplate template(FulfillmentTemplate.Step... steps) {
    return new FulfillmentTemplate(
        UUID.randomUUID(),
        "SH_GENERAL_TRADE",
        "2026.1",
        "SH_GENERAL_TRADE",
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        List.of(steps));
  }

  private static FulfillmentTemplate.Step step(
      String code, int sequence, List<String> dependencies) {
    return step(code, sequence, 10, dependencies);
  }

  private static FulfillmentTemplate.Step step(
      String code, int sequence, int durationMinutes, List<String> dependencies) {
    return new FulfillmentTemplate.Step(
        UUID.randomUUID(),
        code,
        code,
        sequence,
        "TRADE_OPERATOR",
        durationMinutes,
        false,
        false,
        false,
        dependencies);
  }
}
