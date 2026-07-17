package com.rom.cellarbridge.inventory.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventoryReservationRequestHashV1;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.Line;
import java.util.List;
import java.util.UUID;

public final class ReservationRequestHashV1 {

  private ReservationRequestHashV1() {}

  public static String hash(
      TenantId tenantId,
      UUID orderId,
      String routeCode,
      String supplyDecisionHash,
      List<Line> lines) {
    return InventoryReservationRequestHashV1.hash(
        tenantId.value(),
        orderId,
        routeCode,
        supplyDecisionHash,
        lines.stream()
            .map(
                line ->
                    new InventoryReservationRequestHashV1.Line(
                        line.orderLineId(),
                        line.sourceQuotationLineId(),
                        line.skuId(),
                        line.requestedQuantity(),
                        line.quantityUnit(),
                        line.allocationMode() == null ? null : line.allocationMode().name(),
                        line.supplyPoolId(),
                        line.supplyType()))
            .toList());
  }
}
