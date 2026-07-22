package com.rom.cellarbridge.fulfillment.internal.application;

import com.rom.cellarbridge.fulfillment.FulfillmentRecoveryOperations;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class FulfillmentRecoveryAdapter implements FulfillmentRecoveryOperations {

  private final FulfillmentPlanService plans;

  FulfillmentRecoveryAdapter(FulfillmentPlanService plans) {
    this.plans = plans;
  }

  @Override
  public RecoveryResult retryFailedStep(
      UUID planId, UUID stepId, String idempotencyKey, String reason) {
    long expectedVersion = plans.get(planId).summary().version();
    FulfillmentPlanService.ActionResult result =
        plans.act(
            planId,
            stepId,
            expectedVersion,
            idempotencyKey,
            FulfillmentPlanService.Action.RETRY,
            reason,
            null);
    return new RecoveryResult(
        result.planId(),
        result.stepId(),
        result.stepStatus(),
        result.planStatus(),
        result.version(),
        result.replayed());
  }

  @Override
  public RecoveryResult resumeOverdueStep(
      UUID planId, UUID stepId, String idempotencyKey, String reason) {
    FulfillmentPlanService.ActionResult result =
        plans.resumeOverdue(planId, stepId, idempotencyKey, reason);
    return new RecoveryResult(
        result.planId(),
        result.stepId(),
        result.stepStatus(),
        result.planStatus(),
        result.version(),
        result.replayed());
  }
}
