package com.rom.cellarbridge.tradeorder.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.quotation.QuotationSnapshotHashV1;
import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.TradeOrderSupplyDecisionStatus;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.CursorPosition;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.OrderPage;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.ReservationOutcomeEvidence;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderRepository.TimelineEntry;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.CommercialSnapshot;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Customer;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.DeliveryAddress;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Line;
import com.rom.cellarbridge.tradeorder.internal.domain.TradeOrder.Route;
import com.rom.cellarbridge.tradeplanning.SupplyAllocationMode;
import com.rom.cellarbridge.tradeplanning.SupplyDecisionSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcTradeOrderRepository implements TradeOrderRepository {

  private static final DateTimeFormatter NUMBER_PERIOD =
      DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
  private static final Pattern OUTCOME_EVIDENCE =
      Pattern.compile("^Inventory reservation outcome evidence: ([0-9a-f]{64})$");
  private static final String SELECT_ORDER =
      """
      SELECT id, tenant_id, number, source_quotation_id, source_revision_id,
             source_quotation_number, source_revision_number, source_event_id,
             acceptance_id, accepted_at, partner_id, partner_number,
             source_owner_id, partner_display_name, partner_source_version, status, currency,
             total_amount, payment_term_days, route_code, route_policy_version,
             route_estimated_delivery_date, accepted_terms_version,
             requested_delivery_date, delivery_address::text,
             snapshot_schema_version, snapshot_hash,
             supply_decision_status, supply_decision_schema_version,
             supply_decision_policy_version, supply_decision_at,
             supply_decision_hash, supply_decision_snapshot::text, created_event_id,
             correlation_id, causation_id, created_at, updated_at, version
        FROM trade_order.trade_order
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper jsonMapper;

  public JdbcTradeOrderRepository(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public String nextNumber(TenantId tenantId, Instant now) {
    Long value =
        jdbc.getJdbcTemplate()
            .queryForObject("SELECT nextval('trade_order.order_number_seq')", Long.class);
    if (value == null) {
      throw new IllegalStateException("Trade Order number sequence returned no value");
    }
    return "ORD-" + NUMBER_PERIOD.format(now) + "-" + "%06d".formatted(value);
  }

  @Override
  public boolean insertIfAbsent(TenantId tenantId, TradeOrder order, UUID actorId) {
    requireTenant(tenantId, order.tenantId());
    if (!QuotationSnapshotHashV1.isCurrentFormat(order.snapshotHash())) {
      throw new IllegalArgumentException("New order snapshot hash must use current bare V1 format");
    }
    List<UUID> inserted =
        jdbc.query(
            """
            INSERT INTO trade_order.trade_order
              (id, tenant_id, number, source_quotation_id, source_revision_id,
               source_quotation_number, source_revision_number, source_event_id,
               acceptance_id, accepted_at, partner_id, partner_number,
               source_owner_id, partner_display_name, partner_source_version, status, currency,
               total_amount, payment_term_days, route_code, route_policy_version,
               route_estimated_delivery_date, accepted_terms_version,
               requested_delivery_date, delivery_address, commercial_snapshot,
               snapshot_schema_version, snapshot_hash, supply_decision_status,
               supply_decision_schema_version, supply_decision_policy_version,
               supply_decision_at, supply_decision_hash, supply_decision_snapshot,
               created_event_id,
               correlation_id, causation_id, created_at, created_by, updated_at,
               updated_by, version)
            VALUES
              (:id, :tenantId, :number, :quotationId, :revisionId,
               :quotationNumber, :revision, :sourceEventId, :acceptanceId,
               :acceptedAt, :partnerId, :partnerNumber, :sourceOwnerId,
               :partnerName, :partnerVersion, :status, :currency, :totalAmount, :paymentTermDays,
               :routeCode, :routePolicyVersion, :estimatedDeliveryDate,
               :acceptedTermsVersion, :requestedDeliveryDate,
               CAST(:deliveryAddress AS jsonb), CAST(:commercialSnapshot AS jsonb),
               :snapshotSchemaVersion, :snapshotHash, :supplyDecisionStatus,
               :supplyDecisionSchemaVersion, :supplyDecisionPolicyVersion,
               :supplyDecisionAt, :supplyDecisionHash,
               CAST(:supplyDecisionSnapshot AS jsonb), :createdEventId,
               :correlationId, :causationId, :createdAt, :actorId, :createdAt,
               :actorId, 0)
            ON CONFLICT DO NOTHING
            RETURNING id
            """,
            orderParameters(order, actorId),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    if (inserted.isEmpty()) {
      return false;
    }
    int lineNumber = 0;
    for (Line line : order.commercialSnapshot().lines()) {
      insertLine(order, line, ++lineNumber, actorId);
    }
    insertCreatedTimeline(order, actorId);
    return true;
  }

  @Override
  public Optional<TradeOrder> findBySourceQuotation(TenantId tenantId, UUID sourceQuotationId) {
    return queryOne(
        SELECT_ORDER + " WHERE tenant_id = :tenantId AND source_quotation_id = :sourceQuotationId",
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("sourceQuotationId", sourceQuotationId));
  }

  @Override
  public Optional<TradeOrder> find(
      TenantId tenantId, UUID orderId, UUID partnerScope, UUID ownerScope) {
    String sql = SELECT_ORDER + " WHERE tenant_id = :tenantId AND id = :orderId";
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("orderId", orderId);
    if (partnerScope != null) {
      sql += " AND partner_id = :partnerId";
      parameters.addValue("partnerId", partnerScope);
    }
    if (ownerScope != null) {
      sql += " AND source_owner_id = :ownerId";
      parameters.addValue("ownerId", ownerScope);
    }
    return queryOne(sql, parameters);
  }

  @Override
  public Optional<TradeOrder> findForUpdate(TenantId tenantId, UUID orderId) {
    return queryOne(
        SELECT_ORDER + " WHERE tenant_id = :tenantId AND id = :orderId FOR UPDATE",
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("orderId", orderId));
  }

  @Override
  public Optional<ReservationOutcomeEvidence> findReservationOutcome(
      TenantId tenantId, UUID orderId) {
    List<ReservationOutcomeEvidence> rows =
        jdbc.query(
            """
            SELECT event_id, event_type, status, code, message, occurred_at
              FROM trade_order.timeline_entry
             WHERE tenant_id = :tenantId
               AND order_id = :orderId
               AND event_type IN
                   ('cellarbridge.inventory.reservation-confirmed.v1',
                    'cellarbridge.inventory.reservation-failed.v1')
             ORDER BY occurred_at, id
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("orderId", orderId),
            (resultSet, rowNumber) -> {
              Matcher evidence = OUTCOME_EVIDENCE.matcher(resultSet.getString("message"));
              if (!evidence.matches()) {
                throw new IllegalStateException("Stored reservation outcome evidence is invalid");
              }
              return new ReservationOutcomeEvidence(
                  resultSet.getObject("event_id", UUID.class),
                  resultSet.getString("event_type"),
                  TradeOrderStatus.valueOf(resultSet.getString("status")),
                  resultSet.getString("code"),
                  evidence.group(1),
                  instant(resultSet, "occurred_at"));
            });
    if (rows.size() > 1) {
      throw new IllegalStateException("Trade Order contains conflicting reservation outcomes");
    }
    return rows.stream().findFirst();
  }

  @Override
  public void saveReservationOutcome(
      TenantId tenantId,
      TradeOrder before,
      TradeOrder after,
      ReservationOutcomeEvidence outcome,
      UUID actorId) {
    requireTenant(tenantId, before.tenantId());
    requireTenant(tenantId, after.tenantId());
    if (!before.id().equals(after.id())
        || before.version() + 1 != after.version()
        || outcome.status() != after.status()
        || !outcome.occurredAt().equals(after.updatedAt())) {
      throw new IllegalArgumentException("Reservation outcome does not match the order transition");
    }
    int updated =
        jdbc.update(
            """
            UPDATE trade_order.trade_order
               SET status = :status,
                   updated_at = :occurredAt,
                   updated_by = :actorId,
                   version = :newVersion
             WHERE tenant_id = :tenantId
               AND id = :orderId
               AND status = :previousStatus
               AND version = :previousVersion
            """,
            new MapSqlParameterSource()
                .addValue("status", after.status().name())
                .addValue("occurredAt", timestamp(after.updatedAt()))
                .addValue("actorId", actorId)
                .addValue("newVersion", after.version())
                .addValue("tenantId", tenantId.value())
                .addValue("orderId", before.id())
                .addValue("previousStatus", before.status().name())
                .addValue("previousVersion", before.version()));
    if (updated != 1) {
      throw new IllegalStateException("Trade Order reservation transition lost its row lock");
    }
    jdbc.update(
        """
        INSERT INTO trade_order.timeline_entry
          (id, tenant_id, order_id, event_id, event_type, status, code,
           message, visibility, occurred_at, created_at, created_by,
           updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :orderId, :eventId, :eventType, :status, :code,
           :message, 'INTERNAL', :occurredAt, :occurredAt, :actorId,
           :occurredAt, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("tenantId", tenantId.value())
            .addValue("orderId", before.id())
            .addValue("eventId", outcome.eventId())
            .addValue("eventType", outcome.eventType())
            .addValue("status", outcome.status().name())
            .addValue("code", outcome.reasonCode())
            .addValue(
                "message", "Inventory reservation outcome evidence: " + outcome.evidenceHash())
            .addValue("occurredAt", timestamp(outcome.occurredAt()))
            .addValue("actorId", actorId));
  }

  @Override
  public OrderPage list(
      TenantId tenantId,
      Set<TradeOrderStatus> statuses,
      UUID partnerFilter,
      UUID ownerFilter,
      CursorPosition after,
      int limit) {
    StringBuilder sql = new StringBuilder(SELECT_ORDER).append(" WHERE tenant_id = :tenantId");
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("limit", limit + 1);
    if (statuses != null && !statuses.isEmpty()) {
      sql.append(" AND status IN (:statuses)");
      parameters.addValue("statuses", statuses.stream().map(Enum::name).toList());
    }
    if (partnerFilter != null) {
      sql.append(" AND partner_id = :partnerId");
      parameters.addValue("partnerId", partnerFilter);
    }
    if (ownerFilter != null) {
      sql.append(" AND source_owner_id = :ownerId");
      parameters.addValue("ownerId", ownerFilter);
    }
    if (after != null) {
      sql.append(" AND (created_at, id) < (:afterCreatedAt, :afterId)");
      parameters
          .addValue("afterCreatedAt", timestamp(after.createdAt()))
          .addValue("afterId", after.id());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
    List<TradeOrder> rows =
        jdbc.query(sql.toString(), parameters, (resultSet, rowNumber) -> mapOrder(resultSet));
    boolean hasNext = rows.size() > limit;
    List<TradeOrder> items = hasNext ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
    CursorPosition next =
        hasNext ? new CursorPosition(items.getLast().createdAt(), items.getLast().id()) : null;
    return new OrderPage(items, next, hasNext);
  }

  @Override
  public List<TimelineEntry> timeline(TenantId tenantId, UUID orderId, boolean customerOnly) {
    String sql =
        """
        SELECT id, occurred_at, code, status, message, visibility
          FROM trade_order.timeline_entry
         WHERE tenant_id = :tenantId AND order_id = :orderId
        """;
    if (customerOnly) {
      sql += " AND visibility = 'CUSTOMER'";
    }
    sql += " ORDER BY occurred_at, id";
    return jdbc.query(
        sql,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("orderId", orderId),
        (resultSet, rowNumber) ->
            new TimelineEntry(
                resultSet.getObject("id", UUID.class),
                instant(resultSet, "occurred_at"),
                resultSet.getString("code"),
                null,
                TradeOrderStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("message"),
                resultSet.getString("visibility")));
  }

  private Optional<TradeOrder> queryOne(String sql, MapSqlParameterSource parameters) {
    return jdbc.query(sql, parameters, (resultSet, rowNumber) -> mapOrder(resultSet)).stream()
        .findFirst();
  }

  private TradeOrder mapOrder(ResultSet resultSet) throws SQLException {
    TenantId tenantId = new TenantId(resultSet.getObject("tenant_id", UUID.class));
    UUID orderId = resultSet.getObject("id", UUID.class);
    List<Line> lines = lines(tenantId, orderId);
    DeliveryAddress address = readAddress(resultSet.getString("delivery_address"));
    CommercialSnapshot snapshot =
        new CommercialSnapshot(
            Integer.parseInt(resultSet.getString("snapshot_schema_version")),
            new Customer(
                resultSet.getObject("partner_id", UUID.class),
                resultSet.getString("partner_number"),
                resultSet.getString("partner_display_name"),
                resultSet.getInt("partner_source_version")),
            resultSet.getString("currency"),
            resultSet.getBigDecimal("total_amount"),
            resultSet.getInt("payment_term_days"),
            new Route(
                resultSet.getString("route_code"),
                resultSet.getString("route_policy_version"),
                resultSet.getObject("route_estimated_delivery_date", java.time.LocalDate.class)),
            resultSet.getString("accepted_terms_version"),
            resultSet.getObject("requested_delivery_date", java.time.LocalDate.class),
            address,
            lines);
    TradeOrderSupplyDecisionStatus decisionStatus =
        TradeOrderSupplyDecisionStatus.valueOf(resultSet.getString("supply_decision_status"));
    SupplyDecisionSnapshot supplyDecision =
        readSupplyDecision(resultSet, decisionStatus, snapshot.route().code(), lines);
    return new TradeOrder(
        orderId,
        tenantId,
        resultSet.getString("number"),
        resultSet.getObject("source_quotation_id", UUID.class),
        resultSet.getObject("source_revision_id", UUID.class),
        resultSet.getString("source_quotation_number"),
        resultSet.getInt("source_revision_number"),
        resultSet.getObject("source_event_id", UUID.class),
        resultSet.getObject("acceptance_id", UUID.class),
        instant(resultSet, "accepted_at"),
        resultSet.getObject("source_owner_id", UUID.class),
        TradeOrderStatus.valueOf(resultSet.getString("status")),
        decisionStatus,
        supplyDecision,
        snapshot,
        resultSet.getString("snapshot_hash"),
        resultSet.getObject("correlation_id", UUID.class),
        resultSet.getObject("causation_id", UUID.class),
        resultSet.getObject("created_event_id", UUID.class),
        instant(resultSet, "created_at"),
        instant(resultSet, "updated_at"),
        resultSet.getLong("version"));
  }

  private List<Line> lines(TenantId tenantId, UUID orderId) {
    return jdbc.query(
        """
        SELECT id, source_quotation_line_id, sku_id, sku_code, description,
               quantity, quantity_unit, net_unit_price, line_total,
               supply_pool_id, allocation_mode, supply_type
          FROM trade_order.order_line
         WHERE tenant_id = :tenantId AND order_id = :orderId
         ORDER BY line_number, id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("orderId", orderId),
        (resultSet, rowNumber) ->
            new Line(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("source_quotation_line_id", UUID.class),
                resultSet.getObject("sku_id", UUID.class),
                resultSet.getString("sku_code"),
                resultSet.getString("description"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getString("quantity_unit"),
                resultSet.getBigDecimal("net_unit_price"),
                resultSet.getBigDecimal("line_total"),
                resultSet.getObject("supply_pool_id", UUID.class),
                allocationMode(resultSet.getString("allocation_mode")),
                resultSet.getString("supply_type")));
  }

  private void insertLine(TradeOrder order, Line line, int lineNumber, UUID actorId) {
    jdbc.update(
        """
        INSERT INTO trade_order.order_line
          (id, tenant_id, order_id, source_quotation_line_id, line_number,
           sku_id, sku_code, description, quantity, quantity_unit, currency,
           net_unit_price, line_total, supply_pool_id, supply_type,
           allocation_mode,
           created_at, created_by, updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :orderId, :sourceLineId, :lineNumber,
           :skuId, :skuCode, :description, :quantity, :unit, :currency,
           :unitPrice, :lineTotal, :supplyPoolId, :supplyType,
           :allocationMode,
           :createdAt, :actorId, :createdAt, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("id", line.id())
            .addValue("tenantId", order.tenantId().value())
            .addValue("orderId", order.id())
            .addValue("sourceLineId", line.sourceQuotationLineId())
            .addValue("lineNumber", lineNumber)
            .addValue("skuId", line.skuId())
            .addValue("skuCode", line.skuCode())
            .addValue("description", line.description())
            .addValue("quantity", line.quantity())
            .addValue("unit", line.unit())
            .addValue("currency", order.commercialSnapshot().currency())
            .addValue("unitPrice", line.netUnitPrice())
            .addValue("lineTotal", line.lineTotal())
            .addValue("supplyPoolId", line.supplyPoolId())
            .addValue("supplyType", line.supplyType())
            .addValue(
                "allocationMode",
                line.allocationMode() == null ? null : line.allocationMode().name())
            .addValue("createdAt", timestamp(order.createdAt()))
            .addValue("actorId", actorId));
  }

  private void insertCreatedTimeline(TradeOrder order, UUID actorId) {
    jdbc.update(
        """
        INSERT INTO trade_order.timeline_entry
          (id, tenant_id, order_id, event_id, event_type, status, code,
           message, visibility, occurred_at, created_at, created_by,
           updated_at, updated_by, version)
        VALUES
          (:id, :tenantId, :orderId, :eventId, :eventType, :status,
           'ORDER_CREATED', 'Order created from accepted quotation', 'CUSTOMER',
           :createdAt, :createdAt, :actorId, :createdAt, :actorId, 0)
        """,
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("tenantId", order.tenantId().value())
            .addValue("orderId", order.id())
            .addValue("eventId", order.createdEventId())
            .addValue("eventType", "cellarbridge.order.created.v1")
            .addValue("status", order.status().name())
            .addValue("createdAt", timestamp(order.createdAt()))
            .addValue("actorId", actorId));
  }

  private MapSqlParameterSource orderParameters(TradeOrder order, UUID actorId) {
    CommercialSnapshot snapshot = order.commercialSnapshot();
    Customer customer = snapshot.customer();
    Route route = snapshot.route();
    return new MapSqlParameterSource()
        .addValue("id", order.id())
        .addValue("tenantId", order.tenantId().value())
        .addValue("number", order.number())
        .addValue("quotationId", order.sourceQuotationId())
        .addValue("revisionId", order.sourceRevisionId())
        .addValue("quotationNumber", order.sourceQuotationNumber())
        .addValue("revision", order.sourceRevision())
        .addValue("sourceEventId", order.sourceEventId())
        .addValue("acceptanceId", order.acceptanceId())
        .addValue("acceptedAt", timestamp(order.acceptedAt()))
        .addValue("sourceOwnerId", order.sourceOwnerId())
        .addValue("partnerId", customer.partnerId())
        .addValue("partnerNumber", customer.partnerNumber())
        .addValue("partnerName", customer.displayName())
        .addValue("partnerVersion", customer.sourceVersion())
        .addValue("status", order.status().name())
        .addValue("currency", snapshot.currency())
        .addValue("totalAmount", snapshot.totalAmount())
        .addValue("paymentTermDays", snapshot.paymentTermDays())
        .addValue("routeCode", route.code())
        .addValue("routePolicyVersion", route.policyVersion())
        .addValue("estimatedDeliveryDate", route.estimatedDeliveryDate())
        .addValue("acceptedTermsVersion", snapshot.acceptedTermsVersion())
        .addValue("requestedDeliveryDate", snapshot.requestedDeliveryDate())
        .addValue("deliveryAddress", json(snapshot.deliveryAddress()))
        .addValue("commercialSnapshot", json(snapshot))
        .addValue("snapshotSchemaVersion", Integer.toString(snapshot.schemaVersion()))
        .addValue("snapshotHash", order.snapshotHash())
        .addValue("supplyDecisionStatus", order.supplyDecisionStatus().name())
        .addValue(
            "supplyDecisionSchemaVersion",
            order.supplyDecision() == null ? null : order.supplyDecision().schemaVersion())
        .addValue(
            "supplyDecisionPolicyVersion",
            order.supplyDecision() == null ? null : order.supplyDecision().policyVersion())
        .addValue(
            "supplyDecisionAt",
            order.supplyDecision() == null ? null : timestamp(order.supplyDecision().decidedAt()))
        .addValue(
            "supplyDecisionHash",
            order.supplyDecision() == null ? null : order.supplyDecision().decisionHash())
        .addValue(
            "supplyDecisionSnapshot",
            order.supplyDecision() == null ? null : json(order.supplyDecision()))
        .addValue("createdEventId", order.createdEventId())
        .addValue("correlationId", order.correlationId())
        .addValue("causationId", order.causationId())
        .addValue("createdAt", timestamp(order.createdAt()))
        .addValue("actorId", actorId);
  }

  private String json(Object value) {
    try {
      return jsonMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize the Trade Order snapshot", exception);
    }
  }

  private DeliveryAddress readAddress(String value) {
    try {
      return jsonMapper.readValue(value, DeliveryAddress.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Could not read the Trade Order delivery snapshot", exception);
    }
  }

  private SupplyDecisionSnapshot readSupplyDecision(
      ResultSet resultSet,
      TradeOrderSupplyDecisionStatus status,
      String routeCode,
      List<Line> lines)
      throws SQLException {
    Integer schemaVersion = (Integer) resultSet.getObject("supply_decision_schema_version");
    String policyVersion = resultSet.getString("supply_decision_policy_version");
    Timestamp decidedAt = resultSet.getTimestamp("supply_decision_at");
    String decisionHash = resultSet.getString("supply_decision_hash");
    String snapshotJson = resultSet.getString("supply_decision_snapshot");
    if (status == TradeOrderSupplyDecisionStatus.LEGACY_UNVERIFIED) {
      if (schemaVersion != null
          || policyVersion != null
          || decidedAt != null
          || decisionHash != null
          || snapshotJson != null
          || lines.stream().anyMatch(line -> line.allocationMode() != null)) {
        throw new IllegalStateException("Legacy order contains frozen decision evidence");
      }
      return null;
    }
    if (schemaVersion == null
        || policyVersion == null
        || decidedAt == null
        || decisionHash == null
        || snapshotJson == null) {
      throw new IllegalStateException("Frozen order decision evidence is incomplete");
    }
    try {
      SupplyDecisionSnapshot snapshot =
          jsonMapper.readValue(snapshotJson, SupplyDecisionSnapshot.class);
      if (snapshot.schemaVersion() != schemaVersion
          || !snapshot.policyVersion().equals(policyVersion)
          || !snapshot.decidedAt().equals(decidedAt.toInstant())
          || !snapshot.decisionHash().equals(decisionHash)
          || !snapshot.selectedRouteCode().name().equals(routeCode)) {
        throw new IllegalStateException("Frozen order root and JSON evidence conflict");
      }
      return snapshot;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Could not verify the frozen order decision", exception);
    }
  }

  private static SupplyAllocationMode allocationMode(String value) {
    return value == null ? null : SupplyAllocationMode.valueOf(value);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getTimestamp(column).toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(value);
  }

  private static void requireTenant(TenantId expected, TenantId actual) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("Tenant scope does not match the Trade Order");
    }
  }
}
