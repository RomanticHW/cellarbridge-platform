package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface AuditReportingStore {

  long activeGeneration(TenantId tenantId, Instant now);

  InboxDecision begin(
      EventDelivery delivery,
      String projectorName,
      String payloadHash,
      String dependencyKey,
      String resolutionAction,
      Instant now);

  ProjectionStatus project(
      long generation, Projection projection, boolean appendAudit, Instant now);

  void complete(
      EventDelivery delivery,
      String projectorName,
      ProjectionStatus status,
      String errorCode,
      Instant now);

  void deadLetter(
      EventDelivery delivery,
      String projectorName,
      String payloadHash,
      String errorCode,
      Instant now);

  DashboardRecord dashboard(
      TenantId tenantId,
      LocalDate from,
      LocalDate to,
      UUID ownerId,
      Set<String> allowedMetricTypes,
      Instant generatedAt);

  List<AuditRecord> audit(
      TenantId tenantId,
      AuditFilter filter,
      UUID actorScope,
      Set<String> classifications,
      int pageSize);

  List<TimelineRecord> timeline(
      TenantId tenantId, String subjectType, UUID subjectId, UUID partnerScope, int pageSize);

  List<WorkItemRecord> workItems(
      TenantId tenantId,
      WorkFilter filter,
      UUID userId,
      Set<String> permissionValues,
      boolean teamScope,
      int pageSize);

  ProjectionFreshness projectionFreshness(TenantId tenantId, Instant generatedAt);

  long beginRebuild(TenantId tenantId, Instant now);

  void activateRebuild(
      TenantId tenantId, long generation, long sourceCount, Instant dataAsOf, Instant now);

  enum InboxDecision {
    PROCESS,
    DUPLICATE,
    PENDING_RETRY,
    DEAD_LETTER
  }

  enum ProjectionStatus {
    PROCESSED,
    PENDING,
    DEAD_LETTER
  }

  record Projection(
      EventDelivery delivery,
      String module,
      String action,
      String outcome,
      String state,
      Long businessVersion,
      UUID actorId,
      String actorType,
      String safeReason,
      String safeSummary,
      String internalSummary,
      String classification,
      String visibility,
      UUID relatedOrderId,
      UUID relatedQuotationId,
      UUID relatedPartnerId,
      MetricFact metric,
      WorkChange workChange,
      String entryHash) {}

  record MetricFact(
      String type,
      String outcome,
      String routeCode,
      UUID ownerId,
      Long durationMs,
      BigDecimal amount,
      String currency,
      Map<String, String> attributes) {}

  record WorkChange(
      String dependencyKey,
      WorkAction action,
      String type,
      String title,
      String safeSummary,
      String priority,
      String candidatePermission,
      String candidateRole,
      UUID ownerId,
      Instant dueAt) {}

  enum WorkAction {
    OPEN,
    COMPLETE,
    CANCEL
  }

  record DashboardRecord(
      LocalDate from,
      LocalDate to,
      Instant generatedAt,
      Instant dataAsOf,
      long projectionLagSeconds,
      String projectionStatus,
      Map<String, Object> metrics,
      Map<String, List<Map<String, Object>>> charts) {}

  record AuditFilter(
      String subjectType,
      UUID subjectId,
      UUID correlationId,
      UUID actorId,
      String action,
      Instant from,
      Instant to,
      Instant beforeOccurredAt,
      UUID beforeId) {}

  record AuditRecord(
      UUID id,
      Instant occurredAt,
      String module,
      String action,
      String outcome,
      String subjectType,
      UUID subjectId,
      String subjectNumber,
      String actorType,
      UUID actorId,
      String actorDisplay,
      String previousState,
      String newState,
      String safeReason,
      UUID correlationId,
      UUID causationId) {}

  record TimelineRecord(
      UUID sourceEventId,
      Instant occurredAt,
      String eventType,
      String sourceModule,
      String subjectType,
      UUID subjectId,
      String subjectNumber,
      String safeSummary,
      String actorType,
      UUID actorId,
      UUID correlationId,
      UUID causationId,
      Instant dataAsOf) {}

  record WorkFilter(
      Set<String> statuses,
      Set<String> priorities,
      Set<String> types,
      Instant dueFrom,
      Instant dueTo,
      String subjectNumber) {}

  record ProjectionFreshness(
      Instant dataAsOf, long projectionLagSeconds, String projectionStatus) {}

  record WorkItemRecord(
      UUID id,
      String type,
      String subjectType,
      UUID subjectId,
      String subjectNumber,
      String title,
      String safeSummary,
      String priority,
      String status,
      String candidateRole,
      UUID assigneeUserId,
      Instant dueAt,
      Instant createdAt,
      Instant completedAt,
      long version) {}
}
