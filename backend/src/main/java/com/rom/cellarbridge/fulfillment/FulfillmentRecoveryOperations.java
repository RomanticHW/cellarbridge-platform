package com.rom.cellarbridge.fulfillment;

import java.util.UUID;

public interface FulfillmentRecoveryOperations {

  RecoveryResult retryFailedStep(UUID planId, UUID stepId, String idempotencyKey, String reason);

  RecoveryResult resumeOverdueStep(UUID planId, UUID stepId, String idempotencyKey, String reason);

  record RecoveryResult(
      UUID planId,
      UUID stepId,
      FulfillmentStepStatus stepStatus,
      FulfillmentStatus planStatus,
      long version,
      boolean replayed) {}
}
