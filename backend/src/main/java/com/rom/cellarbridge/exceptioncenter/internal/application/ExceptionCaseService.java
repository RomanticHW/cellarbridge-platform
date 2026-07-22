package com.rom.cellarbridge.exceptioncenter.internal.application;

import com.rom.cellarbridge.exceptioncenter.ExceptionCategory;
import com.rom.cellarbridge.exceptioncenter.ExceptionClosedV1;
import com.rom.cellarbridge.exceptioncenter.ExceptionOpenedV1;
import com.rom.cellarbridge.exceptioncenter.ExceptionSeverity;
import com.rom.cellarbridge.exceptioncenter.ExceptionStatus;
import com.rom.cellarbridge.exceptioncenter.RecoveryAction;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.CaseRecord;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.Detection;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.History;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.Occurrence;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.OpenResult;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.RecoveryClaim;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.RecoveryOutcome;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseStore.RecoveryView;
import com.rom.cellarbridge.fulfillment.FulfillmentRecoveryOperations;
import com.rom.cellarbridge.identityaccess.AuthorizationService;
import com.rom.cellarbridge.identityaccess.PermissionCode;
import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.InventoryRecoveryOperations;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.EventPublicationOperations;
import com.rom.cellarbridge.platform.PendingEvent;
import com.rom.cellarbridge.platform.ReliableEventPublisher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExceptionCaseService {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{20,200}");
  private static final int MAX_RECOVERY_ATTEMPTS = 3;
  private static final Set<String> CLOSURE_REASONS =
      Set.of("RECOVERY_VERIFIED", "FALSE_POSITIVE", "DUPLICATE");
  private final TenantContextHolder contextHolder;
  private final AuthorizationService authorization;
  private final ExceptionCaseStore store;
  private final FulfillmentRecoveryOperations fulfillment;
  private final InventoryRecoveryOperations inventory;
  private final EventPublicationOperations publications;
  private final ReliableEventPublisher events;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate transactions;

  ExceptionCaseService(
      TenantContextHolder contextHolder,
      AuthorizationService authorization,
      ExceptionCaseStore store,
      FulfillmentRecoveryOperations fulfillment,
      InventoryRecoveryOperations inventory,
      EventPublicationOperations publications,
      ReliableEventPublisher events,
      JsonMapper json,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.contextHolder = contextHolder;
    this.authorization = authorization;
    this.store = store;
    this.fulfillment = fulfillment;
    this.inventory = inventory;
    this.publications = publications;
    this.events = events;
    this.json = json;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Transactional
  EventHandlingResult detect(Detection detection) {
    Instant now = max(clock.instant(), detection.detectedAt());
    OpenResult result = store.open(detection, UUID.randomUUID(), store.nextNumber(now), now);
    if (result.created()) publishOpened(result.exceptionCase(), detection.sourceEventId(), now);
    String reference = result.exceptionCase().id().toString();
    return new EventHandlingResult(reference, digest(reference + "|" + detection.dedupKey()));
  }

  @Transactional(readOnly = true)
  public PageView list(
      Set<ExceptionStatus> statuses,
      ExceptionSeverity severity,
      UUID assigneeId,
      String sourceType,
      Boolean overdue,
      Integer requestedSize,
      String cursor) {
    TenantContext context = readableContext();
    int pageSize = requestedSize == null ? 25 : Math.max(1, Math.min(requestedSize, 100));
    int offset = decodeCursor(cursor);
    boolean technicalOnly = context.hasRoleCode("system-operator");
    List<CaseRecord> rows =
        store.list(
            context.tenantId(),
            statuses,
            severity,
            assigneeId,
            normalizeSourceType(sourceType),
            overdue,
            technicalOnly,
            offset,
            pageSize + 1);
    boolean hasNext = rows.size() > pageSize;
    if (hasNext) rows = rows.subList(0, pageSize);
    return new PageView(
        rows.stream().map(ExceptionCaseService::summary).toList(),
        hasNext ? encodeCursor(offset + pageSize) : null,
        hasNext,
        pageSize);
  }

  @Transactional(readOnly = true)
  public DetailView get(UUID caseId) {
    TenantContext context = readableContext();
    CaseRecord value = find(context.tenantId(), caseId, false);
    requireTechnicalScope(context, value);
    return detail(value);
  }

  @Transactional
  public DetailView assign(UUID caseId, long expectedVersion, UUID assigneeId, String reason) {
    TenantContext context = actionContext(PermissionCode.EXCEPTION_ASSIGN);
    requireReason(reason);
    if (assigneeId == null) throw validation("Assignee id is required");
    CaseRecord current = versioned(context, caseId, expectedVersion);
    requireState(current, Set.of(ExceptionStatus.OPEN, ExceptionStatus.ASSIGNED));
    Instant now = clock.instant();
    store.update(
        current,
        ExceptionStatus.ASSIGNED,
        current.severity(),
        assigneeId,
        current.primaryCaseId(),
        null,
        null,
        now);
    store.appendHistory(
        current,
        "ASSIGN",
        "INTERNAL_USER",
        context.userId(),
        current.status(),
        ExceptionStatus.ASSIGNED,
        "ASSIGNED",
        reason,
        current.correlationId(),
        now);
    return detail(find(context.tenantId(), caseId, false));
  }

  @Transactional
  public DetailView acknowledge(UUID caseId, long expectedVersion, String reason) {
    TenantContext context = actionContext(PermissionCode.EXCEPTION_RECOVER);
    requireReason(reason);
    CaseRecord current = versioned(context, caseId, expectedVersion);
    requireState(current, Set.of(ExceptionStatus.OPEN, ExceptionStatus.ASSIGNED));
    return transition(context, current, ExceptionStatus.ACKNOWLEDGED, "ACKNOWLEDGE", reason);
  }

  @Transactional
  public DetailView begin(UUID caseId, long expectedVersion, String reason) {
    TenantContext context = actionContext(PermissionCode.EXCEPTION_RECOVER);
    requireReason(reason);
    CaseRecord current = versioned(context, caseId, expectedVersion);
    requireState(current, Set.of(ExceptionStatus.ACKNOWLEDGED));
    return transition(context, current, ExceptionStatus.IN_PROGRESS, "BEGIN_INVESTIGATION", reason);
  }

  public RecoveryResult recover(
      UUID caseId,
      long expectedVersion,
      String idempotencyKey,
      RecoveryAction action,
      String reason,
      Map<String, Object> parameters) {
    if (action == null) throw validation("Recovery action is required");
    TenantContext context = actionContext(PermissionCode.EXCEPTION_RECOVER);
    requireReason(reason);
    String keyHash = keyHash(idempotencyKey);
    String inputSummary = safeInput(action, parameters);
    String requestHash =
        digest(
            context.tenantId().value()
                + "|"
                + context.userId()
                + "|"
                + caseId
                + "|"
                + action
                + "|"
                + reason
                + "|"
                + inputSummary);
    PreparedRecovery prepared =
        transactions.execute(
            ignored ->
                prepare(
                    context,
                    caseId,
                    expectedVersion,
                    keyHash,
                    requestHash,
                    action,
                    inputSummary,
                    reason));
    if (prepared == null)
      throw new IllegalStateException("Recovery preparation returned no result");
    if (prepared.outcome() != null) {
      return recoveryResult(prepared.exceptionCase(), prepared.claim(), prepared.outcome(), true);
    }
    Execution execution;
    try {
      execution =
          transactions.execute(
              ignored -> execute(context, prepared.exceptionCase(), prepared.claim(), parameters));
      if (execution == null)
        throw new IllegalStateException("Recovery execution returned no result");
    } catch (RuntimeException failure) {
      execution = new Execution(false, recoveryCode(failure), safeFailure(failure), null, false);
    }
    Execution finalExecution = execution;
    return Objects.requireNonNull(
        transactions.execute(ignored -> complete(context, prepared, finalExecution)));
  }

  @Transactional
  public DetailView close(
      UUID caseId, long expectedVersion, String reasonCode, String reason, UUID primaryCaseId) {
    TenantContext context = actionContext(PermissionCode.EXCEPTION_RECOVER);
    requireReason(reason);
    CaseRecord current = versioned(context, caseId, expectedVersion);
    if (!CLOSURE_REASONS.contains(reasonCode)) {
      throw validation("Closure reason code is invalid");
    }
    boolean recoveryVerified = "RECOVERY_VERIFIED".equals(reasonCode);
    boolean quickClosure = Set.of("FALSE_POSITIVE", "DUPLICATE").contains(reasonCode);
    if (recoveryVerified && current.status() != ExceptionStatus.RESOLVED
        || quickClosure && current.status() != ExceptionStatus.OPEN) {
      throw new ExceptionProblem(
          "EXCEPTION_SOURCE_NOT_RECOVERED", "Source recovery must be verified before closure");
    }
    if ("DUPLICATE".equals(reasonCode)) {
      if (primaryCaseId == null) throw validation("Duplicate closure requires the primary case id");
      if (current.id().equals(primaryCaseId)) {
        throw validation("Duplicate closure cannot reference the same case");
      }
      CaseRecord primary = find(context.tenantId(), primaryCaseId, false);
      requireTechnicalScope(context, primary);
    } else if (primaryCaseId != null) {
      throw validation("Only duplicate closure may reference a primary case");
    }
    Instant now = clock.instant();
    Instant resolvedAt = current.resolvedAt() == null ? now : current.resolvedAt();
    store.update(
        current,
        ExceptionStatus.CLOSED,
        current.severity(),
        current.assigneeId(),
        primaryCaseId,
        resolvedAt,
        now,
        now);
    store.appendHistory(
        current,
        "CLOSE",
        "INTERNAL_USER",
        context.userId(),
        current.status(),
        ExceptionStatus.CLOSED,
        reasonCode,
        reason,
        current.correlationId(),
        now);
    store.completeWorkItem(context.tenantId(), current.id(), now);
    events.publish(
        new PendingEvent(
            UUID.randomUUID(),
            context.tenantId().value(),
            ExceptionClosedV1.TYPE,
            1,
            now,
            "exception-center",
            new PendingEvent.Subject("EXCEPTION_CASE", current.id(), current.number()),
            current.correlationId(),
            context.userId(),
            new ExceptionClosedV1.Payload(
                current.id(), current.number(), reasonCode, reason, context.userId(), now),
            Map.of()));
    return detail(find(context.tenantId(), caseId, false));
  }

  @Transactional(readOnly = true)
  public FailedDeliveryPage failedDeliveries(Integer requestedSize, String cursor) {
    TenantContext context = actionContext(PermissionCode.EVENT_PUBLICATION_READ);
    int pageSize = requestedSize == null ? 25 : Math.max(1, Math.min(requestedSize, 100));
    int offset = decodeCursor(cursor);
    List<EventPublicationOperations.FailedDelivery> rows =
        publications.listFailed(context.tenantId().value(), offset, pageSize + 1);
    boolean hasNext = rows.size() > pageSize;
    if (hasNext) rows = rows.subList(0, pageSize);
    return new FailedDeliveryPage(
        rows.stream().map(ExceptionCaseService::failedDelivery).toList(),
        hasNext ? encodeCursor(offset + pageSize) : null,
        hasNext,
        pageSize);
  }

  private PreparedRecovery prepare(
      TenantContext context,
      UUID caseId,
      long expectedVersion,
      String keyHash,
      String requestHash,
      RecoveryAction action,
      String inputSummary,
      String reason) {
    CaseRecord current = find(context.tenantId(), caseId, true);
    requireTechnicalScope(context, current);
    RecoveryClaim claim =
        store.claimRecovery(
            current,
            UUID.randomUUID(),
            action,
            context.userId(),
            keyHash,
            requestHash,
            inputSummary,
            clock.instant());
    if (!claim.action().equals(action)
        || !claim.requesterId().equals(context.userId())
        || !claim.requestHash().equals(requestHash)) {
      throw new ExceptionProblem(
          "IDEMPOTENCY_KEY_REUSED", "The idempotency key identifies another recovery request");
    }
    RecoveryOutcome outcome = store.recoveryOutcome(context.tenantId(), claim.id()).orElse(null);
    if (outcome != null) return new PreparedRecovery(current, claim, outcome);
    if (!claim.created()
        && claim.requestedAt().plus(1, ChronoUnit.MINUTES).isAfter(clock.instant())) {
      throw new ExceptionProblem(
          "EXCEPTION_RECOVERY_IN_PROGRESS", "The earlier recovery request is still processing");
    }
    if (claim.created() && current.version() != expectedVersion) {
      throw new ExceptionProblem(
          "RESOURCE_VERSION_CONFLICT",
          "The exception changed; refresh before retrying",
          current.version());
    }
    requireState(current, Set.of(ExceptionStatus.IN_PROGRESS, ExceptionStatus.RECOVERY_PENDING));
    requireAllowed(current, action, context);
    if (claim.created()) {
      int attempts = store.recoveryAttempts(context.tenantId(), caseId, action);
      if (attempts > MAX_RECOVERY_ATTEMPTS) {
        throw new ExceptionProblem(
            "EXCEPTION_RECOVERY_NOT_ALLOWED", "The recovery attempt limit has been reached");
      }
      Instant now = clock.instant();
      store.update(
          current,
          ExceptionStatus.RECOVERY_PENDING,
          current.severity(),
          current.assigneeId(),
          current.primaryCaseId(),
          null,
          null,
          now);
      store.appendHistory(
          current,
          "REQUEST_RECOVERY",
          "INTERNAL_USER",
          context.userId(),
          current.status(),
          ExceptionStatus.RECOVERY_PENDING,
          action.name(),
          reason,
          current.correlationId(),
          now);
      current = find(context.tenantId(), caseId, false);
    }
    return new PreparedRecovery(current, claim, null);
  }

  private Execution execute(
      TenantContext context,
      CaseRecord exceptionCase,
      RecoveryClaim claim,
      Map<String, Object> parameters) {
    return switch (claim.action()) {
      case RETRY_FULFILLMENT_STEP -> {
        Map<String, Object> details = object(exceptionCase.safeDetails());
        UUID planId = uuid(details.get("planId"), "planId");
        UUID stepId = uuid(details.get("stepId"), "stepId");
        FulfillmentRecoveryOperations.RecoveryResult result =
            fulfillment.retryFailedStep(
                planId,
                stepId,
                "exception-recovery-" + claim.id(),
                "Exception " + exceptionCase.number() + " approved retry");
        boolean verified =
            Set.of("READY", "BLOCKED").contains(result.stepStatus().name())
                && result.planStatus().name().equals("IN_PROGRESS");
        yield new Execution(
            verified,
            verified ? "SOURCE_STATE_VERIFIED" : "SOURCE_STATE_NOT_VERIFIED",
            verified
                ? "Fulfillment accepted the retry and returned a recoverable step state."
                : "Fulfillment did not return the expected recoverable state.",
            result.planStatus() + "/" + result.stepStatus(),
            verified);
      }
      case REPLAY_PUBLICATION -> {
        authorization.require(PermissionCode.EVENT_PUBLICATION_REPLAY, context.tenantId());
        Map<String, Object> details = object(exceptionCase.safeDetails());
        UUID eventId = uuid(details.get("eventId"), "eventId");
        String consumerName = text(details.get("consumerName"), "consumerName");
        long sourceVersion = number(details.get("deliveryVersion"), "deliveryVersion");
        EventPublicationOperations.ReplayResult result =
            publications.replay(
                context.tenantId().value(), eventId, consumerName, sourceVersion, clock.instant());
        yield new Execution(
            true,
            "REPLAY_SCHEDULED",
            "The immutable event delivery was scheduled for bounded replay.",
            result.status(),
            true);
      }
      case RETRY_RESERVATION -> {
        Map<String, Object> details = object(exceptionCase.safeDetails());
        UUID reservationId = uuid(details.get("reservationId"), "reservationId");
        String orderNumber = text(details.get("orderNumber"), "orderNumber");
        InventoryRecoveryOperations.RecoveryResult result =
            inventory.retryReservation(
                reservationId, orderNumber, exceptionCase.correlationId(), claim.id());
        boolean verified = result.status() != InventoryRecoveryOperations.Status.FAILED;
        yield new Execution(
            verified,
            verified ? "SOURCE_STATE_VERIFIED" : "INVENTORY_RESERVATION_RETRY_FAILED",
            verified
                ? "Inventory confirmed the Reservation after a bounded retry."
                : "Inventory remained unavailable after the Reservation retry.",
            result.status() + (result.failureCode() == null ? "" : "/" + result.failureCode()),
            verified);
      }
      case RESUME_FULFILLMENT_PLAN -> {
        Map<String, Object> details = object(exceptionCase.safeDetails());
        UUID planId = uuid(details.get("planId"), "planId");
        UUID stepId = uuid(details.get("stepId"), "stepId");
        FulfillmentRecoveryOperations.RecoveryResult result =
            fulfillment.resumeOverdueStep(
                planId,
                stepId,
                "exception-recovery-" + claim.id(),
                "Exception " + exceptionCase.number() + " approved resume");
        boolean verified =
            Set.of("READY", "IN_PROGRESS").contains(result.stepStatus().name())
                && result.planStatus().name().equals("IN_PROGRESS");
        yield new Execution(
            verified,
            verified ? "SOURCE_STATE_VERIFIED" : "SOURCE_STATE_NOT_VERIFIED",
            verified
                ? "Fulfillment cleared the overdue marker and returned an active step state."
                : "Fulfillment did not return the expected active state.",
            result.planStatus() + "/" + result.stepStatus(),
            verified);
      }
      case MANUAL_ACKNOWLEDGE ->
          new Execution(
              true,
              "MANUAL_EVIDENCE_RECORDED",
              "Manual evidence was recorded; source recovery is still required.",
              "ACKNOWLEDGED_ONLY",
              false);
    };
  }

  private RecoveryResult complete(
      TenantContext context, PreparedRecovery prepared, Execution execution) {
    CaseRecord current = find(context.tenantId(), prepared.exceptionCase().id(), true);
    Instant now = clock.instant();
    boolean outcomeCreated =
        store.appendRecoveryOutcome(
            context.tenantId(),
            prepared.claim().id(),
            execution.succeeded() ? "SUCCEEDED" : "FAILED",
            execution.code(),
            execution.safeResult(),
            execution.sourceState(),
            now);
    if (!outcomeCreated) {
      RecoveryOutcome existing =
          store.recoveryOutcome(context.tenantId(), prepared.claim().id()).orElseThrow();
      return recoveryResult(current, prepared.claim(), existing, true);
    }
    int attempts =
        store.recoveryAttempts(context.tenantId(), current.id(), prepared.claim().action());
    ExceptionSeverity severity =
        !execution.succeeded() && attempts >= MAX_RECOVERY_ATTEMPTS
            ? current.severity().escalate()
            : current.severity();
    if (!execution.succeeded() && attempts == MAX_RECOVERY_ATTEMPTS) {
      store.notifyRecoveryThreshold(
          context.tenantId(), current.id(), prepared.claim().action(), now);
    }
    ExceptionStatus next =
        execution.sourceVerified() ? ExceptionStatus.RESOLVED : ExceptionStatus.IN_PROGRESS;
    store.update(
        current,
        next,
        severity,
        current.assigneeId(),
        current.primaryCaseId(),
        next == ExceptionStatus.RESOLVED ? now : null,
        null,
        now);
    store.appendHistory(
        current,
        execution.succeeded() ? "RECOVERY_SUCCEEDED" : "RECOVERY_FAILED",
        "INTERNAL_USER",
        context.userId(),
        current.status(),
        next,
        execution.code(),
        execution.safeResult(),
        current.correlationId(),
        now);
    CaseRecord updated = find(context.tenantId(), current.id(), false);
    RecoveryOutcome outcome =
        store.recoveryOutcome(context.tenantId(), prepared.claim().id()).orElseThrow();
    return recoveryResult(updated, prepared.claim(), outcome, false);
  }

  private RecoveryResult recoveryResult(
      CaseRecord exceptionCase, RecoveryClaim claim, RecoveryOutcome outcome, boolean replayed) {
    return new RecoveryResult(
        exceptionCase.id(),
        claim.id(),
        outcome.status(),
        outcome.resultCode(),
        outcome.safeResult(),
        outcome.sourceState(),
        exceptionCase.status(),
        exceptionCase.version(),
        outcome.completedAt(),
        replayed);
  }

  private DetailView transition(
      TenantContext context,
      CaseRecord current,
      ExceptionStatus next,
      String action,
      String reason) {
    Instant now = clock.instant();
    store.update(
        current,
        next,
        current.severity(),
        current.assigneeId(),
        current.primaryCaseId(),
        null,
        null,
        now);
    store.appendHistory(
        current,
        action,
        "INTERNAL_USER",
        context.userId(),
        current.status(),
        next,
        action,
        reason,
        current.correlationId(),
        now);
    return detail(find(context.tenantId(), current.id(), false));
  }

  private DetailView detail(CaseRecord value) {
    TenantContext context = contextHolder.requireCurrent();
    return new DetailView(
        summary(value),
        object(value.safeDetails()),
        store.occurrences(value.tenantId(), value.id()).stream().map(this::occurrence).toList(),
        store.history(value.tenantId(), value.id()).stream()
            .map(ExceptionCaseService::history)
            .toList(),
        store.recoveries(value.tenantId(), value.id()).stream().map(this::recovery).toList(),
        allowedRecoveries(value, context),
        allowedActions(value, context));
  }

  private EvidenceOccurrenceView occurrence(Occurrence value) {
    return new EvidenceOccurrenceView(
        value.sourceEventId(), value.eventType(), value.detectedAt(), object(value.evidence()));
  }

  private static CaseHistoryView history(History value) {
    return new CaseHistoryView(
        value.action(),
        value.actorType(),
        value.actorId(),
        value.previousStatus(),
        value.newStatus(),
        value.reasonCode(),
        value.safeReason(),
        value.correlationId(),
        value.occurredAt());
  }

  private RecoveryAttemptView recovery(RecoveryView value) {
    RecoveryOutcome outcome = value.outcome();
    return new RecoveryAttemptView(
        value.id(),
        value.action(),
        value.requesterId(),
        object(value.inputSummary()),
        value.requestedAt(),
        outcome == null
            ? null
            : new RecoveryOutcomeView(
                outcome.status(),
                outcome.resultCode(),
                outcome.safeResult(),
                outcome.sourceState(),
                outcome.completedAt()));
  }

  private void publishOpened(CaseRecord value, UUID sourceEventId, Instant at) {
    events.publish(
        new PendingEvent(
            UUID.randomUUID(),
            value.tenantId().value(),
            ExceptionOpenedV1.TYPE,
            1,
            at,
            "exception-center",
            new PendingEvent.Subject("EXCEPTION_CASE", value.id(), value.number()),
            value.correlationId(),
            sourceEventId,
            new ExceptionOpenedV1.Payload(
                value.id(),
                value.number(),
                value.category().name(),
                value.severity(),
                value.sourceType(),
                value.sourceId(),
                value.sourceNumber(),
                value.summary(),
                value.openedAt(),
                value.dueAt()),
            Map.of()));
  }

  private TenantContext readableContext() {
    TenantContext context = contextHolder.requireCurrent();
    authorization.require(PermissionCode.EXCEPTION_READ, context.tenantId());
    if (context.partnerId() != null)
      throw new AccessDeniedException("Customer cannot read exceptions");
    return context;
  }

  private TenantContext actionContext(PermissionCode permission) {
    TenantContext context = contextHolder.requireCurrent();
    authorization.require(permission, context.tenantId());
    if (context.partnerId() != null)
      throw new AccessDeniedException("Customer cannot operate exceptions");
    return context;
  }

  private static void requireTechnicalScope(TenantContext context, CaseRecord value) {
    if (context.hasRoleCode("system-operator")
        && value.category() != ExceptionCategory.EVENT_DELIVERY_FAILED) {
      throw new ExceptionProblem("RESOURCE_NOT_FOUND", "Exception case not found");
    }
  }

  private CaseRecord versioned(TenantContext context, UUID caseId, long expectedVersion) {
    CaseRecord value = find(context.tenantId(), caseId, true);
    requireTechnicalScope(context, value);
    if (value.version() != expectedVersion) {
      throw new ExceptionProblem(
          "RESOURCE_VERSION_CONFLICT",
          "The exception changed; refresh before retrying",
          value.version());
    }
    return value;
  }

  private CaseRecord find(TenantId tenantId, UUID caseId, boolean forUpdate) {
    return store
        .find(tenantId, Objects.requireNonNull(caseId, "caseId"), forUpdate)
        .orElseThrow(() -> new ExceptionProblem("RESOURCE_NOT_FOUND", "Exception case not found"));
  }

  private static void requireState(CaseRecord value, Set<ExceptionStatus> allowed) {
    if (!allowed.contains(value.status())) {
      throw new ExceptionProblem(
          "INVALID_STATE_TRANSITION", "The exception action is not valid in its current state");
    }
  }

  private void requireAllowed(CaseRecord value, RecoveryAction action, TenantContext context) {
    if (!allowedRecoveries(value, context).contains(action)) {
      throw new ExceptionProblem(
          "EXCEPTION_RECOVERY_NOT_ALLOWED", "The selected recovery is not allowed for this case");
    }
    if (action == RecoveryAction.REPLAY_PUBLICATION
        && !context.hasPermission(PermissionCode.EVENT_PUBLICATION_REPLAY)) {
      throw new AccessDeniedException("Event replay permission is required");
    }
  }

  private List<RecoveryAction> allowedRecoveries(CaseRecord value, TenantContext context) {
    if (value.status() != ExceptionStatus.IN_PROGRESS
            && value.status() != ExceptionStatus.RECOVERY_PENDING
        || !context.hasPermission(PermissionCode.EXCEPTION_RECOVER)) return List.of();
    return switch (value.category()) {
      case FULFILLMENT_STEP_FAILED ->
          List.of(RecoveryAction.RETRY_FULFILLMENT_STEP, RecoveryAction.MANUAL_ACKNOWLEDGE);
      case FULFILLMENT_STEP_OVERDUE ->
          List.of(RecoveryAction.RESUME_FULFILLMENT_PLAN, RecoveryAction.MANUAL_ACKNOWLEDGE);
      case INVENTORY_SHORTAGE ->
          Boolean.TRUE.equals(object(value.safeDetails()).get("retryable"))
              ? List.of(RecoveryAction.RETRY_RESERVATION, RecoveryAction.MANUAL_ACKNOWLEDGE)
              : List.of(RecoveryAction.MANUAL_ACKNOWLEDGE);
      case EVENT_DELIVERY_FAILED ->
          context.hasPermission(PermissionCode.EVENT_PUBLICATION_REPLAY)
              ? List.of(RecoveryAction.REPLAY_PUBLICATION)
              : List.of();
    };
  }

  private static List<String> allowedActions(CaseRecord value, TenantContext context) {
    List<String> actions =
        switch (value.status()) {
          case OPEN -> List.of("ASSIGN", "ACKNOWLEDGE", "CLOSE_FALSE_POSITIVE", "CLOSE_DUPLICATE");
          case ASSIGNED -> List.of("ASSIGN", "ACKNOWLEDGE");
          case ACKNOWLEDGED -> List.of("BEGIN_INVESTIGATION");
          case IN_PROGRESS, RECOVERY_PENDING -> List.of("REQUEST_RECOVERY");
          case RESOLVED -> List.of("CLOSE");
          case CLOSED -> List.of();
        };
    return actions.stream()
        .filter(
            action ->
                "ASSIGN".equals(action)
                    ? context.hasPermission(PermissionCode.EXCEPTION_ASSIGN)
                    : context.hasPermission(PermissionCode.EXCEPTION_RECOVER))
        .toList();
  }

  private static SummaryView summary(CaseRecord value) {
    return new SummaryView(
        value.id(),
        value.number(),
        value.category(),
        value.severity(),
        value.status(),
        value.sourceType(),
        value.sourceId(),
        value.sourceNumber(),
        value.summary(),
        value.assigneeId(),
        value.primaryCaseId(),
        value.openedAt(),
        value.dueAt(),
        value.version());
  }

  private static FailedDeliveryView failedDelivery(
      EventPublicationOperations.FailedDelivery value) {
    return new FailedDeliveryView(
        value.eventId(),
        value.eventType(),
        value.consumerName(),
        value.status(),
        value.attempts(),
        value.nextRetryAt(),
        value.errorCode(),
        value.lastAttemptAt(),
        value.version());
  }

  private String safeInput(RecoveryAction action, Map<String, Object> parameters) {
    return json(Map.of("action", action.name()));
  }

  private Map<String, Object> object(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored exception evidence is invalid", exception);
    }
  }

  private String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize exception evidence", exception);
    }
  }

  private static String keyHash(String key) {
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw new ExceptionProblem("IDEMPOTENCY_KEY_REQUIRED", "A valid Idempotency-Key is required");
    }
    return digest(key);
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

  private static long number(Object raw, String name) {
    if (!(raw instanceof Number value)) {
      throw validation(name + " is required");
    }
    return value.longValue();
  }

  private static UUID uuid(Object value, String name) {
    try {
      return UUID.fromString(text(value, name));
    } catch (IllegalArgumentException exception) {
      throw validation(name + " is invalid");
    }
  }

  private static String text(Object value, String name) {
    if (!(value instanceof String text) || text.isBlank()) throw validation(name + " is required");
    return text;
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.trim().length() < 5 || reason.length() > 500) {
      throw validation("A reason between 5 and 500 characters is required");
    }
  }

  private static String normalizeSourceType(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!normalized.matches("[A-Z][A-Z0-9_]{1,79}")) throw validation("Source type is invalid");
    return normalized;
  }

  private static int decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) return 0;
    try {
      String value =
          new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (!value.startsWith("exception-offset:")) throw new IllegalArgumentException();
      int offset = Integer.parseInt(value.substring("exception-offset:".length()));
      if (offset < 0) throw new IllegalArgumentException();
      return offset;
    } catch (IllegalArgumentException exception) {
      throw validation("Cursor is invalid");
    }
  }

  private static String encodeCursor(int offset) {
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("exception-offset:" + offset).getBytes(StandardCharsets.UTF_8));
  }

  private static ExceptionProblem validation(String message) {
    return new ExceptionProblem("VALIDATION_FAILED", message);
  }

  private static String recoveryCode(RuntimeException failure) {
    if (failure instanceof ExceptionProblem problem) return problem.code();
    return "RECOVERY_EXECUTION_FAILED";
  }

  private static String safeFailure(RuntimeException failure) {
    if (failure instanceof ExceptionProblem) return failure.getMessage();
    return "The source recovery did not complete; the case remains actionable.";
  }

  private static Instant max(Instant first, Instant second) {
    return first.isAfter(second) ? first : second;
  }

  private record PreparedRecovery(
      CaseRecord exceptionCase, RecoveryClaim claim, RecoveryOutcome outcome) {}

  private record Execution(
      boolean succeeded,
      String code,
      String safeResult,
      String sourceState,
      boolean sourceVerified) {}

  public record SummaryView(
      UUID id,
      String number,
      ExceptionCategory category,
      ExceptionSeverity severity,
      ExceptionStatus status,
      String sourceType,
      UUID sourceId,
      String sourceNumber,
      String summary,
      UUID assigneeId,
      UUID primaryCaseId,
      Instant openedAt,
      Instant dueAt,
      long version) {}

  public record PageView(
      List<SummaryView> items, String nextCursor, boolean hasNext, int pageSize) {}

  public record DetailView(
      SummaryView summary,
      Map<String, Object> safeDetails,
      List<EvidenceOccurrenceView> occurrences,
      List<CaseHistoryView> history,
      List<RecoveryAttemptView> recoveries,
      List<RecoveryAction> allowedRecoveryActions,
      List<String> allowedActions) {}

  public record EvidenceOccurrenceView(
      UUID sourceEventId, String eventType, Instant detectedAt, Map<String, Object> evidence) {}

  public record CaseHistoryView(
      String action,
      String actorType,
      UUID actorId,
      ExceptionStatus previousStatus,
      ExceptionStatus newStatus,
      String reasonCode,
      String safeReason,
      UUID correlationId,
      Instant occurredAt) {}

  public record RecoveryAttemptView(
      UUID id,
      RecoveryAction action,
      UUID requesterId,
      Map<String, Object> inputSummary,
      Instant requestedAt,
      RecoveryOutcomeView outcome) {}

  public record RecoveryOutcomeView(
      String status,
      String resultCode,
      String safeResult,
      String sourceState,
      Instant completedAt) {}

  public record RecoveryResult(
      UUID exceptionId,
      UUID attemptId,
      String status,
      String resultCode,
      String safeResult,
      String sourceState,
      ExceptionStatus caseStatus,
      long version,
      Instant completedAt,
      boolean replayed) {}

  public record FailedDeliveryPage(
      List<FailedDeliveryView> items, String nextCursor, boolean hasNext, int pageSize) {}

  public record FailedDeliveryView(
      UUID eventId,
      String eventType,
      String consumerName,
      String status,
      int attempts,
      Instant nextRetryAt,
      String errorCode,
      Instant lastAttemptAt,
      long version) {}
}
