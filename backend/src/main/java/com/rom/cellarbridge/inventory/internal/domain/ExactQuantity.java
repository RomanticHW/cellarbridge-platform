package com.rom.cellarbridge.inventory.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

final class ExactQuantity {

  static final int SCALE = 6;
  static final int PRECISION = 19;
  static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

  private ExactQuantity() {}

  static BigDecimal positive(BigDecimal value, String field) {
    BigDecimal exact = nonNegative(value, field);
    if (exact.signum() == 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return exact;
  }

  static BigDecimal nonNegative(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    BigDecimal exact;
    try {
      exact = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(
          field + " must have at most six decimal places", exception);
    }
    if (exact.precision() > PRECISION) {
      throw new IllegalArgumentException(field + " exceeds numeric(19,6)");
    }
    if (exact.signum() < 0) {
      throw new IllegalArgumentException(field + " cannot be negative");
    }
    return exact;
  }
}
