package com.rom.cellarbridge.fulfillment.internal.application;

import com.rom.cellarbridge.fulfillment.FulfillmentStepOverdueV1;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStatus;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.OverdueCandidate;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.Plan;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.Step;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentSlaService {
  private final FulfillmentPlanStore store;
  private final ReliableEventPublisher events;
  private final Clock clock;

  FulfillmentSlaService(FulfillmentPlanStore store, ReliableEventPublisher events, Clock clock) {
    this.store = store;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public int markOverdue(int limit) {
    Instant now = clock.instant();
    int changed = 0;
    for (OverdueCandidate candidate : store.overdueCandidates(now, limit)) {
      Plan plan = store.find(candidate.tenantId(), candidate.planId(), true).orElse(null);
      if (plan == null) continue;
      Step step =
          store.steps(candidate.tenantId(), candidate.planId()).stream()
              .filter(item -> item.id().equals(candidate.stepId()))
              .findFirst()
              .orElse(null);
      if (step == null
          || step.dueAt().compareTo(now) >= 0
          || (step.status() != FulfillmentStepStatus.READY
              && step.status() != FulfillmentStepStatus.IN_PROGRESS)) continue;
      store.updateStep(
          candidate.tenantId(),
          step,
          FulfillmentStepStatus.OVERDUE,
          step.status(),
          step.startedAt(),
          null,
          null,
          "The fulfillment step is overdue.",
          step.attempt());
      store.updatePlan(candidate.tenantId(), plan, plan.status(), null, now);
      events.publish(
          new PendingEvent(
              UUID.randomUUID(),
              candidate.tenantId().value(),
              FulfillmentStepOverdueV1.TYPE,
              1,
              now,
              "fulfillment",
              new PendingEvent.Subject("FULFILLMENT_PLAN", plan.id(), plan.number()),
              plan.correlationId(),
              plan.causationId(),
              new FulfillmentStepOverdueV1.Payload(
                  plan.id(),
                  plan.number(),
                  plan.orderId(),
                  plan.orderNumber(),
                  step.id(),
                  step.code(),
                  step.dueAt(),
                  now),
              Map.of()));
      changed++;
    }
    return changed;
  }
}
