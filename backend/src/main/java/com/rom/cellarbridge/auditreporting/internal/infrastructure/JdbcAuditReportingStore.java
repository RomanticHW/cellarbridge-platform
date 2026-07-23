package com.rom.cellarbridge.auditreporting.internal.infrastructure;

import com.rom.cellarbridge.auditreporting.internal.application.AuditReportingStore;
import com.rom.cellarbridge.auditreporting.internal.application.ProjectionEventSource.Watermark;
import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.platform.EventDelivery;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcAuditReportingStore implements AuditReportingStore {
  private static final int PROJECTION_VERSION = 1;
  private static final String WORK_PRIORITY_RANK =
      "CASE work.priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3"
          + " WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END";
  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper json;

  public JdbcAuditReportingStore(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public long activeGeneration(TenantId tenantId, Instant now) {
    jdbc.update(
        """
        INSERT INTO audit_reporting.projection_generation
          (tenant_id, generation, status, schema_version, source_event_count,
           data_as_of, created_at, activated_at)
        SELECT :tenantId, 1, 'ACTIVE', :schemaVersion, 0, NULL, :now, :now
         WHERE NOT EXISTS (
           SELECT 1 FROM audit_reporting.projection_generation
            WHERE tenant_id = :tenantId AND status = 'ACTIVE'
         )
        ON CONFLICT DO NOTHING
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("schemaVersion", PROJECTION_VERSION)
            .addValue("now", timestamp(now)));
    Long generation =
        jdbc.queryForObject(
            "SELECT generation FROM audit_reporting.projection_generation WHERE tenant_id = :tenantId AND status = 'ACTIVE'",
            new MapSqlParameterSource("tenantId", tenantId.value()),
            Long.class);
    if (generation == null)
      throw new IllegalStateException("Active projection generation is missing");
    return generation;
  }

  @Override
  public InboxDecision begin(
      EventDelivery delivery,
      String projectorName,
      String payloadHash,
      String dependencyKey,
      String resolutionAction,
      Instant now) {
    MapSqlParameterSource values =
        inboxValues(delivery, projectorName, payloadHash, now)
            .addValue("dependencyKey", dependencyKey)
            .addValue("resolutionAction", resolutionAction);
    int inserted =
        jdbc.update(
            """
            INSERT INTO audit_reporting.projector_inbox
              (tenant_id, projector_name, event_id, event_type, payload_hash,
               event_occurred_at, status, error_code, dependency_key, resolution_action,
               retry_count, first_received_at, processed_at, updated_at)
            VALUES
              (:tenantId, :projectorName, :eventId, :eventType, :payloadHash,
               :eventOccurredAt, 'PROCESSING', NULL, :dependencyKey, :resolutionAction,
               0, :now, NULL, :now)
            ON CONFLICT DO NOTHING
            """,
            values);
    if (inserted == 1) return InboxDecision.PROCESS;
    InboxRow existing = existingInbox(projectorName, delivery.eventId());
    if (!existing.tenantId().equals(delivery.tenantId())
        || !existing.eventType().equals(delivery.eventType())
        || !existing.payloadHash().equals(payloadHash)) {
      throw new IllegalStateException("Projection inbox binding conflict");
    }
    return switch (existing.status()) {
      case "PROCESSED" -> {
        checkpoint(delivery, projectorName, false, true, now);
        yield InboxDecision.DUPLICATE;
      }
      case "PENDING" -> {
        jdbc.update(
            """
            UPDATE audit_reporting.projector_inbox
               SET status = 'PROCESSING', retry_count = retry_count + 1,
                   error_code = NULL, updated_at = :now
             WHERE tenant_id = :tenantId AND projector_name = :projectorName
               AND event_id = :eventId AND status = 'PENDING'
            """,
            values);
        yield InboxDecision.PENDING_RETRY;
      }
      case "DEAD_LETTER" -> InboxDecision.DEAD_LETTER;
      default -> throw new IllegalStateException("Projection inbox has an orphaned processing row");
    };
  }

  @Override
  public ProjectionStatus project(
      long generation, Projection projection, boolean appendAudit, Instant now) {
    EventDelivery event = projection.delivery();
    MapSqlParameterSource values = projectionValues(generation, projection, now);
    if (appendAudit) insertAudit(projection, now);
    int timelineInserted =
        jdbc.update(
            """
            INSERT INTO audit_reporting.timeline_projection
              (tenant_id, generation, source_event_id, occurred_at, event_type, source_module,
               subject_type, subject_id, subject_number, related_order_id,
               related_quotation_id, related_partner_id, actor_type, actor_id,
               safe_summary, internal_summary, visibility, correlation_id, causation_id, data_as_of)
            VALUES
              (:tenantId, :generation, :eventId, :occurredAt, :eventType, :module,
               :subjectType, :subjectId, :subjectNumber, :relatedOrderId,
               :relatedQuotationId, :relatedPartnerId, :actorType, :actorId,
               :safeSummary, :internalSummary, :visibility, :correlationId, :causationId, :occurredAt)
            ON CONFLICT DO NOTHING
            """,
            values);
    upsertSubjectState(values);
    insertMetric(values, projection);
    ProjectionStatus workStatus = applyWork(generation, projection, now);
    if (timelineInserted == 1) {
      jdbc.update(
          """
          UPDATE audit_reporting.projection_generation
             SET source_event_count = source_event_count + 1,
                 data_as_of = CASE
                   WHEN data_as_of IS NULL OR data_as_of < :occurredAt THEN :occurredAt
                   ELSE data_as_of END
           WHERE tenant_id = :tenantId AND generation = :generation
          """,
          values);
    }
    return workStatus;
  }

  @Override
  public void complete(
      EventDelivery delivery,
      String projectorName,
      ProjectionStatus status,
      String errorCode,
      Instant now) {
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tenantId", delivery.tenantId())
            .addValue("projectorName", projectorName)
            .addValue("eventId", delivery.eventId())
            .addValue("status", status.name())
            .addValue("errorCode", errorCode)
            .addValue("now", timestamp(now));
    int updated =
        jdbc.update(
            """
            UPDATE audit_reporting.projector_inbox
               SET status = :status, error_code = :errorCode,
                   processed_at = CASE
                     WHEN :status = 'PROCESSED' THEN CAST(:now AS timestamptz)
                     ELSE NULL::timestamptz END,
                   updated_at = :now
             WHERE tenant_id = :tenantId AND projector_name = :projectorName
               AND event_id = :eventId AND status = 'PROCESSING'
            """,
            values);
    if (updated != 1) throw new IllegalStateException("Projection inbox completion lost its claim");
    checkpoint(delivery, projectorName, true, false, now);
  }

  @Override
  public void deadLetter(
      EventDelivery delivery,
      String projectorName,
      String payloadHash,
      String errorCode,
      Instant now) {
    MapSqlParameterSource values =
        inboxValues(delivery, projectorName, payloadHash, now).addValue("errorCode", errorCode);
    jdbc.update(
        """
        INSERT INTO audit_reporting.projector_inbox
          (tenant_id, projector_name, event_id, event_type, payload_hash,
           event_occurred_at, status, error_code, retry_count,
           first_received_at, processed_at, updated_at)
        VALUES
          (:tenantId, :projectorName, :eventId, :eventType, :payloadHash,
           :eventOccurredAt, 'DEAD_LETTER', :errorCode, 0, :now, NULL, :now)
        ON CONFLICT DO NOTHING
        """,
        values);
    checkpoint(delivery, projectorName, true, false, now);
  }

  @Override
  public DashboardRecord dashboard(
      TenantId tenantId,
      LocalDate from,
      LocalDate to,
      UUID ownerId,
      Set<String> allowedMetricTypes,
      Instant generatedAt,
      int chartLimit) {
    long generation = activeGenerationReadOnly(tenantId);
    if (generation == 0) {
      return new DashboardRecord(
          from,
          to,
          generatedAt,
          null,
          0L,
          "EMPTY",
          Map.ofEntries(
              Map.entry("quotationCount", 0L),
              Map.entry("quotationCycleSeconds", 0D),
              Map.entry("acceptanceRate", 0D),
              Map.entry("approvalBacklog", 0L),
              Map.entry("quoteToOrderConversion", 0D),
              Map.entry("idempotencyHits", 0L),
              Map.entry("reservationEvents", 0L),
              Map.entry("openExceptions", 0L),
              Map.entry("overdueExceptions", 0L),
              Map.entry("overdueWorkItems", 0L),
              Map.entry("receivableEvents", 0L)),
          Map.of(
              "routeDistribution", List.of(),
              "reservationResults", List.of(),
              "fulfillmentSla", List.of(),
              "exceptionStatus", List.of(),
              "receivableStatus", List.of()));
    }
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("generation", generation)
            .addValue("from", from)
            .addValue("toExclusive", to.plusDays(1))
            .addValue("ownerId", ownerId)
            .addValue("allowedMetricTypes", allowedMetricTypes)
            .addValue("chartLimit", chartLimit);
    String restrictions =
        (ownerId == null ? "" : " AND owner_user_id = :ownerId")
            + (allowedMetricTypes.isEmpty() ? "" : " AND metric_type IN (:allowedMetricTypes)");
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Map<String, Object> row :
        jdbc.queryForList(
            "SELECT metric_type, count(*) AS value FROM audit_reporting.metric_fact_projection WHERE tenant_id = :tenantId AND generation = :generation AND occurred_date >= :from AND occurred_date < :toExclusive"
                + restrictions
                + " GROUP BY metric_type",
            values)) {
      counts.put((String) row.get("metric_type"), ((Number) row.get("value")).longValue());
    }
    long accepted = counts.getOrDefault("QUOTATION_ACCEPTED", 0L);
    long issued = counts.getOrDefault("QUOTATION_ISSUED", 0L);
    long orders = counts.getOrDefault("ORDER_CREATED", 0L);
    String workRestrictions = ownerId == null ? "" : " AND owner_user_id = :ownerId";
    boolean financeOnly = allowedMetricTypes.equals(Set.of("RECEIVABLE_STATUS"));
    long approvalBacklog =
        financeOnly
            ? 0
            : count(
                "SELECT count(*) FROM audit_reporting.work_item_projection WHERE tenant_id = :tenantId AND generation = :generation AND type = 'QUOTATION_APPROVAL' AND status IN ('OPEN','CLAIMED')"
                    + workRestrictions,
                values);
    long idempotencyHits =
        financeOnly
            ? 0
            : count(
                "SELECT COALESCE(sum(duplicate_count),0) FROM audit_reporting.projector_checkpoint WHERE tenant_id = :tenantId",
                values);
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("quotationCount", counts.getOrDefault("QUOTATION_CREATED", 0L));
    metrics.put("quotationCycleSeconds", quotationCycleSeconds(values, restrictions));
    metrics.put("acceptanceRate", issued == 0 ? 0D : round((double) accepted / issued));
    metrics.put("approvalBacklog", approvalBacklog);
    metrics.put("quoteToOrderConversion", accepted == 0 ? 0D : round((double) orders / accepted));
    metrics.put("idempotencyHits", idempotencyHits);
    metrics.put("reservationEvents", counts.getOrDefault("RESERVATION_RESULT", 0L));
    metrics.put(
        "openExceptions",
        financeOnly ? 0 : openWorkCount(values, "EXCEPTION_ACTION", workRestrictions));
    metrics.put(
        "overdueExceptions",
        financeOnly
            ? 0
            : overdueWorkCount(values, generatedAt, "EXCEPTION_ACTION", workRestrictions));
    metrics.put(
        "overdueWorkItems",
        overdueWorkCount(
            values, generatedAt, financeOnly ? "RECEIVABLE_FOLLOW_UP" : null, workRestrictions));
    metrics.put("receivableEvents", counts.getOrDefault("RECEIVABLE_STATUS", 0L));

    Map<String, List<Map<String, Object>>> charts = new LinkedHashMap<>();
    charts.put("routeDistribution", grouped(values, restrictions, "route_code", "ROUTE_DECISION"));
    charts.put(
        "reservationResults", grouped(values, restrictions, "outcome", "RESERVATION_RESULT"));
    charts.put("fulfillmentSla", grouped(values, restrictions, "outcome", "FULFILLMENT_SLA"));
    charts.put("exceptionStatus", grouped(values, restrictions, "outcome", "EXCEPTION_STATUS"));
    charts.put("receivableStatus", grouped(values, restrictions, "outcome", "RECEIVABLE_STATUS"));

    GenerationRow generationRow = generation(tenantId, generation);
    return new DashboardRecord(
        from, to, generatedAt, generationRow.dataAsOf(), 0L, "UNKNOWN", metrics, charts);
  }

  @Override
  public List<AuditRecord> audit(
      TenantId tenantId,
      AuditFilter filter,
      UUID actorScope,
      Set<String> classifications,
      int pageSize) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, occurred_at, module, action, outcome, subject_type, subject_id, subject_number, actor_type, actor_id, actor_display, previous_state, new_state, safe_reason, correlation_id, causation_id FROM audit_reporting.audit_entry WHERE tenant_id = :tenantId AND classification IN (:classifications)");
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("classifications", classifications)
            .addValue("pageSize", pageSize);
    condition(sql, values, "subject_type", "subjectType", filter.subjectType());
    condition(sql, values, "subject_id", "subjectId", filter.subjectId());
    condition(sql, values, "correlation_id", "correlationId", filter.correlationId());
    condition(
        sql, values, "actor_id", "actorId", actorScope == null ? filter.actorId() : actorScope);
    condition(sql, values, "action", "action", filter.action());
    if (filter.from() != null) {
      sql.append(" AND occurred_at >= :from");
      values.addValue("from", timestamp(filter.from()));
    }
    if (filter.to() != null) {
      sql.append(" AND occurred_at < :to");
      values.addValue("to", timestamp(filter.to()));
    }
    if (filter.beforeOccurredAt() != null && filter.beforeId() != null) {
      sql.append(" AND (occurred_at, id) < (:beforeOccurredAt, :beforeId)");
      values.addValue("beforeOccurredAt", timestamp(filter.beforeOccurredAt()));
      values.addValue("beforeId", filter.beforeId());
    }
    sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :pageSize");
    return jdbc.query(
        sql.toString(),
        values,
        (resultSet, rowNumber) ->
            new AuditRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getString("module"),
                resultSet.getString("action"),
                resultSet.getString("outcome"),
                resultSet.getString("subject_type"),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getString("subject_number"),
                resultSet.getString("actor_type"),
                resultSet.getObject("actor_id", UUID.class),
                resultSet.getString("actor_display"),
                resultSet.getString("previous_state"),
                resultSet.getString("new_state"),
                resultSet.getString("safe_reason"),
                resultSet.getObject("correlation_id", UUID.class),
                resultSet.getObject("causation_id", UUID.class)));
  }

  @Override
  public List<TimelineRecord> timeline(
      TenantId tenantId,
      String subjectType,
      UUID subjectId,
      UUID partnerScope,
      Instant beforeOccurredAt,
      UUID beforeSourceEventId,
      int pageSize) {
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("subjectType", subjectType.toUpperCase())
            .addValue("subjectId", subjectId)
            .addValue("partnerScope", partnerScope)
            .addValue(
                "beforeOccurredAt", beforeOccurredAt == null ? null : timestamp(beforeOccurredAt))
            .addValue("beforeSourceEventId", beforeSourceEventId)
            .addValue("pageSize", pageSize);
    return jdbc.query(
        """
        SELECT timeline.source_event_id, timeline.occurred_at, timeline.event_type,
               timeline.source_module, timeline.subject_type, timeline.subject_id,
               timeline.subject_number, timeline.safe_summary, timeline.actor_type,
               timeline.actor_id, timeline.correlation_id, timeline.causation_id,
               generation.data_as_of
          FROM audit_reporting.timeline_projection timeline
          JOIN audit_reporting.projection_generation generation
            ON generation.tenant_id = timeline.tenant_id
           AND generation.generation = timeline.generation
           AND generation.status = 'ACTIVE'
         WHERE timeline.tenant_id = :tenantId
           AND ((timeline.subject_type = :subjectType AND timeline.subject_id = :subjectId)
             OR (:subjectType IN ('ORDER','TRADE_ORDER') AND timeline.related_order_id = :subjectId)
             OR (:subjectType = 'QUOTATION' AND timeline.related_quotation_id = :subjectId)
             OR (:subjectType = 'PARTNER' AND timeline.related_partner_id = :subjectId))
           AND (CAST(:partnerScope AS uuid) IS NULL OR timeline.related_partner_id = :partnerScope)
           AND timeline.visibility <> 'TECHNICAL'
           AND (
             CAST(:beforeOccurredAt AS timestamptz) IS NULL
             OR (timeline.occurred_at, timeline.source_event_id)
                < (:beforeOccurredAt, CAST(:beforeSourceEventId AS uuid))
           )
         ORDER BY timeline.occurred_at DESC, timeline.source_event_id DESC
         LIMIT :pageSize
        """,
        values,
        (resultSet, rowNumber) ->
            new TimelineRecord(
                resultSet.getObject("source_event_id", UUID.class),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getString("event_type"),
                resultSet.getString("source_module"),
                resultSet.getString("subject_type"),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getString("subject_number"),
                resultSet.getString("safe_summary"),
                resultSet.getString("actor_type"),
                resultSet.getObject("actor_id", UUID.class),
                resultSet.getObject("correlation_id", UUID.class),
                resultSet.getObject("causation_id", UUID.class),
                resultSet.getTimestamp("data_as_of") == null
                    ? null
                    : resultSet.getTimestamp("data_as_of").toInstant()));
  }

  @Override
  public List<WorkItemRecord> workItems(
      TenantId tenantId,
      WorkFilter filter,
      UUID userId,
      Set<String> permissionValues,
      boolean teamScope,
      Instant afterDueAt,
      String afterPriority,
      UUID afterId,
      int pageSize) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT work.id, work.type, work.subject_type, work.subject_id, work.subject_number, work.title, work.safe_summary, work.priority, work.status, work.candidate_role, work.assignee_user_id, work.due_at, work.source_occurred_at, work.completed_at, work.version FROM audit_reporting.work_item_projection work JOIN audit_reporting.projection_generation generation ON generation.tenant_id = work.tenant_id AND generation.generation = work.generation AND generation.status = 'ACTIVE' WHERE work.tenant_id = :tenantId AND work.candidate_permission IN (:permissions)");
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("permissions", permissionValues.isEmpty() ? Set.of("none") : permissionValues)
            .addValue("userId", userId)
            .addValue("pageSize", pageSize);
    if (!teamScope) {
      sql.append(
          " AND (work.assignee_user_id = :userId OR work.assignee_user_id IS NULL OR work.owner_user_id = :userId)");
    }
    inCondition(sql, values, "work.status", "statuses", filter.statuses());
    inCondition(sql, values, "work.priority", "priorities", filter.priorities());
    inCondition(sql, values, "work.type", "types", filter.types());
    if (filter.dueFrom() != null) {
      sql.append(" AND work.due_at >= :dueFrom");
      values.addValue("dueFrom", timestamp(filter.dueFrom()));
    }
    if (filter.dueTo() != null) {
      sql.append(" AND work.due_at < :dueTo");
      values.addValue("dueTo", timestamp(filter.dueTo()));
    }
    if (filter.subjectNumber() != null) {
      sql.append(" AND work.subject_number ILIKE :subjectNumber ESCAPE '\\'");
      values.addValue("subjectNumber", "%" + escapeLike(filter.subjectNumber()) + "%");
    }
    if (afterId != null) {
      values
          .addValue("afterPriorityRank", workPriorityRank(afterPriority))
          .addValue("afterId", afterId);
      if (afterDueAt == null) {
        sql.append(
            " AND work.due_at IS NULL"
                + " AND ("
                + WORK_PRIORITY_RANK
                + " < :afterPriorityRank"
                + " OR ("
                + WORK_PRIORITY_RANK
                + " = :afterPriorityRank AND work.id > :afterId))");
      } else {
        values.addValue("afterDueAt", timestamp(afterDueAt));
        sql.append(
            " AND (work.due_at > :afterDueAt"
                + " OR work.due_at IS NULL"
                + " OR (work.due_at = :afterDueAt"
                + " AND ("
                + WORK_PRIORITY_RANK
                + " < :afterPriorityRank"
                + " OR ("
                + WORK_PRIORITY_RANK
                + " = :afterPriorityRank AND work.id > :afterId))))");
      }
    }
    sql.append(
        " ORDER BY work.due_at ASC NULLS LAST, "
            + WORK_PRIORITY_RANK
            + " DESC, work.id ASC LIMIT :pageSize");
    return jdbc.query(
        sql.toString(),
        values,
        (resultSet, rowNumber) ->
            new WorkItemRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("type"),
                resultSet.getString("subject_type"),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getString("subject_number"),
                resultSet.getString("title"),
                resultSet.getString("safe_summary"),
                resultSet.getString("priority"),
                resultSet.getString("status"),
                resultSet.getString("candidate_role"),
                resultSet.getObject("assignee_user_id", UUID.class),
                instant(resultSet.getTimestamp("due_at")),
                resultSet.getTimestamp("source_occurred_at").toInstant(),
                instant(resultSet.getTimestamp("completed_at")),
                resultSet.getLong("version")));
  }

  private static int workPriorityRank(String priority) {
    return switch (priority) {
      case "CRITICAL" -> 4;
      case "HIGH" -> 3;
      case "MEDIUM" -> 2;
      case "LOW" -> 1;
      default -> throw new IllegalArgumentException("Work item cursor priority is invalid");
    };
  }

  @Override
  public ProjectionCheckpoint projectionCheckpoint(TenantId tenantId) {
    return jdbc.queryForObject(
        """
        WITH active AS (
          SELECT data_as_of, activated_at
            FROM audit_reporting.projection_generation
           WHERE tenant_id = :tenantId AND status = 'ACTIVE'
        ),
        checkpoint AS (
          SELECT last_event_id, last_occurred_at
            FROM audit_reporting.projector_checkpoint
           WHERE tenant_id = :tenantId AND projector_name = :projectorName
        ),
        inbox AS (
          SELECT count(*) FILTER (WHERE status <> 'PROCESSING') AS handled_count,
                 count(*) FILTER (WHERE status = 'PENDING') AS pending_count,
                 count(*) FILTER (WHERE status = 'DEAD_LETTER') AS dead_letter_count,
                 max(processed_at) FILTER (WHERE status = 'PROCESSED')
                   AS last_processed_at
            FROM audit_reporting.projector_inbox
           WHERE tenant_id = :tenantId AND projector_name = :projectorName
        )
        SELECT EXISTS (SELECT 1 FROM active) AS active_generation,
               EXISTS (
                 SELECT 1 FROM audit_reporting.projection_generation
                  WHERE tenant_id = :tenantId AND status = 'STAGING'
               ) AS rebuilding,
               EXISTS (SELECT 1 FROM checkpoint) AS checkpoint_present,
               (SELECT data_as_of FROM active) AS data_as_of,
               (SELECT last_event_id FROM checkpoint) AS last_event_id,
               (SELECT last_occurred_at FROM checkpoint) AS last_occurred_at,
               inbox.handled_count, inbox.pending_count, inbox.dead_letter_count,
               inbox.last_processed_at
          FROM inbox
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("projectorName", "audit-reporting.business.v1"),
        (resultSet, rowNumber) -> {
          UUID eventId = resultSet.getObject("last_event_id", UUID.class);
          Instant occurredAt = instant(resultSet.getTimestamp("last_occurred_at"));
          Watermark processedThrough =
              eventId == null || occurredAt == null ? null : new Watermark(occurredAt, eventId);
          return new ProjectionCheckpoint(
              resultSet.getBoolean("active_generation"),
              resultSet.getBoolean("rebuilding"),
              resultSet.getBoolean("checkpoint_present"),
              instant(resultSet.getTimestamp("data_as_of")),
              processedThrough,
              instant(resultSet.getTimestamp("last_processed_at")),
              resultSet.getLong("handled_count"),
              resultSet.getLong("pending_count"),
              resultSet.getLong("dead_letter_count"));
        });
  }

  @Override
  public long beginRebuild(TenantId tenantId, Instant now) {
    jdbc.queryForObject(
        "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))) acquisition",
        new MapSqlParameterSource("lockKey", "audit-reporting-rebuild:" + tenantId.value()),
        Integer.class);
    Long generation =
        jdbc.queryForObject(
            "SELECT COALESCE(max(generation),0) + 1 FROM audit_reporting.projection_generation WHERE tenant_id = :tenantId",
            new MapSqlParameterSource("tenantId", tenantId.value()),
            Long.class);
    if (generation == null)
      throw new IllegalStateException("Could not allocate projection generation");
    jdbc.update(
        "INSERT INTO audit_reporting.projection_generation (tenant_id,generation,status,schema_version,source_event_count,created_at) VALUES (:tenantId,:generation,'STAGING',:schemaVersion,0,:now)",
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("generation", generation)
            .addValue("schemaVersion", PROJECTION_VERSION)
            .addValue("now", timestamp(now)));
    return generation;
  }

  @Override
  public void activateRebuild(
      TenantId tenantId, long generation, long sourceCount, Instant dataAsOf, Instant now) {
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("generation", generation)
            .addValue("sourceCount", sourceCount)
            .addValue("dataAsOf", dataAsOf == null ? null : timestamp(dataAsOf))
            .addValue("now", timestamp(now));
    jdbc.update(
        "UPDATE audit_reporting.projection_generation SET status = 'RETIRED' WHERE tenant_id = :tenantId AND status = 'ACTIVE'",
        values);
    int updated =
        jdbc.update(
            "UPDATE audit_reporting.projection_generation SET status = 'ACTIVE', source_event_count = :sourceCount, data_as_of = :dataAsOf, activated_at = :now WHERE tenant_id = :tenantId AND generation = :generation AND status = 'STAGING'",
            values);
    if (updated != 1)
      throw new IllegalStateException("Staging projection generation is unavailable");
  }

  private void insertAudit(Projection projection, Instant now) {
    EventDelivery event = projection.delivery();
    jdbc.update(
        """
        INSERT INTO audit_reporting.audit_entry
          (id, tenant_id, source_event_id, occurred_at, module, action, outcome,
           subject_type, subject_id, subject_number, actor_type, actor_id, actor_display,
           correlation_id, causation_id, previous_state, new_state, safe_reason,
           safe_changed_fields, classification, schema_version, entry_hash, created_at)
        VALUES
          (:id, :tenantId, :sourceEventId, :occurredAt, :module, :action, :outcome,
           :subjectType, :subjectId, :subjectNumber, :actorType, :actorId, NULL,
           :correlationId, :causationId, NULL, :newState, :safeReason,
           '[]'::jsonb, :classification, :schemaVersion, :entryHash, :now)
        ON CONFLICT (tenant_id, source_event_id) DO NOTHING
        """,
        new MapSqlParameterSource()
            .addValue(
                "id",
                UUID.nameUUIDFromBytes(
                    ("audit|" + event.eventId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .addValue("tenantId", event.tenantId())
            .addValue("sourceEventId", event.eventId())
            .addValue("occurredAt", timestamp(event.occurredAt()))
            .addValue("module", projection.module())
            .addValue("action", projection.action())
            .addValue("outcome", projection.outcome())
            .addValue("subjectType", event.subject().type())
            .addValue("subjectId", event.subject().id())
            .addValue("subjectNumber", event.subject().number())
            .addValue("actorType", projection.actorType())
            .addValue("actorId", projection.actorId())
            .addValue("correlationId", event.correlationId())
            .addValue("causationId", event.causationId())
            .addValue("newState", projection.state())
            .addValue("safeReason", projection.safeReason())
            .addValue("classification", projection.classification())
            .addValue("schemaVersion", PROJECTION_VERSION)
            .addValue("entryHash", projection.entryHash())
            .addValue("now", timestamp(now)));
  }

  private void upsertSubjectState(MapSqlParameterSource values) {
    jdbc.update(
        """
        INSERT INTO audit_reporting.subject_state_projection
          (tenant_id, generation, subject_type, subject_id, subject_number, state,
           business_version, last_event_id, last_event_type, last_occurred_at, updated_at)
        VALUES
          (:tenantId, :generation, :subjectType, :subjectId, :subjectNumber, :state,
           :businessVersion, :eventId, :eventType, :occurredAt, :now)
        ON CONFLICT (tenant_id, generation, subject_type, subject_id) DO UPDATE
          SET subject_number = EXCLUDED.subject_number,
              state = COALESCE(EXCLUDED.state, audit_reporting.subject_state_projection.state),
              business_version = COALESCE(EXCLUDED.business_version, audit_reporting.subject_state_projection.business_version),
              last_event_id = EXCLUDED.last_event_id,
              last_event_type = EXCLUDED.last_event_type,
              last_occurred_at = EXCLUDED.last_occurred_at,
              updated_at = EXCLUDED.updated_at
        WHERE (EXCLUDED.business_version IS NOT NULL
               AND (audit_reporting.subject_state_projection.business_version IS NULL
                    OR EXCLUDED.business_version > audit_reporting.subject_state_projection.business_version))
           OR (EXCLUDED.business_version IS NULL
               AND (EXCLUDED.last_occurred_at, EXCLUDED.last_event_id)
                   > (audit_reporting.subject_state_projection.last_occurred_at,
                      audit_reporting.subject_state_projection.last_event_id))
           OR (EXCLUDED.business_version = audit_reporting.subject_state_projection.business_version
               AND (EXCLUDED.last_occurred_at, EXCLUDED.last_event_id)
                   > (audit_reporting.subject_state_projection.last_occurred_at,
                      audit_reporting.subject_state_projection.last_event_id))
        """,
        values);
  }

  private void insertMetric(MapSqlParameterSource values, Projection projection) {
    MetricFact metric = projection.metric();
    if (metric == null) return;
    values
        .addValue("metricType", metric.type())
        .addValue("metricOutcome", metric.outcome())
        .addValue("routeCode", metric.routeCode())
        .addValue("ownerId", metric.ownerId())
        .addValue("durationMs", metric.durationMs())
        .addValue("amount", metric.amount())
        .addValue("currency", metric.currency())
        .addValue("attributes", writeJson(metric.attributes()));
    jdbc.update(
        """
        INSERT INTO audit_reporting.metric_fact_projection
          (tenant_id, generation, source_event_id, occurred_at, occurred_date,
           metric_type, outcome, route_code, subject_type, subject_id,
           owner_user_id, duration_ms, amount, currency, attributes)
        VALUES
          (:tenantId, :generation, :eventId, :occurredAt,
           CAST(CAST(:occurredAt AS timestamptz) AT TIME ZONE 'UTC' AS date),
           :metricType, :metricOutcome, :routeCode, :subjectType, :subjectId,
           :ownerId, :durationMs, :amount, :currency, CAST(:attributes AS jsonb))
        ON CONFLICT DO NOTHING
        """,
        values);
  }

  private ProjectionStatus applyWork(long generation, Projection projection, Instant now) {
    WorkChange work = projection.workChange();
    if (work == null) return ProjectionStatus.PROCESSED;
    EventDelivery event = projection.delivery();
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tenantId", event.tenantId())
            .addValue("generation", generation)
            .addValue(
                "id",
                UUID.nameUUIDFromBytes(
                    ("work|" + generation + "|" + work.dependencyKey())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .addValue("dedupKey", work.dependencyKey())
            .addValue("type", work.type())
            .addValue("subjectType", event.subject().type())
            .addValue("subjectId", event.subject().id())
            .addValue("subjectNumber", event.subject().number())
            .addValue("title", work.title())
            .addValue("safeSummary", work.safeSummary())
            .addValue("priority", work.priority())
            .addValue("candidatePermission", work.candidatePermission())
            .addValue("candidateRole", work.candidateRole())
            .addValue("ownerId", work.ownerId())
            .addValue("dueAt", work.dueAt() == null ? null : timestamp(work.dueAt()))
            .addValue("eventId", event.eventId())
            .addValue("occurredAt", timestamp(event.occurredAt()))
            .addValue("now", timestamp(now))
            .addValue(
                "terminalStatus", work.action() == WorkAction.CANCEL ? "CANCELLED" : "COMPLETED");
    if (work.action() == WorkAction.OPEN) {
      jdbc.update(
          """
          INSERT INTO audit_reporting.work_item_projection
            (id, tenant_id, generation, dedup_key, type, subject_type, subject_id,
             subject_number, title, safe_summary, priority, status, candidate_permission,
             candidate_role, assignee_user_id, owner_user_id, due_at, source_event_id,
             source_occurred_at, completed_at, updated_at, version)
          VALUES
            (:id, :tenantId, :generation, :dedupKey, :type, :subjectType, :subjectId,
             :subjectNumber, :title, :safeSummary, :priority, 'OPEN', :candidatePermission,
             :candidateRole, NULL, :ownerId, :dueAt, :eventId,
             :occurredAt, NULL, :now, 0)
          ON CONFLICT (tenant_id, generation, dedup_key) DO NOTHING
          """,
          values);
      reconcilePendingWork(values);
      return ProjectionStatus.PROCESSED;
    }
    int updated =
        jdbc.update(
            """
            UPDATE audit_reporting.work_item_projection
               SET status = :terminalStatus, completed_at = :occurredAt,
                   updated_at = :now, version = version + 1
             WHERE tenant_id = :tenantId AND generation = :generation
               AND dedup_key = :dedupKey
               AND source_occurred_at <= :occurredAt
               AND status IN ('OPEN','CLAIMED')
            """,
            values);
    return updated == 0 ? ProjectionStatus.PENDING : ProjectionStatus.PROCESSED;
  }

  private void reconcilePendingWork(MapSqlParameterSource values) {
    List<PendingResolution> pending =
        jdbc.query(
            """
            SELECT event_id, event_occurred_at, resolution_action
              FROM audit_reporting.projector_inbox
             WHERE tenant_id = :tenantId AND dependency_key = :dedupKey
               AND status = 'PENDING'
             ORDER BY event_occurred_at, event_id
            """,
            values,
            (resultSet, rowNumber) ->
                new PendingResolution(
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getTimestamp("event_occurred_at").toInstant(),
                    resultSet.getString("resolution_action")));
    for (PendingResolution resolution : pending) {
      values
          .addValue("pendingEventId", resolution.eventId())
          .addValue("pendingOccurredAt", timestamp(resolution.occurredAt()))
          .addValue(
              "pendingStatus", "CANCEL".equals(resolution.action()) ? "CANCELLED" : "COMPLETED");
      jdbc.update(
          """
          UPDATE audit_reporting.work_item_projection
             SET status = :pendingStatus, completed_at = :pendingOccurredAt,
                 updated_at = :now, version = version + 1
           WHERE tenant_id = :tenantId AND generation = :generation
             AND dedup_key = :dedupKey AND source_occurred_at <= :pendingOccurredAt
             AND status IN ('OPEN','CLAIMED')
          """,
          values);
      jdbc.update(
          """
          UPDATE audit_reporting.projector_inbox
             SET status = 'PROCESSED', error_code = NULL,
                 processed_at = :now, updated_at = :now
           WHERE tenant_id = :tenantId AND projector_name = 'audit-reporting.business.v1'
             AND event_id = :pendingEventId AND status = 'PENDING'
          """,
          values);
    }
  }

  private void checkpoint(
      EventDelivery delivery,
      String projectorName,
      boolean processed,
      boolean duplicate,
      Instant now) {
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tenantId", delivery.tenantId())
            .addValue("projectorName", projectorName)
            .addValue("projectionVersion", PROJECTION_VERSION)
            .addValue("eventId", delivery.eventId())
            .addValue("occurredAt", timestamp(delivery.occurredAt()))
            .addValue("processedIncrement", processed ? 1 : 0)
            .addValue("duplicateIncrement", duplicate ? 1 : 0)
            .addValue("now", timestamp(now));
    jdbc.update(
        """
        INSERT INTO audit_reporting.projector_checkpoint
          (tenant_id, projector_name, projection_version, last_event_id, last_occurred_at,
           processed_count, duplicate_count, pending_count, dead_letter_count, updated_at)
        VALUES
          (:tenantId, :projectorName, :projectionVersion, :eventId, :occurredAt,
           :processedIncrement, :duplicateIncrement,
           (SELECT count(*) FROM audit_reporting.projector_inbox WHERE tenant_id = :tenantId AND projector_name = :projectorName AND status = 'PENDING'),
           (SELECT count(*) FROM audit_reporting.projector_inbox WHERE tenant_id = :tenantId AND projector_name = :projectorName AND status = 'DEAD_LETTER'), :now)
        ON CONFLICT (tenant_id, projector_name) DO UPDATE
          SET last_event_id = CASE
                WHEN (EXCLUDED.last_occurred_at, EXCLUDED.last_event_id)
                     > (audit_reporting.projector_checkpoint.last_occurred_at,
                        audit_reporting.projector_checkpoint.last_event_id)
                THEN EXCLUDED.last_event_id ELSE audit_reporting.projector_checkpoint.last_event_id END,
              last_occurred_at = GREATEST(audit_reporting.projector_checkpoint.last_occurred_at, EXCLUDED.last_occurred_at),
              processed_count = audit_reporting.projector_checkpoint.processed_count + :processedIncrement,
              duplicate_count = audit_reporting.projector_checkpoint.duplicate_count + :duplicateIncrement,
              pending_count = EXCLUDED.pending_count,
              dead_letter_count = EXCLUDED.dead_letter_count,
              updated_at = :now
        """,
        values);
  }

  private MapSqlParameterSource projectionValues(
      long generation, Projection projection, Instant now) {
    EventDelivery event = projection.delivery();
    return new MapSqlParameterSource()
        .addValue("tenantId", event.tenantId())
        .addValue("generation", generation)
        .addValue("eventId", event.eventId())
        .addValue("occurredAt", timestamp(event.occurredAt()))
        .addValue("eventType", event.eventType())
        .addValue("module", projection.module())
        .addValue("subjectType", event.subject().type())
        .addValue("subjectId", event.subject().id())
        .addValue("subjectNumber", event.subject().number())
        .addValue("relatedOrderId", projection.relatedOrderId())
        .addValue("relatedQuotationId", projection.relatedQuotationId())
        .addValue("relatedPartnerId", projection.relatedPartnerId())
        .addValue("actorType", projection.actorType())
        .addValue("actorId", projection.actorId())
        .addValue("safeSummary", projection.safeSummary())
        .addValue("internalSummary", projection.internalSummary())
        .addValue("visibility", projection.visibility())
        .addValue("correlationId", event.correlationId())
        .addValue("causationId", event.causationId())
        .addValue("state", projection.state())
        .addValue("businessVersion", projection.businessVersion())
        .addValue("now", timestamp(now));
  }

  private MapSqlParameterSource inboxValues(
      EventDelivery delivery, String projectorName, String payloadHash, Instant now) {
    return new MapSqlParameterSource()
        .addValue("tenantId", delivery.tenantId())
        .addValue("projectorName", projectorName)
        .addValue("eventId", delivery.eventId())
        .addValue("eventType", delivery.eventType())
        .addValue("payloadHash", payloadHash)
        .addValue("eventOccurredAt", timestamp(delivery.occurredAt()))
        .addValue("now", timestamp(now));
  }

  private InboxRow existingInbox(String projectorName, UUID eventId) {
    List<InboxRow> rows =
        jdbc.query(
            "SELECT tenant_id,event_type,payload_hash,status FROM audit_reporting.projector_inbox WHERE projector_name = :projectorName AND event_id = :eventId FOR UPDATE",
            new MapSqlParameterSource()
                .addValue("projectorName", projectorName)
                .addValue("eventId", eventId),
            (resultSet, rowNumber) ->
                new InboxRow(
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getString("event_type"),
                    resultSet.getString("payload_hash"),
                    resultSet.getString("status")));
    if (rows.size() != 1) throw new IllegalStateException("Projection inbox uniqueness failed");
    return rows.getFirst();
  }

  private long activeGenerationReadOnly(TenantId tenantId) {
    List<Long> values =
        jdbc.queryForList(
            "SELECT generation FROM audit_reporting.projection_generation WHERE tenant_id = :tenantId AND status = 'ACTIVE'",
            new MapSqlParameterSource("tenantId", tenantId.value()),
            Long.class);
    if (values.isEmpty()) return 0;
    if (values.size() != 1) throw new IllegalStateException("Active projection is not unique");
    return values.getFirst();
  }

  private GenerationRow generation(TenantId tenantId, long generation) {
    return jdbc.queryForObject(
        "SELECT data_as_of,status FROM audit_reporting.projection_generation WHERE tenant_id = :tenantId AND generation = :generation",
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("generation", generation),
        (resultSet, rowNumber) ->
            new GenerationRow(
                resultSet.getTimestamp("data_as_of") == null
                    ? null
                    : resultSet.getTimestamp("data_as_of").toInstant(),
                resultSet.getString("status")));
  }

  private List<Map<String, Object>> grouped(
      MapSqlParameterSource values, String restrictions, String dimension, String metricType) {
    if (!Set.of("route_code", "outcome").contains(dimension))
      throw new IllegalArgumentException("Unsupported metric dimension");
    MapSqlParameterSource parameters =
        new MapSqlParameterSource(values.getValues()).addValue("metricType", metricType);
    return jdbc.query(
        "SELECT COALESCE("
            + dimension
            + ",'UNKNOWN') AS label, count(*) AS value FROM audit_reporting.metric_fact_projection WHERE tenant_id = :tenantId AND generation = :generation AND occurred_date >= :from AND occurred_date < :toExclusive AND metric_type = :metricType"
            + restrictions
            + " GROUP BY "
            + dimension
            + " ORDER BY value DESC, label LIMIT :chartLimit",
        parameters,
        (resultSet, rowNumber) ->
            Map.of("label", resultSet.getString("label"), "value", resultSet.getLong("value")));
  }

  private double quotationCycleSeconds(MapSqlParameterSource values, String restrictions) {
    Double value =
        jdbc.queryForObject(
            "SELECT COALESCE(avg(EXTRACT(EPOCH FROM (accepted_at - created_at))),0) FROM (SELECT subject_id, min(occurred_at) FILTER (WHERE metric_type = 'QUOTATION_CREATED') AS created_at, max(occurred_at) FILTER (WHERE metric_type = 'QUOTATION_ACCEPTED') AS accepted_at FROM audit_reporting.metric_fact_projection WHERE tenant_id = :tenantId AND generation = :generation AND occurred_date >= :from AND occurred_date < :toExclusive"
                + restrictions
                + " GROUP BY subject_id) cycles WHERE created_at IS NOT NULL AND accepted_at IS NOT NULL AND accepted_at >= created_at",
            values,
            Double.class);
    return value == null ? 0D : round(value);
  }

  private long openWorkCount(MapSqlParameterSource values, String type, String workRestrictions) {
    return count(
        "SELECT count(*) FROM audit_reporting.work_item_projection WHERE tenant_id = :tenantId AND generation = :generation AND type = '"
            + type
            + "' AND status IN ('OPEN','CLAIMED')"
            + workRestrictions,
        values);
  }

  private long overdueWorkCount(
      MapSqlParameterSource values, Instant now, String type, String workRestrictions) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource(values.getValues()).addValue("now", timestamp(now));
    return count(
        "SELECT count(*) FROM audit_reporting.work_item_projection WHERE tenant_id = :tenantId AND generation = :generation AND status IN ('OPEN','CLAIMED') AND due_at < :now"
            + (type == null ? "" : " AND type = '" + type + "'")
            + workRestrictions,
        parameters);
  }

  private long count(String sql, MapSqlParameterSource values) {
    Long value = jdbc.queryForObject(sql, values, Long.class);
    return value == null ? 0 : value;
  }

  private static double round(double value) {
    return Math.round(value * 1000D) / 1000D;
  }

  private static void condition(
      StringBuilder sql, MapSqlParameterSource values, String column, String name, Object value) {
    if (value == null) return;
    sql.append(" AND ").append(column).append(" = :").append(name);
    values.addValue(name, value);
  }

  private static void inCondition(
      StringBuilder sql,
      MapSqlParameterSource values,
      String column,
      String name,
      Set<String> entries) {
    if (entries.isEmpty()) return;
    sql.append(" AND ").append(column).append(" IN (:").append(name).append(')');
    values.addValue(name, entries);
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize projection attributes", exception);
    }
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS));
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private record InboxRow(UUID tenantId, String eventType, String payloadHash, String status) {}

  private record PendingResolution(UUID eventId, Instant occurredAt, String action) {}

  private record GenerationRow(Instant dataAsOf, String status) {}
}
