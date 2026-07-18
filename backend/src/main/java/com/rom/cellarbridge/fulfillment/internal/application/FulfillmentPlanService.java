package com.rom.cellarbridge.fulfillment.internal.application;

import com.rom.cellarbridge.fulfillment.FulfillmentCompletedV1;
import com.rom.cellarbridge.fulfillment.FulfillmentPlanCreatedV1;
import com.rom.cellarbridge.fulfillment.FulfillmentStatus;
import com.rom.cellarbridge.fulfillment.FulfillmentStepFailedV1;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStartedV1;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStatus;
import com.rom.cellarbridge.fulfillment.PublicMilestoneReachedV1;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.AdapterAttempt;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.Command;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.Milestone;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.Plan;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.Step;
import com.rom.cellarbridge.fulfillment.internal.domain.FulfillmentTemplate;
import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class FulfillmentPlanService {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{20,200}");
  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorization;
  private final FulfillmentPlanStore store;
  private final SimulatedFulfillmentAdapter adapter;
  private final ReliableEventPublisher events;
  private final JsonMapper json;
  private final Clock clock;

  FulfillmentPlanService(
      TenantContextHolder contextHolder,
      AuthorizationService authorization,
      FulfillmentPlanStore store,
      SimulatedFulfillmentAdapter adapter,
      ReliableEventPublisher events,
      JsonMapper json,
      Clock clock) {
    this.contextHolder = contextHolder;
    this.authorization = authorization;
    this.store = store;
    this.adapter = adapter;
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  PlanCreation createFromReservation(
      EventDelivery delivery,
      UUID reservationId,
      UUID orderId,
      String orderNumber,
      String routeCode,
      Instant confirmedAt) {
    TenantId tenantId = new TenantId(delivery.tenantId());
    Instant createdAt = max(clock.instant(), confirmedAt);
    FulfillmentTemplate template =
        store
            .effectiveTemplate(routeCode, createdAt)
            .orElseThrow(
                () ->
                    new FulfillmentProblem(
                        "FULFILLMENT_TEMPLATE_NOT_FOUND", "No effective route template exists"));
    String snapshot = json(template);
    FulfillmentPlanStore.CreateResult result =
        store.create(
            tenantId,
            UUID.randomUUID(),
            store.nextNumber(createdAt),
            orderId,
            orderNumber,
            reservationId,
            template,
            snapshot,
            createdAt,
            delivery.correlationId(),
            delivery.eventId());
    Plan plan = result.plan();
    if (!result.replayed()) {
      publish(
          plan,
          FulfillmentPlanCreatedV1.TYPE,
          new FulfillmentPlanCreatedV1.Payload(
              plan.id(),
              plan.number(),
              plan.orderId(),
              plan.orderNumber(),
              plan.routeCode(),
              plan.templateCode(),
              plan.templateVersion(),
              plan.createdAt()),
          plan.createdAt(),
          delivery.eventId());
    }
    return new PlanCreation(
        plan.id(), digest(plan.id() + "|" + plan.templateVersion()), result.replayed());
  }

  @Transactional(readOnly = true)
  public PageView list(
      Set<FulfillmentStatus> statuses,
      Boolean overdue,
      String ownerRole,
      UUID orderId,
      Integer requestedSize,
      String cursor) {
    TenantContext context = readableContext();
    int pageSize = requestedSize == null ? 25 : Math.max(1, Math.min(requestedSize, 100));
    int offset = decodeCursor(cursor);
    List<Plan> rows =
        store.list(
            context.tenantId(),
            statuses,
            overdue,
            normalizeOwnerRole(ownerRole),
            orderId,
            offset,
            pageSize + 1);
    boolean hasNext = rows.size() > pageSize;
    if (hasNext) rows = rows.subList(0, pageSize);
    return new PageView(
        rows.stream().map(plan -> summary(context.tenantId(), plan)).toList(),
        hasNext ? encodeCursor(offset + pageSize) : null,
        hasNext,
        pageSize);
  }

  @Transactional(readOnly = true)
  public DetailView get(UUID planId) {
    TenantContext context = readableContext();
    Plan plan =
        store
            .find(context.tenantId(), Objects.requireNonNull(planId, "planId"), false)
            .orElseThrow(FulfillmentPlanService::notFound);
    return detail(context, plan);
  }

  @Transactional
  public ActionResult act(
      UUID planId,
      UUID stepId,
      long expectedVersion,
      String idempotencyKey,
      Action action,
      String reason,
      String scenario) {
    TenantContext context = operatingContext();
    requireReason(action, reason);
    String keyHash = keyHash(idempotencyKey);
    String requestHash =
        digest(
            context.tenantId().value()
                + "|"
                + context.userId()
                + "|"
                + planId
                + "|"
                + stepId
                + "|"
                + action
                + "|"
                + text(reason)
                + "|"
                + text(scenario));
    Command existing = store.command(context.tenantId(), planId, keyHash).orElse(null);
    if (existing != null) {
      return replay(existing, stepId, action, requestHash);
    }
    Plan plan =
        store.find(context.tenantId(), planId, true).orElseThrow(FulfillmentPlanService::notFound);
    if (plan.version() != expectedVersion) {
      throw new FulfillmentProblem(
          "OPTIMISTIC_VERSION_CONFLICT",
          "The Fulfillment Plan changed; refresh before retrying",
          plan.version());
    }
    if (terminal(plan.status())) {
      invalidState();
    }
    Step step =
        store.steps(context.tenantId(), plan.id()).stream()
            .filter(candidate -> candidate.id().equals(stepId))
            .findFirst()
            .orElseThrow(FulfillmentPlanService::notFound);
    requireOwner(context, step);
    Instant now = max(clock.instant(), plan.updatedAt());
    Command command =
        store.claim(
            context.tenantId(),
            plan.id(),
            step.id(),
            action.name(),
            keyHash,
            requestHash,
            context.userId(),
            now);
    if (!command.created()) return replay(command, stepId, action, requestHash);

    SimulatedFulfillmentAdapter.Result adapterResult = null;
    Action effectiveAction = action;
    String effectiveReason = reason;
    if (action == Action.COMPLETE) {
      adapterResult = adapter.execute(scenario);
      store.recordAdapter(
          context.tenantId(),
          plan.id(),
          step.id(),
          command.id(),
          adapterResult.scenario(),
          adapterResult.outcome(),
          now);
      if ("FAILED".equals(adapterResult.outcome())) {
        effectiveAction = Action.FAIL;
        effectiveReason = adapterResult.failureCode();
      } else if ("DELAYED".equals(adapterResult.outcome())) {
        requireActive(step);
        store.updatePlan(context.tenantId(), plan, plan.status(), null, now);
        ActionResult result =
            new ActionResult(
                plan.id(), step.id(), step.status(), plan.status(), plan.version() + 1, false);
        store.completeCommand(context.tenantId(), command.id(), json(result), now);
        return result;
      }
    }
    ActionResult result =
        apply(context.tenantId(), plan, step, effectiveAction, effectiveReason, now);
    store.completeCommand(context.tenantId(), command.id(), json(result), now);
    return result;
  }

  private ActionResult apply(
      TenantId tenantId, Plan plan, Step step, Action action, String reason, Instant now) {
    FulfillmentStepStatus stepStatus;
    FulfillmentStatus planStatus;
    Instant startedAt = step.startedAt();
    Instant completedAt = null;
    String failureCode = null;
    String safeMessage = null;
    int attempt = step.attempt();
    switch (action) {
      case START -> {
        if (!isReady(step)) invalidState();
        stepStatus = FulfillmentStepStatus.IN_PROGRESS;
        planStatus = FulfillmentStatus.IN_PROGRESS;
        startedAt = now;
        attempt++;
      }
      case COMPLETE -> {
        requireActive(step);
        stepStatus = FulfillmentStepStatus.COMPLETED;
        planStatus = FulfillmentStatus.IN_PROGRESS;
        completedAt = now;
      }
      case FAIL -> {
        requireActive(step);
        stepStatus = FulfillmentStepStatus.FAILED;
        planStatus = FulfillmentStatus.ON_HOLD;
        failureCode = normalizeFailure(reason);
        safeMessage = "The fulfillment step requires operational review.";
      }
      case RETRY -> {
        if (step.status() != FulfillmentStepStatus.FAILED) invalidState();
        List<Step> all = store.steps(tenantId, plan.id());
        boolean ready = dependenciesSatisfied(step, all);
        stepStatus = ready ? FulfillmentStepStatus.READY : FulfillmentStepStatus.BLOCKED;
        planStatus = FulfillmentStatus.IN_PROGRESS;
        startedAt = null;
      }
      case SKIP -> {
        if (!step.skippable() || !step.optional() || terminal(step.status())) invalidState();
        stepStatus = FulfillmentStepStatus.SKIPPED;
        planStatus = FulfillmentStatus.IN_PROGRESS;
        completedAt = now;
      }
      default -> throw new IllegalStateException("Unsupported Fulfillment action");
    }
    store.updateStep(
        tenantId,
        step,
        stepStatus,
        null,
        startedAt,
        completedAt,
        failureCode,
        safeMessage,
        attempt);
    if (stepStatus == FulfillmentStepStatus.COMPLETED
        || stepStatus == FulfillmentStepStatus.SKIPPED) {
      if (stepStatus == FulfillmentStepStatus.COMPLETED && step.customerVisible()) {
        store.addMilestone(tenantId, plan.id(), step, now);
        publish(
            plan,
            PublicMilestoneReachedV1.TYPE,
            new PublicMilestoneReachedV1.Payload(
                plan.id(),
                plan.number(),
                plan.orderId(),
                plan.orderNumber(),
                step.code(),
                step.name(),
                now),
            now,
            plan.causationId());
      }
      store.unlockReadySteps(tenantId, plan.id());
      List<Step> current = store.steps(tenantId, plan.id());
      if (current.stream()
          .filter(item -> !item.optional())
          .allMatch(item -> terminalSuccess(item.status()))) {
        planStatus = FulfillmentStatus.COMPLETED;
      }
    }
    Instant planCompletedAt = planStatus == FulfillmentStatus.COMPLETED ? now : null;
    store.updatePlan(tenantId, plan, planStatus, planCompletedAt, now);
    if (action == Action.START) {
      publish(
          plan,
          FulfillmentStepStartedV1.TYPE,
          new FulfillmentStepStartedV1.Payload(
              plan.id(),
              plan.number(),
              plan.orderId(),
              plan.orderNumber(),
              step.id(),
              step.code(),
              now),
          now,
          plan.causationId());
    }
    if (stepStatus == FulfillmentStepStatus.FAILED) {
      publish(
          plan,
          FulfillmentStepFailedV1.TYPE,
          new FulfillmentStepFailedV1.Payload(
              plan.id(),
              plan.number(),
              plan.orderId(),
              plan.orderNumber(),
              step.id(),
              step.code(),
              failureCode,
              safeMessage,
              now,
              attempt,
              true),
          now,
          plan.causationId());
    }
    if (planStatus == FulfillmentStatus.COMPLETED) {
      List<FulfillmentCompletedV1.PublicMilestone> publicMilestones =
          store.milestones(tenantId, plan.id()).stream()
              .filter(Milestone::customerVisible)
              .map(
                  item ->
                      new FulfillmentCompletedV1.PublicMilestone(
                          item.code(), item.label(), item.occurredAt()))
              .toList();
      publish(
          plan,
          FulfillmentCompletedV1.TYPE,
          new FulfillmentCompletedV1.Payload(
              plan.id(),
              plan.number(),
              plan.orderId(),
              plan.orderNumber(),
              plan.routeCode(),
              now,
              publicMilestones),
          now,
          plan.causationId());
    }
    return new ActionResult(
        plan.id(), step.id(), stepStatus, planStatus, plan.version() + 1, false);
  }

  private DetailView detail(TenantContext context, Plan plan) {
    boolean planTerminal = terminal(plan.status());
    List<StepView> steps =
        store.steps(context.tenantId(), plan.id()).stream()
            .map(
                step ->
                    new StepView(
                        step.id(),
                        step.code(),
                        step.name(),
                        step.status(),
                        step.dependencies(),
                        step.ownerRole(),
                        step.plannedStartAt(),
                        step.dueAt(),
                        step.startedAt(),
                        step.completedAt(),
                        step.customerVisible(),
                        step.optional(),
                        step.skippable(),
                        step.failureCode(),
                        step.safeMessage(),
                        step.version(),
                        planTerminal ? List.of() : allowed(context, step),
                        store.adapter(context.tenantId(), step.id()).orElse(null)))
            .toList();
    return new DetailView(
        summary(context.tenantId(), plan),
        plan.templateCode(),
        plan.templateVersion(),
        steps,
        store.milestones(context.tenantId(), plan.id()),
        steps.stream().flatMap(step -> step.allowedActions().stream()).distinct().toList());
  }

  private SummaryView summary(TenantId tenantId, Plan plan) {
    List<Step> steps = store.steps(tenantId, plan.id());
    Step current =
        steps.stream().filter(step -> !terminalSuccess(step.status())).findFirst().orElse(null);
    boolean overdue =
        plan.status() != FulfillmentStatus.COMPLETED
            && (plan.dueAt().isBefore(clock.instant())
                || steps.stream().anyMatch(step -> step.status() == FulfillmentStepStatus.OVERDUE));
    return new SummaryView(
        plan.id(),
        plan.number(),
        plan.orderId(),
        plan.orderNumber(),
        plan.routeCode(),
        plan.status(),
        current == null ? null : current.name(),
        plan.dueAt(),
        overdue,
        plan.version());
  }

  private TenantContext readableContext() {
    TenantContext context = contextHolder.requireCurrent();
    authorization.require(PermissionCode.FULFILLMENT_READ, context.tenantId());
    if (context.partnerId() != null) {
      throw new AccessDeniedException(
          "Customer milestones are available through the order timeline");
    }
    return context;
  }

  private TenantContext operatingContext() {
    TenantContext context = contextHolder.requireCurrent();
    authorization.require(PermissionCode.FULFILLMENT_OPERATE, context.tenantId());
    if (context.partnerId() != null)
      throw new AccessDeniedException("Customer cannot operate fulfillment");
    return context;
  }

  private static void requireOwner(TenantContext context, Step step) {
    String roleCode = step.ownerRole().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    if (!context.hasRoleCode(roleCode) && !context.hasRoleCode("tenant-administrator")) {
      throw new AccessDeniedException("The step is owned by another operational role");
    }
  }

  private static List<Action> allowed(TenantContext context, Step step) {
    String roleCode = step.ownerRole().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    if (!context.hasPermission(PermissionCode.FULFILLMENT_OPERATE)
        || (!context.hasRoleCode(roleCode) && !context.hasRoleCode("tenant-administrator")))
      return List.of();
    if (step.status() == FulfillmentStepStatus.READY
        || step.status() == FulfillmentStepStatus.OVERDUE && step.startedAt() == null)
      return List.of(Action.START);
    if (step.status() == FulfillmentStepStatus.IN_PROGRESS
        || step.status() == FulfillmentStepStatus.OVERDUE && step.startedAt() != null)
      return List.of(Action.COMPLETE, Action.FAIL);
    if (step.status() == FulfillmentStepStatus.FAILED) return List.of(Action.RETRY);
    return step.skippable() && !terminal(step.status()) ? List.of(Action.SKIP) : List.of();
  }

  private ActionResult replay(Command command, UUID stepId, Action action, String requestHash) {
    if (!command.stepId().equals(stepId)
        || !command.action().equals(action.name())
        || !command.requestHash().equals(requestHash)) {
      throw new FulfillmentProblem(
          "IDEMPOTENCY_KEY_REUSED", "The idempotency key identifies a different request");
    }
    if (command.resultJson() == null) {
      throw new FulfillmentProblem(
          "FULFILLMENT_ACTION_IN_PROGRESS", "The earlier action is still processing");
    }
    try {
      ActionResult result = json.readValue(command.resultJson(), ActionResult.class);
      return new ActionResult(
          result.planId(),
          result.stepId(),
          result.stepStatus(),
          result.planStatus(),
          result.version(),
          true);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored Fulfillment action result is invalid", exception);
    }
  }

  private void publish(Plan plan, String eventType, Object payload, Instant at, UUID causationId) {
    events.publish(
        new PendingEvent(
            UUID.randomUUID(),
            plan.tenantId().value(),
            eventType,
            1,
            at,
            "fulfillment",
            new PendingEvent.Subject("FULFILLMENT_PLAN", plan.id(), plan.number()),
            plan.correlationId(),
            causationId,
            payload,
            Map.of()));
  }

  private static boolean dependenciesSatisfied(Step step, List<Step> all) {
    Map<String, FulfillmentStepStatus> states =
        all.stream().collect(java.util.stream.Collectors.toMap(Step::code, Step::status));
    return step.dependencies().stream().allMatch(code -> terminalSuccess(states.get(code)));
  }

  private static boolean isReady(Step step) {
    return step.status() == FulfillmentStepStatus.READY
        || step.status() == FulfillmentStepStatus.OVERDUE
            && step.overdueFrom() == FulfillmentStepStatus.READY;
  }

  private static void requireActive(Step step) {
    if (step.status() != FulfillmentStepStatus.IN_PROGRESS
        && !(step.status() == FulfillmentStepStatus.OVERDUE
            && step.overdueFrom() == FulfillmentStepStatus.IN_PROGRESS)) invalidState();
  }

  private static void invalidState() {
    throw new FulfillmentProblem(
        "INVALID_STATE_TRANSITION", "The step action is not valid in its current state");
  }

  private static boolean terminal(FulfillmentStepStatus status) {
    return status == FulfillmentStepStatus.COMPLETED
        || status == FulfillmentStepStatus.CANCELLED
        || status == FulfillmentStepStatus.SKIPPED;
  }

  private static boolean terminal(FulfillmentStatus status) {
    return status == FulfillmentStatus.COMPLETED || status == FulfillmentStatus.CANCELLED;
  }

  private static boolean terminalSuccess(FulfillmentStepStatus status) {
    return status == FulfillmentStepStatus.COMPLETED || status == FulfillmentStepStatus.SKIPPED;
  }

  private static void requireReason(Action action, String reason) {
    if (action == null) throw new FulfillmentProblem("VALIDATION_FAILED", "Action is required");
    if (reason != null && reason.length() > 500)
      throw new FulfillmentProblem("VALIDATION_FAILED", "Reason is too long");
    if (action == Action.FAIL && (reason == null || reason.isBlank())) {
      throw new FulfillmentProblem("VALIDATION_FAILED", "A failure reason is required");
    }
  }

  private static String normalizeFailure(String reason) {
    if (reason != null && reason.matches("[A-Z][A-Z0-9_]{2,79}")) return reason;
    return "FULFILLMENT_STEP_FAILED";
  }

  private static String keyHash(String key) {
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw new FulfillmentProblem(
          "IDEMPOTENCY_KEY_REQUIRED", "A valid Idempotency-Key is required");
    }
    return digest(key);
  }

  private static String normalizeOwnerRole(String ownerRole) {
    if (ownerRole == null || ownerRole.isBlank()) return null;
    String normalized = ownerRole.trim().toUpperCase(java.util.Locale.ROOT);
    if (!normalized.matches("[A-Z][A-Z0-9_]{2,59}")) {
      throw new FulfillmentProblem("VALIDATION_FAILED", "Fulfillment owner role is invalid");
    }
    return normalized;
  }

  private String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize Fulfillment evidence", exception);
    }
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static int decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) return 0;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (!decoded.startsWith("fulfillment:v1:")) throw new IllegalArgumentException();
      int offset = Integer.parseInt(decoded.substring("fulfillment:v1:".length()));
      if (offset < 0) throw new IllegalArgumentException();
      return offset;
    } catch (IllegalArgumentException exception) {
      throw new FulfillmentProblem("VALIDATION_FAILED", "Fulfillment cursor is invalid");
    }
  }

  private static String encodeCursor(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("fulfillment:v1:" + offset).getBytes(StandardCharsets.UTF_8));
  }

  private static Instant max(Instant first, Instant second) {
    return first.isBefore(second) ? second : first;
  }

  private static FulfillmentProblem notFound() {
    return new FulfillmentProblem("RESOURCE_NOT_FOUND", "Fulfillment Plan was not found");
  }

  public enum Action {
    START,
    COMPLETE,
    FAIL,
    RETRY,
    SKIP
  }

  public record PlanCreation(UUID planId, String evidenceHash, boolean replayed) {}

  public record PageView(
      List<SummaryView> items, String nextCursor, boolean hasNext, int pageSize) {
    public PageView {
      items = List.copyOf(items);
    }
  }

  public record SummaryView(
      UUID id,
      String number,
      UUID orderId,
      String orderNumber,
      String routeCode,
      FulfillmentStatus status,
      String currentStep,
      Instant dueAt,
      boolean overdue,
      long version) {}

  public record DetailView(
      SummaryView summary,
      String templateCode,
      String templateVersion,
      List<StepView> steps,
      List<Milestone> milestones,
      List<Action> allowedActions) {
    public DetailView {
      steps = List.copyOf(steps);
      milestones = List.copyOf(milestones);
      allowedActions = List.copyOf(allowedActions);
    }
  }

  public record StepView(
      UUID id,
      String code,
      String name,
      FulfillmentStepStatus status,
      List<String> dependencies,
      String ownerRole,
      Instant plannedStartAt,
      Instant dueAt,
      Instant startedAt,
      Instant completedAt,
      boolean customerVisible,
      boolean optional,
      boolean skippable,
      String failureCode,
      String safeMessage,
      long version,
      List<Action> allowedActions,
      AdapterAttempt latestAdapterAttempt) {
    public StepView {
      dependencies = List.copyOf(dependencies);
      allowedActions = List.copyOf(allowedActions);
    }
  }

  public record ActionResult(
      UUID planId,
      UUID stepId,
      FulfillmentStepStatus stepStatus,
      FulfillmentStatus planStatus,
      long version,
      boolean replayed) {}
}
