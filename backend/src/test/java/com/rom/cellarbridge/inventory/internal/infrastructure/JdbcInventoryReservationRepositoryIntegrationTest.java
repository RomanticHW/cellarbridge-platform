package com.rom.cellarbridge.inventory.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationRepository;
import com.rom.cellarbridge.inventory.internal.application.ReservationPersistenceException;
import com.rom.cellarbridge.inventory.internal.application.ReservationPersistenceException.Code;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import com.rom.cellarbridge.inventory.internal.domain.InventoryMovement;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.inventory.internal.domain.ReservationAttempt;
import com.rom.cellarbridge.inventory.internal.domain.ShortageSnapshot;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
@Transactional
class JdbcInventoryReservationRepositoryIntegrationTest extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final UUID POOL = UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final UUID LOT = UUID.fromString("37000000-0000-4000-8000-000000000001");
  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
  private static final String HASH = "a".repeat(64);
  private static final String DECISION_HASH = "d".repeat(64);

  @Autowired private InventoryReservationRepository repository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void roundTripsCompleteConfirmedAggregateWithExactQuantities() {
    Reservation pending = pending("2.123456");
    PersistedFacts facts = appendConfirmedFacts(pending);
    Reservation confirmed =
        pending.transition(Reservation.Status.CONFIRMED, null, NOW.plusSeconds(2));
    repository.updateState(TENANT, confirmed, 0);

    var aggregate = repository.findByTenantAndOrder(TENANT, pending.orderId()).orElseThrow();

    assertThat(aggregate.reservation()).isEqualTo(confirmed);
    assertThat(aggregate.attempts()).containsExactly(facts.attempt());
    assertThat(aggregate.allocations()).containsExactly(facts.allocation());
    assertThat(aggregate.movements()).containsExactly(facts.movement());
    assertThat(aggregate.shortages()).isEmpty();
    assertThat(repository.findByRequestHash(TENANT, HASH)).contains(aggregate);
    assertThat(aggregate.allocations().getFirst().allocatedQuantity().scale()).isEqualTo(6);
  }

  @Test
  void persistsIdempotencyTenantIsolationAndOptimisticVersionArbitration() {
    Reservation pending = pending("2");
    assertThat(repository.create(TENANT, pending).replayed()).isFalse();
    Reservation duplicateIdentity =
        Reservation.pending(
            UUID.randomUUID(),
            TENANT,
            pending.orderId(),
            HASH,
            DECISION_HASH,
            pending.routeCode(),
            pending.lines(),
            NOW);

    var replay = repository.create(TENANT, duplicateIdentity);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.reservation()).isEqualTo(pending);
    assertThat(repository.findByTenantAndOrder(OTHER_TENANT, pending.orderId())).isEmpty();
    Reservation confirmed =
        pending.transition(Reservation.Status.CONFIRMED, null, NOW.plusSeconds(1));
    assertThat(repository.compareAndUpdateVersion(TENANT, confirmed, 0)).isTrue();
    assertThat(repository.compareAndUpdateVersion(TENANT, confirmed, 0)).isFalse();

    Reservation conflicting =
        Reservation.pending(
            UUID.randomUUID(),
            TENANT,
            pending.orderId(),
            "b".repeat(64),
            DECISION_HASH,
            pending.routeCode(),
            pending.lines(),
            NOW);
    assertThatThrownBy(() -> repository.create(TENANT, conflicting))
        .isInstanceOfSatisfying(
            ReservationPersistenceException.class,
            error -> assertThat(error.code()).isEqualTo(Code.RESERVATION_REQUEST_CONFLICT));
  }

  @Test
  void roundTripsFailedAttemptAndShortageWithoutInventoryFacts() {
    Reservation pending = pending("3");
    repository.create(TENANT, pending);
    ReservationAttempt attempt = failedAttempt(pending, "INSUFFICIENT_INVENTORY");
    ShortageSnapshot shortage =
        new ShortageSnapshot(
            UUID.randomUUID(),
            TENANT,
            pending.id(),
            pending.lines().getFirst().orderLineId(),
            SKU,
            QuantityUnit.CASE,
            new BigDecimal("3"),
            new BigDecimal("1"),
            new BigDecimal("2"),
            "INSUFFICIENT_INVENTORY",
            POOL,
            SupplyType.DOMESTIC_ON_HAND,
            NOW.plusSeconds(1));
    repository.appendAttempt(TENANT, attempt);
    repository.appendShortage(TENANT, shortage);
    repository.updateState(
        TENANT,
        pending.transition(Reservation.Status.FAILED, "INSUFFICIENT_INVENTORY", NOW.plusSeconds(2)),
        0);

    var aggregate = repository.findByTenantAndOrder(TENANT, pending.orderId()).orElseThrow();

    assertThat(aggregate.attempts()).containsExactly(attempt);
    assertThat(aggregate.shortages()).containsExactly(shortage);
    assertThat(aggregate.allocations()).isEmpty();
    assertThat(aggregate.movements()).isEmpty();
  }

  @Test
  void failsClosedOnMissingAllocationMovementAndRejectsDuplicateAttemptNumber() {
    Reservation pending = pending("2");
    PersistedFacts facts = appendConfirmedFacts(pending);
    repository.updateState(
        TENANT, pending.transition(Reservation.Status.CONFIRMED, null, NOW.plusSeconds(2)), 0);
    jdbc.update("DELETE FROM inventory.inventory_movement WHERE id = ?", facts.movement().id());

    assertThatThrownBy(() -> repository.findByTenantAndOrder(TENANT, pending.orderId()))
        .isInstanceOfSatisfying(
            ReservationPersistenceException.class,
            error -> assertThat(error.code()).isEqualTo(Code.PERSISTENCE_INTEGRITY_VIOLATION));

    ReservationAttempt duplicate =
        new ReservationAttempt(
            UUID.randomUUID(),
            TENANT,
            pending.id(),
            1,
            HASH,
            ReservationAttempt.Trigger.EVENT,
            NOW,
            NOW.plusSeconds(1),
            ReservationAttempt.Outcome.CONFIRMED,
            null,
            UUID.randomUUID(),
            UUID.randomUUID());
    assertThatThrownBy(() -> repository.appendAttempt(TENANT, duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private PersistedFacts appendConfirmedFacts(Reservation pending) {
    repository.create(TENANT, pending);
    ReservationAttempt attempt =
        new ReservationAttempt(
            UUID.randomUUID(),
            TENANT,
            pending.id(),
            1,
            HASH,
            ReservationAttempt.Trigger.EVENT,
            NOW,
            NOW.plusSeconds(1),
            ReservationAttempt.Outcome.CONFIRMED,
            null,
            UUID.randomUUID(),
            UUID.randomUUID());
    BigDecimal quantity = pending.lines().getFirst().requestedQuantity();
    Allocation allocation =
        new Allocation(
            UUID.randomUUID(),
            TENANT,
            pending.id(),
            pending.lines().getFirst().orderLineId(),
            pending.lines().getFirst().sourceQuotationLineId(),
            SKU,
            QuantityUnit.CASE,
            SupplyType.DOMESTIC_ON_HAND,
            AllocationMode.FIXED_POOL,
            POOL,
            LOT,
            quantity,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            quantity,
            10,
            0);
    InventoryMovement movement =
        new InventoryMovement(
            UUID.randomUUID(),
            TENANT,
            pending.id(),
            allocation.id(),
            allocation.orderLineId(),
            LOT,
            InventoryMovement.Type.RESERVE,
            quantity,
            QuantityUnit.CASE,
            "reserve:" + pending.id(),
            NOW.plusSeconds(1));
    repository.appendAttempt(TENANT, attempt);
    repository.appendAllocations(TENANT, List.of(allocation));
    repository.appendMovement(TENANT, movement);
    return new PersistedFacts(attempt, allocation, movement);
  }

  private static Reservation pending(String quantity) {
    return Reservation.pending(
        UUID.randomUUID(),
        TENANT,
        UUID.randomUUID(),
        HASH,
        DECISION_HASH,
        "SH_GENERAL_TRADE",
        List.of(
            new Reservation.Line(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SKU,
                new BigDecimal(quantity),
                QuantityUnit.CASE,
                AllocationMode.FIXED_POOL,
                POOL,
                SupplyType.DOMESTIC_ON_HAND)),
        NOW);
  }

  private static ReservationAttempt failedAttempt(Reservation reservation, String failureCode) {
    return new ReservationAttempt(
        UUID.randomUUID(),
        TENANT,
        reservation.id(),
        1,
        HASH,
        ReservationAttempt.Trigger.EVENT,
        NOW,
        NOW.plusSeconds(1),
        ReservationAttempt.Outcome.FAILED,
        failureCode,
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private record PersistedFacts(
      ReservationAttempt attempt, Allocation allocation, InventoryMovement movement) {}
}
