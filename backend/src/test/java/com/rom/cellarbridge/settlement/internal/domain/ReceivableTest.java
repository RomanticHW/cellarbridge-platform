package com.rom.cellarbridge.settlement.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.settlement.ReceivableStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceivableTest {
  private static final LocalDate DUE = LocalDate.of(2026, 9, 13);

  @Test
  void derivesPartialPaidAndReopenedStatesFromImmutableMoneyFacts() {
    Receivable open = receivable("128400.0000", "0.0000", ReceivableStatus.OPEN);

    Receivable partial = open.recordPayment(money("50000.0000"), DUE);
    Receivable paid = partial.recordPayment(money("78400.0000"), DUE);
    Receivable reopened = paid.reversePayment(money("10000.0000"), DUE);
    Receivable reset = reopened.reversePayment(money("118400.0000"), DUE);

    assertThat(partial.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
    assertThat(partial.outstanding().amount()).isEqualByComparingTo("78400.0000");
    assertThat(paid.status()).isEqualTo(ReceivableStatus.PAID);
    assertThat(paid.outstanding().amount()).isZero();
    assertThat(reopened.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
    assertThat(reset.status()).isEqualTo(ReceivableStatus.OPEN);
    assertThat(reset.paidNet().amount()).isZero();
  }

  @Test
  void enforcesCurrencyOverpaymentAndOverReversalBoundaries() {
    Receivable value = receivable("100.0000", "0.0000", ReceivableStatus.OPEN);

    assertThatThrownBy(
            () -> value.recordPayment(new SettlementMoney(new BigDecimal("10"), "USD"), DUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> value.recordPayment(money("100.0001"), DUE))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> value.reversePayment(money("0.0001"), DUE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void marksOnlyPositiveBalancesAfterTheDueDateOverdue() {
    Receivable open = receivable("100.0000", "0.0000", ReceivableStatus.OPEN);
    Receivable paid = open.recordPayment(money("100.0000"), DUE);

    assertThat(open.markOverdue(DUE).status()).isEqualTo(ReceivableStatus.OPEN);
    assertThat(open.markOverdue(DUE.plusDays(1)).status()).isEqualTo(ReceivableStatus.OVERDUE);
    assertThat(paid.markOverdue(DUE.plusDays(1)).status()).isEqualTo(ReceivableStatus.PAID);
    assertThat(open.markOverdue(DUE.plusDays(1)).markOverdue(DUE.plusDays(2)).status())
        .isEqualTo(ReceivableStatus.OVERDUE);
  }

  @Test
  void preservesMoneyEquationAcrossDeterministicPropertySamples() {
    Random random = new Random(20260722L);
    for (int sample = 0; sample < 1_000; sample++) {
      long originalMinor = 1 + random.nextLong(1_000_000_000L);
      long firstMinor = random.nextLong(originalMinor + 1);
      long secondMinor = random.nextLong(originalMinor - firstMinor + 1);
      Receivable value = receivable(decimal(originalMinor), "0.0000", ReceivableStatus.OPEN);
      if (firstMinor > 0) value = value.recordPayment(money(decimal(firstMinor)), DUE);
      if (secondMinor > 0) value = value.recordPayment(money(decimal(secondMinor)), DUE);
      long reversibleMinor = random.nextLong(firstMinor + secondMinor + 1);
      if (reversibleMinor > 0) {
        value = value.reversePayment(money(decimal(reversibleMinor)), DUE);
      }

      assertThat(value.paidNet().amount().add(value.outstanding().amount()))
          .isEqualByComparingTo(value.original().amount());
      assertThat(value.paidNet().amount()).isNotNegative();
      assertThat(value.outstanding().amount()).isNotNegative();
    }
  }

  private static Receivable receivable(String original, String paidNet, ReceivableStatus status) {
    return new Receivable(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "REC-202607-000001",
        UUID.randomUUID(),
        "ORD-202607-000001",
        UUID.randomUUID(),
        "PAR-202607-000001",
        "North Cellars Demo Buyer",
        1,
        money(original),
        money(paidNet),
        DUE,
        status,
        0);
  }

  private static SettlementMoney money(String amount) {
    return new SettlementMoney(new BigDecimal(amount), "CNY");
  }

  private static String decimal(long minorUnits) {
    return BigDecimal.valueOf(minorUnits, 4).toPlainString();
  }
}
