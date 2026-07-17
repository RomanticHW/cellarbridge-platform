package com.rom.cellarbridge.tradeorder.internal.application;

import com.rom.cellarbridge.inventory.InventoryReservationConfirmedV1;
import com.rom.cellarbridge.inventory.InventoryReservationFailedV1;
import com.rom.cellarbridge.inventory.InventoryReservationRequestHashV1;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.TradeOrderSupplyDecisionStatus;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Line;
import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reconstructs and validates Inventory-owned reservation outcome evidence. */
@Component
final class InventoryReservationOutcomeParser {

  private static final String PRODUCER = "inventory";
  private static final String SUBJECT_TYPE = "INVENTORY_RESERVATION";
  private static final String CONFIRMED_CODE = "INVENTORY_RESERVATION_CONFIRMED";
  private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
  private static final Set<String> FAILURE_CODES =
      Set.of(
          "INVENTORY_INSUFFICIENT",
          "SUPPLY_NOT_AUTOMATICALLY_RESERVABLE",
          "INVENTORY_FIXED_POOL_INELIGIBLE",
          "INVENTORY_ALLOCATION_CONFLICT",
          "SUPPLY_DECISION_MISSING");
  private static final Comparator<InventoryReservationConfirmedV1.Allocation> ALLOCATION_ORDER =
      Comparator.comparing(
              (InventoryReservationConfirmedV1.Allocation allocation) ->
                  allocation.orderLineId().toString())
          .thenComparing(allocation -> allocation.supplyPoolId().toString())
          .thenComparing(allocation -> allocation.lotId().toString());
  private static final Comparator<InventoryReservationFailedV1.LineFailure> FAILURE_ORDER =
      Comparator.comparing(failure -> failure.orderLineId().toString());
  private static final Comparator<InventoryReservationFailedV1.Shortage> SHORTAGE_ORDER =
      Comparator.comparing(shortage -> shortage.orderLineId().toString());

  private final JsonMapper jsonMapper;

  InventoryReservationOutcomeParser(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  UUID orderId(EventDelivery delivery) {
    try {
      JsonNode body = parseBody(delivery.payloadJson());
      require(body.hasNonNull("orderId") && body.path("orderId").isTextual());
      return UUID.fromString(body.path("orderId").textValue());
    } catch (JacksonException | IllegalArgumentException exception) {
      throw invalid("INVENTORY_RESERVATION_OUTCOME_INVALID");
    }
  }

  Outcome parseConfirmed(EventDelivery delivery, TradeOrder order) {
    try {
      JsonNode body = parseBody(delivery.payloadJson());
      requireFields(
          body,
          "reservationId",
          "reservationNumber",
          "orderId",
          "orderNumber",
          "requestHash",
          "supplyDecisionHash",
          "confirmedAt",
          "allocations");
      require(body.path("allocations").isArray() && !body.path("allocations").isEmpty());
      InventoryReservationConfirmedV1.Payload payload =
          jsonMapper.readValue(body.toString(), InventoryReservationConfirmedV1.Payload.class);
      validateCommon(
          delivery,
          order,
          InventoryReservationConfirmedV1.TYPE,
          payload.reservationId(),
          payload.reservationNumber(),
          payload.orderId(),
          payload.orderNumber(),
          payload.requestHash(),
          payload.supplyDecisionHash(),
          payload.confirmedAt());
      if (order.supplyDecisionStatus() != TradeOrderSupplyDecisionStatus.FROZEN) {
        throw invalid("ORDER_RESERVATION_SUPPLY_DECISION_MISMATCH");
      }
      validateAllocations(order, payload.allocations());
      String evidenceHash = confirmedEvidence(payload);
      return new Outcome(
          delivery.eventId(),
          InventoryReservationConfirmedV1.TYPE,
          TradeOrderStatus.RESERVED,
          CONFIRMED_CODE,
          evidenceHash,
          payload.confirmedAt());
    } catch (OutcomeValidationException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw invalid("INVENTORY_RESERVATION_OUTCOME_INVALID");
    }
  }

  Outcome parseFailed(EventDelivery delivery, TradeOrder order) {
    try {
      JsonNode body = parseBody(delivery.payloadJson());
      requireFields(
          body,
          "reservationId",
          "reservationNumber",
          "orderId",
          "orderNumber",
          "requestHash",
          "failedAt",
          "reasonCode",
          "shortages",
          "lineFailures",
          "retryable");
      require(body.has("supplyDecisionHash"));
      require(body.path("shortages").isArray());
      require(body.path("lineFailures").isArray() && !body.path("lineFailures").isEmpty());
      require(body.path("retryable").isBoolean());
      InventoryReservationFailedV1.Payload payload =
          jsonMapper.readValue(body.toString(), InventoryReservationFailedV1.Payload.class);
      validateCommon(
          delivery,
          order,
          InventoryReservationFailedV1.TYPE,
          payload.reservationId(),
          payload.reservationNumber(),
          payload.orderId(),
          payload.orderNumber(),
          payload.requestHash(),
          payload.supplyDecisionHash(),
          payload.failedAt());
      if (payload.retryable() || !FAILURE_CODES.contains(payload.reasonCode())) {
        throw invalid("INVENTORY_RESERVATION_OUTCOME_INVALID");
      }
      boolean legacy =
          order.supplyDecisionStatus() == TradeOrderSupplyDecisionStatus.LEGACY_UNVERIFIED;
      if (legacy != "SUPPLY_DECISION_MISSING".equals(payload.reasonCode())) {
        throw invalid("ORDER_RESERVATION_SUPPLY_DECISION_MISMATCH");
      }
      validateFailures(order, payload);
      String evidenceHash = failedEvidence(payload);
      return new Outcome(
          delivery.eventId(),
          InventoryReservationFailedV1.TYPE,
          TradeOrderStatus.RESERVATION_FAILED,
          payload.reasonCode(),
          evidenceHash,
          payload.failedAt());
    } catch (OutcomeValidationException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw invalid("INVENTORY_RESERVATION_OUTCOME_INVALID");
    }
  }

  private JsonNode parseBody(String payloadJson) throws JacksonException {
    JsonNode body = jsonMapper.readTree(payloadJson);
    require(body != null && body.isObject());
    return body;
  }

  private static void validateCommon(
      EventDelivery delivery,
      TradeOrder order,
      String expectedType,
      UUID reservationId,
      String reservationNumber,
      UUID orderId,
      String orderNumber,
      String requestHash,
      String supplyDecisionHash,
      Instant occurredAt) {
    require(expectedType.equals(delivery.eventType()) && delivery.eventVersion() == 1);
    require(PRODUCER.equals(delivery.producer()));
    require(
        SUBJECT_TYPE.equals(delivery.subject().type())
            && Objects.equals(delivery.subject().id(), reservationId)
            && Objects.equals(delivery.subject().number(), reservationNumber));
    require(
        reservationId != null
            && Objects.equals("RES-" + reservationId, reservationNumber)
            && Objects.equals(order.id(), orderId)
            && Objects.equals(order.number(), orderNumber));
    require(
        Objects.equals(order.correlationId(), delivery.correlationId())
            && Objects.equals(order.createdEventId(), delivery.causationId()));
    require(
        occurredAt != null
            && occurredAt.equals(delivery.occurredAt())
            && !occurredAt.isBefore(order.updatedAt()));

    String expectedRequestHash = requestHash(order);
    if (!sameHash(expectedRequestHash, requestHash)) {
      throw invalid("ORDER_RESERVATION_REQUEST_HASH_MISMATCH");
    }
    String expectedDecisionHash =
        order.supplyDecisionStatus() == TradeOrderSupplyDecisionStatus.FROZEN
            ? order.supplyDecision().decisionHash()
            : null;
    if (expectedDecisionHash == null) {
      if (supplyDecisionHash != null) {
        throw invalid("ORDER_RESERVATION_SUPPLY_DECISION_MISMATCH");
      }
    } else if (!sameHash(expectedDecisionHash, supplyDecisionHash)) {
      throw invalid("ORDER_RESERVATION_SUPPLY_DECISION_MISMATCH");
    }
  }

  private static String requestHash(TradeOrder order) {
    boolean legacy =
        order.supplyDecisionStatus() == TradeOrderSupplyDecisionStatus.LEGACY_UNVERIFIED;
    return InventoryReservationRequestHashV1.hash(
        order.tenantId().value(),
        order.id(),
        order.commercialSnapshot().route().code(),
        legacy ? null : order.supplyDecision().decisionHash(),
        order.commercialSnapshot().lines().stream()
            .map(
                line ->
                    new InventoryReservationRequestHashV1.Line(
                        line.id(),
                        line.sourceQuotationLineId(),
                        line.skuId(),
                        line.quantity(),
                        QuantityUnit.valueOf(line.unit()),
                        legacy ? null : line.allocationMode().name(),
                        legacy ? null : line.supplyPoolId(),
                        legacy ? null : SupplyType.valueOf(line.supplyType())))
            .toList());
  }

  private static void validateAllocations(
      TradeOrder order, List<InventoryReservationConfirmedV1.Allocation> allocations) {
    require(allocations != null && !allocations.isEmpty());
    Map<UUID, Line> lines = lines(order);
    Map<UUID, BigDecimal> totals = new HashMap<>();
    HashSet<String> allocationKeys = new HashSet<>();
    for (InventoryReservationConfirmedV1.Allocation allocation : allocations) {
      require(allocation != null);
      Line line = lines.get(allocation.orderLineId());
      require(
          line != null
              && Objects.equals(line.skuId(), allocation.skuId())
              && Objects.equals(QuantityUnit.valueOf(line.unit()), allocation.unit())
              && Objects.equals(SupplyType.valueOf(line.supplyType()), allocation.supplyType())
              && allocation.supplyPoolId() != null
              && allocation.lotId() != null
              && allocationKeys.add(allocation.orderLineId() + "|" + allocation.lotId())
              && allocation.lotCode() != null
              && !allocation.lotCode().isBlank()
              && allocation.lotCode().length() <= 80);
      if (line.allocationMode().name().equals("FIXED_POOL")) {
        require(Objects.equals(line.supplyPoolId(), allocation.supplyPoolId()));
      }
      BigDecimal quantity = quantity(allocation.quantity());
      totals.merge(line.id(), quantity, BigDecimal::add);
    }
    require(totals.size() == lines.size());
    lines
        .values()
        .forEach(
            line -> require(line.quantity().setScale(6).compareTo(totals.get(line.id())) == 0));
  }

  private static void validateFailures(
      TradeOrder order, InventoryReservationFailedV1.Payload payload) {
    Map<UUID, Line> lines = lines(order);
    Map<UUID, InventoryReservationFailedV1.LineFailure> failures = new HashMap<>();
    for (InventoryReservationFailedV1.LineFailure failure : payload.lineFailures()) {
      require(failure != null && failures.put(failure.orderLineId(), failure) == null);
      Line line = lines.get(failure.orderLineId());
      require(
          line != null
              && Objects.equals(line.skuId(), failure.skuId())
              && Objects.equals(line.skuCode(), failure.skuCode())
              && line.quantity().setScale(6).compareTo(quantity(failure.requestedQuantity())) == 0
              && Objects.equals(QuantityUnit.valueOf(line.unit()), failure.unit()));
      boolean legacy =
          order.supplyDecisionStatus() == TradeOrderSupplyDecisionStatus.LEGACY_UNVERIFIED;
      require(
          Objects.equals(legacy ? null : line.allocationMode().name(), failure.allocationMode())
              && Objects.equals(legacy ? null : line.supplyPoolId(), failure.supplyPoolId())
              && Objects.equals(SupplyType.valueOf(line.supplyType()), failure.supplyType()));
      validateAvailability(failure);
    }
    require(!failures.isEmpty());

    Map<UUID, InventoryReservationFailedV1.Shortage> shortages = new HashMap<>();
    for (InventoryReservationFailedV1.Shortage shortage : payload.shortages()) {
      require(shortage != null && shortages.put(shortage.orderLineId(), shortage) == null);
      InventoryReservationFailedV1.LineFailure failure = failures.get(shortage.orderLineId());
      require(
          failure != null
              && Objects.equals(failure.skuId(), shortage.skuId())
              && Objects.equals(failure.skuCode(), shortage.skuCode())
              && Objects.equals(failure.requestedQuantity(), shortage.requestedQuantity())
              && Objects.equals(
                  failure.observedAvailableQuantity(), shortage.observedAvailableQuantity())
              && Objects.equals(failure.shortageQuantity(), shortage.shortageQuantity())
              && Objects.equals(failure.unit(), shortage.unit()));
    }
    long failuresWithAvailability =
        payload.lineFailures().stream()
            .filter(failure -> failure.observedAvailableQuantity() != null)
            .count();
    require(shortages.size() == failuresWithAvailability);
    if ("INVENTORY_INSUFFICIENT".equals(payload.reasonCode())
        || "INVENTORY_ALLOCATION_CONFLICT".equals(payload.reasonCode())) {
      require(shortages.size() == 1 && failures.size() == 1);
    } else {
      require(shortages.isEmpty());
    }
    if ("SUPPLY_NOT_AUTOMATICALLY_RESERVABLE".equals(payload.reasonCode())) {
      require(
          failures.keySet().stream()
              .map(lines::get)
              .allMatch(line -> !SupplyType.valueOf(line.supplyType()).automaticallyReservable()));
    }
    if ("INVENTORY_FIXED_POOL_INELIGIBLE".equals(payload.reasonCode())) {
      require(
          failures.keySet().stream()
              .map(lines::get)
              .allMatch(line -> line.allocationMode() == SupplyAllocationMode.FIXED_POOL));
    }
  }

  private static void validateAvailability(InventoryReservationFailedV1.LineFailure failure) {
    boolean present = failure.observedAvailableQuantity() != null;
    require(present == (failure.shortageQuantity() != null));
    if (!present) {
      return;
    }
    BigDecimal requested = quantity(failure.requestedQuantity());
    BigDecimal observed = nonNegativeQuantity(failure.observedAvailableQuantity());
    BigDecimal shortage = quantity(failure.shortageQuantity());
    require(
        observed.compareTo(requested) < 0 && requested.subtract(observed).compareTo(shortage) == 0);
  }

  private static Map<UUID, Line> lines(TradeOrder order) {
    Map<UUID, Line> result = new HashMap<>();
    order
        .commercialSnapshot()
        .lines()
        .forEach(line -> require(result.put(line.id(), line) == null));
    return Map.copyOf(result);
  }

  private static String confirmedEvidence(InventoryReservationConfirmedV1.Payload payload) {
    List<String> values =
        commonEvidence(
            payload.reservationId(),
            payload.reservationNumber(),
            payload.orderId(),
            payload.orderNumber(),
            payload.requestHash(),
            payload.supplyDecisionHash(),
            payload.confirmedAt());
    values.add(TradeOrderStatus.RESERVED.name());
    for (InventoryReservationConfirmedV1.Allocation allocation :
        payload.allocations().stream().sorted(ALLOCATION_ORDER).toList()) {
      values.add(allocation.orderLineId().toString());
      values.add(allocation.skuId().toString());
      values.add(allocation.supplyPoolId().toString());
      values.add(allocation.lotId().toString());
      values.add(allocation.lotCode());
      values.add(allocation.supplyType().name());
      values.add(quantity(allocation.quantity()).toPlainString());
      values.add(allocation.unit().name());
    }
    return digest(values);
  }

  private static String failedEvidence(InventoryReservationFailedV1.Payload payload) {
    List<String> values =
        commonEvidence(
            payload.reservationId(),
            payload.reservationNumber(),
            payload.orderId(),
            payload.orderNumber(),
            payload.requestHash(),
            payload.supplyDecisionHash(),
            payload.failedAt());
    values.add(TradeOrderStatus.RESERVATION_FAILED.name());
    values.add(payload.reasonCode());
    values.add(Boolean.toString(payload.retryable()));
    for (InventoryReservationFailedV1.Shortage shortage :
        payload.shortages().stream().sorted(SHORTAGE_ORDER).toList()) {
      values.add(shortage.orderLineId().toString());
      values.add(shortage.skuId().toString());
      values.add(shortage.skuCode());
      values.add(quantity(shortage.requestedQuantity()).toPlainString());
      values.add(nonNegativeQuantity(shortage.observedAvailableQuantity()).toPlainString());
      values.add(quantity(shortage.shortageQuantity()).toPlainString());
      values.add(shortage.unit().name());
    }
    for (InventoryReservationFailedV1.LineFailure failure :
        payload.lineFailures().stream().sorted(FAILURE_ORDER).toList()) {
      values.add(failure.orderLineId().toString());
      values.add(failure.skuId().toString());
      values.add(failure.skuCode());
      values.add(quantity(failure.requestedQuantity()).toPlainString());
      values.add(failure.unit().name());
      values.add(failure.allocationMode());
      values.add(failure.supplyPoolId() == null ? null : failure.supplyPoolId().toString());
      values.add(failure.supplyType().name());
      values.add(
          failure.observedAvailableQuantity() == null
              ? null
              : nonNegativeQuantity(failure.observedAvailableQuantity()).toPlainString());
      values.add(
          failure.shortageQuantity() == null
              ? null
              : quantity(failure.shortageQuantity()).toPlainString());
    }
    return digest(values);
  }

  private static List<String> commonEvidence(
      UUID reservationId,
      String reservationNumber,
      UUID orderId,
      String orderNumber,
      String requestHash,
      String decisionHash,
      Instant occurredAt) {
    List<String> values = new ArrayList<>();
    values.add("trade-order-inventory-reservation-outcome-v1");
    values.add(reservationId.toString());
    values.add(reservationNumber);
    values.add(orderId.toString());
    values.add(orderNumber);
    values.add(requestHash);
    values.add(decisionHash);
    values.add(occurredAt.toString());
    return values;
  }

  private static String digest(List<String> values) {
    StringBuilder canonical = new StringBuilder();
    values.forEach(value -> frame(canonical, value));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void frame(StringBuilder target, String value) {
    if (value == null) {
      target.append("-1:\n");
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    target.append(bytes.length).append(':').append(value).append('\n');
  }

  private static BigDecimal quantity(String value) {
    BigDecimal quantity = new BigDecimal(value).setScale(6, RoundingMode.UNNECESSARY);
    require(quantity.signum() > 0 && quantity.precision() <= 19);
    return quantity;
  }

  private static BigDecimal nonNegativeQuantity(String value) {
    BigDecimal quantity = new BigDecimal(value).setScale(6, RoundingMode.UNNECESSARY);
    require(quantity.signum() >= 0 && quantity.precision() <= 19);
    return quantity;
  }

  private static boolean sameHash(String expected, String supplied) {
    if (expected == null || supplied == null || !HASH.matcher(supplied).matches()) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
  }

  private static void requireFields(JsonNode body, String... fields) {
    for (String field : fields) {
      require(body.hasNonNull(field));
    }
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw invalid("INVENTORY_RESERVATION_OUTCOME_INVALID");
    }
  }

  private static OutcomeValidationException invalid(String failureCode) {
    return new OutcomeValidationException(failureCode);
  }

  record Outcome(
      UUID eventId,
      String eventType,
      TradeOrderStatus status,
      String reasonCode,
      String evidenceHash,
      Instant occurredAt) {}

  static final class OutcomeValidationException extends RuntimeException {
    private final String failureCode;

    OutcomeValidationException(String failureCode) {
      super(failureCode);
      this.failureCode = failureCode;
    }

    String failureCode() {
      return failureCode;
    }
  }
}
