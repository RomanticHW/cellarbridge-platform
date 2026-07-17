package com.rom.cellarbridge.inventory.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.Line;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ReservationRequestHashV1 {

  private static final Comparator<Line> LINE_ORDER =
      Comparator.comparing((Line line) -> line.skuId().toString())
          .thenComparing(line -> line.quantityUnit().name())
          .thenComparing(line -> line.orderLineId().toString());

  private ReservationRequestHashV1() {}

  public static String hash(
      TenantId tenantId,
      UUID orderId,
      String routeCode,
      String supplyDecisionHash,
      List<Line> lines) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(orderId, "orderId");
    Reservation.requireText(routeCode, "routeCode");
    if (supplyDecisionHash != null) {
      Reservation.requireHash(supplyDecisionHash, "supplyDecisionHash");
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
    frame(canonical, tenantId.value().toString());
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
      frame(canonical, line.allocationMode() == null ? null : line.allocationMode().name());
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

  private static void frame(StringBuilder target, String value) {
    if (value == null) {
      target.append("-1:\n");
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    target.append(bytes.length).append(':').append(value).append('\n');
  }
}
