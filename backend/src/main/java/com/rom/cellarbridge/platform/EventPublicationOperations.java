package com.rom.cellarbridge.platform;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EventPublicationOperations {

  List<FailedDelivery> listFailed(UUID tenantId, int offset, int limit);

  List<FailedDelivery> listFinal(int offset, int limit);

  ReplayResult replay(
      UUID tenantId, UUID eventId, String consumerName, long expectedVersion, Instant requestedAt);

  record FailedDelivery(
      UUID tenantId,
      UUID eventId,
      String eventType,
      String producer,
      String subjectType,
      UUID subjectId,
      String subjectNumber,
      UUID correlationId,
      String consumerName,
      String status,
      int attempts,
      Instant nextRetryAt,
      String errorCode,
      Instant lastAttemptAt,
      long version) {}

  record ReplayResult(
      UUID eventId, String consumerName, String status, long version, boolean alreadyScheduled) {}
}
