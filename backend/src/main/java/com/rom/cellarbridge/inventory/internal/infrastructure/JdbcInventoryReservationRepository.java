package com.rom.cellarbridge.inventory.internal.infrastructure;

import com.rom.cellarbridge.identityaccess.TenantId;
import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import com.rom.cellarbridge.inventory.internal.application.InventoryReservationRepository;
import com.rom.cellarbridge.inventory.internal.application.ReservationPersistenceException;
import com.rom.cellarbridge.inventory.internal.application.ReservationPersistenceException.Code;
import com.rom.cellarbridge.inventory.internal.domain.Allocation;
import com.rom.cellarbridge.inventory.internal.domain.InventoryMovement;
import com.rom.cellarbridge.inventory.internal.domain.Reservation;
import com.rom.cellarbridge.inventory.internal.domain.Reservation.AllocationMode;
import com.rom.cellarbridge.inventory.internal.domain.ReservationAttempt;
import com.rom.cellarbridge.inventory.internal.domain.ShortageSnapshot;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcInventoryReservationRepository implements InventoryReservationRepository {

  private static final String RESERVATION_SELECT =
      """
      SELECT id, tenant_id, order_id, request_hash, supply_decision_hash, route_code,
             status, failure_code, request_schema_version, request_lines, version,
             created_at, updated_at
        FROM inventory.reservation
      """;
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper jsonMapper;

  public JdbcInventoryReservationRepository(
      NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public CreateResult create(TenantId tenantId, Reservation reservation) {
    Objects.requireNonNull(reservation, "reservation");
    requireTenant(tenantId, reservation.tenantId());
    int inserted =
        jdbc.update(
            """
            INSERT INTO inventory.reservation
              (id, tenant_id, order_id, request_hash, supply_decision_hash, route_code,
               status, failure_code, request_schema_version, request_lines, version,
               created_at, updated_at)
            VALUES
              (:id, :tenantId, :orderId, :requestHash, :supplyDecisionHash, :routeCode,
               :status, :failureCode, 1, CAST(:requestLines AS jsonb), :version,
               :createdAt, :updatedAt)
            ON CONFLICT DO NOTHING
            """,
            reservationParameters(reservation));
    Reservation persisted =
        findReservationByTenantAndOrder(reservation.tenantId(), reservation.orderId())
            .orElseThrow(
                () -> integrity("Reservation create did not produce a readable row", null));
    if (!persisted.requestHash().equals(reservation.requestHash())) {
      throw new ReservationPersistenceException(
          Code.RESERVATION_REQUEST_CONFLICT,
          "Order already has a Reservation with a different request hash");
    }
    return new CreateResult(persisted, inserted == 0);
  }

  @Override
  public Optional<ReservationAggregate> findByTenantAndOrder(TenantId tenantId, UUID orderId) {
    return findReservationByTenantAndOrder(tenantId, orderId).map(this::hydrate);
  }

  @Override
  public Optional<ReservationAggregate> findByRequestHash(TenantId tenantId, String requestHash) {
    if (requestHash == null || !HASH.matcher(requestHash).matches()) {
      throw new IllegalArgumentException("requestHash must be lowercase SHA-256 hex");
    }
    return queryReservation(
            RESERVATION_SELECT + " WHERE tenant_id = :tenantId AND request_hash = :requestHash",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("requestHash", requestHash))
        .map(this::hydrate);
  }

  @Override
  public void updateState(TenantId tenantId, Reservation reservation, long expectedVersion) {
    if (!compareAndUpdateVersion(tenantId, reservation, expectedVersion)) {
      throw new ReservationPersistenceException(
          Code.OPTIMISTIC_VERSION_CONFLICT, "Reservation state update lost its expected version");
    }
  }

  @Override
  public boolean compareAndUpdateVersion(
      TenantId tenantId, Reservation reservation, long expectedVersion) {
    Objects.requireNonNull(reservation, "reservation");
    requireTenant(tenantId, reservation.tenantId());
    if (expectedVersion < 0 || reservation.version() != expectedVersion + 1) {
      throw new IllegalArgumentException("Reservation must advance exactly one expected version");
    }
    return jdbc.update(
            """
            UPDATE inventory.reservation
               SET status = :status,
                   failure_code = :failureCode,
                   version = :version,
                   updated_at = :updatedAt
             WHERE tenant_id = :tenantId
               AND id = :id
               AND order_id = :orderId
               AND request_hash = :requestHash
               AND version = :expectedVersion
            """,
            reservationParameters(reservation).addValue("expectedVersion", expectedVersion))
        == 1;
  }

  @Override
  public void appendAttempt(TenantId tenantId, ReservationAttempt attempt) {
    requireTenant(tenantId, attempt.tenantId());
    jdbc.update(
        """
        INSERT INTO inventory.reservation_attempt
          (id, tenant_id, reservation_id, attempt_number, request_hash, trigger_type,
           started_at, completed_at, status, failure_code, correlation_id, causation_id,
           created_at)
        VALUES
          (:id, :tenantId, :reservationId, :attemptNumber, :requestHash, :triggerType,
           :startedAt, :completedAt, :status, :failureCode, :correlationId, :causationId,
           :createdAt)
        """,
        new MapSqlParameterSource()
            .addValue("id", attempt.id())
            .addValue("tenantId", attempt.tenantId().value())
            .addValue("reservationId", attempt.reservationId())
            .addValue("attemptNumber", attempt.attemptNumber())
            .addValue("requestHash", attempt.requestHash())
            .addValue("triggerType", attempt.trigger().name())
            .addValue("startedAt", timestamp(attempt.startedAt()))
            .addValue("completedAt", timestamp(attempt.completedAt()))
            .addValue("status", attempt.outcome().name())
            .addValue("failureCode", attempt.failureCode())
            .addValue("correlationId", attempt.correlationId())
            .addValue("causationId", attempt.causationId())
            .addValue("createdAt", timestamp(attempt.completedAt())));
  }

  @Override
  public List<ReservationAttempt> findAttempts(TenantId tenantId, UUID reservationId) {
    return jdbc.query(
        """
        SELECT id, tenant_id, reservation_id, attempt_number, request_hash, trigger_type,
               started_at, completed_at, status, failure_code, correlation_id, causation_id
          FROM inventory.reservation_attempt
         WHERE tenant_id = :tenantId AND reservation_id = :reservationId
         ORDER BY attempt_number, id
        """,
        ids(tenantId, reservationId),
        (resultSet, rowNumber) -> mapAttempt(resultSet));
  }

  @Override
  public void appendAllocations(TenantId tenantId, List<Allocation> allocations) {
    Objects.requireNonNull(allocations, "allocations");
    allocations.forEach(allocation -> requireTenant(tenantId, allocation.tenantId()));
    for (Allocation allocation : allocations) {
      jdbc.update(
          """
          INSERT INTO inventory.allocation
            (id, tenant_id, reservation_id, order_line_id, source_quotation_line_id,
             sku_id, quantity_unit, supply_type, allocation_mode, supply_pool_id, lot_id,
             allocated_quantity, released_quantity, consumed_quantity,
             remaining_reserved_quantity, warehouse_priority, warehouse_version, created_at)
          VALUES
            (:id, :tenantId, :reservationId, :orderLineId, :sourceQuotationLineId,
             :skuId, :quantityUnit, :supplyType, :allocationMode, :supplyPoolId, :lotId,
             :allocatedQuantity, :releasedQuantity, :consumedQuantity,
             :remainingReservedQuantity, :warehousePriority, :warehouseVersion, CURRENT_TIMESTAMP)
          """,
          allocationParameters(allocation));
    }
  }

  @Override
  public List<Allocation> findAllocationsByReservation(TenantId tenantId, UUID reservationId) {
    return jdbc.query(
        """
        SELECT id, tenant_id, reservation_id, order_line_id, source_quotation_line_id,
               sku_id, quantity_unit, supply_type, allocation_mode, supply_pool_id, lot_id,
               allocated_quantity, released_quantity, consumed_quantity,
               remaining_reserved_quantity, warehouse_priority, warehouse_version
          FROM inventory.allocation
         WHERE tenant_id = :tenantId AND reservation_id = :reservationId
         ORDER BY order_line_id, lot_id, id
        """,
        ids(tenantId, reservationId),
        (resultSet, rowNumber) -> mapAllocation(resultSet));
  }

  @Override
  public void appendMovement(TenantId tenantId, InventoryMovement movement) {
    requireTenant(tenantId, movement.tenantId());
    jdbc.update(
        """
        INSERT INTO inventory.inventory_movement
          (id, tenant_id, reservation_id, allocation_id, order_line_id, lot_id,
           movement_type, quantity, quantity_unit, business_key, occurred_at)
        VALUES
          (:id, :tenantId, :reservationId, :allocationId, :orderLineId, :lotId,
           :movementType, :quantity, :quantityUnit, :businessKey, :occurredAt)
        """,
        new MapSqlParameterSource()
            .addValue("id", movement.id())
            .addValue("tenantId", movement.tenantId().value())
            .addValue("reservationId", movement.reservationId())
            .addValue("allocationId", movement.allocationId())
            .addValue("orderLineId", movement.orderLineId())
            .addValue("lotId", movement.lotId())
            .addValue("movementType", movement.type().name())
            .addValue("quantity", movement.quantity())
            .addValue("quantityUnit", movement.quantityUnit().name())
            .addValue("businessKey", movement.businessKey())
            .addValue("occurredAt", timestamp(movement.occurredAt())));
  }

  @Override
  public List<InventoryMovement> findMovements(TenantId tenantId, UUID reservationId) {
    return jdbc.query(
        """
        SELECT id, tenant_id, reservation_id, allocation_id, order_line_id, lot_id,
               movement_type, quantity, quantity_unit, business_key, occurred_at
          FROM inventory.inventory_movement
         WHERE tenant_id = :tenantId AND reservation_id = :reservationId
         ORDER BY occurred_at, id
        """,
        ids(tenantId, reservationId),
        (resultSet, rowNumber) -> mapMovement(resultSet));
  }

  @Override
  public void appendShortage(TenantId tenantId, ShortageSnapshot shortage) {
    requireTenant(tenantId, shortage.tenantId());
    jdbc.update(
        """
        INSERT INTO inventory.shortage_snapshot
          (id, tenant_id, reservation_id, order_line_id, sku_id, quantity_unit,
           requested_quantity, available_quantity, shortage_quantity, failure_code,
           supply_pool_id, supply_type, observed_at)
        VALUES
          (:id, :tenantId, :reservationId, :orderLineId, :skuId, :quantityUnit,
           :requestedQuantity, :availableQuantity, :shortageQuantity, :failureCode,
           :supplyPoolId, :supplyType, :observedAt)
        """,
        new MapSqlParameterSource()
            .addValue("id", shortage.id())
            .addValue("tenantId", shortage.tenantId().value())
            .addValue("reservationId", shortage.reservationId())
            .addValue("orderLineId", shortage.orderLineId())
            .addValue("skuId", shortage.skuId())
            .addValue("quantityUnit", shortage.quantityUnit().name())
            .addValue("requestedQuantity", shortage.requestedQuantity())
            .addValue("availableQuantity", shortage.availableQuantity())
            .addValue("shortageQuantity", shortage.shortageQuantity())
            .addValue("failureCode", shortage.failureCode())
            .addValue("supplyPoolId", shortage.supplyPoolId())
            .addValue(
                "supplyType", shortage.supplyType() == null ? null : shortage.supplyType().name())
            .addValue("observedAt", timestamp(shortage.observedAt())));
  }

  @Override
  public List<ShortageSnapshot> findShortages(TenantId tenantId, UUID reservationId) {
    return jdbc.query(
        """
        SELECT id, tenant_id, reservation_id, order_line_id, sku_id, quantity_unit,
               requested_quantity, available_quantity, shortage_quantity, failure_code,
               supply_pool_id, supply_type, observed_at
          FROM inventory.shortage_snapshot
         WHERE tenant_id = :tenantId AND reservation_id = :reservationId
         ORDER BY order_line_id, id
        """,
        ids(tenantId, reservationId),
        (resultSet, rowNumber) -> mapShortage(resultSet));
  }

  private Optional<Reservation> findReservationByTenantAndOrder(TenantId tenantId, UUID orderId) {
    return queryReservation(
        RESERVATION_SELECT + " WHERE tenant_id = :tenantId AND order_id = :orderId",
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId.value())
            .addValue("orderId", orderId));
  }

  private Optional<Reservation> queryReservation(String sql, MapSqlParameterSource parameters) {
    List<Reservation> rows =
        jdbc.query(sql, parameters, (resultSet, rowNumber) -> mapReservation(resultSet));
    if (rows.size() > 1) {
      throw integrity("Reservation identity resolved more than one row", null);
    }
    return rows.stream().findFirst();
  }

  private ReservationAggregate hydrate(Reservation reservation) {
    List<ReservationAttempt> attempts = findAttempts(reservation.tenantId(), reservation.id());
    List<Allocation> allocations =
        findAllocationsByReservation(reservation.tenantId(), reservation.id());
    List<InventoryMovement> movements = findMovements(reservation.tenantId(), reservation.id());
    List<ShortageSnapshot> shortages = findShortages(reservation.tenantId(), reservation.id());
    try {
      new ReservationAttempt.History(reservation.tenantId(), reservation.id(), attempts);
      validateAggregate(reservation, attempts, allocations, movements, shortages);
      return new ReservationAggregate(reservation, attempts, allocations, movements, shortages);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      throw integrity("Reservation aggregate is incomplete or inconsistent", exception);
    }
  }

  private static void validateAggregate(
      Reservation reservation,
      List<ReservationAttempt> attempts,
      List<Allocation> allocations,
      List<InventoryMovement> movements,
      List<ShortageSnapshot> shortages) {
    if (reservation.status() == Reservation.Status.PENDING) {
      requireFacts(
          attempts.isEmpty()
              && allocations.isEmpty()
              && movements.isEmpty()
              && shortages.isEmpty());
      return;
    }
    requireFacts(!attempts.isEmpty());
    ReservationAttempt lastAttempt = attempts.getLast();
    if (reservation.status() == Reservation.Status.FAILED) {
      requireFacts(
          lastAttempt.outcome() == ReservationAttempt.Outcome.FAILED
              && Objects.equals(lastAttempt.failureCode(), reservation.failureCode())
              && allocations.isEmpty()
              && movements.isEmpty());
      validateShortages(reservation, shortages);
      return;
    }
    requireFacts(
        lastAttempt.outcome() == ReservationAttempt.Outcome.CONFIRMED
            && !allocations.isEmpty()
            && shortages.isEmpty());
    validateSuccessfulFacts(reservation, allocations, movements);
  }

  private static void validateSuccessfulFacts(
      Reservation reservation, List<Allocation> allocations, List<InventoryMovement> movements) {
    Map<UUID, Reservation.Line> lines = new HashMap<>();
    reservation.lines().forEach(line -> lines.put(line.orderLineId(), line));
    Map<UUID, BigDecimal> allocatedByLine = new HashMap<>();
    Map<UUID, Allocation> allocationsById = new HashMap<>();
    for (Allocation allocation : allocations) {
      Reservation.Line line = lines.get(allocation.orderLineId());
      requireFacts(
          line != null
              && reservation.tenantId().equals(allocation.tenantId())
              && reservation.id().equals(allocation.reservationId())
              && line.sourceQuotationLineId().equals(allocation.sourceQuotationLineId())
              && line.skuId().equals(allocation.skuId())
              && line.quantityUnit() == allocation.quantityUnit()
              && line.supplyType() == allocation.supplyType()
              && line.allocationMode() == allocation.allocationMode()
              && (line.allocationMode() == AllocationMode.ROUTE_ELIGIBLE_AUTO
                  || line.supplyPoolId().equals(allocation.supplyPoolId())));
      allocatedByLine.merge(
          allocation.orderLineId(), allocation.allocatedQuantity(), BigDecimal::add);
      requireFacts(allocationsById.put(allocation.id(), allocation) == null);
      validateAllocationState(reservation.status(), allocation);
    }
    for (Reservation.Line line : reservation.lines()) {
      requireFacts(
          line.requestedQuantity()
                  .compareTo(allocatedByLine.getOrDefault(line.orderLineId(), BigDecimal.ZERO))
              == 0);
    }
    Map<MovementKey, BigDecimal> movementTotals = new HashMap<>();
    for (InventoryMovement movement : movements) {
      Allocation allocation = allocationsById.get(movement.allocationId());
      requireFacts(
          allocation != null
              && reservation.tenantId().equals(movement.tenantId())
              && reservation.id().equals(movement.reservationId())
              && allocation.orderLineId().equals(movement.orderLineId())
              && allocation.lotId().equals(movement.lotId())
              && allocation.quantityUnit() == movement.quantityUnit());
      movementTotals.merge(
          new MovementKey(movement.allocationId(), movement.type()),
          movement.quantity(),
          BigDecimal::add);
    }
    for (Allocation allocation : allocations) {
      requireFacts(
          total(movementTotals, allocation.id(), InventoryMovement.Type.RESERVE)
                      .compareTo(allocation.allocatedQuantity())
                  == 0
              && total(movementTotals, allocation.id(), InventoryMovement.Type.RELEASE)
                      .compareTo(allocation.releasedQuantity())
                  == 0
              && total(movementTotals, allocation.id(), InventoryMovement.Type.CONSUME)
                      .compareTo(allocation.consumedQuantity())
                  == 0);
    }
  }

  private static void validateAllocationState(Reservation.Status status, Allocation allocation) {
    boolean valid =
        switch (status) {
          case CONFIRMED ->
              allocation.remainingReservedQuantity().compareTo(allocation.allocatedQuantity()) == 0
                  && allocation.releasedQuantity().signum() == 0
                  && allocation.consumedQuantity().signum() == 0;
          case RELEASED ->
              allocation.releasedQuantity().compareTo(allocation.allocatedQuantity()) == 0
                  && allocation.remainingReservedQuantity().signum() == 0
                  && allocation.consumedQuantity().signum() == 0;
          case CONSUMED ->
              allocation.consumedQuantity().compareTo(allocation.allocatedQuantity()) == 0
                  && allocation.remainingReservedQuantity().signum() == 0
                  && allocation.releasedQuantity().signum() == 0;
          default -> false;
        };
    requireFacts(valid);
  }

  private static void validateShortages(Reservation reservation, List<ShortageSnapshot> shortages) {
    Map<UUID, Reservation.Line> lines = new HashMap<>();
    reservation.lines().forEach(line -> lines.put(line.orderLineId(), line));
    for (ShortageSnapshot shortage : shortages) {
      Reservation.Line line = lines.get(shortage.orderLineId());
      requireFacts(
          line != null
              && reservation.tenantId().equals(shortage.tenantId())
              && reservation.id().equals(shortage.reservationId())
              && line.skuId().equals(shortage.skuId())
              && line.quantityUnit() == shortage.quantityUnit()
              && line.requestedQuantity().compareTo(shortage.requestedQuantity()) == 0
              && Objects.equals(line.supplyPoolId(), shortage.supplyPoolId())
              && line.supplyType() == shortage.supplyType());
    }
  }

  private Reservation mapReservation(ResultSet resultSet) throws SQLException {
    try {
      if (resultSet.getInt("request_schema_version") != 1) {
        throw new IllegalArgumentException("Unknown Reservation request schema");
      }
      return new Reservation(
          resultSet.getObject("id", UUID.class),
          TenantId.of(resultSet.getObject("tenant_id", UUID.class)),
          resultSet.getObject("order_id", UUID.class),
          resultSet.getString("request_hash"),
          resultSet.getString("supply_decision_hash"),
          resultSet.getString("route_code"),
          Reservation.Status.valueOf(resultSet.getString("status")),
          resultSet.getString("failure_code"),
          readLines(resultSet.getString("request_lines")),
          resultSet.getLong("version"),
          instant(resultSet, "created_at"),
          instant(resultSet, "updated_at"));
    } catch (JacksonException | IllegalArgumentException exception) {
      throw integrity("Could not hydrate Reservation", exception);
    }
  }

  private List<Reservation.Line> readLines(String value) throws JacksonException {
    return Arrays.stream(jsonMapper.readValue(value, StoredLine[].class))
        .map(
            line ->
                new Reservation.Line(
                    line.orderLineId(),
                    line.sourceQuotationLineId(),
                    line.skuId(),
                    line.requestedQuantity(),
                    QuantityUnit.valueOf(line.quantityUnit()),
                    line.allocationMode() == null
                        ? null
                        : AllocationMode.valueOf(line.allocationMode()),
                    line.supplyPoolId(),
                    line.supplyType() == null ? null : SupplyType.valueOf(line.supplyType())))
        .toList();
  }

  private static ReservationAttempt mapAttempt(ResultSet resultSet) throws SQLException {
    try {
      return new ReservationAttempt(
          resultSet.getObject("id", UUID.class),
          TenantId.of(resultSet.getObject("tenant_id", UUID.class)),
          resultSet.getObject("reservation_id", UUID.class),
          resultSet.getInt("attempt_number"),
          resultSet.getString("request_hash"),
          ReservationAttempt.Trigger.valueOf(resultSet.getString("trigger_type")),
          instant(resultSet, "started_at"),
          instant(resultSet, "completed_at"),
          ReservationAttempt.Outcome.valueOf(resultSet.getString("status")),
          resultSet.getString("failure_code"),
          resultSet.getObject("correlation_id", UUID.class),
          resultSet.getObject("causation_id", UUID.class));
    } catch (IllegalArgumentException exception) {
      throw integrity("Could not hydrate ReservationAttempt", exception);
    }
  }

  private static Allocation mapAllocation(ResultSet resultSet) throws SQLException {
    try {
      return new Allocation(
          resultSet.getObject("id", UUID.class),
          TenantId.of(resultSet.getObject("tenant_id", UUID.class)),
          resultSet.getObject("reservation_id", UUID.class),
          resultSet.getObject("order_line_id", UUID.class),
          resultSet.getObject("source_quotation_line_id", UUID.class),
          resultSet.getObject("sku_id", UUID.class),
          QuantityUnit.valueOf(resultSet.getString("quantity_unit")),
          SupplyType.valueOf(resultSet.getString("supply_type")),
          AllocationMode.valueOf(resultSet.getString("allocation_mode")),
          resultSet.getObject("supply_pool_id", UUID.class),
          resultSet.getObject("lot_id", UUID.class),
          resultSet.getBigDecimal("allocated_quantity"),
          resultSet.getBigDecimal("released_quantity"),
          resultSet.getBigDecimal("consumed_quantity"),
          resultSet.getBigDecimal("remaining_reserved_quantity"),
          resultSet.getInt("warehouse_priority"),
          resultSet.getLong("warehouse_version"));
    } catch (IllegalArgumentException exception) {
      throw integrity("Could not hydrate Allocation", exception);
    }
  }

  private static InventoryMovement mapMovement(ResultSet resultSet) throws SQLException {
    try {
      return new InventoryMovement(
          resultSet.getObject("id", UUID.class),
          TenantId.of(resultSet.getObject("tenant_id", UUID.class)),
          resultSet.getObject("reservation_id", UUID.class),
          resultSet.getObject("allocation_id", UUID.class),
          resultSet.getObject("order_line_id", UUID.class),
          resultSet.getObject("lot_id", UUID.class),
          InventoryMovement.Type.valueOf(resultSet.getString("movement_type")),
          resultSet.getBigDecimal("quantity"),
          QuantityUnit.valueOf(resultSet.getString("quantity_unit")),
          resultSet.getString("business_key"),
          instant(resultSet, "occurred_at"));
    } catch (IllegalArgumentException exception) {
      throw integrity("Could not hydrate InventoryMovement", exception);
    }
  }

  private static ShortageSnapshot mapShortage(ResultSet resultSet) throws SQLException {
    try {
      String supplyType = resultSet.getString("supply_type");
      return new ShortageSnapshot(
          resultSet.getObject("id", UUID.class),
          TenantId.of(resultSet.getObject("tenant_id", UUID.class)),
          resultSet.getObject("reservation_id", UUID.class),
          resultSet.getObject("order_line_id", UUID.class),
          resultSet.getObject("sku_id", UUID.class),
          QuantityUnit.valueOf(resultSet.getString("quantity_unit")),
          resultSet.getBigDecimal("requested_quantity"),
          resultSet.getBigDecimal("available_quantity"),
          resultSet.getBigDecimal("shortage_quantity"),
          resultSet.getString("failure_code"),
          resultSet.getObject("supply_pool_id", UUID.class),
          supplyType == null ? null : SupplyType.valueOf(supplyType),
          instant(resultSet, "observed_at"));
    } catch (IllegalArgumentException exception) {
      throw integrity("Could not hydrate ShortageSnapshot", exception);
    }
  }

  private MapSqlParameterSource reservationParameters(Reservation reservation) {
    return new MapSqlParameterSource()
        .addValue("id", reservation.id())
        .addValue("tenantId", reservation.tenantId().value())
        .addValue("orderId", reservation.orderId())
        .addValue("requestHash", reservation.requestHash())
        .addValue("supplyDecisionHash", reservation.supplyDecisionHash())
        .addValue("routeCode", reservation.routeCode())
        .addValue("status", reservation.status().name())
        .addValue("failureCode", reservation.failureCode())
        .addValue("requestLines", writeLines(reservation.lines()))
        .addValue("version", reservation.version())
        .addValue("createdAt", timestamp(reservation.createdAt()))
        .addValue("updatedAt", timestamp(reservation.updatedAt()));
  }

  private String writeLines(List<Reservation.Line> lines) {
    try {
      return jsonMapper.writeValueAsString(
          lines.stream()
              .map(
                  line ->
                      new StoredLine(
                          line.orderLineId(),
                          line.sourceQuotationLineId(),
                          line.skuId(),
                          line.requestedQuantity(),
                          line.quantityUnit().name(),
                          line.allocationMode() == null ? null : line.allocationMode().name(),
                          line.supplyPoolId(),
                          line.supplyType() == null ? null : line.supplyType().name()))
              .toList());
    } catch (JacksonException exception) {
      throw integrity("Could not serialize Reservation request lines", exception);
    }
  }

  private static MapSqlParameterSource allocationParameters(Allocation allocation) {
    return new MapSqlParameterSource()
        .addValue("id", allocation.id())
        .addValue("tenantId", allocation.tenantId().value())
        .addValue("reservationId", allocation.reservationId())
        .addValue("orderLineId", allocation.orderLineId())
        .addValue("sourceQuotationLineId", allocation.sourceQuotationLineId())
        .addValue("skuId", allocation.skuId())
        .addValue("quantityUnit", allocation.quantityUnit().name())
        .addValue("supplyType", allocation.supplyType().name())
        .addValue("allocationMode", allocation.allocationMode().name())
        .addValue("supplyPoolId", allocation.supplyPoolId())
        .addValue("lotId", allocation.lotId())
        .addValue("allocatedQuantity", allocation.allocatedQuantity())
        .addValue("releasedQuantity", allocation.releasedQuantity())
        .addValue("consumedQuantity", allocation.consumedQuantity())
        .addValue("remainingReservedQuantity", allocation.remainingReservedQuantity())
        .addValue("warehousePriority", allocation.warehousePriority())
        .addValue("warehouseVersion", allocation.warehouseVersion());
  }

  private static MapSqlParameterSource ids(TenantId tenantId, UUID reservationId) {
    return new MapSqlParameterSource()
        .addValue("tenantId", tenantId.value())
        .addValue("reservationId", reservationId);
  }

  private static BigDecimal total(
      Map<MovementKey, BigDecimal> totals, UUID allocationId, InventoryMovement.Type type) {
    return totals.getOrDefault(new MovementKey(allocationId, type), BigDecimal.ZERO);
  }

  private static void requireFacts(boolean condition) {
    if (!condition) {
      throw new IllegalStateException(
          "Persisted Reservation facts do not form a complete aggregate");
    }
  }

  private static void requireTenant(TenantId expected, TenantId actual) {
    Objects.requireNonNull(expected, "tenantId");
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("Tenant scope mismatch");
    }
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getTimestamp(column).toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(value);
  }

  private static ReservationPersistenceException integrity(String message, Throwable cause) {
    return new ReservationPersistenceException(
        Code.PERSISTENCE_INTEGRITY_VIOLATION, message, cause);
  }

  private record StoredLine(
      UUID orderLineId,
      UUID sourceQuotationLineId,
      UUID skuId,
      BigDecimal requestedQuantity,
      String quantityUnit,
      String allocationMode,
      UUID supplyPoolId,
      String supplyType) {}

  private record MovementKey(UUID allocationId, InventoryMovement.Type type) {}
}
