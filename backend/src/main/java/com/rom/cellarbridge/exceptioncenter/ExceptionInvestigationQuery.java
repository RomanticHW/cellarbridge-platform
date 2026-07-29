package com.rom.cellarbridge.exceptioncenter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ExceptionInvestigationQuery {

  InvestigationCase investigateCase(UUID caseId);

  InvestigationCases investigateBySource(ExceptionSourceType sourceType, UUID sourceId, int limit);

  enum ExceptionSourceType {
    FULFILLMENT_STEP,
    INVENTORY_RESERVATION,
    EVENT_PUBLICATION
  }

  enum DataClassification {
    UNTRUSTED_BUSINESS_DATA
  }

  record UntrustedText(DataClassification classification, String value, boolean truncated) {}

  record InvestigationCases(List<InvestigationCase> items, boolean hasMore) {
    public InvestigationCases {
      items = List.copyOf(items);
    }
  }

  record InvestigationCase(
      UUID id,
      ExceptionCategory category,
      ExceptionSeverity severity,
      ExceptionStatus status,
      ExceptionSource source,
      UUID assigneeId,
      UntrustedText summary,
      Instant openedAt,
      Instant dueAt,
      Instant updatedAt,
      long version) {}

  record ExceptionSource(ExceptionSourceType type, UUID id, UntrustedText number) {}
}
