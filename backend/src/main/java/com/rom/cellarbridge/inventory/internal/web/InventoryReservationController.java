package com.rom.cellarbridge.inventory.internal.web;

import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationOperationsService;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationOperationsService.Detail;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationOperationsService.Item;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationOperationsService.OperationOutcome;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationOperationsService.OperationType;
import com.rom.cellarbridge.inventory.internal.application.ReservationOperationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/reservations")
final class InventoryReservationController {

  private final InventoryReservationOperationsService service;

  InventoryReservationController(InventoryReservationOperationsService service) {
    this.service = service;
  }

  @GetMapping("/by-order/{orderId}")
  ResponseEntity<Detail> getByOrder(@PathVariable UUID orderId) {
    Detail detail = service.getByOrder(orderId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag("\"" + detail.version() + "\"")
        .body(detail);
  }

  @GetMapping("/{reservationId}")
  ResponseEntity<Detail> get(@PathVariable UUID reservationId) {
    Detail detail = service.get(reservationId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag("\"" + detail.version() + "\"")
        .body(detail);
  }

  @PostMapping("/{reservationId}/release")
  ResponseEntity<OperationOutcome> release(
      @PathVariable UUID reservationId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody OperationRequest request) {
    return operation(reservationId, OperationType.RELEASE, idempotencyKey, request);
  }

  @PostMapping("/{reservationId}/consume")
  ResponseEntity<OperationOutcome> consume(
      @PathVariable UUID reservationId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody OperationRequest request) {
    return operation(reservationId, OperationType.CONSUME, idempotencyKey, request);
  }

  private ResponseEntity<OperationOutcome> operation(
      UUID reservationId, OperationType type, String idempotencyKey, OperationRequest request) {
    List<Item> items =
        request == null || request.allocations() == null
            ? null
            : request.allocations().stream().map(InventoryReservationController::item).toList();
    OperationOutcome outcome = service.execute(reservationId, type, idempotencyKey, items);
    if (!outcome.completed()) {
      throw new ReservationOperationException(outcome.code(), outcome.message());
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(outcome.replayed()))
        .eTag("\"" + outcome.reservationVersion() + "\"")
        .body(outcome);
  }

  private static Item item(OperationItemRequest request) {
    if (request == null
        || request.allocationId() == null
        || request.quantity() == null
        || request.quantityUnit() == null) {
      throw new IllegalArgumentException("Allocation values are required");
    }
    return new Item(request.allocationId(), request.quantity(), request.quantityUnit());
  }

  record OperationRequest(List<OperationItemRequest> allocations) {}

  record OperationItemRequest(UUID allocationId, BigDecimal quantity, QuantityUnit quantityUnit) {}
}
