package com.rom.cellarbridge.tradeorder.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.CommercialSnapshot;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Customer;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.DeliveryAddress;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Line;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Route;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrderDomainException.FailureKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeOrderTest {

  private static final Instant CREATED_AT = Instant.parse("2026-07-14T10:00:00Z");

  @Test
  void createsOnlyInPendingReservationAndFreezesTheCommercialSnapshot() {
    List<Line> sourceLines = new ArrayList<>();
    sourceLines.add(line("100.00"));
    CommercialSnapshot snapshot = snapshot(sourceLines, "100.00");
    TradeOrder order = order(snapshot);

    sourceLines.clear();

    assertThat(order.status()).isEqualTo(TradeOrderStatus.PENDING_RESERVATION);
    assertThat(order.version()).isZero();
    assertThat(order.commercialSnapshot().lines()).hasSize(1);
    assertThat(order.commercialSnapshot().customer().displayName()).isEqualTo("North Buyer");
    assertThat(order.snapshotHash()).isEqualTo("a".repeat(64));
  }

  @Test
  void permitsOnlyTheApprovedLifecycleTransitions() {
    TradeOrder fulfilled =
        order(snapshot(List.of(line("100.00")), "100.00"))
            .reservationSucceeded(CREATED_AT.plusSeconds(1))
            .markReadyForFulfillment(CREATED_AT.plusSeconds(2))
            .beginFulfillment(CREATED_AT.plusSeconds(3))
            .fulfill(CREATED_AT.plusSeconds(4));

    assertThat(fulfilled.status()).isEqualTo(TradeOrderStatus.FULFILLED);
    assertThat(fulfilled.version()).isEqualTo(4);
    assertThatThrownBy(() -> fulfilled.requestCancellation(CREATED_AT.plusSeconds(5)))
        .isInstanceOfSatisfying(
            TradeOrderDomainException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("INVALID_STATE_TRANSITION");
              assertThat(failure.kind()).isEqualTo(FailureKind.STATE_CONFLICT);
              assertThat(failure.currentState()).isEqualTo("FULFILLED");
              assertThat(failure.details()).containsEntry("targetState", "CANCELLATION_PENDING");
              assertThatThrownBy(() -> failure.details().put("targetState", "PENDING"))
                  .isInstanceOf(UnsupportedOperationException.class);
            });
  }

  @Test
  void supportsControlledReservationRetryAndCancellationSkeletons() {
    TradeOrder failed =
        order(snapshot(List.of(line("100.00")), "100.00"))
            .reservationFailed(CREATED_AT.plusSeconds(1));

    assertThat(failed.retryReservation(CREATED_AT.plusSeconds(2)).status())
        .isEqualTo(TradeOrderStatus.PENDING_RESERVATION);
    assertThat(failed.requestCancellation(CREATED_AT.plusSeconds(2)).status())
        .isEqualTo(TradeOrderStatus.CANCELLED);

    TradeOrder pendingCancellation =
        order(snapshot(List.of(line("100.00")), "100.00"))
            .reservationSucceeded(CREATED_AT.plusSeconds(1))
            .requestCancellation(CREATED_AT.plusSeconds(2));
    assertThat(pendingCancellation.status()).isEqualTo(TradeOrderStatus.CANCELLATION_PENDING);
    assertThat(pendingCancellation.cancellationFailed(CREATED_AT.plusSeconds(3)).status())
        .isEqualTo(TradeOrderStatus.CANCELLATION_FAILED);
  }

  @Test
  void rejectsAnInconsistentCommercialTotal() {
    assertThatThrownBy(() -> snapshot(List.of(line("90.00")), "100.00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line totals");
  }

  @Test
  void acceptsZeroAmountsAndRejectsNegativeAmountsOrNonPositiveQuantity() {
    TradeOrder zero = order(snapshot(List.of(line("1", "0", "0")), "0"));
    assertThat(zero.commercialSnapshot().totalAmount()).isEqualTo(new BigDecimal("0.0000"));
    assertThat(order(snapshot(List.of(line("2", "10", "25")), "25"))).isNotNull();

    assertThatThrownBy(() -> line("1", "-0.00001", "0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> line("1", "0", "-0.00001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> line("0", "0", "0")).isInstanceOf(IllegalArgumentException.class);
  }

  private static TradeOrder order(CommercialSnapshot snapshot) {
    return TradeOrder.create(
        UUID.fromString("71000000-0000-4000-8000-000000000001"),
        new TenantId(UUID.fromString("10000000-0000-4000-8000-000000000001")),
        "ORD-202607-000001",
        UUID.fromString("62000000-0000-4000-8000-000000000001"),
        UUID.fromString("63000000-0000-4000-8000-000000000001"),
        "QUO-202607-000001",
        1,
        UUID.fromString("64000000-0000-4000-8000-000000000001"),
        UUID.fromString("65000000-0000-4000-8000-000000000001"),
        CREATED_AT.minusSeconds(10),
        UUID.fromString("11200000-0000-4000-8000-000000000001"),
        snapshot,
        "a".repeat(64),
        UUID.fromString("66000000-0000-4000-8000-000000000001"),
        UUID.fromString("64000000-0000-4000-8000-000000000001"),
        UUID.fromString("67000000-0000-4000-8000-000000000001"),
        CREATED_AT);
  }

  private static CommercialSnapshot snapshot(List<Line> lines, String total) {
    return new CommercialSnapshot(
        1,
        new Customer(
            UUID.fromString("53000000-0000-4000-8000-000000000001"),
            "PARTNER-001",
            "North Buyer",
            0),
        "CNY",
        new BigDecimal(total),
        30,
        new Route("SH_GENERAL_TRADE", "trade-route-policy-v1", LocalDate.parse("2026-08-01")),
        "terms-v1",
        LocalDate.parse("2026-08-01"),
        new DeliveryAddress("CN", "Shanghai", "Shanghai", null, "Bund Road 1", "200000"),
        lines);
  }

  private static Line line(String total) {
    return line("1", total, total);
  }

  private static Line line(String quantity, String unitPrice, String total) {
    return new Line(
        UUID.randomUUID(),
        UUID.fromString("61100000-0000-4000-8000-000000000001"),
        UUID.fromString("34000000-0000-4000-8000-000000000001"),
        "SKU-001",
        "Synthetic wine case",
        new BigDecimal(quantity),
        "CASE",
        new BigDecimal(unitPrice),
        new BigDecimal(total),
        null,
        "DOMESTIC_ON_HAND");
  }
}
