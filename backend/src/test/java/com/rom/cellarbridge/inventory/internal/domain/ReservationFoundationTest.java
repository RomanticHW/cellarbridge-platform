package com.rom.cellarbridge.inventory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.domain.InventoryMovement.Type;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.Line;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.Status;
import com.rom.cellarbridge.inventory.internal.domain.ReservationAttempt.History;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationFoundationTest {

  private static final TenantId TENANT = tenant("10000000-0000-4000-8000-000000000001");
  private static final UUID ORDER = uuid("81000000-0000-4000-8000-000000000001");
  private static final UUID RESERVATION = uuid("86000000-0000-4000-8000-000000000001");
  private static final UUID FACT = uuid("87000000-0000-4000-8000-000000000001");
  private static final String REQUEST_HASH = "b".repeat(64);
  private static final String DECISION_HASH = "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-07-16T18:00:00Z");

  @Test
  void reservationEnforcesStateVersionFailureAndEvidenceInvariants() {
    Reservation pending = currentReservation();
    Reservation confirmed = pending.transition(Status.CONFIRMED, null, NOW.plusSeconds(1));
    Reservation consumed = confirmed.transition(Status.CONSUMED, null, NOW.plusSeconds(2));
    Line legacyLine = legacyLine();
    Reservation legacy =
        new Reservation(
            RESERVATION,
            TENANT,
            ORDER,
            REQUEST_HASH,
            null,
            "SH_GENERAL_TRADE",
            Status.FAILED,
            "SUPPLY_DECISION_MISSING",
            List.of(legacyLine),
            0,
            NOW,
            NOW);

    assertThat(pending.status()).isEqualTo(Status.PENDING);
    assertThat(consumed.status()).isEqualTo(Status.CONSUMED);
    assertThat(pending.version()).isZero();
    assertThat(consumed.version()).isEqualTo(2);
    assertThat(legacy.lines()).allMatch(Line::legacy);
    assertThatThrownBy(() -> pending.transition(Status.RELEASED, null, NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> pending.transition(Status.FAILED, null, NOW.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("failureCode");
    assertThatThrownBy(
            () ->
                new Reservation(
                    RESERVATION,
                    TENANT,
                    ORDER,
                    REQUEST_HASH,
                    DECISION_HASH,
                    "SH_GENERAL_TRADE",
                    Status.FAILED,
                    "SUPPLY_DECISION_MISSING",
                    List.of(legacyLine),
                    0,
                    NOW,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("entirely Current or Legacy");
    assertThatThrownBy(
            () ->
                new Line(
                    legacyLine.orderLineId(),
                    legacyLine.sourceQuotationLineId(),
                    legacyLine.skuId(),
                    BigDecimal.ONE,
                    QuantityUnit.CASE,
                    null,
                    null,
                    SupplyType.DOMESTIC_ON_HAND))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("partial decision");
  }

  @Test
  void attemptHistoryIsImmutableAppendOnlyAndContinuouslyNumbered() {
    History empty = History.empty(TENANT, RESERVATION);
    History first = empty.append(attempt(1));
    History second = first.append(attempt(2));

    assertThat(empty.attempts()).isEmpty();
    assertThat(first.attempts()).extracting(ReservationAttempt::attemptNumber).containsExactly(1);
    assertThat(second.attempts())
        .extracting(ReservationAttempt::attemptNumber)
        .containsExactly(1, 2);
    assertThatThrownBy(() -> first.attempts().add(attempt(2)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> first.append(attempt(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("next number");
  }

  @Test
  void allocationRequiresExactQuantityConservationAndFrozenEvidence() {
    Allocation allocation = allocation("4", "1", "1", "2");

    assertThat(allocation.allocatedQuantity().toPlainString()).isEqualTo("4.000000");
    assertThat(allocation.supplyPoolId()).isNotNull();
    assertThat(allocation.lotId()).isNotNull();
    assertThatThrownBy(() -> allocation("4", "1", "1", "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conservation");
  }

  @Test
  void movementRequiresKnownTypePositiveExactQuantityAndBusinessIdentity() {
    InventoryMovement movement = movement(Type.RESERVE, "4", "reserve:1");

    assertThat(movement.quantity().toPlainString()).isEqualTo("4.000000");
    assertThat(Arrays.asList(Type.values()))
        .containsExactly(Type.RESERVE, Type.RELEASE, Type.CONSUME);
    assertThatThrownBy(() -> movement(Type.RELEASE, "0", "release:1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> movement(Type.CONSUME, "1", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("businessKey");
  }

  @Test
  void shortageSnapshotRequiresRequestedMinusAvailableEquality() {
    ShortageSnapshot shortage = shortage("5", "2", "3");

    assertThat(shortage.shortageQuantity().toPlainString()).isEqualTo("3.000000");
    assertThatThrownBy(() -> shortage("5", "2", "2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested minus available");
  }

  @Test
  void exactQuantityMatchesNumeric19Scale6WithoutRounding() {
    assertThat(autoLine("2.5").requestedQuantity().toPlainString()).isEqualTo("2.500000");
    assertThatThrownBy(() -> autoLine("0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> autoLine("1.0000001"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("six decimal");
    assertThatThrownBy(() -> autoLine("10000000000000.000000"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("numeric(19,6)");
  }

  @Test
  void requestHashMatchesStableKnownVector() {
    List<Line> lines = hashLines();

    String first = hash(lines);
    String repeated = hash(lines);

    assertThat(first).isEqualTo("23053d00575895302f9021aa1f7e11f08646fac5538a73ffacd8f7cb62f7af4e");
    assertThat(repeated).isEqualTo(first);
  }

  @Test
  void requestHashIsStableAcrossLinePermutation() {
    List<Line> lines = hashLines();

    assertThat(hash(List.of(lines.get(1), lines.get(0)))).isEqualTo(hash(lines));
  }

  @Test
  void requestHashChangesWhenMaterialQuantityChanges() {
    Line fixed = hashLines().getFirst();

    assertThat(hash(List.of(fixed, autoLine("2.500001")))).isNotEqualTo(hash(hashLines()));
  }

  @Test
  void duplicateOrderLineIsRejectedByAggregateAndRequestHash() {
    Line auto = autoLine("2.5");

    assertThatThrownBy(
            () ->
                Reservation.pending(
                    RESERVATION,
                    TENANT,
                    ORDER,
                    REQUEST_HASH,
                    DECISION_HASH,
                    "SH_GENERAL_TRADE",
                    List.of(auto, auto),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate order line");
    assertThatThrownBy(() -> hash(List.of(auto, auto)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
  }

  private static Reservation currentReservation() {
    return Reservation.pending(
        RESERVATION,
        TENANT,
        ORDER,
        REQUEST_HASH,
        DECISION_HASH,
        "SH_GENERAL_TRADE",
        List.of(autoLine("2.5")),
        NOW);
  }

  private static ReservationAttempt attempt(int number) {
    boolean failed = number == 1;
    return new ReservationAttempt(
        uuid("87000000-0000-4000-8000-%012d".formatted(number)),
        TENANT,
        RESERVATION,
        number,
        REQUEST_HASH,
        failed ? ReservationAttempt.Trigger.EVENT : ReservationAttempt.Trigger.MANUAL_RETRY,
        NOW.plusSeconds(number),
        NOW.plusSeconds(number + 1L),
        failed ? ReservationAttempt.Outcome.FAILED : ReservationAttempt.Outcome.CONFIRMED,
        failed ? "INSUFFICIENT_INVENTORY" : null,
        uuid("88000000-0000-4000-8000-000000000001"),
        uuid("89000000-0000-4000-8000-000000000001"));
  }

  private static Line legacyLine() {
    return new Line(
        uuid("82000000-0000-4000-8000-000000000001"),
        uuid("83000000-0000-4000-8000-000000000001"),
        uuid("84000000-0000-4000-8000-000000000001"),
        BigDecimal.ONE,
        QuantityUnit.CASE,
        null,
        null,
        null);
  }

  private static Line autoLine(String quantity) {
    return new Line(
        uuid("82000000-0000-4000-8000-000000000002"),
        uuid("83000000-0000-4000-8000-000000000002"),
        uuid("84000000-0000-4000-8000-000000000002"),
        new BigDecimal(quantity),
        QuantityUnit.CASE,
        AllocationMode.ROUTE_ELIGIBLE_AUTO,
        null,
        SupplyType.DOMESTIC_ON_HAND);
  }

  private static List<Line> hashLines() {
    Line fixed =
        new Line(
            uuid("82000000-0000-4000-8000-000000000001"),
            uuid("83000000-0000-4000-8000-000000000001"),
            uuid("84000000-0000-4000-8000-000000000001"),
            new BigDecimal("12"),
            QuantityUnit.BOTTLE,
            AllocationMode.FIXED_POOL,
            uuid("85000000-0000-4000-8000-000000000001"),
            SupplyType.HONG_KONG_ON_HAND);
    return List.of(fixed, autoLine("2.5"));
  }

  private static String hash(List<Line> lines) {
    return ReservationRequestHashV1.hash(TENANT, ORDER, "SH_GENERAL_TRADE", DECISION_HASH, lines);
  }

  private static Allocation allocation(
      String allocated, String released, String consumed, String remaining) {
    return new Allocation(
        uuid("8a000000-0000-4000-8000-000000000001"),
        TENANT,
        RESERVATION,
        uuid("82000000-0000-4000-8000-000000000002"),
        uuid("83000000-0000-4000-8000-000000000002"),
        uuid("84000000-0000-4000-8000-000000000002"),
        QuantityUnit.CASE,
        SupplyType.DOMESTIC_ON_HAND,
        AllocationMode.ROUTE_ELIGIBLE_AUTO,
        uuid("85000000-0000-4000-8000-000000000002"),
        uuid("8b000000-0000-4000-8000-000000000001"),
        new BigDecimal(allocated),
        new BigDecimal(released),
        new BigDecimal(consumed),
        new BigDecimal(remaining),
        10,
        2);
  }

  private static InventoryMovement movement(Type type, String quantity, String businessKey) {
    Allocation allocation = allocation("4", "0", "0", "4");
    return new InventoryMovement(
        FACT,
        TENANT,
        RESERVATION,
        allocation.id(),
        allocation.orderLineId(),
        allocation.lotId(),
        type,
        new BigDecimal(quantity),
        QuantityUnit.CASE,
        businessKey,
        NOW);
  }

  private static ShortageSnapshot shortage(String requested, String available, String shortage) {
    return new ShortageSnapshot(
        FACT,
        TENANT,
        RESERVATION,
        uuid("82000000-0000-4000-8000-000000000002"),
        uuid("84000000-0000-4000-8000-000000000002"),
        QuantityUnit.CASE,
        new BigDecimal(requested),
        new BigDecimal(available),
        new BigDecimal(shortage),
        "INSUFFICIENT_INVENTORY",
        null,
        SupplyType.DOMESTIC_ON_HAND,
        NOW);
  }

  private static TenantId tenant(String value) {
    return TenantId.of(uuid(value));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
