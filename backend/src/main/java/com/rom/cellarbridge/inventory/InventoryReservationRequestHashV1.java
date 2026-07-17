package com.rom.cellarbridge.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical Inventory reservation request identity shared with outcome consumers. */
public final class InventoryReservationRequestHashV1 {

  private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
  private static final Set<String> MODES = Set.of("FIXED_POOL", "ROUTE_ELIGIBLE_AUTO");
  private static final Comparator<Line> LINE_ORDER =
      Comparator.comparing((Line line) -> line.skuId().toString())
          .thenComparing(line -> line.quantityUnit().name())
          .thenComparing(line -> line.orderLineId().toString());

  private InventoryReservationRequestHashV1() {}

  public static String hash(
      UUID tenantId, UUID orderId, String routeCode, String supplyDecisionHash, List<Line> lines) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(orderId, "orderId");
    requireText(routeCode, "routeCode");
    if (supplyDecisionHash != null && !HASH.matcher(supplyDecisionHash).matches()) {
      throw new IllegalArgumentException("supplyDecisionHash must be a lowercase SHA-256 digest");
    }
    List<Line> ordered =
        List.copyOf(Objects.requireNonNull(lines, "lines")).stream().sorted(LINE_ORDER).toList();
    if (ordered.isEmpty()) {
      throw new IllegalArgumentException("Request Hash requires at least one line");
    }
    HashSet<UUID> orderLines = new HashSet<>();
    HashSet<UUID> sourceLines = new HashSet<>();
    if (ordered.stream()
        .anyMatch(
            line ->
                !orderLines.add(line.orderLineId())
                    || !sourceLines.add(line.sourceQuotationLineId()))) {
      throw new IllegalArgumentException("Request Hash lines must be unique");
    }

    StringBuilder canonical = new StringBuilder();
    frame(canonical, "inventory-reservation-request-v1");
    frame(canonical, tenantId.toString());
    frame(canonical, orderId.toString());
    frame(canonical, routeCode);
    frame(canonical, supplyDecisionHash);
    frame(canonical, Integer.toString(ordered.size()));
    for (Line line : ordered) {
      frame(canonical, line.orderLineId().toString());
      frame(canonical, line.sourceQuotationLineId().toString());
      frame(canonical, line.skuId().toString());
      frame(canonical, line.requestedQuantity().toPlainString());
      frame(canonical, line.quantityUnit().name());
      frame(canonical, line.allocationMode());
      frame(canonical, line.supplyPoolId() == null ? null : line.supplyPoolId().toString());
      frame(canonical, line.supplyType() == null ? null : line.supplyType().name());
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record Line(
      UUID orderLineId,
      UUID sourceQuotationLineId,
      UUID skuId,
      BigDecimal requestedQuantity,
      QuantityUnit quantityUnit,
      String allocationMode,
      UUID supplyPoolId,
      SupplyType supplyType) {

    public Line {
      Objects.requireNonNull(orderLineId, "orderLineId");
      Objects.requireNonNull(sourceQuotationLineId, "sourceQuotationLineId");
      Objects.requireNonNull(skuId, "skuId");
      Objects.requireNonNull(requestedQuantity, "requestedQuantity");
      requestedQuantity = requestedQuantity.setScale(6, RoundingMode.UNNECESSARY);
      if (requestedQuantity.signum() <= 0 || requestedQuantity.precision() > 19) {
        throw new IllegalArgumentException("requestedQuantity must be a positive numeric(19,6)");
      }
      Objects.requireNonNull(quantityUnit, "quantityUnit");
      boolean legacy = allocationMode == null && supplyPoolId == null && supplyType == null;
      if (!legacy) {
        if (!MODES.contains(allocationMode) || supplyType == null) {
          throw new IllegalArgumentException("Current request line evidence is incomplete");
        }
        if (("FIXED_POOL".equals(allocationMode)) != (supplyPoolId != null)) {
          throw new IllegalArgumentException("Supply Pool must match allocation mode");
        }
      }
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

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
