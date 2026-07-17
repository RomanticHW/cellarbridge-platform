package com.rom.cellarbridge.inventory.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.AtomicInventoryLotRepository;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationRepository;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.test.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles({"test", "demo"})
@SpringBootTest
class JdbcAtomicInventoryLotRepositoryConcurrencyTest extends PostgresIntegrationTestSupport {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final UUID POOL = UUID.fromString("36000000-0000-4000-8000-000000000001");
  private static final UUID MANUAL_POOL = UUID.fromString("36000000-0000-4000-8000-000000000004");
  private static final UUID SKU = UUID.fromString("34000000-0000-4000-8000-000000000001");
  private static final UUID ACTOR = UUID.fromString("11200000-0000-4000-8000-000000000004");
  private static final Instant NOW = Instant.parse("2026-07-16T13:00:00Z");

  @Autowired private AtomicInventoryLotRepository lots;
  @Autowired private InventoryReservationRepository reservations;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void concurrentDuplicateOrderCreatesOneReservation() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID orderLineId = UUID.randomUUID();
    UUID sourceLineId = UUID.randomUUID();
    String hash = "c".repeat(64);

    long firstCreates =
        compete(
            8,
            () ->
                !reservations
                    .create(
                        TENANT,
                        Reservation.pending(
                            UUID.randomUUID(),
                            TENANT,
                            orderId,
                            hash,
                            "e".repeat(64),
                            "SH_GENERAL_TRADE",
                            List.of(
                                new Reservation.Line(
                                    orderLineId,
                                    sourceLineId,
                                    SKU,
                                    one(),
                                    QuantityUnit.CASE,
                                    AllocationMode.FIXED_POOL,
                                    POOL,
                                    SupplyType.DOMESTIC_ON_HAND)),
                            NOW))
                    .replayed());

    assertThat(firstCreates).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory.reservation WHERE tenant_id = ? AND order_id = ?",
                Integer.class,
                TENANT.value(),
                orderId))
        .isEqualTo(1);
    assertThat(reservations.findByTenantAndOrder(TENANT, orderId)).isPresent();
  }

  @Test
  void concurrentVersionUpdatesAllowOnlyOneWinner() throws Exception {
    Reservation pending =
        Reservation.pending(
            UUID.randomUUID(),
            TENANT,
            UUID.randomUUID(),
            "f".repeat(64),
            "e".repeat(64),
            "SH_GENERAL_TRADE",
            List.of(
                new Reservation.Line(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SKU,
                    one(),
                    QuantityUnit.CASE,
                    AllocationMode.FIXED_POOL,
                    POOL,
                    SupplyType.DOMESTIC_ON_HAND)),
            NOW);
    reservations.create(TENANT, pending);
    Reservation confirmed =
        pending.transition(Reservation.Status.CONFIRMED, null, NOW.plusSeconds(1));

    long successes = compete(2, () -> reservations.compareAndUpdateVersion(TENANT, confirmed, 0));

    assertThat(successes).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM inventory.reservation WHERE tenant_id = ? AND order_id = ?",
                String.class,
                TENANT.value(),
                pending.orderId()))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM inventory.reservation WHERE tenant_id = ? AND order_id = ?",
                Long.class,
                TENANT.value(),
                pending.orderId()))
        .isEqualTo(1L);
  }

  @Test
  void twoTransactionsCannotReservePastOneAvailableUnit() throws Exception {
    UUID lotId = insertLot("1", "0");

    long successes =
        compete(
            2, () -> lots.reserve(TENANT, lotId, QuantityUnit.CASE, one(), ACTOR, NOW).isPresent());

    assertThat(successes).isEqualTo(1);
    assertBalance(lotId, "1", "1");
  }

  @Test
  void multipleOrderCallersCompeteWithoutOverselling() throws Exception {
    UUID lotId = insertLot("8", "0");

    long successes =
        compete(
            16,
            () -> lots.reserve(TENANT, lotId, QuantityUnit.CASE, one(), ACTOR, NOW).isPresent());

    assertThat(successes).isEqualTo(8);
    Balance balance = balance(lotId);
    assertThat(balance.reserved()).isLessThanOrEqualTo(balance.onHand());
    assertThat(balance.reserved()).isEqualByComparingTo("8");
  }

  @Test
  void concurrentReleaseCannotMakeReservedNegative() throws Exception {
    UUID lotId = insertLot("10", "5");

    long successes =
        compete(
            10,
            () -> lots.release(TENANT, lotId, QuantityUnit.CASE, one(), ACTOR, NOW).isPresent());

    assertThat(successes).isEqualTo(5);
    assertBalance(lotId, "10", "0");
  }

  @Test
  void concurrentConsumeCannotExceedReservedOrOnHand() throws Exception {
    UUID lotId = insertLot("8", "5");

    long successes =
        compete(
            10,
            () -> lots.consume(TENANT, lotId, QuantityUnit.CASE, one(), ACTOR, NOW).isPresent());

    assertThat(successes).isEqualTo(5);
    assertBalance(lotId, "3", "0");
    assertThat(lots.reserve(TENANT, lotId, QuantityUnit.BOTTLE, one(), ACTOR, NOW).isPresent())
        .isFalse();
  }

  @Test
  void manualSupplyTypeCannotUseTheReservePrimitive() {
    UUID lotId = insertLot(MANUAL_POOL, "8", "0");

    assertThat(lots.reserve(TENANT, lotId, QuantityUnit.CASE, one(), ACTOR, NOW)).isEmpty();
    assertBalance(lotId, "8", "0");
  }

  private long compete(int workers, Callable<Boolean> mutation) throws Exception {
    CyclicBarrier start = new CyclicBarrier(workers);
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int index = 0; index < workers; index++) {
        results.add(
            executor.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return mutation.call();
                }));
      }
      long successes = 0;
      for (Future<Boolean> result : results) {
        if (result.get(20, TimeUnit.SECONDS)) {
          successes++;
        }
      }
      return successes;
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private UUID insertLot(String onHand, String reserved) {
    return insertLot(POOL, onHand, reserved);
  }

  private UUID insertLot(UUID poolId, String onHand, String reserved) {
    UUID lotId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory.inventory_lot
          (id, tenant_id, supply_pool_id, sku_id, lot_code, status, quantity_unit,
           on_hand_quantity, reserved_quantity, created_at, created_by,
           updated_at, updated_by, version)
        VALUES (?, ?, ?, ?, ?, 'AVAILABLE', 'CASE', ?, ?, ?, ?, ?, ?, 0)
        """,
        lotId,
        TENANT.value(),
        poolId,
        SKU,
        "CONCURRENCY-" + lotId,
        new BigDecimal(onHand),
        new BigDecimal(reserved),
        Timestamp.from(NOW),
        ACTOR,
        Timestamp.from(NOW),
        ACTOR);
    return lotId;
  }

  private void assertBalance(UUID lotId, String onHand, String reserved) {
    Balance balance = balance(lotId);
    assertThat(balance.onHand()).isEqualByComparingTo(onHand);
    assertThat(balance.reserved()).isEqualByComparingTo(reserved);
    assertThat(balance.reserved()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(balance.reserved()).isLessThanOrEqualTo(balance.onHand());
  }

  private Balance balance(UUID lotId) {
    return jdbc.queryForObject(
        """
        SELECT on_hand_quantity, reserved_quantity
          FROM inventory.inventory_lot
         WHERE tenant_id = ? AND id = ?
        """,
        (resultSet, rowNumber) ->
            new Balance(
                resultSet.getBigDecimal("on_hand_quantity"),
                resultSet.getBigDecimal("reserved_quantity")),
        TENANT.value(),
        lotId);
  }

  private static BigDecimal one() {
    return new BigDecimal("1.000000");
  }

  private record Balance(BigDecimal onHand, BigDecimal reserved) {}
}
