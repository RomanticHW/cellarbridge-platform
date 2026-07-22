package com.rom.cellarbridge.settlement.internal.domain;

import com.rom.cellarbridge.settlement.ReceivableStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record Receivable(
    UUID id,
    UUID tenantId,
    String number,
    UUID orderId,
    String orderNumber,
    UUID partnerId,
    String partnerNumber,
    String partnerName,
    int partnerVersion,
    SettlementMoney original,
    SettlementMoney paidNet,
    LocalDate dueDate,
    ReceivableStatus status,
    long version) {

  public Receivable {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    requireText(number, "number");
    Objects.requireNonNull(orderId, "orderId");
    requireText(orderNumber, "orderNumber");
    Objects.requireNonNull(partnerId, "partnerId");
    requireText(partnerNumber, "partnerNumber");
    requireText(partnerName, "partnerName");
    if (partnerVersion < 0) throw new IllegalArgumentException("partnerVersion cannot be negative");
    Objects.requireNonNull(original, "original");
    Objects.requireNonNull(paidNet, "paidNet");
    Objects.requireNonNull(dueDate, "dueDate");
    Objects.requireNonNull(status, "status");
    if (original.amount().signum() <= 0) {
      throw new IllegalArgumentException("original amount must be positive");
    }
    if (!original.currency().equals(paidNet.currency())) {
      throw new IllegalArgumentException("receivable currencies must match");
    }
    if (paidNet.amount().signum() < 0 || paidNet.amount().compareTo(original.amount()) > 0) {
      throw new IllegalArgumentException("paid net must stay within the original amount");
    }
    if (version < 0) throw new IllegalArgumentException("version cannot be negative");
  }

  public SettlementMoney outstanding() {
    return original.subtract(paidNet);
  }

  public Receivable recordPayment(SettlementMoney payment, LocalDate today) {
    Objects.requireNonNull(today, "today");
    if (payment.amount().signum() <= 0)
      throw new IllegalArgumentException("payment must be positive");
    SettlementMoney nextPaid = paidNet.add(payment);
    if (nextPaid.amount().compareTo(original.amount()) > 0) {
      throw new IllegalStateException("payment exceeds outstanding amount");
    }
    return withPaidNet(nextPaid, today);
  }

  public Receivable reversePayment(SettlementMoney reversal, LocalDate today) {
    Objects.requireNonNull(today, "today");
    if (reversal.amount().signum() <= 0) {
      throw new IllegalArgumentException("reversal must be positive");
    }
    SettlementMoney nextPaid = paidNet.subtract(reversal);
    if (nextPaid.amount().signum() < 0) {
      throw new IllegalStateException("reversal exceeds effective payments");
    }
    return withPaidNet(nextPaid, today);
  }

  public Receivable markOverdue(LocalDate today) {
    Objects.requireNonNull(today, "today");
    if (!today.isAfter(dueDate) || outstanding().isZero()) return this;
    return copy(paidNet, ReceivableStatus.OVERDUE);
  }

  private Receivable withPaidNet(SettlementMoney nextPaid, LocalDate today) {
    ReceivableStatus next = deriveStatus(original, nextPaid, dueDate, today);
    return copy(nextPaid, next);
  }

  private Receivable copy(SettlementMoney nextPaid, ReceivableStatus nextStatus) {
    return new Receivable(
        id,
        tenantId,
        number,
        orderId,
        orderNumber,
        partnerId,
        partnerNumber,
        partnerName,
        partnerVersion,
        original,
        nextPaid,
        dueDate,
        nextStatus,
        version + 1);
  }

  public static ReceivableStatus deriveStatus(
      SettlementMoney original, SettlementMoney paidNet, LocalDate dueDate, LocalDate today) {
    BigDecimal outstanding = original.amount().subtract(paidNet.amount());
    if (outstanding.signum() == 0) return ReceivableStatus.PAID;
    if (today.isAfter(dueDate)) return ReceivableStatus.OVERDUE;
    if (paidNet.amount().signum() > 0) return ReceivableStatus.PARTIALLY_PAID;
    return ReceivableStatus.OPEN;
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
  }
}
