package com.rom.cellarbridge.fulfillment.internal.domain;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, versioned route template validated before any plan snapshot is created. */
public record FulfillmentTemplate(
    UUID id,
    String code,
    String version,
    String routeCode,
    Instant effectiveFrom,
    Instant effectiveTo,
    List<Step> steps) {

  public FulfillmentTemplate {
    Objects.requireNonNull(id, "id");
    requireText(code, "code");
    requireText(version, "version");
    requireText(routeCode, "routeCode");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
      throw new IllegalArgumentException("effectiveTo must follow effectiveFrom");
    }
    steps = List.copyOf(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("steps must not be empty");
    }
    validateGraph(steps);
  }

  public boolean effectiveAt(Instant instant) {
    return !instant.isBefore(effectiveFrom)
        && (effectiveTo == null || instant.isBefore(effectiveTo));
  }

  public Map<String, PlannedWindow> scheduleFrom(Instant planStart) {
    Objects.requireNonNull(planStart, "planStart");
    Map<String, PlannedWindow> schedule = new HashMap<>();
    Set<String> remaining = new HashSet<>();
    steps.forEach(step -> remaining.add(step.code()));
    while (!remaining.isEmpty()) {
      List<Step> ready =
          steps.stream()
              .filter(step -> remaining.contains(step.code()))
              .filter(step -> schedule.keySet().containsAll(step.dependencies()))
              .toList();
      if (ready.isEmpty()) {
        throw new IllegalStateException("Validated Fulfillment template could not be scheduled");
      }
      for (Step step : ready) {
        Instant plannedStart =
            step.dependencies().stream()
                .map(schedule::get)
                .map(PlannedWindow::dueAt)
                .max(Instant::compareTo)
                .orElse(planStart);
        schedule.put(
            step.code(),
            new PlannedWindow(
                plannedStart, plannedStart.plusSeconds(step.plannedDurationMinutes() * 60L)));
        remaining.remove(step.code());
      }
    }
    return Map.copyOf(schedule);
  }

  private static void validateGraph(List<Step> steps) {
    Map<String, Step> byCode = new HashMap<>();
    Set<Integer> sequences = new HashSet<>();
    for (Step step : steps) {
      if (byCode.put(step.code(), step) != null || !sequences.add(step.sequence())) {
        throw new IllegalArgumentException("Template step code and sequence must be unique");
      }
    }
    Map<String, Integer> indegree = new HashMap<>();
    Map<String, Set<String>> outgoing = new HashMap<>();
    byCode.keySet().forEach(code -> indegree.put(code, 0));
    for (Step step : steps) {
      for (String dependency : step.dependencies()) {
        if (!byCode.containsKey(dependency) || dependency.equals(step.code())) {
          throw new IllegalArgumentException("Template dependency must identify another step");
        }
        indegree.compute(step.code(), (key, value) -> value + 1);
        outgoing.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(step.code());
      }
    }
    ArrayDeque<String> ready = new ArrayDeque<>();
    indegree.forEach(
        (code, count) -> {
          if (count == 0) ready.add(code);
        });
    int visited = 0;
    while (!ready.isEmpty()) {
      String code = ready.removeFirst();
      visited++;
      for (String dependent : outgoing.getOrDefault(code, Set.of())) {
        int remaining = indegree.compute(dependent, (key, value) -> value - 1);
        if (remaining == 0) ready.add(dependent);
      }
    }
    if (visited != steps.size()) {
      throw new IllegalArgumentException("Template dependencies must not contain a cycle");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
  }

  public record Step(
      UUID id,
      String code,
      String name,
      int sequence,
      String ownerRole,
      int plannedDurationMinutes,
      boolean customerVisible,
      boolean optional,
      boolean skippable,
      List<String> dependencies) {
    public Step {
      Objects.requireNonNull(id, "id");
      requireText(code, "code");
      requireText(name, "name");
      requireText(ownerRole, "ownerRole");
      if (sequence < 1 || plannedDurationMinutes < 1) {
        throw new IllegalArgumentException("Step sequence and duration must be positive");
      }
      if (skippable && !optional) {
        throw new IllegalArgumentException("Only optional steps can be skippable");
      }
      dependencies = List.copyOf(dependencies);
    }
  }

  public record PlannedWindow(Instant plannedStartAt, Instant dueAt) {}
}
