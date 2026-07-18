package com.rom.cellarbridge.fulfillment.internal.infrastructure;

import com.rom.cellarbridge.fulfillment.FulfillmentStatus;
import com.rom.cellarbridge.fulfillment.FulfillmentStepStatus;
import com.rom.cellarbridge.fulfillment.internal.application.FulfillmentPlanStore;
import com.rom.cellarbridge.fulfillment.internal.domain.FulfillmentTemplate;
import com.rom.cellarbridge.fulfillment.internal.domain.FulfillmentTemplate.PlannedWindow;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFulfillmentPlanStore implements FulfillmentPlanStore {

  private static final DateTimeFormatter NUMBER_PERIOD =
      DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
  private static final String SELECT_PLAN =
      """
      SELECT id, tenant_id, number, order_id, order_number, reservation_id, route_code,
             template_code, template_version, status, due_at, correlation_id, causation_id,
             created_at, updated_at, completed_at, version
        FROM fulfillment.fulfillment_plan fp
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcFulfillmentPlanStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<FulfillmentTemplate> effectiveTemplate(String routeCode, Instant at) {
    List<TemplateRoot> roots =
        jdbc.query(
            """
            SELECT id, code, version, route_code, effective_from, effective_to
              FROM fulfillment.template_version
             WHERE route_code = :routeCode
               AND status = 'ACTIVE'
               AND effective_from <= :at
               AND (effective_to IS NULL OR effective_to > :at)
             ORDER BY effective_from DESC
             LIMIT 1
            """,
            new MapSqlParameterSource()
                .addValue("routeCode", routeCode)
                .addValue("at", timestamp(at)),
            (rs, row) ->
                new TemplateRoot(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("version"),
                    rs.getString("route_code"),
                    instant(rs, "effective_from"),
                    nullableInstant(rs, "effective_to")));
    if (roots.isEmpty()) return Optional.empty();
    TemplateRoot root = roots.getFirst();
    Map<UUID, List<String>> dependencies = new HashMap<>();
    jdbc.query(
        """
        SELECT d.step_id, required.code
          FROM fulfillment.template_step_dependency d
          JOIN fulfillment.template_step required
            ON required.template_id = d.template_id
           AND required.id = d.depends_on_step_id
         WHERE d.template_id = :templateId
         ORDER BY required.sequence_number
        """,
        new MapSqlParameterSource("templateId", root.id()),
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                dependencies
                    .computeIfAbsent(
                        rs.getObject("step_id", UUID.class), ignored -> new ArrayList<>())
                    .add(rs.getString("code")));
    List<FulfillmentTemplate.Step> steps =
        jdbc.query(
            """
            SELECT id, code, name, sequence_number, owner_role, planned_duration_minutes,
                   customer_visible, optional, skippable
              FROM fulfillment.template_step
             WHERE template_id = :templateId
             ORDER BY sequence_number
            """,
            new MapSqlParameterSource("templateId", root.id()),
            (rs, row) -> {
              UUID id = rs.getObject("id", UUID.class);
              return new FulfillmentTemplate.Step(
                  id,
                  rs.getString("code"),
                  rs.getString("name"),
                  rs.getInt("sequence_number"),
                  rs.getString("owner_role"),
                  rs.getInt("planned_duration_minutes"),
                  rs.getBoolean("customer_visible"),
                  rs.getBoolean("optional"),
                  rs.getBoolean("skippable"),
                  dependencies.getOrDefault(id, List.of()));
            });
    return Optional.of(
        new FulfillmentTemplate(
            root.id(),
            root.code(),
            root.version(),
            root.routeCode(),
            root.from(),
            root.to(),
            steps));
  }

  @Override
  public String nextNumber(Instant at) {
    Long value =
        jdbc.getJdbcTemplate()
            .queryForObject("SELECT nextval('fulfillment.plan_number_seq')", Long.class);
    if (value == null)
      throw new IllegalStateException("Fulfillment plan sequence returned no value");
    return "FUL-" + NUMBER_PERIOD.format(at) + "-" + "%06d".formatted(value);
  }

  @Override
  public CreateResult create(
      TenantId tenantId,
      UUID planId,
      String number,
      UUID orderId,
      String orderNumber,
      UUID reservationId,
      FulfillmentTemplate template,
      String templateSnapshot,
      Instant createdAt,
      UUID correlationId,
      UUID causationId) {
    Map<String, PlannedWindow> schedule = template.scheduleFrom(createdAt);
    Instant dueAt =
        schedule.values().stream().map(PlannedWindow::dueAt).max(Instant::compareTo).orElseThrow();
    List<UUID> inserted =
        jdbc.query(
            """
            INSERT INTO fulfillment.fulfillment_plan
              (id, tenant_id, number, order_id, order_number, reservation_id, route_code,
               template_code, template_version, template_snapshot, status, due_at,
               correlation_id, causation_id, created_at, updated_at, version)
            VALUES
              (:id, :tenantId, :number, :orderId, :orderNumber, :reservationId, :routeCode,
               :templateCode, :templateVersion, CAST(:snapshot AS jsonb), 'READY', :dueAt,
               :correlationId, :causationId, :createdAt, :createdAt, 0)
            ON CONFLICT DO NOTHING
            RETURNING id
            """,
            new MapSqlParameterSource()
                .addValue("id", planId)
                .addValue("tenantId", tenantId.value())
                .addValue("number", number)
                .addValue("orderId", orderId)
                .addValue("orderNumber", orderNumber)
                .addValue("reservationId", reservationId)
                .addValue("routeCode", template.routeCode())
                .addValue("templateCode", template.code())
                .addValue("templateVersion", template.version())
                .addValue("snapshot", templateSnapshot)
                .addValue("dueAt", timestamp(dueAt))
                .addValue("correlationId", correlationId)
                .addValue("causationId", causationId)
                .addValue("createdAt", timestamp(createdAt)),
            (rs, row) -> rs.getObject("id", UUID.class));
    if (inserted.isEmpty()) {
      Plan existing = findByOrder(tenantId, orderId).orElseThrow();
      return new CreateResult(existing, true);
    }
    Map<String, UUID> stepIds = new HashMap<>();
    for (FulfillmentTemplate.Step definition : template.steps()) {
      UUID stepId = UUID.randomUUID();
      stepIds.put(definition.code(), stepId);
      PlannedWindow window = schedule.get(definition.code());
      jdbc.update(
          """
          INSERT INTO fulfillment.fulfillment_step
            (id, tenant_id, plan_id, code, name, sequence_number, owner_role, status,
             planned_start_at, due_at, customer_visible, optional, skippable, attempt, version)
          VALUES
            (:id, :tenantId, :planId, :code, :name, :sequence, :ownerRole, :status,
             :plannedStart, :dueAt, :customerVisible, :optional, :skippable, 0, 0)
          """,
          new MapSqlParameterSource()
              .addValue("id", stepId)
              .addValue("tenantId", tenantId.value())
              .addValue("planId", planId)
              .addValue("code", definition.code())
              .addValue("name", definition.name())
              .addValue("sequence", definition.sequence())
              .addValue("ownerRole", definition.ownerRole())
              .addValue("status", definition.dependencies().isEmpty() ? "READY" : "BLOCKED")
              .addValue("plannedStart", timestamp(window.plannedStartAt()))
              .addValue("dueAt", timestamp(window.dueAt()))
              .addValue("customerVisible", definition.customerVisible())
              .addValue("optional", definition.optional())
              .addValue("skippable", definition.skippable()));
    }
    for (FulfillmentTemplate.Step definition : template.steps()) {
      for (String required : definition.dependencies()) {
        jdbc.update(
            """
            INSERT INTO fulfillment.fulfillment_step_dependency
              (tenant_id, plan_id, step_id, depends_on_step_id)
            VALUES (:tenantId, :planId, :stepId, :requiredId)
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("planId", planId)
                .addValue("stepId", stepIds.get(definition.code()))
                .addValue("requiredId", stepIds.get(required)));
      }
    }
    return new CreateResult(find(tenantId, planId, false).orElseThrow(), false);
  }

  @Override
  public Optional<Plan> findByOrder(TenantId tenantId, UUID orderId) {
    return queryPlan(
        SELECT_PLAN + " WHERE tenant_id = :tenantId AND order_id = :orderId",
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("orderId", orderId));
  }

  @Override
  public Optional<Plan> find(TenantId tenantId, UUID planId, boolean forUpdate) {
    return queryPlan(
        SELECT_PLAN
            + " WHERE tenant_id = :tenantId AND id = :planId"
            + (forUpdate ? " FOR UPDATE" : ""),
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("planId", planId));
  }

  @Override
  public List<Plan> list(
      TenantId tenantId,
      Set<FulfillmentStatus> statuses,
      Boolean overdue,
      String ownerRole,
      UUID orderId,
      int offset,
      int limit) {
    String sql = SELECT_PLAN + " WHERE tenant_id = :tenantId";
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("offset", offset)
            .addValue("limit", limit);
    if (statuses != null && !statuses.isEmpty()) {
      sql += " AND status IN (:statuses)";
      params.addValue("statuses", statuses.stream().map(Enum::name).toList());
    }
    if (Boolean.TRUE.equals(overdue)) sql += " AND due_at < now() AND status <> 'COMPLETED'";
    if (Boolean.FALSE.equals(overdue)) sql += " AND (due_at >= now() OR status = 'COMPLETED')";
    if (orderId != null) {
      sql += " AND order_id = :orderId";
      params.addValue("orderId", orderId);
    }
    if (ownerRole != null) {
      sql +=
          """
           AND EXISTS (
             SELECT 1
               FROM fulfillment.fulfillment_step owned_step
              WHERE owned_step.tenant_id = fp.tenant_id
                AND owned_step.plan_id = fp.id
                AND owned_step.owner_role = :ownerRole
                AND owned_step.status NOT IN ('COMPLETED', 'SKIPPED', 'CANCELLED'))
          """;
      params.addValue("ownerRole", ownerRole);
    }
    sql += " ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset";
    return jdbc.query(sql, params, (rs, row) -> plan(rs));
  }

  @Override
  public List<Step> steps(TenantId tenantId, UUID planId) {
    Map<UUID, List<String>> dependencies = new HashMap<>();
    jdbc.query(
        """
        SELECT d.step_id, required.code
          FROM fulfillment.fulfillment_step_dependency d
          JOIN fulfillment.fulfillment_step required
            ON required.tenant_id = d.tenant_id AND required.plan_id = d.plan_id
           AND required.id = d.depends_on_step_id
         WHERE d.tenant_id = :tenantId AND d.plan_id = :planId
         ORDER BY required.sequence_number
        """,
        params(tenantId, planId),
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                dependencies
                    .computeIfAbsent(
                        rs.getObject("step_id", UUID.class), ignored -> new ArrayList<>())
                    .add(rs.getString("code")));
    return jdbc.query(
        """
        SELECT id, plan_id, code, name, sequence_number, owner_role, status, overdue_from_status,
               planned_start_at, due_at, started_at, completed_at, failure_code, safe_message,
               customer_visible, optional, skippable, attempt, version
          FROM fulfillment.fulfillment_step
         WHERE tenant_id = :tenantId AND plan_id = :planId
         ORDER BY sequence_number
        """,
        params(tenantId, planId),
        (rs, row) ->
            step(rs, dependencies.getOrDefault(rs.getObject("id", UUID.class), List.of())));
  }

  @Override
  public List<Milestone> milestones(TenantId tenantId, UUID planId) {
    return jdbc.query(
        """
        SELECT code, label, occurred_at, customer_visible
          FROM fulfillment.milestone
         WHERE tenant_id = :tenantId AND plan_id = :planId
         ORDER BY occurred_at, id
        """,
        params(tenantId, planId),
        (rs, row) ->
            new Milestone(
                rs.getString("code"),
                rs.getString("label"),
                instant(rs, "occurred_at"),
                rs.getBoolean("customer_visible")));
  }

  @Override
  public Optional<Command> command(TenantId tenantId, UUID planId, String keyHash) {
    List<Command> rows =
        jdbc.query(
            """
            SELECT id, step_id, action, request_hash, result_snapshot::text
              FROM fulfillment.step_action_command
             WHERE tenant_id = :tenantId AND plan_id = :planId AND key_hash = :keyHash
            """,
            params(tenantId, planId).addValue("keyHash", keyHash),
            (rs, row) -> command(rs, false));
    return rows.stream().findFirst();
  }

  @Override
  public Command claim(
      TenantId tenantId,
      UUID planId,
      UUID stepId,
      String action,
      String keyHash,
      String requestHash,
      UUID actorId,
      Instant at) {
    UUID id = UUID.randomUUID();
    List<Command> created =
        jdbc.query(
            """
            INSERT INTO fulfillment.step_action_command
              (id, tenant_id, plan_id, step_id, action, key_hash, request_hash, actor_id, created_at)
            VALUES (:id, :tenantId, :planId, :stepId, :action, :keyHash, :requestHash, :actorId, :at)
            ON CONFLICT DO NOTHING
            RETURNING id, step_id, action, request_hash, result_snapshot::text
            """,
            params(tenantId, planId)
                .addValue("id", id)
                .addValue("stepId", stepId)
                .addValue("action", action)
                .addValue("keyHash", keyHash)
                .addValue("requestHash", requestHash)
                .addValue("actorId", actorId)
                .addValue("at", timestamp(at)),
            (rs, row) -> command(rs, true));
    return created.isEmpty()
        ? command(tenantId, planId, keyHash).orElseThrow()
        : created.getFirst();
  }

  @Override
  public void completeCommand(TenantId tenantId, UUID commandId, String resultJson, Instant at) {
    int updated =
        jdbc.update(
            """
            UPDATE fulfillment.step_action_command
               SET result_snapshot = CAST(:result AS jsonb), completed_at = :at
             WHERE tenant_id = :tenantId AND id = :commandId AND result_snapshot IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("commandId", commandId)
                .addValue("result", resultJson)
                .addValue("at", timestamp(at)));
    if (updated != 1) throw new IllegalStateException("Fulfillment command completion conflict");
  }

  @Override
  public void updateStep(
      TenantId tenantId,
      Step before,
      FulfillmentStepStatus status,
      FulfillmentStepStatus overdueFrom,
      Instant startedAt,
      Instant completedAt,
      String failureCode,
      String safeMessage,
      int attempt) {
    int updated =
        jdbc.update(
            """
            UPDATE fulfillment.fulfillment_step
               SET status = :status, overdue_from_status = :overdueFrom,
                   started_at = :startedAt, completed_at = :completedAt,
                   failure_code = :failureCode, safe_message = :safeMessage,
                   attempt = :attempt, version = version + 1
             WHERE tenant_id = :tenantId AND plan_id = :planId AND id = :stepId
               AND version = :version
            """,
            params(tenantId, before.planId())
                .addValue("stepId", before.id())
                .addValue("status", status.name())
                .addValue("overdueFrom", overdueFrom == null ? null : overdueFrom.name())
                .addValue("startedAt", nullableTimestamp(startedAt))
                .addValue("completedAt", nullableTimestamp(completedAt))
                .addValue("failureCode", failureCode)
                .addValue("safeMessage", safeMessage)
                .addValue("attempt", attempt)
                .addValue("version", before.version()));
    if (updated != 1) throw new FulfillmentStorageConflict();
  }

  @Override
  public void updatePlan(
      TenantId tenantId, Plan before, FulfillmentStatus status, Instant completedAt, Instant at) {
    int updated =
        jdbc.update(
            """
            UPDATE fulfillment.fulfillment_plan
               SET status = :status, completed_at = :completedAt, updated_at = :at,
                   version = version + 1
             WHERE tenant_id = :tenantId AND id = :planId AND version = :version
            """,
            params(tenantId, before.id())
                .addValue("status", status.name())
                .addValue("completedAt", nullableTimestamp(completedAt))
                .addValue("at", timestamp(at))
                .addValue("version", before.version()));
    if (updated != 1) throw new FulfillmentStorageConflict();
  }

  @Override
  public void addMilestone(TenantId tenantId, UUID planId, Step step, Instant at) {
    jdbc.update(
        """
        INSERT INTO fulfillment.milestone
          (id, tenant_id, plan_id, step_id, code, label, occurred_at, customer_visible)
        VALUES (:id, :tenantId, :planId, :stepId, :code, :label, :at, :customerVisible)
        ON CONFLICT DO NOTHING
        """,
        params(tenantId, planId)
            .addValue("id", UUID.randomUUID())
            .addValue("stepId", step.id())
            .addValue("code", step.code())
            .addValue("label", step.name())
            .addValue("at", timestamp(at))
            .addValue("customerVisible", step.customerVisible()));
  }

  @Override
  public void unlockReadySteps(TenantId tenantId, UUID planId) {
    jdbc.update(
        """
        UPDATE fulfillment.fulfillment_step candidate
           SET status = 'READY', version = version + 1
         WHERE candidate.tenant_id = :tenantId AND candidate.plan_id = :planId
           AND candidate.status = 'BLOCKED'
           AND NOT EXISTS (
             SELECT 1
               FROM fulfillment.fulfillment_step_dependency dependency
               JOIN fulfillment.fulfillment_step required
                 ON required.tenant_id = dependency.tenant_id
                AND required.plan_id = dependency.plan_id
                AND required.id = dependency.depends_on_step_id
              WHERE dependency.tenant_id = candidate.tenant_id
                AND dependency.plan_id = candidate.plan_id
                AND dependency.step_id = candidate.id
                AND required.status NOT IN ('COMPLETED', 'SKIPPED'))
        """,
        params(tenantId, planId));
  }

  @Override
  public AdapterAttempt recordAdapter(
      TenantId tenantId,
      UUID planId,
      UUID stepId,
      UUID commandId,
      String scenario,
      String outcome,
      Instant at) {
    String reference = "SIM-" + commandId;
    jdbc.update(
        """
        INSERT INTO fulfillment.simulated_adapter_attempt
          (id, tenant_id, plan_id, step_id, command_id, scenario, outcome, reference, occurred_at)
        VALUES (:id, :tenantId, :planId, :stepId, :commandId, :scenario, :outcome, :reference, :at)
        ON CONFLICT DO NOTHING
        """,
        params(tenantId, planId)
            .addValue("id", UUID.randomUUID())
            .addValue("stepId", stepId)
            .addValue("commandId", commandId)
            .addValue("scenario", scenario)
            .addValue("outcome", outcome)
            .addValue("reference", reference)
            .addValue("at", timestamp(at)));
    return adapterByCommand(tenantId, commandId).orElseThrow();
  }

  @Override
  public Optional<AdapterAttempt> adapter(TenantId tenantId, UUID stepId) {
    List<AdapterAttempt> rows =
        jdbc.query(
            """
            SELECT scenario, outcome, reference, occurred_at
              FROM fulfillment.simulated_adapter_attempt
             WHERE tenant_id = :tenantId AND step_id = :stepId
             ORDER BY occurred_at DESC, id DESC LIMIT 1
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("stepId", stepId),
            (rs, row) -> adapter(rs));
    return rows.stream().findFirst();
  }

  @Override
  public List<OverdueCandidate> overdueCandidates(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT step.tenant_id, step.plan_id, step.id
          FROM fulfillment.fulfillment_step step
          JOIN fulfillment.fulfillment_plan plan
            ON plan.tenant_id = step.tenant_id AND plan.id = step.plan_id
         WHERE step.status IN ('READY', 'IN_PROGRESS') AND step.due_at < :now
           AND plan.status NOT IN ('COMPLETED', 'CANCELLED')
         ORDER BY step.due_at, step.id LIMIT :limit
        """,
        new MapSqlParameterSource().addValue("now", timestamp(now)).addValue("limit", limit),
        (rs, row) ->
            new OverdueCandidate(
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("plan_id", UUID.class),
                rs.getObject("id", UUID.class)));
  }

  private Optional<AdapterAttempt> adapterByCommand(TenantId tenantId, UUID commandId) {
    List<AdapterAttempt> rows =
        jdbc.query(
            """
            SELECT scenario, outcome, reference, occurred_at
              FROM fulfillment.simulated_adapter_attempt
             WHERE tenant_id = :tenantId AND command_id = :commandId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("commandId", commandId),
            (rs, row) -> adapter(rs));
    return rows.stream().findFirst();
  }

  private Optional<Plan> queryPlan(String sql, MapSqlParameterSource parameters) {
    List<Plan> rows = jdbc.query(sql, parameters, (rs, row) -> plan(rs));
    if (rows.size() > 1) throw new IllegalStateException("Fulfillment plan identity is ambiguous");
    return rows.stream().findFirst();
  }

  private static Plan plan(ResultSet rs) throws SQLException {
    return new Plan(
        rs.getObject("id", UUID.class),
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getString("number"),
        rs.getObject("order_id", UUID.class),
        rs.getString("order_number"),
        rs.getObject("reservation_id", UUID.class),
        rs.getString("route_code"),
        rs.getString("template_code"),
        rs.getString("template_version"),
        FulfillmentStatus.valueOf(rs.getString("status")),
        instant(rs, "due_at"),
        rs.getObject("correlation_id", UUID.class),
        rs.getObject("causation_id", UUID.class),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        nullableInstant(rs, "completed_at"),
        rs.getLong("version"));
  }

  private static Step step(ResultSet rs, List<String> dependencies) throws SQLException {
    String overdue = rs.getString("overdue_from_status");
    return new Step(
        rs.getObject("id", UUID.class),
        rs.getObject("plan_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getInt("sequence_number"),
        rs.getString("owner_role"),
        FulfillmentStepStatus.valueOf(rs.getString("status")),
        overdue == null ? null : FulfillmentStepStatus.valueOf(overdue),
        dependencies,
        instant(rs, "planned_start_at"),
        instant(rs, "due_at"),
        nullableInstant(rs, "started_at"),
        nullableInstant(rs, "completed_at"),
        rs.getString("failure_code"),
        rs.getString("safe_message"),
        rs.getBoolean("customer_visible"),
        rs.getBoolean("optional"),
        rs.getBoolean("skippable"),
        rs.getInt("attempt"),
        rs.getLong("version"));
  }

  private static Command command(ResultSet rs, boolean created) throws SQLException {
    return new Command(
        rs.getObject("id", UUID.class),
        rs.getObject("step_id", UUID.class),
        rs.getString("action"),
        rs.getString("request_hash"),
        rs.getString("result_snapshot"),
        created);
  }

  private static AdapterAttempt adapter(ResultSet rs) throws SQLException {
    return new AdapterAttempt(
        rs.getString("scenario"),
        rs.getString("outcome"),
        rs.getString("reference"),
        instant(rs, "occurred_at"));
  }

  private static MapSqlParameterSource params(TenantId tenantId, UUID planId) {
    return new MapSqlParameterSource()
        .addValue("tenantId", tenantId.value())
        .addValue("planId", planId);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(value);
  }

  private static Timestamp nullableTimestamp(Instant value) {
    return value == null ? null : timestamp(value);
  }

  private record TemplateRoot(
      UUID id, String code, String version, String routeCode, Instant from, Instant to) {}

  static final class FulfillmentStorageConflict extends RuntimeException {}
}
