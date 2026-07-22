package com.rom.cellarbridge.settlement.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

public record SettlementMoney(BigDecimal amount, String currency) {
  public static final int SCALE = 4;
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

  public SettlementMoney {
    Objects.requireNonNull(amount, "amount");
    if (currency == null || !CURRENCY.matcher(currency).matches()) {
      throw new IllegalArgumentException("currency must be a three-letter uppercase code");
    }
    amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
  }

  public static SettlementMoney positive(BigDecimal amount, String currency) {
    SettlementMoney money = new SettlementMoney(amount, currency);
    if (money.amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
    return money;
  }

  public static SettlementMoney nonNegative(BigDecimal amount, String currency) {
    SettlementMoney money = new SettlementMoney(amount, currency);
    if (money.amount.signum() < 0) throw new IllegalArgumentException("amount cannot be negative");
    return money;
  }

  public SettlementMoney add(SettlementMoney other) {
    requireCurrency(other);
    return new SettlementMoney(amount.add(other.amount), currency);
  }

  public SettlementMoney subtract(SettlementMoney other) {
    requireCurrency(other);
    return new SettlementMoney(amount.subtract(other.amount), currency);
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  private void requireCurrency(SettlementMoney other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException("money currencies must match");
    }
  }
}
