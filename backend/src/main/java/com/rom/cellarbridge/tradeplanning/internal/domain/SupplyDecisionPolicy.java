package com.rom.cellarbridge.tradeplanning.internal.domain;

import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot.LineDecision;
import com.rom.cellarbridge.tradeplanning.TradePlanningQuantityUnit;
import com.rom.cellarbridge.tradeplanning.TradePlanningSupplyType;
import com.rom.cellarbridge.tradeplanning.TradeRouteCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure, deterministic selection of route-bound supply evidence. */
public final class SupplyDecisionPolicy {

  public static final String VERSION = SupplyDecisionSnapshot.POLICY_VERSION;
  public static final String FIXED_POOL_INELIGIBLE_CODE = "QUOTE_FIXED_SUPPLY_POOL_INELIGIBLE";
  public static final String NO_PROMISABLE_SUPPLY_CODE = "NO_PROMISABLE_SUPPLY";

  private static final Set<TradePlanningSupplyType> AUTOMATIC_TYPES =
      EnumSet.of(
          TradePlanningSupplyType.DOMESTIC_ON_HAND,
          TradePlanningSupplyType.BONDED_ON_HAND,
          TradePlanningSupplyType.HONG_KONG_ON_HAND);
  private static final Map<TradePlanningSupplyType, Integer> TYPE_PRIORITY = typePriority();

  private SupplyDecisionPolicy() {}

  public static Result decide(
      TradeRouteCode selectedRouteCode,
      List<LineInput> lines,
      List<AvailabilityInput> availability) {
    Objects.requireNonNull(selectedRouteCode, "selectedRouteCode");
    Objects.requireNonNull(lines, "lines");
    Objects.requireNonNull(availability, "availability");
    List<LineInput> orderedLines =
        lines.stream()
            .map(line -> Objects.requireNonNull(line, "line"))
            .sorted(Comparator.comparing(LineInput::quotationLineId))
            .toList();
    if (orderedLines.isEmpty()) {
      throw new IllegalArgumentException("lines must not be empty");
    }
    rejectDuplicateLineIds(orderedLines);
    List<AvailabilityInput> rows =
        availability.stream()
            .map(item -> Objects.requireNonNull(item, "availabilityItem"))
            .toList();

    List<Selection> selections = new ArrayList<>();
    List<Failure> failures = new ArrayList<>();
    for (LineInput line : orderedLines) {
      Selection selection =
          line.preferredSupplyPoolId() == null
              ? selectAutomatic(selectedRouteCode, line, rows)
              : selectFixed(selectedRouteCode, line, rows);
      if (selection == null) {
        failures.add(
            new Failure(
                line.quotationLineId(),
                line.preferredSupplyPoolId() == null
                    ? NO_PROMISABLE_SUPPLY_CODE
                    : FIXED_POOL_INELIGIBLE_CODE));
      } else {
        selections.add(selection);
      }
    }
    if (!failures.isEmpty()) {
      return Result.failure(selectedRouteCode, failures);
    }

    Confidence minimumConfidence =
        selections.stream()
            .map(Selection::minimumConfidence)
            .min(Comparator.comparingInt(Confidence::rank))
            .orElseThrow();
    Instant inventoryDataAsOf =
        selections.stream()
            .flatMap(selection -> selection.contributingRows().stream())
            .map(AvailabilityInput::dataAsOf)
            .max(Comparator.naturalOrder())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "A complete supply decision requires inventory evidence"));
    return Result.success(
        selectedRouteCode,
        selections.stream().map(Selection::lineDecision).toList(),
        minimumConfidence,
        inventoryDataAsOf);
  }

  private static Selection selectFixed(
      TradeRouteCode selectedRouteCode, LineInput line, List<AvailabilityInput> availability) {
    List<AvailabilityInput> matching =
        availability.stream()
            .filter(item -> item.routeCode() == selectedRouteCode)
            .filter(item -> item.skuId().equals(line.skuId()))
            .filter(item -> item.quantityUnit() == line.quantityUnit())
            .filter(item -> item.supplyPoolId().equals(line.preferredSupplyPoolId()))
            .toList();
    Set<TradePlanningSupplyType> supplyTypes =
        matching.stream()
            .map(AvailabilityInput::supplyType)
            .collect(java.util.stream.Collectors.toSet());
    if (matching.isEmpty()
        || supplyTypes.size() != 1
        || !hasKnownConfidence(matching)
        || totalQuantity(matching).compareTo(line.requestedQuantity()) < 0) {
      return null;
    }
    TradePlanningSupplyType supplyType = supplyTypes.iterator().next();
    return new Selection(
        new LineDecision(
            line.quotationLineId(),
            line.skuId(),
            line.requestedQuantity(),
            line.quantityUnit(),
            SupplyAllocationMode.FIXED_POOL,
            line.preferredSupplyPoolId(),
            supplyType),
        minimumConfidence(matching),
        matching);
  }

  private static Selection selectAutomatic(
      TradeRouteCode selectedRouteCode, LineInput line, List<AvailabilityInput> availability) {
    Map<TradePlanningSupplyType, List<AvailabilityInput>> groups =
        new EnumMap<>(TradePlanningSupplyType.class);
    availability.stream()
        .filter(item -> item.routeCode() == selectedRouteCode)
        .filter(item -> item.skuId().equals(line.skuId()))
        .filter(item -> item.quantityUnit() == line.quantityUnit())
        .filter(item -> AUTOMATIC_TYPES.contains(item.supplyType()))
        .forEach(
            item ->
                groups.computeIfAbsent(item.supplyType(), ignored -> new ArrayList<>()).add(item));

    TypeCandidate chosen =
        groups.entrySet().stream()
            .filter(entry -> hasKnownConfidence(entry.getValue()))
            .filter(
                entry -> totalQuantity(entry.getValue()).compareTo(line.requestedQuantity()) >= 0)
            .map(entry -> candidate(entry.getKey(), entry.getValue()))
            .sorted(typeCandidateOrder())
            .findFirst()
            .orElse(null);
    if (chosen == null) {
      return null;
    }
    return new Selection(
        new LineDecision(
            line.quotationLineId(),
            line.skuId(),
            line.requestedQuantity(),
            line.quantityUnit(),
            SupplyAllocationMode.ROUTE_ELIGIBLE_AUTO,
            null,
            chosen.supplyType()),
        chosen.minimumConfidence(),
        chosen.rows());
  }

  private static TypeCandidate candidate(
      TradePlanningSupplyType supplyType, List<AvailabilityInput> rows) {
    Instant earliestAvailableFrom = null;
    if (rows.stream().noneMatch(row -> row.availableFrom() == null)) {
      earliestAvailableFrom =
          rows.stream()
              .map(AvailabilityInput::availableFrom)
              .min(Comparator.naturalOrder())
              .orElseThrow();
    }
    return new TypeCandidate(
        supplyType, minimumConfidence(rows), earliestAvailableFrom, List.copyOf(rows));
  }

  private static Comparator<TypeCandidate> typeCandidateOrder() {
    return Comparator.comparingInt(
            (TypeCandidate candidate) -> candidate.minimumConfidence().rank())
        .reversed()
        .thenComparing(
            TypeCandidate::earliestAvailableFrom, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparingInt(candidate -> TYPE_PRIORITY.get(candidate.supplyType()))
        .thenComparing(candidate -> candidate.supplyType().name());
  }

  private static boolean hasKnownConfidence(List<AvailabilityInput> rows) {
    return rows.stream().allMatch(row -> Confidence.from(row.confidence()) != null);
  }

  private static Confidence minimumConfidence(List<AvailabilityInput> rows) {
    return rows.stream()
        .map(row -> Confidence.from(row.confidence()))
        .min(Comparator.comparingInt(Confidence::rank))
        .orElseThrow();
  }

  private static BigDecimal totalQuantity(List<AvailabilityInput> rows) {
    return rows.stream()
        .map(AvailabilityInput::availableQuantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static void rejectDuplicateLineIds(List<LineInput> lines) {
    Set<UUID> lineIds = new HashSet<>();
    for (LineInput line : lines) {
      if (!lineIds.add(line.quotationLineId())) {
        throw new IllegalArgumentException("Duplicate quotationLineId");
      }
    }
  }

  private static Map<TradePlanningSupplyType, Integer> typePriority() {
    Map<TradePlanningSupplyType, Integer> priorities = new EnumMap<>(TradePlanningSupplyType.class);
    priorities.put(TradePlanningSupplyType.DOMESTIC_ON_HAND, 10);
    priorities.put(TradePlanningSupplyType.BONDED_ON_HAND, 20);
    priorities.put(TradePlanningSupplyType.HONG_KONG_ON_HAND, 30);
    return Map.copyOf(priorities);
  }

  public enum Confidence {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    private final int rank;

    Confidence(int rank) {
      this.rank = rank;
    }

    int rank() {
      return rank;
    }

    static Confidence from(String value) {
      try {
        return Confidence.valueOf(value);
      } catch (IllegalArgumentException exception) {
        return null;
      }
    }
  }

  public record LineInput(
      UUID quotationLineId,
      UUID skuId,
      BigDecimal requestedQuantity,
      TradePlanningQuantityUnit quantityUnit,
      BigDecimal moqCaseEquivalentQuantity,
      UUID preferredSupplyPoolId) {

    public LineInput {
      quotationLineId = Objects.requireNonNull(quotationLineId, "quotationLineId");
      skuId = Objects.requireNonNull(skuId, "skuId");
      requestedQuantity = persistenceQuantity(requestedQuantity, "requestedQuantity", false);
      quantityUnit = Objects.requireNonNull(quantityUnit, "quantityUnit");
      moqCaseEquivalentQuantity =
          persistenceQuantity(moqCaseEquivalentQuantity, "moqCaseEquivalentQuantity", false);
    }
  }

  public record AvailabilityInput(
      UUID supplyPoolId,
      UUID skuId,
      TradeRouteCode routeCode,
      TradePlanningSupplyType supplyType,
      TradePlanningQuantityUnit quantityUnit,
      BigDecimal availableQuantity,
      Instant availableFrom,
      String confidence,
      String inventoryPolicyVersion,
      Instant dataAsOf) {

    public AvailabilityInput {
      supplyPoolId = Objects.requireNonNull(supplyPoolId, "supplyPoolId");
      skuId = Objects.requireNonNull(skuId, "skuId");
      routeCode = Objects.requireNonNull(routeCode, "routeCode");
      supplyType = Objects.requireNonNull(supplyType, "supplyType");
      quantityUnit = Objects.requireNonNull(quantityUnit, "quantityUnit");
      availableQuantity = persistenceQuantity(availableQuantity, "availableQuantity", true);
      confidence = Objects.requireNonNull(confidence, "confidence");
      inventoryPolicyVersion =
          Objects.requireNonNull(inventoryPolicyVersion, "inventoryPolicyVersion");
      dataAsOf = Objects.requireNonNull(dataAsOf, "dataAsOf");
    }
  }

  private static BigDecimal persistenceQuantity(BigDecimal value, String field, boolean allowZero) {
    BigDecimal normalized =
        Objects.requireNonNull(value, field).setScale(6, RoundingMode.UNNECESSARY);
    if ((allowZero ? normalized.signum() < 0 : normalized.signum() <= 0)
        || normalized.precision() - normalized.scale() > 13) {
      throw new IllegalArgumentException(field + " is outside numeric(19,6)");
    }
    return normalized;
  }

  public record Failure(UUID quotationLineId, String code) {

    public Failure {
      quotationLineId = Objects.requireNonNull(quotationLineId, "quotationLineId");
      code = Objects.requireNonNull(code, "code");
    }
  }

  public record Result(
      TradeRouteCode selectedRouteCode,
      List<LineDecision> lineDecisions,
      Confidence minimumConfidence,
      Instant inventoryDataAsOf,
      List<Failure> failures) {

    public Result {
      selectedRouteCode = Objects.requireNonNull(selectedRouteCode, "selectedRouteCode");
      lineDecisions =
          List.copyOf(Objects.requireNonNull(lineDecisions, "lineDecisions")).stream()
              .sorted(Comparator.comparing(LineDecision::quotationLineId))
              .toList();
      failures =
          List.copyOf(Objects.requireNonNull(failures, "failures")).stream()
              .sorted(Comparator.comparing(Failure::quotationLineId).thenComparing(Failure::code))
              .toList();
    }

    public boolean feasible() {
      return failures.isEmpty();
    }

    public SupplyDecisionSnapshot snapshot(
        Instant decidedAt, UUID sourceRouteEvaluationId, String sourceRouteInputHash) {
      if (!feasible()) {
        throw new IllegalStateException("Cannot snapshot an infeasible supply decision");
      }
      return SupplyDecisionSnapshot.create(
          VERSION,
          decidedAt,
          sourceRouteEvaluationId,
          sourceRouteInputHash,
          selectedRouteCode,
          inventoryDataAsOf,
          lineDecisions);
    }

    private static Result success(
        TradeRouteCode selectedRouteCode,
        List<LineDecision> lineDecisions,
        Confidence minimumConfidence,
        Instant inventoryDataAsOf) {
      return new Result(
          selectedRouteCode,
          lineDecisions,
          Objects.requireNonNull(minimumConfidence, "minimumConfidence"),
          Objects.requireNonNull(inventoryDataAsOf, "inventoryDataAsOf"),
          List.of());
    }

    private static Result failure(TradeRouteCode selectedRouteCode, List<Failure> failures) {
      return new Result(selectedRouteCode, List.of(), null, null, failures);
    }
  }

  private record Selection(
      LineDecision lineDecision,
      Confidence minimumConfidence,
      List<AvailabilityInput> contributingRows) {}

  private record TypeCandidate(
      TradePlanningSupplyType supplyType,
      Confidence minimumConfidence,
      Instant earliestAvailableFrom,
      List<AvailabilityInput> rows) {}
}
