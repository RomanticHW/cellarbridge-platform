package com.rom.cellarbridge.inventory;

import java.util.UUID;

/** Tenant-scoped recovery entry point for a previously failed Reservation. */
public interface InventoryRecoveryOperations {

  RecoveryResult retryReservation(
      UUID reservationId, String orderNumber, UUID correlationId, UUID causationId);

  record RecoveryResult(
      UUID reservationId, Status status, long version, String failureCode, boolean replayed) {}

  enum Status {
    CONFIRMED,
    RELEASED,
    CONSUMED,
    FAILED
  }
}
