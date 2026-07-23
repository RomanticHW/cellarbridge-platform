package com.rom.cellarbridge.auditreporting.internal.mcp;

import static com.rom.cellarbridge.platform.mcp.McpSchemaSupport.*;

import java.util.List;
import java.util.Map;

/** Exact data schemas for the reporting module's MCP tools. */
final class ReportingMcpSchemas {

  private static final Map<String, Object> UUID = string("uuid");
  private static final Map<String, Object> NULLABLE_UUID = nullable(UUID);
  private static final Map<String, Object> INSTANT = string("date-time");
  private static final Map<String, Object> NULLABLE_INSTANT = nullable(INSTANT);
  static final Map<String, Object> WORK_ITEMS = workItems();
  static final Map<String, Object> DASHBOARD = dashboard();
  static final Map<String, Object> TIMELINE = timeline();
  static final Map<String, Object> AUDIT = audit();

  private ReportingMcpSchemas() {}

  private static Map<String, Object> workItems() {
    Map<String, Object> item =
        object(
            Map.ofEntries(
                Map.entry("id", UUID),
                Map.entry(
                    "type",
                    enumeration(
                        List.of(
                            "PARTNER_REVIEW",
                            "QUOTATION_APPROVAL",
                            "FULFILLMENT_STEP",
                            "EXCEPTION_ACTION",
                            "RECEIVABLE_FOLLOW_UP"))),
                Map.entry("subjectType", string(80)),
                Map.entry("subjectId", UUID),
                Map.entry("subjectNumber", string(80)),
                Map.entry("title", string(240)),
                Map.entry("safeSummary", nullableString(500)),
                Map.entry("priority", enumeration(List.of("LOW", "MEDIUM", "HIGH", "CRITICAL"))),
                Map.entry(
                    "status",
                    enumeration(List.of("OPEN", "CLAIMED", "COMPLETED", "CANCELLED", "EXPIRED"))),
                Map.entry("candidateRole", nullableString(80)),
                Map.entry("assigneeUserId", NULLABLE_UUID),
                Map.entry("dueAt", NULLABLE_INSTANT),
                Map.entry("createdAt", INSTANT),
                Map.entry("completedAt", NULLABLE_INSTANT),
                Map.entry("version", nonNegativeInteger())));
    return object(
        Map.of(
            "items", array(item, 100),
            "scope", enumeration(List.of("PERSONAL", "TEAM")),
            "pageInfo", pageInfo()));
  }

  private static Map<String, Object> dashboard() {
    Map<String, Object> metrics =
        object(
            Map.ofEntries(
                Map.entry("quotationCount", nonNegativeInteger()),
                Map.entry("quotationCycleSeconds", number()),
                Map.entry("acceptanceRate", number()),
                Map.entry("approvalBacklog", nonNegativeInteger()),
                Map.entry("quoteToOrderConversion", number()),
                Map.entry("idempotencyHits", nonNegativeInteger()),
                Map.entry("reservationEvents", nonNegativeInteger()),
                Map.entry("openExceptions", nonNegativeInteger()),
                Map.entry("overdueExceptions", nonNegativeInteger()),
                Map.entry("overdueWorkItems", nonNegativeInteger()),
                Map.entry("receivableEvents", nonNegativeInteger())));
    Map<String, Object> chartRow =
        object(
            Map.of(
                "label", string(160),
                "value", nonNegativeInteger()));
    Map<String, Object> chart = array(chartRow, 1000);
    Map<String, Object> charts =
        object(
            Map.of(
                "routeDistribution", chart,
                "reservationResults", chart,
                "fulfillmentSla", chart,
                "exceptionStatus", chart,
                "receivableStatus", chart));
    return object(
        Map.of(
            "from",
            string("date"),
            "to",
            string("date"),
            "generatedAt",
            INSTANT,
            "dataAsOf",
            NULLABLE_INSTANT,
            "projectionLagSeconds",
            nullable(nonNegativeInteger()),
            "projectionStatus",
            projectionStatus(),
            "metrics",
            metrics,
            "charts",
            charts));
  }

  private static Map<String, Object> timeline() {
    Map<String, Object> item =
        object(
            Map.ofEntries(
                Map.entry("sourceEventId", UUID),
                Map.entry("occurredAt", INSTANT),
                Map.entry("eventType", string(160)),
                Map.entry("sourceModule", string(80)),
                Map.entry("subjectType", string(80)),
                Map.entry("subjectId", UUID),
                Map.entry("subjectNumber", string(80)),
                Map.entry("safeSummary", string(500)),
                Map.entry("actorType", string(40)),
                Map.entry("actorId", NULLABLE_UUID),
                Map.entry("correlationId", UUID),
                Map.entry("causationId", UUID),
                Map.entry("dataAsOf", INSTANT)));
    return object(
        Map.of(
            "items", array(item, 100),
            "dataAsOf", NULLABLE_INSTANT,
            "projectionStatus", projectionStatus(),
            "pageInfo", pageInfo()));
  }

  private static Map<String, Object> audit() {
    Map<String, Object> item =
        object(
            Map.ofEntries(
                Map.entry("id", UUID),
                Map.entry("occurredAt", INSTANT),
                Map.entry("module", string(80)),
                Map.entry("action", string(120)),
                Map.entry("outcome", enumeration(List.of("SUCCEEDED", "FAILED", "OBSERVED"))),
                Map.entry("subjectType", string(80)),
                Map.entry("subjectId", UUID),
                Map.entry("subjectNumber", string(80)),
                Map.entry("actorType", string(40)),
                Map.entry("actorId", NULLABLE_UUID),
                Map.entry("actorDisplay", nullableString(160)),
                Map.entry("previousState", nullableString(80)),
                Map.entry("newState", nullableString(80)),
                Map.entry("safeReason", nullableString(500)),
                Map.entry("correlationId", UUID),
                Map.entry("causationId", UUID)));
    return object(
        Map.of(
            "items", array(item, 100),
            "pageInfo", pageInfo()));
  }

  private static Map<String, Object> pageInfo() {
    return object(
        Map.of(
            "nextCursor", nullable(string(2048)),
            "hasNext", bool(),
            "pageSize", boundedInteger(1, 100)));
  }

  private static Map<String, Object> projectionStatus() {
    return enumeration(List.of("CURRENT", "STALE", "EMPTY", "REBUILDING", "UNKNOWN"));
  }

  private static Map<String, Object> nullableString(int maxLength) {
    return nullable(string(maxLength));
  }
}
