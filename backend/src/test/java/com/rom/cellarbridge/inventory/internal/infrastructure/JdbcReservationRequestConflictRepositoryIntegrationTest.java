package com.rom.cellarbridge.inventory.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationRepository;
import com.rom.cellarbridge.inventory.internal.application.ReservationPersistenceException;
import com.rom.cellarbridge.inventory.internal.application.ReservationPersistenceException.Code;
import com.rom.cellarbridge.inventory.internal.application.ReservationRequestConflictRepository;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.inventory.internal.domain.ReservationRequestConflict;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
@Transactional
class JdbcReservationRequestConflictRepositoryIntegrationTest
    extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final UUID POOL = UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-07-16T14:00:00Z");
  private static final String EXISTING_HASH = "a".repeat(64);
  private static final String CONFLICTING_HASH = "b".repeat(64);

  @Autowired private InventoryReservationRepository reservations;
  @Autowired private ReservationRequestConflictRepository conflicts;

  @Test
  void recordsAndReplaysFirstObservationWithoutMutatingCanonicalReservation() {
    Reservation reservation = pending();
    reservations.create(TENANT, reservation);
    ReservationRequestConflict first = conflict(reservation, UUID.randomUUID(), NOW);

    var created = conflicts.record(TENANT, first);
    var replay =
        conflicts.record(TENANT, conflict(reservation, UUID.randomUUID(), NOW.plusSeconds(30)));

    assertThat(created.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.conflict()).isEqualTo(first);
    assertThat(
            conflicts.findByOrderAndConflictingHash(
                TENANT, reservation.orderId(), CONFLICTING_HASH))
        .contains(first);
    assertThat(
            conflicts.findByOrderAndConflictingHash(
                OTHER_TENANT, reservation.orderId(), CONFLICTING_HASH))
        .isEmpty();

    var canonical = reservations.findByTenantAndOrder(TENANT, reservation.orderId()).orElseThrow();
    assertThat(canonical.reservation()).isEqualTo(reservation);
    assertThat(canonical.attempts()).isEmpty();
    assertThat(canonical.allocations()).isEmpty();
    assertThat(canonical.movements()).isEmpty();
    assertThat(canonical.shortages()).isEmpty();
  }

  @Test
  void failsClosedWhenAReplayClaimsDifferentCanonicalEvidence() {
    Reservation reservation = pending();
    reservations.create(TENANT, reservation);
    conflicts.record(TENANT, conflict(reservation, UUID.randomUUID(), NOW));
    ReservationRequestConflict mismatched =
        new ReservationRequestConflict(
            UUID.randomUUID(),
            TENANT,
            reservation.orderId(),
            UUID.randomUUID(),
            "c".repeat(64),
            CONFLICTING_HASH,
            UUID.randomUUID(),
            UUID.randomUUID(),
            NOW.plusSeconds(1),
            ReservationRequestConflict.RESERVATION_REQUEST_CONFLICT);

    assertThatThrownBy(() -> conflicts.record(TENANT, mismatched))
        .isInstanceOfSatisfying(
            ReservationPersistenceException.class,
            error -> assertThat(error.code()).isEqualTo(Code.PERSISTENCE_INTEGRITY_VIOLATION));
  }

  @Test
  void rejectsAWriterThatDoesNotOwnTheConflictTenant() {
    Reservation reservation = pending();

    assertThatThrownBy(
            () -> conflicts.record(OTHER_TENANT, conflict(reservation, UUID.randomUUID(), NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Tenant");
  }

  private static Reservation pending() {
    return Reservation.pending(
        UUID.randomUUID(),
        TENANT,
        UUID.randomUUID(),
        EXISTING_HASH,
        "d".repeat(64),
        "SH_GENERAL_TRADE",
        List.of(
            new Reservation.Line(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SKU,
                new BigDecimal("1.000000"),
                QuantityUnit.CASE,
                AllocationMode.FIXED_POOL,
                POOL,
                SupplyType.DOMESTIC_ON_HAND)),
        NOW);
  }

  private static ReservationRequestConflict conflict(
      Reservation reservation, UUID sourceEventId, Instant observedAt) {
    return new ReservationRequestConflict(
        UUID.randomUUID(),
        TENANT,
        reservation.orderId(),
        reservation.id(),
        reservation.requestHash(),
        CONFLICTING_HASH,
        sourceEventId,
        UUID.randomUUID(),
        observedAt,
        ReservationRequestConflict.RESERVATION_REQUEST_CONFLICT);
  }
}
