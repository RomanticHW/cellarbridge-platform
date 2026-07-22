package com.rom.cellarbridge.fulfillment.internal.web;

import com.rom.cellarbridge.fulfillment.FulfillmentStatus;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStatus;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.Action;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.ActionResult;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.DetailView;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.PageView;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.StepView;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanService.SummaryView;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.AdapterAttempt;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore.Milestone;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentProblem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fulfillment/plans")
final class FulfillmentPlanController {
  private final FulfillmentPlanService service;

  FulfillmentPlanController(FulfillmentPlanService service) {
    this.service = service;
  }

  @GetMapping
  PageResponse list(
      @RequestParam(required = false) Set<FulfillmentStatus> status,
      @RequestParam(required = false) Boolean overdue,
      @RequestParam(required = false) String ownerRole,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    PageView page = service.list(status, overdue, ownerRole, orderId, pageSize, cursor);
    return new PageResponse(
        page.items().stream().map(FulfillmentPlanController::summary).toList(),
        new PageInfo(page.nextCursor(), page.hasNext(), page.pageSize()));
  }

  @GetMapping("/{planId}")
  ResponseEntity<DetailResponse> get(@PathVariable UUID planId) {
    DetailResponse response = detail(service.get(planId));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag("\"" + response.version() + "\"")
        .body(response);
  }

  @PostMapping("/{planId}/steps/{stepId}/actions")
  ResponseEntity<ActionResult> act(
      @PathVariable UUID planId,
      @PathVariable UUID stepId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ActionRequest request) {
    if (request == null || request.action() == null) {
      throw new FulfillmentProblem("VALIDATION_FAILED", "Action is required");
    }
    ActionResult result =
        service.act(
            planId,
            stepId,
            expectedVersion(ifMatch),
            idempotencyKey,
            request.action(),
            request.reason(),
            scenario(request.resultData()));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .eTag("\"" + result.version() + "\"")
        .body(result);
  }

  private static long expectedVersion(String value) {
    if (value == null || value.isBlank()) {
      throw new FulfillmentProblem("PRECONDITION_REQUIRED", "If-Match is required");
    }
    if (!value.matches("\"[0-9]+\"")) {
      throw new FulfillmentProblem("VALIDATION_FAILED", "If-Match must be a quoted version");
    }
    try {
      return Long.parseLong(value.substring(1, value.length() - 1));
    } catch (NumberFormatException exception) {
      throw new FulfillmentProblem("VALIDATION_FAILED", "If-Match version is invalid");
    }
  }

  private static String scenario(Map<String, Object> resultData) {
    if (resultData == null || !resultData.containsKey("scenario")) return null;
    Object value = resultData.get("scenario");
    if (!(value instanceof String text)) {
      throw new FulfillmentProblem("VALIDATION_FAILED", "Simulation scenario must be text");
    }
    return text;
  }

  private static DetailResponse detail(DetailView view) {
    SummaryView summary = view.summary();
    return new DetailResponse(
        summary.id(),
        summary.number(),
        summary.orderId(),
        summary.orderNumber(),
        summary.routeCode(),
        summary.status(),
        summary.currentStep(),
        summary.dueAt(),
        summary.overdue(),
        summary.version(),
        view.templateCode(),
        view.templateVersion(),
        view.steps().stream().map(FulfillmentPlanController::step).toList(),
        view.milestones().stream().map(FulfillmentPlanController::milestone).toList(),
        view.allowedActions());
  }

  private static SummaryResponse summary(SummaryView value) {
    return new SummaryResponse(
        value.id(),
        value.number(),
        value.orderId(),
        value.orderNumber(),
        value.routeCode(),
        value.status(),
        value.currentStep(),
        value.dueAt(),
        value.overdue(),
        value.version());
  }

  private static StepResponse step(StepView value) {
    return new StepResponse(
        value.id(),
        value.code(),
        value.name(),
        value.status(),
        value.dependencies(),
        value.ownerRole(),
        null,
        value.plannedStartAt(),
        value.dueAt(),
        value.startedAt(),
        value.completedAt(),
        value.customerVisible(),
        value.optional(),
        value.skippable(),
        value.failureCode(),
        value.safeMessage(),
        value.version(),
        value.allowedActions(),
        adapter(value.latestAdapterAttempt()));
  }

  private static MilestoneResponse milestone(Milestone value) {
    return new MilestoneResponse(
        value.code(), value.label(), value.occurredAt(), value.customerVisible());
  }

  private static AdapterResponse adapter(AdapterAttempt value) {
    return value == null
        ? null
        : new AdapterResponse(
            value.scenario(), value.outcome(), value.reference(), value.occurredAt());
  }

  record ActionRequest(Action action, String reason, Map<String, Object> resultData) {}

  record PageResponse(List<SummaryResponse> items, PageInfo pageInfo) {}

  record PageInfo(String nextCursor, boolean hasNext, int pageSize) {}

  record SummaryResponse(
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

  record DetailResponse(
      UUID id,
      String number,
      UUID orderId,
      String orderNumber,
      String routeCode,
      FulfillmentStatus status,
      String currentStep,
      Instant dueAt,
      boolean overdue,
      long version,
      String templateCode,
      String templateVersion,
      List<StepResponse> steps,
      List<MilestoneResponse> milestones,
      List<Action> allowedActions) {}

  record StepResponse(
      UUID id,
      String code,
      String name,
      FulfillmentStepStatus status,
      List<String> dependencies,
      String assigneeRole,
      UUID assigneeUserId,
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
      AdapterResponse latestAdapterAttempt) {}

  record MilestoneResponse(
      String code, String label, Instant occurredAt, boolean customerVisible) {}

  record AdapterResponse(String scenario, String outcome, String reference, Instant occurredAt) {}
}
