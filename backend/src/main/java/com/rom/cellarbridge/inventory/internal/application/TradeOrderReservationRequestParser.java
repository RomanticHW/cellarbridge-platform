package com.rom.cellarbridge.inventory.internal.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.inventory.internal.domain.ReservationRequestHashV1;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Classifies raw Current/Legacy presence before binding it to the immutable request model. */
@Component
final class TradeOrderReservationRequestParser {

  static final String EVENT_TYPE = "cellarbridge.order.created.v1";
  private static final String DECISION_POLICY = "SUPPLY-DECISION-2026-01";
  private static final Set<String> MODES = Set.of("ROUTE_ELIGIBLE_AUTO", "FIXED_POOL");
  private static final Set<String> UNITS = Set.of("CASE", "BOTTLE");
  private static final Set<String> SUPPLY_TYPES =
      Set.of(
          "DOMESTIC_ON_HAND",
          "BONDED_ON_HAND",
          "HONG_KONG_ON_HAND",
          "IN_TRANSIT_PRESALE",
          "OVERSEAS_SOURCING");
  private static final Set<String> ROUTES =
      Set.of("SH_GENERAL_TRADE", "NB_BONDED_B2B", "HK_FREE_TRADE");
  private final JsonMapper jsonMapper;

  TradeOrderReservationRequestParser(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  Request parse(EventDelivery delivery) {
    try {
      require(EVENT_TYPE.equals(delivery.eventType()) && delivery.eventVersion() == 1);
      JsonNode body = jsonMapper.readTree(delivery.payloadJson());
      Kind kind = classifyPresence(body);
      Payload payload = jsonMapper.readValue(body.toString(), Payload.class);
      validate(delivery, payload);
      TenantId tenantId = TenantId.of(delivery.tenantId());
      String decisionHash = kind == Kind.CURRENT ? rebuildDecision(payload) : null;
      List<Line> lines = payload.lines().stream().map(line -> line(kind, line)).toList();
      List<Reservation.Line> requestLines = lines.stream().map(Line::requestLine).toList();
      return new Request(
          tenantId,
          payload.orderId(),
          payload.orderNumber(),
          payload.route().code(),
          decisionHash,
          ReservationRequestHashV1.hash(
              tenantId, payload.orderId(), payload.route().code(), decisionHash, requestLines),
          kind,
          requestLines,
          lines);
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
      throw EventHandlingException.finalFailure("TRADE_ORDER_CREATED_EVENT_INVALID");
    }
  }

  private static Kind classifyPresence(JsonNode body) {
    require(body != null && body.isObject());
    JsonNode lines = body.path("lines");
    require(lines.isArray() && !lines.isEmpty());
    if (!body.has("supplyDecision")) {
      for (JsonNode line : lines) {
        if (!line.isObject() || line.has("allocationMode")) {
          throw invalidDecision();
        }
      }
      return Kind.LEGACY;
    }
    JsonNode decision = body.get("supplyDecision");
    if (decision == null || !decision.isObject()) {
      throw invalidDecision();
    }
    for (JsonNode line : lines) {
      if (!line.isObject()
          || !line.hasNonNull("allocationMode")
          || !line.path("allocationMode").isTextual()
          || !line.has("supplyPoolId")
          || !(line.path("supplyPoolId").isNull() || line.path("supplyPoolId").isTextual())) {
        throw invalidDecision();
      }
    }
    return Kind.CURRENT;
  }

  private static void validate(EventDelivery delivery, Payload payload) {
    require(payload != null && delivery.subject() != null);
    require(
        "TRADE_ORDER".equals(delivery.subject().type())
            && Objects.equals(delivery.subject().id(), payload.orderId())
            && Objects.equals(delivery.subject().number(), payload.orderNumber()));
    require(
        payload.orderId() != null
            && payload.sourceQuotationId() != null
            && payload.sourceRevisionId() != null
            && payload.sourceRevision() >= 1
            && payload.createdAt() != null
            && payload.route() != null
            && ROUTES.contains(payload.route().code())
            && payload.lines() != null
            && !payload.lines().isEmpty()
            && payload.lines().size() <= 50);
    requireText(payload.orderNumber());
    HashSet<UUID> orderLines = new HashSet<>();
    HashSet<UUID> sourceLines = new HashSet<>();
    for (EventLine line : payload.lines()) {
      require(
          line != null
              && line.orderLineId() != null
              && orderLines.add(line.orderLineId())
              && line.sourceQuotationLineId() != null
              && sourceLines.add(line.sourceQuotationLineId())
              && line.skuId() != null
              && line.skuCode() != null
              && line.unit() != null
              && line.supplyType() != null);
      exactPositive(line.quantity());
    }
  }

  private static String rebuildDecision(Payload payload) {
    SupplyDecision root = payload.supplyDecision();
    try {
      require(
          root != null
              && root.schemaVersion() == 1
              && DECISION_POLICY.equals(root.policyVersion())
              && root.decisionHash() != null
              && root.decisionHash().matches("^[0-9a-f]{64}$")
              && root.selectedRouteCode().equals(payload.route().code())
              && root.sourceRouteEvaluationId() != null
              && root.sourceRouteInputHash() != null
              && root.sourceRouteInputHash().matches("^[0-9a-f]{64}$")
              && root.decidedAt() != null
              && root.inventoryDataAsOf() != null
              && microsecond(root.decidedAt())
              && microsecond(root.inventoryDataAsOf()));
      List<EventLine> ordered =
          payload.lines().stream()
              .peek(TradeOrderReservationRequestParser::validateDecisionLine)
              .sorted(Comparator.comparing(EventLine::sourceQuotationLineId))
              .toList();
      String rebuilt = decisionHash(root, ordered);
      if (!rebuilt.equals(root.decisionHash())) {
        throw EventHandlingException.finalFailure(
            "TRADE_ORDER_CREATED_SUPPLY_DECISION_HASH_MISMATCH");
      }
      return rebuilt;
    } catch (EventHandlingException exception) {
      throw exception;
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw invalidDecision();
    }
  }

  private static void validateDecisionLine(EventLine line) {
    require(
        line != null
            && UNITS.contains(line.unit())
            && MODES.contains(line.allocationMode())
            && SUPPLY_TYPES.contains(line.supplyType()));
    exactPositive(line.quantity());
    if ("FIXED_POOL".equals(line.allocationMode())) {
      require(line.supplyPoolId() != null);
    } else {
      require(
          line.supplyPoolId() == null
              && !"IN_TRANSIT_PRESALE".equals(line.supplyType())
              && !"OVERSEAS_SOURCING".equals(line.supplyType()));
    }
  }

  private static String decisionHash(SupplyDecision root, List<EventLine> lines) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream canonical = new DataOutputStream(bytes)) {
        write(canonical, Integer.toString(root.schemaVersion()));
        write(canonical, root.policyVersion());
        write(canonical, DateTimeFormatter.ISO_INSTANT.format(root.decidedAt()));
        write(canonical, root.sourceRouteEvaluationId().toString());
        write(canonical, root.sourceRouteInputHash());
        write(canonical, root.selectedRouteCode());
        write(canonical, DateTimeFormatter.ISO_INSTANT.format(root.inventoryDataAsOf()));
        canonical.writeInt(lines.size());
        for (EventLine line : lines) {
          write(canonical, line.sourceQuotationLineId().toString());
          write(canonical, line.skuId().toString());
          write(canonical, exactPositive(line.quantity()).toPlainString());
          write(canonical, line.unit());
          write(canonical, line.allocationMode());
          write(canonical, line.supplyPoolId() == null ? "" : line.supplyPoolId().toString());
          write(canonical, line.supplyType());
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Supply Decision Hash V1 calculation failed", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static boolean microsecond(Instant value) {
    return value.equals(value.truncatedTo(ChronoUnit.MICROS));
  }

  private static Line line(Kind kind, EventLine source) {
    BigDecimal quantity = exactPositive(source.quantity());
    QuantityUnit unit = QuantityUnit.valueOf(source.unit());
    Reservation.Line requestLine;
    if (kind == Kind.LEGACY) {
      requestLine =
          new Reservation.Line(
              source.orderLineId(),
              source.sourceQuotationLineId(),
              source.skuId(),
              quantity,
              unit,
              null,
              null,
              null);
    } else {
      requestLine =
          new Reservation.Line(
              source.orderLineId(),
              source.sourceQuotationLineId(),
              source.skuId(),
              quantity,
              unit,
              AllocationMode.valueOf(source.allocationMode()),
              source.supplyPoolId(),
              SupplyType.valueOf(source.supplyType()));
    }
    return new Line(requestLine, source.skuCode());
  }

  private static BigDecimal exactPositive(String value) {
    require(value != null && value.matches("[0-9]+(?:\\.[0-9]{1,6})?"));
    BigDecimal quantity = new BigDecimal(value).setScale(6, RoundingMode.UNNECESSARY);
    require(quantity.signum() > 0 && quantity.precision() <= 19);
    return quantity;
  }

  private static void requireText(String value) {
    require(value != null && !value.isBlank() && value.length() <= 80);
  }

  private static void require(boolean valid) {
    if (!valid) {
      throw new IllegalArgumentException("Invalid TradeOrderCreatedV1 payload");
    }
  }

  private static EventHandlingException invalidDecision() {
    return EventHandlingException.finalFailure("TRADE_ORDER_CREATED_SUPPLY_DECISION_INVALID");
  }

  enum Kind {
    CURRENT,
    LEGACY
  }

  record Request(
      TenantId tenantId,
      UUID orderId,
      String orderNumber,
      String routeCode,
      String supplyDecisionHash,
      String requestHash,
      Kind kind,
      List<Reservation.Line> requestLines,
      List<Line> lines) {

    Request {
      requestLines = List.copyOf(requestLines);
      lines = List.copyOf(lines);
    }
  }

  record Line(Reservation.Line requestLine, String skuCode) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Payload(
      UUID orderId,
      String orderNumber,
      UUID sourceQuotationId,
      UUID sourceRevisionId,
      int sourceRevision,
      Route route,
      SupplyDecision supplyDecision,
      List<EventLine> lines,
      Instant createdAt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Route(String code) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SupplyDecision(
      int schemaVersion,
      String policyVersion,
      Instant decidedAt,
      UUID sourceRouteEvaluationId,
      String sourceRouteInputHash,
      String selectedRouteCode,
      Instant inventoryDataAsOf,
      String decisionHash) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EventLine(
      UUID orderLineId,
      UUID sourceQuotationLineId,
      UUID skuId,
      String skuCode,
      String quantity,
      String unit,
      UUID supplyPoolId,
      String allocationMode,
      String supplyType) {}
}
