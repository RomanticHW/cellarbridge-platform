package com.rom.cellarbridge.tradeorder.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrderDomainException.FailureKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable commercial snapshot with explicit lifecycle behavior. */
public record TradeOrder(
    UUID id,
    TenantId tenantId,
    String number,
    UUID sourceQuotationId,
    UUID sourceRevisionId,
    String sourceQuotationNumber,
    int sourceRevision,
    UUID sourceEventId,
    UUID acceptanceId,
    Instant acceptedAt,
    UUID sourceOwnerId,
    TradeOrderStatus status,
    CommercialSnapshot commercialSnapshot,
    String snapshotHash,
    UUID correlationId,
    UUID causationId,
    UUID createdEventId,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public TradeOrder {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    requireText(number, "number");
    Objects.requireNonNull(sourceQuotationId, "sourceQuotationId");
    Objects.requireNonNull(sourceRevisionId, "sourceRevisionId");
    requireText(sourceQuotationNumber, "sourceQuotationNumber");
    if (sourceRevision < 1) {
      throw new IllegalArgumentException("sourceRevision must be positive");
    }
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(acceptanceId, "acceptanceId");
    Objects.requireNonNull(acceptedAt, "acceptedAt");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(commercialSnapshot, "commercialSnapshot");
    requireText(snapshotHash, "snapshotHash");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(causationId, "causationId");
    Objects.requireNonNull(createdEventId, "createdEventId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < 0) {
      throw new IllegalArgumentException("version cannot be negative");
    }
  }

  public static TradeOrder create(
      UUID id,
      TenantId tenantId,
      String number,
      UUID sourceQuotationId,
      UUID sourceRevisionId,
      String sourceQuotationNumber,
      int sourceRevision,
      UUID sourceEventId,
      UUID acceptanceId,
      Instant acceptedAt,
      UUID sourceOwnerId,
      CommercialSnapshot commercialSnapshot,
      String snapshotHash,
      UUID correlationId,
      UUID causationId,
      UUID createdEventId,
      Instant now) {
    return new TradeOrder(
        id,
        tenantId,
        number,
        sourceQuotationId,
        sourceRevisionId,
        sourceQuotationNumber,
        sourceRevision,
        sourceEventId,
        acceptanceId,
        acceptedAt,
        sourceOwnerId,
        TradeOrderStatus.PENDING_RESERVATION,
        commercialSnapshot,
        snapshotHash,
        correlationId,
        causationId,
        createdEventId,
        now,
        now,
        0);
  }

  public TradeOrder reservationSucceeded(Instant now) {
    return transition(TradeOrderStatus.PENDING_RESERVATION, TradeOrderStatus.RESERVED, now);
  }

  public TradeOrder reservationFailed(Instant now) {
    return transition(
        TradeOrderStatus.PENDING_RESERVATION, TradeOrderStatus.RESERVATION_FAILED, now);
  }

  public TradeOrder retryReservation(Instant now) {
    return transition(
        TradeOrderStatus.RESERVATION_FAILED, TradeOrderStatus.PENDING_RESERVATION, now);
  }

  public TradeOrder markReadyForFulfillment(Instant now) {
    return transition(TradeOrderStatus.RESERVED, TradeOrderStatus.READY_FOR_FULFILLMENT, now);
  }

  public TradeOrder beginFulfillment(Instant now) {
    return transition(TradeOrderStatus.READY_FOR_FULFILLMENT, TradeOrderStatus.IN_FULFILLMENT, now);
  }

  public TradeOrder fulfill(Instant now) {
    return transition(TradeOrderStatus.IN_FULFILLMENT, TradeOrderStatus.FULFILLED, now);
  }

  public TradeOrder requestCancellation(Instant now) {
    if (status == TradeOrderStatus.PENDING_RESERVATION
        || status == TradeOrderStatus.RESERVATION_FAILED) {
      return withStatus(TradeOrderStatus.CANCELLED, now);
    }
    if (status == TradeOrderStatus.RESERVED || status == TradeOrderStatus.READY_FOR_FULFILLMENT) {
      return withStatus(TradeOrderStatus.CANCELLATION_PENDING, now);
    }
    throw invalidTransition(TradeOrderStatus.CANCELLATION_PENDING);
  }

  public TradeOrder cancellationSucceeded(Instant now) {
    return transition(TradeOrderStatus.CANCELLATION_PENDING, TradeOrderStatus.CANCELLED, now);
  }

  public TradeOrder cancellationFailed(Instant now) {
    return transition(
        TradeOrderStatus.CANCELLATION_PENDING, TradeOrderStatus.CANCELLATION_FAILED, now);
  }

  private TradeOrder transition(
      TradeOrderStatus expected, TradeOrderStatus target, Instant occurredAt) {
    if (status != expected) {
      throw invalidTransition(target);
    }
    return withStatus(target, occurredAt);
  }

  private TradeOrder withStatus(TradeOrderStatus target, Instant occurredAt) {
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (occurredAt.isBefore(updatedAt)) {
      throw new IllegalArgumentException("transition time cannot precede the last update");
    }
    return new TradeOrder(
        id,
        tenantId,
        number,
        sourceQuotationId,
        sourceRevisionId,
        sourceQuotationNumber,
        sourceRevision,
        sourceEventId,
        acceptanceId,
        acceptedAt,
        sourceOwnerId,
        target,
        commercialSnapshot,
        snapshotHash,
        correlationId,
        causationId,
        createdEventId,
        createdAt,
        occurredAt,
        version + 1);
  }

  private TradeOrderDomainException invalidTransition(TradeOrderStatus target) {
    return new TradeOrderDomainException(
        FailureKind.STATE_CONFLICT,
        "INVALID_STATE_TRANSITION",
        "Trade order cannot move from " + status + " to " + target,
        status.name(),
        Map.of("targetState", target.name()));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  public record CommercialSnapshot(
      int schemaVersion,
      Customer customer,
      String currency,
      BigDecimal totalAmount,
      int paymentTermDays,
      Route route,
      String acceptedTermsVersion,
      LocalDate requestedDeliveryDate,
      DeliveryAddress deliveryAddress,
      List<Line> lines) {

    public CommercialSnapshot {
      if (schemaVersion != 1) {
        throw new IllegalArgumentException("commercial snapshot schemaVersion must be 1");
      }
      Objects.requireNonNull(customer, "customer");
      if (currency == null || !currency.matches("^[A-Z]{3}$")) {
        throw new IllegalArgumentException("currency must be an ISO-style uppercase code");
      }
      Objects.requireNonNull(totalAmount, "totalAmount");
      if (totalAmount.signum() < 0) {
        throw new IllegalArgumentException("totalAmount cannot be negative");
      }
      if (paymentTermDays < 0 || paymentTermDays > 180) {
        throw new IllegalArgumentException("paymentTermDays must be between 0 and 180");
      }
      Objects.requireNonNull(route, "route");
      requireText(acceptedTermsVersion, "acceptedTermsVersion");
      Objects.requireNonNull(requestedDeliveryDate, "requestedDeliveryDate");
      Objects.requireNonNull(deliveryAddress, "deliveryAddress");
      lines = List.copyOf(lines);
      if (lines.isEmpty()) {
        throw new IllegalArgumentException("commercial snapshot requires at least one line");
      }
      BigDecimal lineTotal =
          lines.stream().map(Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (lineTotal.compareTo(totalAmount) != 0) {
        throw new IllegalArgumentException("line totals must equal the order total");
      }
    }
  }

  public record Customer(
      UUID partnerId, String partnerNumber, String displayName, int sourceVersion) {
    public Customer {
      Objects.requireNonNull(partnerId, "partnerId");
      requireText(partnerNumber, "partnerNumber");
      requireText(displayName, "displayName");
      if (sourceVersion < 0) {
        throw new IllegalArgumentException("sourceVersion cannot be negative");
      }
    }
  }

  public record Route(String code, String policyVersion, LocalDate estimatedDeliveryDate) {
    public Route {
      requireText(code, "route.code");
      requireText(policyVersion, "route.policyVersion");
      Objects.requireNonNull(estimatedDeliveryDate, "estimatedDeliveryDate");
    }
  }

  public record DeliveryAddress(
      String countryCode,
      String province,
      String city,
      String district,
      String line1,
      String postalCode) {
    public DeliveryAddress {
      if (countryCode == null || !countryCode.matches("^[A-Z]{2}$")) {
        throw new IllegalArgumentException("countryCode must contain two uppercase letters");
      }
      requireText(province, "province");
      requireText(city, "city");
      requireText(line1, "line1");
    }
  }

  public record Line(
      UUID id,
      UUID sourceQuotationLineId,
      UUID skuId,
      String skuCode,
      String description,
      BigDecimal quantity,
      String unit,
      BigDecimal netUnitPrice,
      BigDecimal lineTotal,
      UUID supplyPoolId,
      String supplyType) {
    public Line {
      Objects.requireNonNull(id, "line.id");
      Objects.requireNonNull(sourceQuotationLineId, "sourceQuotationLineId");
      Objects.requireNonNull(skuId, "skuId");
      requireText(skuCode, "skuCode");
      requireText(description, "description");
      Objects.requireNonNull(quantity, "quantity");
      requireText(unit, "unit");
      Objects.requireNonNull(netUnitPrice, "netUnitPrice");
      Objects.requireNonNull(lineTotal, "lineTotal");
      if (quantity.signum() <= 0 || netUnitPrice.signum() <= 0 || lineTotal.signum() < 0) {
        throw new IllegalArgumentException("line quantity and amounts must be positive");
      }
      requireText(supplyType, "supplyType");
    }
  }
}
