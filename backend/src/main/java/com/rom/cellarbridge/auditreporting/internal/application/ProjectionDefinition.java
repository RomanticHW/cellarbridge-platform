package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.MetricFact;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.Projection;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.WorkAction;
import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore.WorkChange;
import com.rom.cellarbridge.platform.EventDelivery;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class ProjectionDefinition {
  static final String PROJECTOR = "audit-reporting.business.v1";

  static final Set<String> EVENT_TYPES =
      Set.of(
          "cellarbridge.partner.submitted-for-review.v1",
          "cellarbridge.partner.activated.v1",
          "cellarbridge.partner.changes-requested.v1",
          "cellarbridge.partner.rejected.v1",
          "cellarbridge.partner.suspended.v1",
          "cellarbridge.quotation.draft-created.v1",
          "cellarbridge.trade-planning.route-evaluated.v1",
          "cellarbridge.quotation.approval-requested.v1",
          "cellarbridge.quotation.approved.v1",
          "cellarbridge.quotation.changes-requested.v1",
          "cellarbridge.quotation.rejected.v1",
          "cellarbridge.quotation.issued.v1",
          "cellarbridge.quotation.accepted.v1",
          "cellarbridge.order.created.v1",
          "cellarbridge.inventory.reservation-confirmed.v1",
          "cellarbridge.inventory.reservation-failed.v1",
          "cellarbridge.fulfillment.plan-created.v1",
          "cellarbridge.fulfillment.step-started.v1",
          "cellarbridge.fulfillment.step-overdue.v1",
          "cellarbridge.fulfillment.step-failed.v1",
          "cellarbridge.fulfillment.public-milestone-reached.v1",
          "cellarbridge.fulfillment.completed.v1",
          "cellarbridge.exception.opened.v1",
          "cellarbridge.exception.closed.v1",
          "cellarbridge.settlement.receivable-created.v1",
          "cellarbridge.settlement.payment-recorded.v1",
          "cellarbridge.settlement.payment-reversed.v1",
          "cellarbridge.settlement.receivable-overdue.v1",
          "cellarbridge.settlement.receivable-paid.v1");

  private ProjectionDefinition() {}

  static Projection from(EventDelivery event, JsonNode payload) {
    String type = event.eventType();
    String module = module(type, event.producer());
    String action = action(type);
    String state = text(payload, "status", text(payload, "receivableStatus", null));
    UUID actor = uuid(payload, "actorId");
    if (actor == null) actor = uuid(payload, "submittedBy");
    if (actor == null) actor = uuid(payload, "sourceOwnerId");
    String actorType = actor == null ? systemActor(type) : "USER";
    String reason = limited(text(payload, "safeReason", text(payload, "reason", null)), 500);
    UUID orderId = uuid(payload, "orderId");
    UUID quotationId = uuid(payload, "quotationId");
    UUID partnerId = uuid(payload, "partnerId");
    JsonNode customer = payload.path("customer");
    if (partnerId == null && customer != null) partnerId = uuid(customer, "partnerId");
    String routeCode = route(payload);
    Long businessVersion = number(payload, "version", number(payload, "revision", null));
    WorkChange work = work(event, payload, actor);
    MetricFact metric = metric(event, payload, actor, routeCode);
    String summary = safeSummary(type, event.subject().number(), state, routeCode);
    String visibility = type.contains("public-milestone") ? "CUSTOMER" : "INTERNAL";
    String classification =
        type.contains("payment") || type.contains("receivable")
            ? "COMMERCIAL_SENSITIVE"
            : "INTERNAL";
    String entryHash =
        sha256(
            event.eventId()
                + "|"
                + event.tenantId()
                + "|"
                + type
                + "|"
                + event.occurredAt()
                + "|"
                + event.subject().id()
                + "|"
                + action
                + "|"
                + summary);
    return new Projection(
        event,
        module,
        action,
        outcome(type),
        state,
        businessVersion,
        actor,
        actorType,
        reason,
        summary,
        summary,
        classification,
        visibility,
        orderId,
        quotationId,
        partnerId,
        metric,
        work,
        entryHash);
  }

  static String dependencyKey(EventDelivery event, JsonNode payload) {
    WorkChange work = work(event, payload, uuid(payload, "actorId"));
    return work == null ? null : work.dependencyKey();
  }

  static String resolutionAction(EventDelivery event, JsonNode payload) {
    WorkChange work = work(event, payload, uuid(payload, "actorId"));
    return work == null ? null : work.action().name();
  }

  private static WorkChange work(EventDelivery event, JsonNode payload, UUID ownerId) {
    String type = event.eventType();
    UUID subjectId = event.subject().id();
    Instant occurred = event.occurredAt();
    if (type.equals("cellarbridge.partner.submitted-for-review.v1")) {
      return work(
          "partner-review:" + subjectId,
          WorkAction.OPEN,
          "PARTNER_REVIEW",
          "Review partner application",
          "HIGH",
          "partner:review",
          "sales-manager",
          ownerId,
          occurred.plus(2, ChronoUnit.DAYS));
    }
    if (Set.of(
            "cellarbridge.partner.activated.v1",
            "cellarbridge.partner.changes-requested.v1",
            "cellarbridge.partner.rejected.v1")
        .contains(type)) {
      return work(
          "partner-review:" + subjectId,
          type.contains("changes-requested") ? WorkAction.CANCEL : WorkAction.COMPLETE,
          "PARTNER_REVIEW",
          "Review partner application",
          "HIGH",
          "partner:review",
          "sales-manager",
          ownerId,
          null);
    }
    if (type.equals("cellarbridge.quotation.approval-requested.v1")) {
      return work(
          "quotation-approval:" + subjectId,
          WorkAction.OPEN,
          "QUOTATION_APPROVAL",
          "Approve quotation",
          "HIGH",
          "quotation:approve",
          "sales-manager",
          ownerId,
          occurred.plus(8, ChronoUnit.HOURS));
    }
    if (Set.of(
            "cellarbridge.quotation.approved.v1",
            "cellarbridge.quotation.changes-requested.v1",
            "cellarbridge.quotation.rejected.v1")
        .contains(type)) {
      return work(
          "quotation-approval:" + subjectId,
          type.contains("changes-requested") ? WorkAction.CANCEL : WorkAction.COMPLETE,
          "QUOTATION_APPROVAL",
          "Approve quotation",
          "HIGH",
          "quotation:approve",
          "sales-manager",
          ownerId,
          null);
    }
    if (type.equals("cellarbridge.fulfillment.plan-created.v1")) {
      UUID planId = uuid(payload, "planId");
      return work(
          "fulfillment-step:" + (planId == null ? subjectId : planId),
          WorkAction.OPEN,
          "FULFILLMENT_STEP",
          "Advance fulfillment plan",
          "MEDIUM",
          "fulfillment:operate",
          "trade-operator",
          ownerId,
          occurred.plus(1, ChronoUnit.DAYS));
    }
    if (type.equals("cellarbridge.fulfillment.completed.v1")) {
      UUID planId = uuid(payload, "planId");
      return work(
          "fulfillment-step:" + (planId == null ? subjectId : planId),
          WorkAction.COMPLETE,
          "FULFILLMENT_STEP",
          "Advance fulfillment plan",
          "NORMAL",
          "fulfillment:operate",
          "trade-operator",
          ownerId,
          null);
    }
    if (type.equals("cellarbridge.exception.opened.v1")) {
      String priority = text(payload, "severity", "HIGH");
      return work(
          "exception-action:" + subjectId,
          WorkAction.OPEN,
          "EXCEPTION_ACTION",
          "Resolve operational exception",
          priority,
          "exception:assign",
          "trade-operator",
          ownerId,
          instant(payload, "dueAt"));
    }
    if (type.equals("cellarbridge.exception.closed.v1")) {
      return work(
          "exception-action:" + subjectId,
          WorkAction.COMPLETE,
          "EXCEPTION_ACTION",
          "Resolve operational exception",
          "HIGH",
          "exception:assign",
          "trade-operator",
          ownerId,
          null);
    }
    if (type.equals("cellarbridge.settlement.receivable-overdue.v1")) {
      UUID receivableId = uuid(payload, "receivableId");
      return work(
          "receivable-follow-up:" + (receivableId == null ? subjectId : receivableId),
          WorkAction.OPEN,
          "RECEIVABLE_FOLLOW_UP",
          "Follow up overdue receivable",
          "HIGH",
          "settlement:record-payment",
          "finance-specialist",
          ownerId,
          occurred.plus(1, ChronoUnit.DAYS));
    }
    if (type.equals("cellarbridge.settlement.receivable-paid.v1")) {
      UUID receivableId = uuid(payload, "receivableId");
      return work(
          "receivable-follow-up:" + (receivableId == null ? subjectId : receivableId),
          WorkAction.COMPLETE,
          "RECEIVABLE_FOLLOW_UP",
          "Follow up overdue receivable",
          "HIGH",
          "settlement:record-payment",
          "finance-specialist",
          ownerId,
          null);
    }
    return null;
  }

  private static WorkChange work(
      String key,
      WorkAction action,
      String type,
      String title,
      String priority,
      String permission,
      String role,
      UUID owner,
      Instant dueAt) {
    return new WorkChange(key, action, type, title, null, priority, permission, role, owner, dueAt);
  }

  private static MetricFact metric(
      EventDelivery event, JsonNode payload, UUID owner, String route) {
    String type = event.eventType();
    String metric =
        switch (type) {
          case "cellarbridge.quotation.draft-created.v1" -> "QUOTATION_CREATED";
          case "cellarbridge.quotation.approval-requested.v1" -> "APPROVAL_REQUESTED";
          case "cellarbridge.quotation.approved.v1" -> "QUOTATION_APPROVED";
          case "cellarbridge.quotation.issued.v1" -> "QUOTATION_ISSUED";
          case "cellarbridge.quotation.accepted.v1" -> "QUOTATION_ACCEPTED";
          case "cellarbridge.trade-planning.route-evaluated.v1" -> "ROUTE_DECISION";
          case "cellarbridge.order.created.v1" -> "ORDER_CREATED";
          case "cellarbridge.inventory.reservation-confirmed.v1",
              "cellarbridge.inventory.reservation-failed.v1" ->
              "RESERVATION_RESULT";
          case "cellarbridge.fulfillment.step-started.v1",
              "cellarbridge.fulfillment.step-overdue.v1",
              "cellarbridge.fulfillment.step-failed.v1",
              "cellarbridge.fulfillment.completed.v1" ->
              "FULFILLMENT_SLA";
          case "cellarbridge.exception.opened.v1", "cellarbridge.exception.closed.v1" ->
              "EXCEPTION_STATUS";
          case "cellarbridge.settlement.receivable-created.v1",
              "cellarbridge.settlement.payment-recorded.v1",
              "cellarbridge.settlement.payment-reversed.v1",
              "cellarbridge.settlement.receivable-overdue.v1",
              "cellarbridge.settlement.receivable-paid.v1" ->
              "RECEIVABLE_STATUS";
          default -> null;
        };
    if (metric == null) return null;
    String outcome =
        type.contains("reservation-confirmed")
            ? "CONFIRMED"
            : type.contains("reservation-failed")
                ? "FAILED"
                : type.contains("overdue")
                    ? "OVERDUE"
                    : type.contains("failed")
                        ? "FAILED"
                        : type.contains("closed")
                            ? "CLOSED"
                            : text(
                                payload, "receivableStatus", text(payload, "status", "OBSERVED"));
    BigDecimal amount =
        decimal(payload, "outstandingAmount", decimal(payload, "originalAmount", null));
    String currency = text(payload, "currency", null);
    return new MetricFact(metric, outcome, route, owner, null, amount, currency, Map.of());
  }

  private static String module(String type, String producer) {
    String value = type.substring("cellarbridge.".length(), type.lastIndexOf('.'));
    int boundary = value.indexOf('.');
    return boundary < 0 ? producer : value.substring(0, boundary);
  }

  private static String action(String type) {
    return type.substring("cellarbridge.".length(), type.lastIndexOf(".v1"))
        .replace('.', '_')
        .replace('-', '_')
        .toUpperCase();
  }

  private static String outcome(String type) {
    return type.contains("failed") || type.contains("rejected") ? "FAILED" : "SUCCEEDED";
  }

  private static String systemActor(String type) {
    return type.equals("cellarbridge.quotation.accepted.v1") ? "CUSTOMER" : "SYSTEM";
  }

  private static String route(JsonNode payload) {
    String direct = text(payload, "routeCode", text(payload, "selectedRouteCode", null));
    if (direct != null) return direct;
    JsonNode route = payload.path("route");
    return route == null ? null : text(route, "code", null);
  }

  private static String safeSummary(String type, String number, String state, String route) {
    String fact = action(type).replace('_', ' ').toLowerCase();
    StringBuilder summary = new StringBuilder(number).append(" · ").append(fact);
    if (state != null) summary.append(" · ").append(state);
    if (route != null) summary.append(" · route ").append(route);
    return limited(summary.toString(), 500);
  }

  private static String text(JsonNode node, String field, String fallback) {
    if (node == null) return fallback;
    JsonNode value = node.path(field);
    return value == null || value.isMissingNode() || value.isNull() || !value.isTextual()
        ? fallback
        : value.asText();
  }

  private static UUID uuid(JsonNode node, String field) {
    String value = text(node, field, null);
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static Long number(JsonNode node, String field, Long fallback) {
    if (node == null) return fallback;
    JsonNode value = node.path(field);
    return value != null && value.isIntegralNumber() ? Long.valueOf(value.longValue()) : fallback;
  }

  private static Instant instant(JsonNode node, String field) {
    String value = text(node, field, null);
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
    String value = text(node, field, null);
    if (value == null) return fallback;
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }

  private static String limited(String value, int max) {
    if (value == null) return null;
    String normalized = value.replaceAll("[\\r\\n\\t]+", " ").strip();
    return normalized.length() <= max ? normalized : normalized.substring(0, max);
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
