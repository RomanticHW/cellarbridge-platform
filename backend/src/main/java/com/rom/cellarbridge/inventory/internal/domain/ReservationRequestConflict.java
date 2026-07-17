package com.rom.cellarbridge.inventory.internal.domain;

import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReservationRequestConflict(
    UUID id,
    TenantId tenantId,
    UUID orderId,
    UUID reservationId,
    String existingRequestHash,
    String conflictingRequestHash,
    UUID sourceEventId,
    UUID correlationId,
    Instant observedAt,
    String failureCode) {

  public static final String RESERVATION_REQUEST_CONFLICT = "RESERVATION_REQUEST_CONFLICT";

  public ReservationRequestConflict {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(reservationId, "reservationId");
    Reservation.requireHash(existingRequestHash, "existingRequestHash");
    Reservation.requireHash(conflictingRequestHash, "conflictingRequestHash");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(observedAt, "observedAt");
    if (existingRequestHash.equals(conflictingRequestHash)) {
      throw new IllegalArgumentException("Conflicting request hash must differ from existing hash");
    }
    if (!RESERVATION_REQUEST_CONFLICT.equals(failureCode)) {
      throw new IllegalArgumentException(
          "Reservation request conflict must use the stable failure code");
    }
  }
}
