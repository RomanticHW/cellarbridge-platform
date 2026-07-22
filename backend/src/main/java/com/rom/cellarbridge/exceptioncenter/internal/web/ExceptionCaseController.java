package com.rom.cellarbridge.exceptioncenter.internal.web;

import com.rom.cellarbridge.exceptioncenter.ExceptionSeverity;
import com.rom.cellarbridge.exceptioncenter.ExceptionStatus;
import com.rom.cellarbridge.exceptioncenter.RecoveryAction;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseService;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseService.DetailView;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseService.FailedDeliveryPage;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseService.PageView;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionCaseService.RecoveryResult;
import com.rom.cellarbridge.exceptioncenter.internal.application.ExceptionProblem;
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
@RequestMapping("/api/v1")
final class ExceptionCaseController {
  private final ExceptionCaseService service;

  ExceptionCaseController(ExceptionCaseService service) {
    this.service = service;
  }

  @GetMapping("/exceptions")
  PageView list(
      @RequestParam(required = false) Set<ExceptionStatus> status,
      @RequestParam(required = false) ExceptionSeverity severity,
      @RequestParam(required = false) UUID assigneeId,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) Boolean overdue,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    return service.list(status, severity, assigneeId, sourceType, overdue, pageSize, cursor);
  }

  @GetMapping("/exceptions/{caseId}")
  ResponseEntity<DetailView> get(@PathVariable UUID caseId) {
    DetailView result = service.get(caseId);
    return response(result);
  }

  @PostMapping("/exceptions/{caseId}/assignment")
  ResponseEntity<DetailView> assign(
      @PathVariable UUID caseId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @RequestBody AssignmentRequest request) {
    if (request == null) throw validation("Assignment request is required");
    return response(
        service.assign(caseId, expectedVersion(ifMatch), request.assigneeId(), request.reason()));
  }

  @PostMapping("/exceptions/{caseId}/actions")
  ResponseEntity<DetailView> act(
      @PathVariable UUID caseId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @RequestBody LifecycleRequest request) {
    if (request == null || request.action() == null) throw validation("Action is required");
    DetailView result =
        switch (request.action()) {
          case ACKNOWLEDGE ->
              service.acknowledge(caseId, expectedVersion(ifMatch), request.reason());
          case BEGIN_INVESTIGATION ->
              service.begin(caseId, expectedVersion(ifMatch), request.reason());
        };
    return response(result);
  }

  @PostMapping("/exceptions/{caseId}/recovery-attempts")
  ResponseEntity<RecoveryResult> recover(
      @PathVariable UUID caseId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody RecoveryRequest request) {
    if (request == null || request.action() == null)
      throw validation("Recovery action is required");
    RecoveryResult result =
        service.recover(
            caseId,
            expectedVersion(ifMatch),
            idempotencyKey,
            request.action(),
            request.reason(),
            request.parameters());
    return ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .eTag("\"" + result.version() + "\"")
        .body(result);
  }

  @PostMapping("/exceptions/{caseId}/closure")
  ResponseEntity<DetailView> close(
      @PathVariable UUID caseId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @RequestBody ClosureRequest request) {
    if (request == null) throw validation("Closure request is required");
    return response(
        service.close(
            caseId,
            expectedVersion(ifMatch),
            request.reasonCode(),
            request.reason(),
            request.primaryCaseId()));
  }

  @GetMapping("/event-publications/failed")
  FailedDeliveryPage failedDeliveries(
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    return service.failedDeliveries(pageSize, cursor);
  }

  private static ResponseEntity<DetailView> response(DetailView value) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag("\"" + value.summary().version() + "\"")
        .body(value);
  }

  private static long expectedVersion(String value) {
    if (value == null || value.isBlank()) {
      throw new ExceptionProblem("PRECONDITION_REQUIRED", "If-Match is required");
    }
    if (!value.matches("\"[0-9]+\"")) throw validation("If-Match must be a quoted version");
    try {
      return Long.parseLong(value.substring(1, value.length() - 1));
    } catch (NumberFormatException exception) {
      throw validation("If-Match version is invalid");
    }
  }

  private static ExceptionProblem validation(String message) {
    return new ExceptionProblem("VALIDATION_FAILED", message);
  }

  enum LifecycleAction {
    ACKNOWLEDGE,
    BEGIN_INVESTIGATION
  }

  record AssignmentRequest(UUID assigneeId, String reason) {}

  record LifecycleRequest(LifecycleAction action, String reason) {}

  record RecoveryRequest(RecoveryAction action, String reason, Map<String, Object> parameters) {}

  record ClosureRequest(String reasonCode, String reason, UUID primaryCaseId) {}
}
