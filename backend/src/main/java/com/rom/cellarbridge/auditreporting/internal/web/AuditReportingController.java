package com.rom.cellarbridge.auditreporting.internal.web;

import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.AuditPage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.TimelinePage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingService.WorkItemPage;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.DashboardRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class AuditReportingController {
  private final AuditReportingService service;

  AuditReportingController(AuditReportingService service) {
    this.service = service;
  }

  @GetMapping("/dashboard")
  ResponseEntity<DashboardRecord> dashboard(
      @RequestParam LocalDate from, @RequestParam LocalDate to) {
    return noStore(service.dashboard(from, to));
  }

  @GetMapping("/audit/entries")
  ResponseEntity<AuditPage> audit(
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) UUID subjectId,
      @RequestParam(required = false) UUID correlationId,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    return noStore(
        service.audit(
            subjectType, subjectId, correlationId, actorId, action, from, to, pageSize, cursor));
  }

  @GetMapping("/timeline")
  ResponseEntity<TimelinePage> timeline(
      @RequestParam String subjectType,
      @RequestParam UUID subjectId,
      @RequestParam(required = false) Integer pageSize) {
    return noStore(service.timeline(subjectType, subjectId, pageSize));
  }

  @GetMapping("/work-items")
  ResponseEntity<WorkItemPage> workItems(
      @RequestParam(required = false) Set<String> status,
      @RequestParam(required = false) Set<String> priority,
      @RequestParam(required = false) Set<String> type,
      @RequestParam(required = false) Instant dueFrom,
      @RequestParam(required = false) Instant dueTo,
      @RequestParam(required = false) String subjectNumber,
      @RequestParam(required = false, defaultValue = "personal") String scope,
      @RequestParam(required = false) Integer pageSize) {
    return noStore(
        service.workItems(status, priority, type, dueFrom, dueTo, subjectNumber, scope, pageSize));
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
