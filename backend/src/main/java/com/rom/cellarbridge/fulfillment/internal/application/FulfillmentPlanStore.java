package com.rom.cellarbridge.fulfillment.internal.application;

import com.rom.cellarbridge.fulfillment.FulfillmentStatus;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStatus;
import com.rom.cellarbridge.fulfillment.internal.domain.FulfillmentTemplate;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FulfillmentPlanStore {

  Optional<FulfillmentTemplate> effectiveTemplate(String routeCode, Instant at);

  String nextNumber(Instant at);

  CreateResult create(
      TenantId tenantId,
      UUID planId,
      String number,
      UUID orderId,
      String orderNumber,
      UUID reservationId,
      FulfillmentTemplate template,
      String templateSnapshot,
      Instant createdAt,
      UUID correlationId,
      UUID causationId);

  Optional<Plan> findByOrder(TenantId tenantId, UUID orderId);

  Optional<Plan> find(TenantId tenantId, UUID planId, boolean forUpdate);

  List<Plan> list(
      TenantId tenantId,
      Set<FulfillmentStatus> statuses,
      Boolean overdue,
      String ownerRole,
      UUID orderId,
      int offset,
      int limit);

  List<Step> steps(TenantId tenantId, UUID planId);

  List<Milestone> milestones(TenantId tenantId, UUID planId);

  Optional<Command> command(TenantId tenantId, UUID planId, String keyHash);

  Command claim(
      TenantId tenantId,
      UUID planId,
      UUID stepId,
      String action,
      String keyHash,
      String requestHash,
      UUID actorId,
      Instant at);

  void completeCommand(TenantId tenantId, UUID commandId, String resultJson, Instant at);

  void updateStep(
      TenantId tenantId,
      Step before,
      FulfillmentStepStatus status,
      FulfillmentStepStatus overdueFrom,
      Instant startedAt,
      Instant completedAt,
      String failureCode,
      String safeMessage,
      int attempt);

  void updatePlan(
      TenantId tenantId, Plan before, FulfillmentStatus status, Instant completedAt, Instant at);

  void addMilestone(TenantId tenantId, UUID planId, Step step, Instant at);

  void unlockReadySteps(TenantId tenantId, UUID planId);

  AdapterAttempt recordAdapter(
      TenantId tenantId,
      UUID planId,
      UUID stepId,
      UUID commandId,
      String scenario,
      String outcome,
      Instant at);

  Optional<AdapterAttempt> adapter(TenantId tenantId, UUID stepId);

  List<OverdueCandidate> overdueCandidates(Instant now, int limit);

  record CreateResult(Plan plan, boolean replayed) {}

  record Plan(
      UUID id,
      TenantId tenantId,
      String number,
      UUID orderId,
      String orderNumber,
      UUID reservationId,
      String routeCode,
      String templateCode,
      String templateVersion,
      FulfillmentStatus status,
      Instant dueAt,
      UUID correlationId,
      UUID causationId,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt,
      long version) {}

  record Step(
      UUID id,
      UUID planId,
      String code,
      String name,
      int sequence,
      String ownerRole,
      FulfillmentStepStatus status,
      FulfillmentStepStatus overdueFrom,
      List<String> dependencies,
      Instant plannedStartAt,
      Instant dueAt,
      Instant startedAt,
      Instant completedAt,
      String failureCode,
      String safeMessage,
      boolean customerVisible,
      boolean optional,
      boolean skippable,
      int attempt,
      long version) {}

  record Milestone(String code, String label, Instant occurredAt, boolean customerVisible) {}

  record Command(
      UUID id,
      UUID stepId,
      String action,
      String requestHash,
      String resultJson,
      boolean created) {}

  record AdapterAttempt(String scenario, String outcome, String reference, Instant occurredAt) {}

  record OverdueCandidate(TenantId tenantId, UUID planId, UUID stepId) {}
}
