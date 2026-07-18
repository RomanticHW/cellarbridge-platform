package com.rom.cellarbridge.inventory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationOperationsService.Item;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationOperationsService.OperationOutcome;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationOperationsService.OperationType;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import com.rom.cellarbridge.inventory.internal.domain.InventoryMovement;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.inventory.internal.domain.ReservationAttempt;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class InventoryReservationOperationsIntegrationTest extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final UUID POOL = UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

  @Autowired private InventoryReservationOperationsService service;
  @Autowired private InventoryReservationRepository reservations;
  @Autowired private ReservationOperationRepository operations;
  @Autowired private TenantContextHolder contexts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  @Test
  void releaseSupportsPartialCompletionExactReplayAndImmutableAudit() {
    Fixture fixture = confirmed("3", "2");
    String key = key("release-partial");

    OperationOutcome partial = execute(fixture, OperationType.RELEASE, key, items(fixture, "1"));
    OperationOutcome replay = execute(fixture, OperationType.RELEASE, key, items(fixture, "1"));

    assertThat(partial.completed()).isTrue();
    assertThat(partial.reservationStatus()).isEqualTo("CONFIRMED");
    assertThat(replay).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(partial);
    assertThat(replay.replayed()).isTrue();
    try (TenantContextHolder.Scope ignored =
        contexts.open(
            context(
                TENANT,
                UUID.fromString("11200000-0000-4000-8000-000000000003"),
                Set.of(PermissionCode.INVENTORY_RESERVE)))) {
      assertThatThrownBy(
              () ->
                  service.execute(
                      fixture.reservation().id(), OperationType.RELEASE, key, items(fixture, "1")))
          .isInstanceOfSatisfying(
              ReservationOperationException.class,
              error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }
    assertThatThrownBy(() -> execute(fixture, OperationType.RELEASE, key, items(fixture, "2")))
        .isInstanceOfSatisfying(
            ReservationOperationException.class,
            error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));

    List<Item> remainder =
        List.of(item(fixture.allocations().get(0), "2"), item(fixture.allocations().get(1), "2"));
    OperationOutcome completed =
        execute(fixture, OperationType.RELEASE, key("release-final"), remainder);

    assertThat(completed.reservationStatus()).isEqualTo("RELEASED");
    var aggregate = reservations.findById(TENANT, fixture.reservation().id()).orElseThrow();
    assertThat(aggregate.reservation().version()).isEqualTo(3);
    assertThat(aggregate.allocations())
        .allSatisfy(
            allocation -> {
              assertThat(allocation.releasedQuantity())
                  .isEqualByComparingTo(allocation.allocatedQuantity());
              assertThat(allocation.consumedQuantity()).isZero();
              assertThat(allocation.remainingReservedQuantity()).isZero();
              assertBalance(allocation.lotId(), "10", "0");
            });
    assertThat(movementCount(fixture, "RELEASE")).isEqualTo(3);
    assertThat(commandCount(fixture)).isEqualTo(2);
    assertThat(operations.findAudits(TENANT, fixture.reservation().id()))
        .hasSize(2)
        .allSatisfy(
            audit -> {
              assertThat(audit.outcome())
                  .isEqualTo(ReservationOperationRepository.Status.COMPLETED);
              assertThat(audit.keyHash()).hasSize(64).doesNotContain(key);
            });
  }

  @Test
  void pendingReservationRejectionIsStoredAndReplayable() {
    Reservation pending = pending();
    String key = key("pending-state");
    List<Item> request = List.of(new Item(UUID.randomUUID(), quantity("1"), QuantityUnit.CASE));

    OperationOutcome rejected;
    OperationOutcome replay;
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      rejected = service.execute(pending.id(), OperationType.RELEASE, key, request);
      replay = service.execute(pending.id(), OperationType.RELEASE, key, request);
    }

    assertThat(rejected.completed()).isFalse();
    assertThat(rejected.code()).isEqualTo("INVALID_STATE_TRANSITION");
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.commandId()).isEqualTo(rejected.commandId());
    assertThat(operations.findAudits(TENANT, pending.id()))
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.previousState()).isEqualTo("PENDING");
              assertThat(audit.newState()).isEqualTo("PENDING");
              assertThat(audit.outcome()).isEqualTo(ReservationOperationRepository.Status.REJECTED);
            });
  }

  @Test
  void consumePreservesConservationAndStoresStableBusinessRejections() {
    Fixture fixture = confirmed("4");
    Allocation allocation = fixture.allocations().getFirst();

    OperationOutcome partial =
        execute(
            fixture, OperationType.CONSUME, key("consume-partial"), List.of(item(allocation, "1")));
    assertThat(partial.reservationStatus()).isEqualTo("CONFIRMED");
    assertBalance(allocation.lotId(), "9", "3");

    String mixedKey = key("release-after-consume");
    OperationOutcome mixed =
        execute(fixture, OperationType.RELEASE, mixedKey, List.of(item(allocation, "1")));
    OperationOutcome mixedReplay =
        execute(fixture, OperationType.RELEASE, mixedKey, List.of(item(allocation, "1")));
    assertThat(mixed.completed()).isFalse();
    assertThat(mixed.code()).isEqualTo("INVENTORY_OPERATION_CONFLICT");
    assertThat(mixedReplay.replayed()).isTrue();
    assertThat(mixedReplay.commandId()).isEqualTo(mixed.commandId());

    assertThat(
            execute(
                    fixture,
                    OperationType.CONSUME,
                    key("consume-over"),
                    List.of(item(allocation, "4")))
                .code())
        .isEqualTo("INVENTORY_CONSUMPTION_EXCEEDS_RESERVED");
    assertThat(
            execute(
                    fixture,
                    OperationType.CONSUME,
                    key("consume-unit"),
                    List.of(new Item(allocation.id(), quantity("1"), QuantityUnit.BOTTLE)))
                .code())
        .isEqualTo("INVENTORY_ALLOCATION_MISMATCH");
    assertThat(
            execute(
                    fixture,
                    OperationType.CONSUME,
                    key("consume-allocation"),
                    List.of(new Item(UUID.randomUUID(), quantity("1"), QuantityUnit.CASE)))
                .code())
        .isEqualTo("RESOURCE_NOT_FOUND");

    OperationOutcome finalResult =
        execute(
            fixture, OperationType.CONSUME, key("consume-final"), List.of(item(allocation, "3")));
    assertThat(finalResult.reservationStatus()).isEqualTo("CONSUMED");
    assertBalance(allocation.lotId(), "6", "0");
    Allocation persisted =
        reservations
            .findById(TENANT, fixture.reservation().id())
            .orElseThrow()
            .allocations()
            .getFirst();
    assertThat(persisted.allocatedQuantity())
        .isEqualByComparingTo(
            persisted
                .releasedQuantity()
                .add(persisted.consumedQuantity())
                .add(persisted.remainingReservedQuantity()));
    assertThat(persisted.consumedQuantity()).isEqualByComparingTo("4");
  }

  @Test
  void multiAllocationConflictRollsBackLotAllocationMovementAndReservationChanges() {
    Fixture fixture = confirmed("2", "2");
    Allocation first = fixture.allocations().get(0);
    Allocation second = fixture.allocations().get(1);
    jdbc.update(
        "UPDATE inventory.inventory_lot SET reserved_quantity = 0 WHERE tenant_id = ? AND id = ?",
        TENANT.value(),
        second.lotId());

    String key = key("release-rollback");
    OperationOutcome rejected =
        execute(fixture, OperationType.RELEASE, key, List.of(item(first, "2"), item(second, "2")));

    assertThat(rejected.completed()).isFalse();
    assertThat(rejected.code()).isEqualTo("INVENTORY_OPERATION_CONFLICT");
    assertBalance(first.lotId(), "10", "2");
    assertBalance(second.lotId(), "10", "0");
    var aggregate = reservations.findById(TENANT, fixture.reservation().id()).orElseThrow();
    assertThat(aggregate.reservation().status()).isEqualTo(Reservation.Status.CONFIRMED);
    assertThat(aggregate.reservation().version()).isEqualTo(1);
    assertThat(aggregate.allocations())
        .allSatisfy(
            allocation -> {
              assertThat(allocation.releasedQuantity()).isZero();
              assertThat(allocation.remainingReservedQuantity()).isEqualByComparingTo("2");
            });
    assertThat(movementCount(fixture, "RELEASE")).isZero();
    assertThat(commandStatus(fixture, key)).isEqualTo("REJECTED");
    assertThat(
            execute(
                    fixture,
                    OperationType.RELEASE,
                    key,
                    List.of(item(first, "2"), item(second, "2")))
                .replayed())
        .isTrue();
  }

  @Test
  void concurrentCommandsSerializeWithoutNegativeBalancesOrDuplicateEffects() throws Exception {
    Fixture sameKeyFixture = confirmed("1");
    String sharedKey = key("concurrent-same-key");
    List<OperationOutcome> sameKeyResults =
        compete(
            20,
            () ->
                execute(
                    sameKeyFixture, OperationType.CONSUME, sharedKey, items(sameKeyFixture, "1")));
    assertThat(sameKeyResults).allMatch(OperationOutcome::completed);
    assertThat(sameKeyResults).filteredOn(OperationOutcome::replayed).hasSize(19);
    assertThat(commandCount(sameKeyFixture)).isEqualTo(1);
    assertThat(movementCount(sameKeyFixture, "CONSUME")).isEqualTo(1);
    assertBalance(sameKeyFixture.allocations().getFirst().lotId(), "9", "0");

    Fixture competingFixture = confirmed("1");
    List<OperationOutcome> competing =
        compete(
            8,
            () ->
                execute(
                    competingFixture,
                    OperationType.RELEASE,
                    key("concurrent-" + UUID.randomUUID()),
                    items(competingFixture, "1")));
    assertThat(competing).filteredOn(OperationOutcome::completed).hasSize(1);
    assertThat(competing)
        .filteredOn(result -> !result.completed())
        .allMatch(result -> result.code().equals("INVALID_STATE_TRANSITION"));
    assertThat(commandCount(competingFixture)).isEqualTo(8);
    assertThat(movementCount(competingFixture, "RELEASE")).isEqualTo(1);
    assertBalance(competingFixture.allocations().getFirst().lotId(), "10", "0");
    System.out.printf(
        "reservation-operations seed=%s same-key-workers=20 competing-workers=8 completed=%d rejected=%d%n",
        competingFixture.reservation().id(),
        competing.stream().filter(OperationOutcome::completed).count(),
        competing.stream().filter(result -> !result.completed()).count());
  }

  @Test
  void permissionTenantAndValidationFailuresHaveNoPersistentSideEffects() {
    Fixture fixture = confirmed("2");
    Allocation allocation = fixture.allocations().getFirst();

    try (TenantContextHolder.Scope ignored = contexts.open(context(TENANT, Set.of()))) {
      assertThatThrownBy(
              () ->
                  service.execute(
                      fixture.reservation().id(),
                      OperationType.RELEASE,
                      key("denied"),
                      List.of(item(allocation, "1"))))
          .isInstanceOf(AccessDeniedException.class);
    }
    try (TenantContextHolder.Scope ignored = contexts.open(operator(OTHER_TENANT))) {
      assertThatThrownBy(
              () ->
                  service.execute(
                      fixture.reservation().id(),
                      OperationType.RELEASE,
                      key("cross-tenant"),
                      List.of(item(allocation, "1"))))
          .isInstanceOfSatisfying(
              ReservationOperationException.class,
              error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      assertThatThrownBy(
              () ->
                  service.execute(
                      fixture.reservation().id(),
                      OperationType.RELEASE,
                      "short",
                      List.of(item(allocation, "1"))))
          .isInstanceOfSatisfying(
              ReservationOperationException.class,
              error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
      assertThatThrownBy(
              () ->
                  service.execute(
                      fixture.reservation().id(), OperationType.RELEASE, key("empty"), List.of()))
          .isInstanceOfSatisfying(
              ReservationOperationException.class,
              error -> assertThat(error.code()).isEqualTo("VALIDATION_FAILED"));
    }

    assertThat(commandCount(fixture)).isZero();
    assertThat(movementCount(fixture, "RELEASE")).isZero();
    assertBalance(allocation.lotId(), "10", "2");
    assertThat(
            meters.find("cellarbridge.inventory.reservation.operation").counters().stream()
                .flatMap(counter -> counter.getId().getTags().stream())
                .map(tag -> tag.getKey() + "=" + tag.getValue()))
        .noneMatch(value -> value.contains(fixture.reservation().id().toString()));
  }

  private OperationOutcome execute(
      Fixture fixture, OperationType type, String key, List<Item> items) {
    try (TenantContextHolder.Scope ignored = contexts.open(operator(TENANT))) {
      return service.execute(fixture.reservation().id(), type, key, items);
    }
  }

  private <T> List<T> compete(int workers, Callable<T> command) throws Exception {
    CyclicBarrier start = new CyclicBarrier(workers);
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int index = 0; index < workers; index++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await(20, TimeUnit.SECONDS);
                  return command.call();
                }));
      }
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(40, TimeUnit.SECONDS));
      }
      return List.copyOf(results);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Fixture confirmed(String... quantities) {
    List<Reservation.Line> lines = new ArrayList<>();
    List<Allocation> allocations = new ArrayList<>();
    List<InventoryMovement> movements = new ArrayList<>();
    UUID reservationId = UUID.randomUUID();
    for (String value : quantities) {
      UUID orderLineId = UUID.randomUUID();
      UUID sourceLineId = UUID.randomUUID();
      UUID lotId = insertLot(value);
      BigDecimal quantity = quantity(value);
      lines.add(
          new Reservation.Line(
              orderLineId,
              sourceLineId,
              SKU,
              quantity,
              QuantityUnit.CASE,
              AllocationMode.FIXED_POOL,
              POOL,
              SupplyType.DOMESTIC_ON_HAND));
      Allocation allocation =
          new Allocation(
              UUID.randomUUID(),
              TENANT,
              reservationId,
              orderLineId,
              sourceLineId,
              SKU,
              QuantityUnit.CASE,
              SupplyType.DOMESTIC_ON_HAND,
              AllocationMode.FIXED_POOL,
              POOL,
              lotId,
              quantity,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              quantity,
              10,
              0);
      allocations.add(allocation);
      movements.add(
          new InventoryMovement(
              UUID.randomUUID(),
              TENANT,
              reservationId,
              allocation.id(),
              orderLineId,
              lotId,
              InventoryMovement.Type.RESERVE,
              quantity,
              QuantityUnit.CASE,
              "reserve:test:" + allocation.id(),
              NOW.plusSeconds(1)));
    }
    Reservation pending =
        Reservation.pending(
            reservationId,
            TENANT,
            UUID.randomUUID(),
            UUID.randomUUID().toString().replace("-", "").repeat(2),
            "d".repeat(64),
            "SH_GENERAL_TRADE",
            lines,
            NOW);
    reservations.create(TENANT, pending);
    reservations.appendAttempt(
        TENANT,
        new ReservationAttempt(
            UUID.randomUUID(),
            TENANT,
            reservationId,
            1,
            pending.requestHash(),
            ReservationAttempt.Trigger.EVENT,
            NOW,
            NOW.plusSeconds(1),
            ReservationAttempt.Outcome.CONFIRMED,
            null,
            UUID.randomUUID(),
            UUID.randomUUID()));
    reservations.appendAllocations(TENANT, allocations);
    movements.forEach(movement -> reservations.appendMovement(TENANT, movement));
    Reservation confirmed =
        pending.transition(Reservation.Status.CONFIRMED, null, NOW.plusSeconds(2));
    reservations.updateState(TENANT, confirmed, 0);
    return new Fixture(confirmed, List.copyOf(allocations));
  }

  private Reservation pending() {
    Reservation reservation =
        Reservation.pending(
            UUID.randomUUID(),
            TENANT,
            UUID.randomUUID(),
            UUID.randomUUID().toString().replace("-", "").repeat(2),
            "d".repeat(64),
            "SH_GENERAL_TRADE",
            List.of(
                new Reservation.Line(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SKU,
                    quantity("1"),
                    QuantityUnit.CASE,
                    AllocationMode.FIXED_POOL,
                    POOL,
                    SupplyType.DOMESTIC_ON_HAND)),
            NOW);
    reservations.create(TENANT, reservation);
    return reservation;
  }

  private UUID insertLot(String reserved) {
    UUID lotId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory.inventory_lot
          (id, tenant_id, supply_pool_id, sku_id, lot_code, status, quantity_unit,
           on_hand_quantity, reserved_quantity, created_at, created_by,
           updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, ?, 'AVAILABLE', 'CASE', 10, ?, ?, ?, ?, ?, 0)
        """,
        lotId,
        TENANT.value(),
        POOL,
        SKU,
        "OPERATIONS-" + lotId,
        quantity(reserved),
        Timestamp.from(NOW),
        ACTOR,
        Timestamp.from(NOW),
        ACTOR);
    return lotId;
  }

  private List<Item> items(Fixture fixture, String quantity) {
    return List.of(item(fixture.allocations().getFirst(), quantity));
  }

  private static Item item(Allocation allocation, String quantity) {
    return new Item(allocation.id(), quantity(quantity), allocation.quantityUnit());
  }

  private static BigDecimal quantity(String value) {
    return new BigDecimal(value).setScale(6);
  }

  private static String key(String suffix) {
    return "reservation-operation-" + suffix;
  }

  private TenantContext operator(TenantId tenantId) {
    return context(tenantId, Set.of(PermissionCode.INVENTORY_RESERVE));
  }

  private static TenantContext context(TenantId tenantId, Set<PermissionCode> permissions) {
    return context(tenantId, ACTOR, permissions);
  }

  private static TenantContext context(
      TenantId tenantId, UUID actorId, Set<PermissionCode> permissions) {
    return new TenantContext(
        actorId,
        "Inventory Operator",
        tenantId,
        "Synthetic Cellars",
        "ACTIVE",
        null,
        Set.of("Inventory Operator"),
        Set.of("inventory-operator"),
        permissions,
        "subject-operations",
        "tenant-operations");
  }

  private int commandCount(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM inventory.reservation_operation_command WHERE tenant_id = ? AND reservation_id = ?",
        Integer.class,
        TENANT.value(),
        fixture.reservation().id());
  }

  private String commandStatus(Fixture fixture, String rawKey) {
    return jdbc.queryForObject(
        "SELECT status FROM inventory.reservation_operation_command WHERE tenant_id = ? AND reservation_id = ? AND business_key_hash = encode(sha256(?::bytea), 'hex')",
        String.class,
        TENANT.value(),
        fixture.reservation().id(),
        rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private int movementCount(Fixture fixture, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM inventory.inventory_movement WHERE tenant_id = ? AND reservation_id = ? AND movement_type = ?",
        Integer.class,
        TENANT.value(),
        fixture.reservation().id(),
        type);
  }

  private void assertBalance(UUID lotId, String onHand, String reserved) {
    var balance =
        jdbc.queryForMap(
            "SELECT on_hand_quantity, reserved_quantity FROM inventory.inventory_lot WHERE tenant_id = ? AND id = ?",
            TENANT.value(),
            lotId);
    assertThat((BigDecimal) balance.get("on_hand_quantity")).isEqualByComparingTo(onHand);
    assertThat((BigDecimal) balance.get("reserved_quantity")).isEqualByComparingTo(reserved);
    assertThat((BigDecimal) balance.get("reserved_quantity"))
        .isBetween(BigDecimal.ZERO, (BigDecimal) balance.get("on_hand_quantity"));
  }

  private record Fixture(Reservation reservation, List<Allocation> allocations) {}
}
