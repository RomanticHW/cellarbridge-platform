package com.rom.cellarbridge.inventory.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationRepository;
import com.rom.cellarbridge.inventory.internal.application.ReservationRequestConflictRepository;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.inventory.internal.domain.ReservationRequestConflict;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class JdbcReservationRequestConflictRepositoryConcurrencyTest
    extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final UUID POOL = UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-07-16T14:00:00Z");
  private static final String EXISTING_HASH = "e".repeat(64);
  private static final String CONFLICTING_HASH = "f".repeat(64);

  @Autowired private InventoryReservationRepository reservations;
  @Autowired private ReservationRequestConflictRepository conflicts;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void concurrentObserversPersistExactlyOneImmutableConflictFact() throws Exception {
    Reservation reservation = pending();
    reservations.create(TENANT, reservation);
    int workers = 12;
    CyclicBarrier start = new CyclicBarrier(workers);
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int index = 0; index < workers; index++) {
        results.add(
            executor.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return !conflicts.record(TENANT, conflict(reservation)).replayed();
                }));
      }

      int firstObservations = 0;
      for (Future<Boolean> result : results) {
        if (result.get(20, TimeUnit.SECONDS)) {
          firstObservations++;
        }
      }

      assertThat(firstObservations).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  """
                  SELECT count(*)
                    FROM inventory.reservation_request_conflict
                   WHERE tenant_id = ? AND order_id = ? AND conflicting_request_hash = ?
                  """,
                  Integer.class,
                  TENANT.value(),
                  reservation.orderId(),
                  CONFLICTING_HASH))
          .isEqualTo(1);
      assertThat(reservations.findByTenantAndOrder(TENANT, reservation.orderId()))
          .get()
          .extracting(InventoryReservationRepository.ReservationAggregate::reservation)
          .isEqualTo(reservation);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
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

  private static ReservationRequestConflict conflict(Reservation reservation) {
    return new ReservationRequestConflict(
        UUID.randomUUID(),
        TENANT,
        reservation.orderId(),
        reservation.id(),
        reservation.requestHash(),
        CONFLICTING_HASH,
        UUID.randomUUID(),
        UUID.randomUUID(),
        NOW,
        ReservationRequestConflict.RESERVATION_REQUEST_CONFLICT);
  }
}
