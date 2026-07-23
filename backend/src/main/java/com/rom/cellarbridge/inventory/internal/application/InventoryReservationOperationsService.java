package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventorySupplyQuery;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationMutationService.MutationConflict;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationMutationService.PlannedChange;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationRepository.ReservationAggregate;
import com.rom.cellarbridge.inventory.internal.application.ReservationOperationRepository.Action;
import com.rom.cellarbridge.inventory.internal.application.ReservationOperationRepository.Audit;
import com.rom.cellarbridge.inventory.internal.application.ReservationOperationRepository.Command;
import com.rom.cellarbridge.inventory.internal.application.ReservationOperationRepository.Status;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class InventoryReservationOperationsService {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{20,200}");
  private static final Comparator<Allocation> ALLOCATION_ORDER =
      Comparator.comparing((Allocation allocation) -> allocation.orderLineId().toString())
          .thenComparing(allocation -> allocation.lotId().toString())
          .thenComparing(allocation -> allocation.id().toString());

  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorization;
  private final InventorySupplyQuery supplyQuery;
  private final InventoryReservationRepository reservations;
  private final ReservationOperationRepository operations;
  private final InventoryReservationMutationService mutations;
  private final JsonMapper json;
  private final MeterRegistry meters;
  private final Clock clock;

  InventoryReservationOperationsService(
      TenantContextHolder contextHolder,
      AuthorizationService authorization,
      InventorySupplyQuery supplyQuery,
      InventoryReservationRepository reservations,
      ReservationOperationRepository operations,
      InventoryReservationMutationService mutations,
      JsonMapper json,
      MeterRegistry meters,
      Clock clock) {
    this.contextHolder = contextHolder;
    this.authorization = authorization;
    this.supplyQuery = supplyQuery;
    this.reservations = reservations;
    this.operations = operations;
    this.mutations = mutations;
    this.json = json;
    this.meters = meters;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Detail get(UUID reservationId) {
    TenantContext context = readableContext();
    return reservations
        .findById(context.tenantId(), Objects.requireNonNull(reservationId, "reservationId"))
        .map(aggregate -> detail(context, aggregate))
        .orElseThrow(InventoryReservationOperationsService::notFound);
  }

  @Transactional(readOnly = true)
  public Detail getByOrder(UUID orderId) {
    TenantContext context = readableContext();
    return reservations
        .findByTenantAndOrder(context.tenantId(), Objects.requireNonNull(orderId, "orderId"))
        .map(aggregate -> detail(context, aggregate))
        .orElseThrow(InventoryReservationOperationsService::notFound);
  }

  @Transactional
  public OperationOutcome execute(
      UUID reservationId, OperationType operationType, String idempotencyKey, List<Item> items) {
    Action action = Action.valueOf(Objects.requireNonNull(operationType, "operationType").name());
    Timer.Sample timer = Timer.start(meters);
    String metricOutcome = "technical_failure";
    String metricCode = "INTERNAL_ERROR";
    try {
      TenantContext context = contextHolder.requireCurrent();
      authorization.require(PermissionCode.INVENTORY_RESERVE, context.tenantId());
      List<Item> normalized = normalize(items);
      String keyHash = hashKey(idempotencyKey);
      String requestHash =
          requestHash(context.tenantId(), context.userId(), reservationId, action, normalized);
      if (!operations.lockReservation(context.tenantId(), reservationId)) {
        throw notFound();
      }
      ReservationAggregate aggregate =
          reservations
              .findById(context.tenantId(), reservationId)
              .orElseThrow(InventoryReservationOperationsService::notFound);
      Command command =
          operations.claim(
              context.tenantId(),
              reservationId,
              action,
              keyHash,
              requestHash,
              context.userId(),
              clock.instant());
      if (!command.requestHash().equals(requestHash)) {
        throw new ReservationOperationException(
            "IDEMPOTENCY_KEY_REUSED",
            "The idempotency key was already used for a different operation request");
      }
      if (!command.created()) {
        OperationOutcome replay = replay(command);
        metricOutcome = replay.completed() ? "replayed" : "rejected";
        metricCode = replay.code();
        return replay;
      }
      OperationOutcome outcome =
          apply(context, aggregate, command, action, normalized, keyHash, clock.instant());
      metricOutcome = outcome.completed() ? "completed" : "rejected";
      metricCode = outcome.code();
      return outcome;
    } catch (ReservationOperationException problem) {
      metricOutcome = "rejected";
      metricCode = problem.code();
      throw problem;
    } catch (AccessDeniedException denied) {
      metricOutcome = "rejected";
      metricCode = "ACCESS_DENIED";
      throw denied;
    } finally {
      Counter.builder("cellarbridge.inventory.reservation.operation")
          .tag("action", actionTag(operationType))
          .tag("outcome", metricOutcome)
          .tag("code", metricCode)
          .register(meters)
          .increment();
      timer.stop(
          Timer.builder("cellarbridge.inventory.reservation.operation.duration")
              .tag("action", actionTag(operationType))
              .tag("outcome", metricOutcome)
              .register(meters));
    }
  }

  private OperationOutcome apply(
      TenantContext context,
      ReservationAggregate aggregate,
      Command command,
      Action action,
      List<Item> items,
      String keyHash,
      Instant now) {
    Reservation reservation = aggregate.reservation();
    if (reservation.status() != Reservation.Status.CONFIRMED) {
      return reject(
          context,
          command,
          action,
          reservation,
          keyHash,
          "INVALID_STATE_TRANSITION",
          "Only a confirmed Reservation can be released or consumed",
          now);
    }
    boolean hasReleased =
        aggregate.allocations().stream().anyMatch(item -> item.releasedQuantity().signum() > 0);
    boolean hasConsumed =
        aggregate.allocations().stream().anyMatch(item -> item.consumedQuantity().signum() > 0);
    if (action == Action.RELEASE && hasConsumed || action == Action.CONSUME && hasReleased) {
      return reject(
          context,
          command,
          action,
          reservation,
          keyHash,
          "INVENTORY_OPERATION_CONFLICT",
          "A Reservation cannot mix release and consume operations",
          now);
    }
    Map<UUID, Allocation> byId = new HashMap<>();
    aggregate.allocations().forEach(allocation -> byId.put(allocation.id(), allocation));
    List<PlannedChange> changes = new ArrayList<>();
    for (Item item : items) {
      Allocation allocation = byId.get(item.allocationId());
      if (allocation == null) {
        return reject(
            context,
            command,
            action,
            reservation,
            keyHash,
            "RESOURCE_NOT_FOUND",
            "A requested Reservation Allocation was not found",
            now);
      }
      if (allocation.quantityUnit() != item.quantityUnit()) {
        return reject(
            context,
            command,
            action,
            reservation,
            keyHash,
            "INVENTORY_ALLOCATION_MISMATCH",
            "The Allocation quantity unit does not match the command",
            now);
      }
      if (item.quantity().compareTo(allocation.remainingReservedQuantity()) > 0) {
        String code =
            action == Action.RELEASE
                ? "INVENTORY_RELEASE_EXCEEDS_RESERVED"
                : "INVENTORY_CONSUMPTION_EXCEEDS_RESERVED";
        return reject(
            context,
            command,
            action,
            reservation,
            keyHash,
            code,
            "The operation quantity exceeds the Allocation reserved balance",
            now);
      }
      changes.add(new PlannedChange(allocation, item.quantity()));
    }
    changes.sort(Comparator.comparing(PlannedChange::allocation, ALLOCATION_ORDER));
    List<UUID> locked =
        operations.lockAllocations(
            context.tenantId(),
            reservation.id(),
            changes.stream().map(change -> change.allocation().id()).toList());
    if (locked.size() != changes.size()) {
      return reject(
          context,
          command,
          action,
          reservation,
          keyHash,
          "RESOURCE_NOT_FOUND",
          "A requested Reservation Allocation was not found",
          now);
    }
    List<Allocation> updated;
    try {
      updated =
          mutations.apply(context.tenantId(), command.id(), action, changes, context.userId(), now);
    } catch (MutationConflict conflict) {
      return reject(
          context,
          command,
          action,
          reservation,
          keyHash,
          "INVENTORY_OPERATION_CONFLICT",
          "Inventory balances changed before the operation could complete",
          now);
    }
    Map<UUID, Allocation> finalAllocations = new HashMap<>(byId);
    updated.forEach(allocation -> finalAllocations.put(allocation.id(), allocation));
    boolean finalOperation =
        finalAllocations.values().stream()
            .allMatch(allocation -> allocation.remainingReservedQuantity().signum() == 0);
    Reservation after =
        reservation.recordOperation(
            finalOperation
                ? (action == Action.RELEASE
                    ? Reservation.Status.RELEASED
                    : Reservation.Status.CONSUMED)
                : null,
            now);
    reservations.updateState(context.tenantId(), after, reservation.version());
    List<Balance> balances = balances(updated);
    StoredResult stored =
        new StoredResult(
            true,
            "COMPLETED",
            "Reservation operation completed",
            after.status().name(),
            after.version(),
            now,
            balances);
    finish(context, command, action, keyHash, reservation, after, stored, Status.COMPLETED);
    return outcome(command, action, false, stored);
  }

  private OperationOutcome reject(
      TenantContext context,
      Command command,
      Action action,
      Reservation reservation,
      String keyHash,
      String code,
      String message,
      Instant now) {
    StoredResult stored =
        new StoredResult(
            false,
            code,
            message,
            reservation.status().name(),
            reservation.version(),
            now,
            List.of());
    finish(context, command, action, keyHash, reservation, reservation, stored, Status.REJECTED);
    return outcome(command, action, false, stored);
  }

  private void finish(
      TenantContext context,
      Command command,
      Action action,
      String keyHash,
      Reservation before,
      Reservation after,
      StoredResult stored,
      Status status) {
    operations.complete(
        context.tenantId(),
        command.id(),
        status,
        stored.code(),
        write(stored),
        stored.completedAt());
    operations.appendAudit(
        context.tenantId(),
        new Audit(
            UUID.randomUUID(),
            context.tenantId(),
            before.id(),
            command.id(),
            action,
            status,
            stored.code(),
            context.userId(),
            keyHash,
            before.status().name(),
            after.status().name(),
            stored.completedAt()));
  }

  private OperationOutcome replay(Command command) {
    if (command.status() == Status.PROCESSING || command.resultSnapshot() == null) {
      throw new IllegalStateException("A committed operation replay must have a terminal result");
    }
    try {
      return outcome(
          command,
          command.action(),
          true,
          json.readValue(command.resultSnapshot(), StoredResult.class));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored operation result is invalid", exception);
    }
  }

  private Detail detail(TenantContext context, ReservationAggregate aggregate) {
    Set<UUID> exactPoolIds =
        context.hasPermission(PermissionCode.INVENTORY_READ_EXACT)
            ? supplyQuery.authorizedSupplyPoolIds(
                aggregate.allocations().stream()
                    .map(allocation -> allocation.supplyPoolId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))
            : Set.of();
    Map<UUID, String> warehouseLabelsByLot = new HashMap<>();
    supplyQuery
        .findAuthorizedLots(
            aggregate.allocations().stream()
                .filter(allocation -> exactPoolIds.contains(allocation.supplyPoolId()))
                .map(
                    allocation ->
                        new InventorySupplyQuery.ExactLotCandidate(
                            allocation.supplyPoolId(),
                            allocation.skuId(),
                            allocation.quantityUnit()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
            Integer.MAX_VALUE)
        .forEach(lot -> warehouseLabelsByLot.put(lot.lotId(), lot.warehouseLabel()));
    Reservation reservation = aggregate.reservation();
    Set<String> allowed = allowedActions(context, reservation, aggregate.allocations());
    return new Detail(
        reservation.id(),
        reservation.orderId(),
        reservation.status().name(),
        reservation.failureCode(),
        reservation.version(),
        reservation.createdAt(),
        reservation.updatedAt(),
        reservation.lines().stream()
            .map(
                line ->
                    new RequestedLine(
                        line.orderLineId(),
                        line.skuId(),
                        quantity(line.requestedQuantity()),
                        line.quantityUnit().name()))
            .toList(),
        aggregate.allocations().stream()
            .map(
                allocation -> {
                  boolean exact = exactPoolIds.contains(allocation.supplyPoolId());
                  return new AllocationView(
                      allocation.id(),
                      allocation.orderLineId(),
                      allocation.skuId(),
                      quantity(allocation.allocatedQuantity()),
                      quantity(allocation.releasedQuantity()),
                      quantity(allocation.consumedQuantity()),
                      quantity(allocation.remainingReservedQuantity()),
                      allocation.quantityUnit().name(),
                      allocation.supplyType().name(),
                      exact ? allocation.supplyPoolId() : null,
                      exact ? allocation.lotId() : null,
                      exact ? warehouseLabelsByLot.get(allocation.lotId()) : null,
                      exact ? allocation.warehousePriority() : null,
                      exact ? allocation.warehouseVersion() : null);
                })
            .toList(),
        aggregate.shortages().stream()
            .map(
                shortage ->
                    new ShortageView(
                        shortage.orderLineId(),
                        shortage.skuId(),
                        quantity(shortage.requestedQuantity()),
                        quantity(shortage.availableQuantity()),
                        quantity(shortage.shortageQuantity()),
                        shortage.quantityUnit().name(),
                        shortage.failureCode()))
            .toList(),
        aggregate.attempts().stream()
            .map(
                attempt ->
                    new AttemptView(
                        attempt.attemptNumber(),
                        attempt.outcome().name(),
                        attempt.failureCode(),
                        attempt.startedAt(),
                        attempt.completedAt()))
            .toList(),
        operations.findAudits(context.tenantId(), reservation.id()).stream()
            .map(
                audit ->
                    new OperationAuditView(
                        audit.commandId(),
                        audit.action().name(),
                        audit.outcome().name(),
                        audit.reasonCode(),
                        audit.previousState(),
                        audit.newState(),
                        audit.occurredAt()))
            .toList(),
        allowed);
  }

  private TenantContext readableContext() {
    TenantContext context = contextHolder.requireCurrent();
    authorization.require(PermissionCode.INVENTORY_READ, context.tenantId());
    return context;
  }

  private static Set<String> allowedActions(
      TenantContext context, Reservation reservation, List<Allocation> allocations) {
    if (!context.hasPermission(PermissionCode.INVENTORY_RESERVE)
        || reservation.status() != Reservation.Status.CONFIRMED) {
      return Set.of();
    }
    boolean released = allocations.stream().anyMatch(item -> item.releasedQuantity().signum() > 0);
    boolean consumed = allocations.stream().anyMatch(item -> item.consumedQuantity().signum() > 0);
    if (released) {
      return Set.of("RELEASE");
    }
    if (consumed) {
      return Set.of("CONSUME");
    }
    return Set.of("RELEASE", "CONSUME");
  }

  private List<Item> normalize(List<Item> items) {
    if (items == null || items.isEmpty() || items.size() > 100) {
      throw validation("An operation requires between one and 100 Allocation items");
    }
    HashSet<UUID> ids = new HashSet<>();
    return items.stream()
        .map(
            item -> {
              Objects.requireNonNull(item, "item");
              if (!ids.add(Objects.requireNonNull(item.allocationId(), "allocationId"))) {
                throw validation("An Allocation may appear only once per operation");
              }
              return new Item(
                  item.allocationId(),
                  exactPositive(item.quantity()),
                  Objects.requireNonNull(item.quantityUnit(), "quantityUnit"));
            })
        .sorted(Comparator.comparing(item -> item.allocationId().toString()))
        .toList();
  }

  private static BigDecimal exactPositive(BigDecimal quantity) {
    try {
      BigDecimal exact =
          Objects.requireNonNull(quantity, "quantity").setScale(6, RoundingMode.UNNECESSARY);
      if (exact.signum() <= 0 || exact.precision() > 19) {
        throw validation("Operation quantity must be positive numeric(19,6)");
      }
      return exact;
    } catch (ArithmeticException exception) {
      throw validation("Operation quantity must have at most six decimal places");
    }
  }

  private static String requestHash(
      TenantId tenantId, UUID actorId, UUID reservationId, Action action, List<Item> items) {
    StringBuilder canonical = new StringBuilder();
    frame(canonical, tenantId.value().toString());
    frame(canonical, actorId.toString());
    frame(canonical, reservationId.toString());
    frame(canonical, action.name());
    items.forEach(
        item -> {
          frame(canonical, item.allocationId().toString());
          frame(canonical, quantity(item.quantity()));
          frame(canonical, item.quantityUnit().name());
        });
    return sha256(canonical.toString());
  }

  private static String hashKey(String key) {
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw new ReservationOperationException(
          "IDEMPOTENCY_KEY_REQUIRED", "A valid Idempotency-Key is required");
    }
    return sha256(key);
  }

  private static void frame(StringBuilder target, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    target.append(bytes.length).append(':').append(value).append('\n');
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String write(StoredResult result) {
    try {
      return json.writeValueAsString(result);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Operation result could not be serialized", exception);
    }
  }

  private static List<Balance> balances(List<Allocation> allocations) {
    return allocations.stream()
        .map(
            allocation ->
                new Balance(
                    allocation.id(),
                    quantity(allocation.releasedQuantity()),
                    quantity(allocation.consumedQuantity()),
                    quantity(allocation.remainingReservedQuantity()),
                    allocation.quantityUnit().name()))
        .toList();
  }

  private static OperationOutcome outcome(
      Command command, Action action, boolean replayed, StoredResult stored) {
    return new OperationOutcome(
        command.id(),
        command.reservationId(),
        action.name(),
        replayed,
        stored.completed(),
        stored.code(),
        stored.message(),
        stored.reservationStatus(),
        stored.reservationVersion(),
        stored.completedAt(),
        stored.balances());
  }

  private static ReservationOperationException notFound() {
    return new ReservationOperationException("RESOURCE_NOT_FOUND", "Reservation not found");
  }

  private static ReservationOperationException validation(String message) {
    return new ReservationOperationException("VALIDATION_FAILED", message);
  }

  private static String quantity(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private static String actionTag(OperationType type) {
    return type == null ? "invalid" : type.name().toLowerCase(java.util.Locale.ROOT);
  }

  public enum OperationType {
    RELEASE,
    CONSUME
  }

  public record Item(UUID allocationId, BigDecimal quantity, QuantityUnit quantityUnit) {}

  public record Detail(
      UUID id,
      UUID orderId,
      String status,
      String failureCode,
      long version,
      Instant createdAt,
      Instant updatedAt,
      List<RequestedLine> requestedLines,
      List<AllocationView> allocations,
      List<ShortageView> shortages,
      List<AttemptView> attempts,
      List<OperationAuditView> operations,
      Set<String> allowedActions) {}

  public record RequestedLine(UUID orderLineId, UUID skuId, String quantity, String quantityUnit) {}

  public record AllocationView(
      UUID id,
      UUID orderLineId,
      UUID skuId,
      String allocatedQuantity,
      String releasedQuantity,
      String consumedQuantity,
      String remainingReservedQuantity,
      String quantityUnit,
      String supplyType,
      UUID supplyPoolId,
      UUID lotId,
      String warehouseLabel,
      Integer warehousePriority,
      Long warehouseVersion) {}

  public record ShortageView(
      UUID orderLineId,
      UUID skuId,
      String requestedQuantity,
      String availableQuantity,
      String shortageQuantity,
      String quantityUnit,
      String failureCode) {}

  public record AttemptView(
      int attemptNumber,
      String outcome,
      String failureCode,
      Instant startedAt,
      Instant completedAt) {}

  public record OperationAuditView(
      UUID commandId,
      String action,
      String outcome,
      String reasonCode,
      String previousState,
      String newState,
      Instant occurredAt) {}

  public record OperationOutcome(
      UUID commandId,
      UUID reservationId,
      String action,
      boolean replayed,
      boolean completed,
      String code,
      String message,
      String reservationStatus,
      long reservationVersion,
      Instant completedAt,
      List<Balance> allocations) {}

  public record Balance(
      UUID allocationId,
      String releasedQuantity,
      String consumedQuantity,
      String remainingReservedQuantity,
      String quantityUnit) {}

  public record StoredResult(
      boolean completed,
      String code,
      String message,
      String reservationStatus,
      long reservationVersion,
      Instant completedAt,
      List<Balance> balances) {
    public StoredResult {
      balances = List.copyOf(balances);
    }
  }
}
