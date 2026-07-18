package com.rom.cellarbridge.inventory.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationOperationRepository {

  boolean lockReservation(TenantId tenantId, UUID reservationId);

  Command claim(
      TenantId tenantId,
      UUID reservationId,
      Action action,
      String keyHash,
      String requestHash,
      UUID actorId,
      Instant now);

  List<UUID> lockAllocations(TenantId tenantId, UUID reservationId, List<UUID> allocationIds);

  boolean updateAllocation(TenantId tenantId, Allocation before, Allocation after, Action action);

  void complete(
      TenantId tenantId,
      UUID commandId,
      Status status,
      String resultCode,
      String resultSnapshot,
      Instant completedAt);

  void appendAudit(TenantId tenantId, Audit audit);

  List<Audit> findAudits(TenantId tenantId, UUID reservationId);

  enum Action {
    RELEASE,
    CONSUME
  }

  enum Status {
    PROCESSING,
    COMPLETED,
    REJECTED
  }

  record Command(
      UUID id,
      TenantId tenantId,
      UUID reservationId,
      Action action,
      String keyHash,
      String requestHash,
      Status status,
      String resultCode,
      String resultSnapshot,
      UUID actorId,
      Instant createdAt,
      Instant completedAt,
      boolean created) {}

  record Audit(
      UUID id,
      TenantId tenantId,
      UUID reservationId,
      UUID commandId,
      Action action,
      Status outcome,
      String reasonCode,
      UUID actorId,
      String keyHash,
      String previousState,
      String newState,
      Instant occurredAt) {}
}
