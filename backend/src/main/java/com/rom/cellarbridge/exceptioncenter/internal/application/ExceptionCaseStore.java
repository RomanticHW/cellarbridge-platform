package com.rom.cellarbridge.exceptioncenter.internal.application;

import com.rom.cellarbridge.exceptioncenter.ExceptionCategory;
import com.rom.cellarbridge.exceptioncenter.ExceptionSeverity;
import com.rom.cellarbridge.exceptioncenter.ExceptionStatus;
import com.rom.cellarbridge.exceptioncenter.RecoveryAction;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ExceptionCaseStore {

  String nextNumber(Instant at);

  OpenResult open(Detection detection, UUID caseId, String number, Instant at);

  Optional<CaseRecord> find(TenantId tenantId, UUID caseId, boolean forUpdate);

  List<CaseRecord> list(
      TenantId tenantId,
      Set<ExceptionStatus> statuses,
      ExceptionSeverity severity,
      UUID assigneeId,
      String sourceType,
      Boolean overdue,
      boolean technicalOnly,
      int offset,
      int limit);

  void update(
      CaseRecord before,
      ExceptionStatus status,
      ExceptionSeverity severity,
      UUID assigneeId,
      UUID primaryCaseId,
      Instant resolvedAt,
      Instant closedAt,
      Instant at);

  void appendHistory(
      CaseRecord exceptionCase,
      String action,
      String actorType,
      UUID actorId,
      ExceptionStatus previousStatus,
      ExceptionStatus newStatus,
      String reasonCode,
      String safeReason,
      UUID correlationId,
      Instant at);

  List<Occurrence> occurrences(TenantId tenantId, UUID caseId);

  List<History> history(TenantId tenantId, UUID caseId);

  RecoveryClaim claimRecovery(
      CaseRecord exceptionCase,
      UUID attemptId,
      RecoveryAction action,
      UUID requesterId,
      String idempotencyKeyHash,
      String requestHash,
      String inputSummary,
      Instant at);

  boolean appendRecoveryOutcome(
      TenantId tenantId,
      UUID attemptId,
      String status,
      String resultCode,
      String safeResult,
      String sourceState,
      Instant at);

  Optional<RecoveryOutcome> recoveryOutcome(TenantId tenantId, UUID attemptId);

  List<RecoveryView> recoveries(TenantId tenantId, UUID caseId);

  int recoveryAttempts(TenantId tenantId, UUID caseId, RecoveryAction action);

  void notifyRecoveryThreshold(TenantId tenantId, UUID caseId, RecoveryAction action, Instant at);

  void completeWorkItem(TenantId tenantId, UUID caseId, Instant at);

  record Detection(
      TenantId tenantId,
      UUID sourceEventId,
      String eventType,
      String sourceType,
      UUID sourceId,
      String sourceNumber,
      ExceptionCategory category,
      String dedupKey,
      ExceptionSeverity severity,
      Instant dueAt,
      String summary,
      String safeDetails,
      String evidence,
      UUID correlationId,
      UUID causationId,
      Instant detectedAt) {}

  record OpenResult(CaseRecord exceptionCase, boolean created, boolean occurrenceAdded) {}

  record CaseRecord(
      UUID id,
      TenantId tenantId,
      String number,
      String sourceType,
      UUID sourceId,
      String sourceNumber,
      ExceptionCategory category,
      String dedupKey,
      ExceptionSeverity severity,
      ExceptionStatus status,
      UUID assigneeId,
      UUID primaryCaseId,
      Instant dueAt,
      String summary,
      String safeDetails,
      UUID correlationId,
      UUID causationId,
      Instant openedAt,
      Instant resolvedAt,
      Instant closedAt,
      Instant updatedAt,
      long version) {}

  record Occurrence(
      UUID id, UUID sourceEventId, String eventType, Instant detectedAt, String evidence) {}

  record History(
      UUID id,
      String action,
      String actorType,
      UUID actorId,
      ExceptionStatus previousStatus,
      ExceptionStatus newStatus,
      String reasonCode,
      String safeReason,
      UUID correlationId,
      Instant occurredAt) {}

  record RecoveryClaim(
      UUID id,
      RecoveryAction action,
      UUID requesterId,
      String requestHash,
      String inputSummary,
      Instant requestedAt,
      boolean created) {}

  record RecoveryOutcome(
      String status,
      String resultCode,
      String safeResult,
      String sourceState,
      Instant completedAt) {}

  record RecoveryView(
      UUID id,
      RecoveryAction action,
      UUID requesterId,
      String inputSummary,
      Instant requestedAt,
      RecoveryOutcome outcome) {}
}
