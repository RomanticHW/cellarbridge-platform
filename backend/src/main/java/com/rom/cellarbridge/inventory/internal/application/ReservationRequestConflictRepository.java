package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.internal.domain.ReservationRequestConflict;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRequestConflictRepository {

  RecordResult record(TenantId tenantId, ReservationRequestConflict conflict);

  Optional<ReservationRequestConflict> findByOrderAndConflictingHash(
      TenantId tenantId, UUID orderId, String conflictingRequestHash);

  record RecordResult(ReservationRequestConflict conflict, boolean replayed) {}
}
