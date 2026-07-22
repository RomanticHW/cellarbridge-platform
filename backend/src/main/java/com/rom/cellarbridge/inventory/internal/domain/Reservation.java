package com.rom.cellarbridge.inventory.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record Reservation(
    UUID id,
    TenantId tenantId,
    UUID orderId,
    String requestHash,
    String supplyDecisionHash,
    String routeCode,
    Status status,
    String failureCode,
    List<Line> lines,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public Reservation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(orderId, "orderId");
    requireHash(requestHash, "requestHash");
    requireText(routeCode, "routeCode");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < 0) {
      throw new IllegalArgumentException("version cannot be negative");
    }
    lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("Reservation requires at least one line");
    }
    HashSet<UUID> orderLineIds = new HashSet<>();
    HashSet<UUID> sourceLineIds = new HashSet<>();
    for (Line line : lines) {
      if (!orderLineIds.add(line.orderLineId())) {
        throw new IllegalArgumentException("Duplicate order line");
      }
      if (!sourceLineIds.add(line.sourceQuotationLineId())) {
        throw new IllegalArgumentException("Duplicate source quotation line");
      }
    }
    boolean legacy = supplyDecisionHash == null;
    if (!legacy) {
      requireHash(supplyDecisionHash, "supplyDecisionHash");
    }
    if (legacy != lines.stream().allMatch(Line::legacy)) {
      throw new IllegalArgumentException(
          "Reservation line evidence must be entirely Current or Legacy");
    }
    if (legacy && (status != Status.FAILED || !"SUPPLY_DECISION_MISSING".equals(failureCode))) {
      throw new IllegalArgumentException(
          "Legacy Reservation must be a controlled missing-decision failure");
    }
    if (status == Status.FAILED) {
      requireText(failureCode, "failureCode");
    } else if (failureCode != null) {
      throw new IllegalArgumentException("Only FAILED Reservation may carry a failure code");
    }
  }

  public static Reservation pending(
      UUID id,
      TenantId tenantId,
      UUID orderId,
      String requestHash,
      String supplyDecisionHash,
      String routeCode,
      List<Line> lines,
      Instant now) {
    return new Reservation(
        id,
        tenantId,
        orderId,
        requestHash,
        supplyDecisionHash,
        routeCode,
        Status.PENDING,
        null,
        lines,
        0,
        now,
        now);
  }

  public Reservation transition(Status target, String failure, Instant now) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(now, "now");
    if (!status.canTransitionTo(target)) {
      throw new IllegalStateException(
          "Illegal Reservation transition: " + status + " -> " + target);
    }
    return new Reservation(
        id,
        tenantId,
        orderId,
        requestHash,
        supplyDecisionHash,
        routeCode,
        target,
        failure,
        lines,
        version + 1,
        createdAt,
        now);
  }

  public Reservation retryOutcome(Status target, String failure, Instant now) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(now, "now");
    if (status != Status.FAILED || target != Status.CONFIRMED && target != Status.FAILED) {
      throw new IllegalStateException(
          "Illegal Reservation retry outcome: " + status + " -> " + target);
    }
    return new Reservation(
        id,
        tenantId,
        orderId,
        requestHash,
        supplyDecisionHash,
        routeCode,
        target,
        failure,
        lines,
        version + 1,
        createdAt,
        now);
  }

  public Reservation recordOperation(Status finalStatus, Instant now) {
    Objects.requireNonNull(now, "now");
    if (status != Status.CONFIRMED) {
      throw new IllegalStateException("Only a confirmed Reservation accepts operations");
    }
    if (finalStatus != null && finalStatus != Status.RELEASED && finalStatus != Status.CONSUMED) {
      throw new IllegalArgumentException("Operation final status must be RELEASED or CONSUMED");
    }
    return new Reservation(
        id,
        tenantId,
        orderId,
        requestHash,
        supplyDecisionHash,
        routeCode,
        finalStatus == null ? Status.CONFIRMED : finalStatus,
        null,
        lines,
        version + 1,
        createdAt,
        now);
  }

  public enum Status {
    PENDING,
    CONFIRMED,
    FAILED,
    RELEASED,
    CONSUMED;

    boolean canTransitionTo(Status target) {
      return this == PENDING && (target == CONFIRMED || target == FAILED)
          || this == CONFIRMED && (target == RELEASED || target == CONSUMED);
    }
  }

  public enum AllocationMode {
    ROUTE_ELIGIBLE_AUTO,
    FIXED_POOL
  }

  public record Line(
      UUID orderLineId,
      UUID sourceQuotationLineId,
      UUID skuId,
      BigDecimal requestedQuantity,
      QuantityUnit quantityUnit,
      AllocationMode allocationMode,
      UUID supplyPoolId,
      SupplyType supplyType) {

    public Line {
      Objects.requireNonNull(orderLineId, "orderLineId");
      Objects.requireNonNull(sourceQuotationLineId, "sourceQuotationLineId");
      Objects.requireNonNull(skuId, "skuId");
      requestedQuantity = ExactQuantity.positive(requestedQuantity, "requestedQuantity");
      Objects.requireNonNull(quantityUnit, "quantityUnit");
      if (allocationMode == null) {
        if (supplyPoolId != null || supplyType != null) {
          throw new IllegalArgumentException(
              "Legacy line cannot contain partial decision evidence");
        }
      } else {
        Objects.requireNonNull(supplyType, "supplyType");
        if (allocationMode == AllocationMode.ROUTE_ELIGIBLE_AUTO && supplyPoolId != null) {
          throw new IllegalArgumentException("AUTO line cannot freeze a Pool");
        }
        if (allocationMode == AllocationMode.FIXED_POOL && supplyPoolId == null) {
          throw new IllegalArgumentException("FIXED_POOL line requires a Pool");
        }
      }
    }

    public boolean legacy() {
      return allocationMode == null;
    }
  }

  static void requireHash(String value, String field) {
    if (value == null || !HASH.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
    }
  }

  static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
