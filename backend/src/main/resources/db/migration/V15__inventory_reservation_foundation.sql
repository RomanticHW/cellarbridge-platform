CREATE TABLE inventory.reservation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    supply_decision_hash varchar(64),
    route_code varchar(50) NOT NULL,
    status varchar(20) NOT NULL,
    failure_code varchar(80),
    request_schema_version smallint NOT NULL DEFAULT 1,
    request_lines jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_inventory_reservation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_reservation_attempt_identity
        UNIQUE (tenant_id, id, request_hash),
    CONSTRAINT uq_inventory_reservation_tenant_order UNIQUE (tenant_id, order_id),
    CONSTRAINT uq_inventory_reservation_tenant_order_hash
        UNIQUE (tenant_id, order_id, request_hash),
    CONSTRAINT uq_inventory_reservation_tenant_hash UNIQUE (tenant_id, request_hash),
    CONSTRAINT ck_inventory_reservation_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}' AND char_length(request_hash) = 64),
    CONSTRAINT ck_inventory_reservation_decision_hash
        CHECK (supply_decision_hash IS NULL
            OR (supply_decision_hash ~ '^[0-9a-f]{64}'
                AND char_length(supply_decision_hash) = 64)),
    CONSTRAINT ck_inventory_reservation_route CHECK (btrim(route_code) <> ''),
    CONSTRAINT ck_inventory_reservation_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'RELEASED', 'CONSUMED')),
    CONSTRAINT ck_inventory_reservation_failure
        CHECK ((status = 'FAILED' AND failure_code IS NOT NULL AND btrim(failure_code) <> '')
            OR (status <> 'FAILED' AND failure_code IS NULL)),
    CONSTRAINT ck_inventory_reservation_legacy
        CHECK (supply_decision_hash IS NOT NULL
            OR (status = 'FAILED' AND failure_code = 'SUPPLY_DECISION_MISSING')),
    CONSTRAINT ck_inventory_reservation_request_schema CHECK (request_schema_version = 1),
    CONSTRAINT ck_inventory_reservation_request_lines
        CHECK (jsonb_typeof(request_lines) = 'array'
            AND jsonb_array_length(request_lines) > 0),
    CONSTRAINT ck_inventory_reservation_version CHECK (version >= 0),
    CONSTRAINT ck_inventory_reservation_time CHECK (updated_at >= created_at)
);

CREATE INDEX ix_inventory_reservation_tenant_status
    ON inventory.reservation (tenant_id, status, updated_at, id);

CREATE TABLE inventory.reservation_attempt (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    request_hash varchar(64) NOT NULL,
    trigger_type varchar(20) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    status varchar(20) NOT NULL,
    failure_code varchar(80),
    correlation_id uuid NOT NULL,
    causation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_inventory_attempt_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_attempt_number
        UNIQUE (tenant_id, reservation_id, attempt_number),
    CONSTRAINT fk_inventory_attempt_reservation
        FOREIGN KEY (tenant_id, reservation_id, request_hash)
        REFERENCES inventory.reservation (tenant_id, id, request_hash),
    CONSTRAINT ck_inventory_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_inventory_attempt_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}' AND char_length(request_hash) = 64),
    CONSTRAINT ck_inventory_attempt_trigger
        CHECK (trigger_type IN ('EVENT', 'MANUAL_RETRY')),
    CONSTRAINT ck_inventory_attempt_status CHECK (status IN ('CONFIRMED', 'FAILED')),
    CONSTRAINT ck_inventory_attempt_failure
        CHECK ((status = 'FAILED' AND failure_code IS NOT NULL AND btrim(failure_code) <> '')
            OR (status = 'CONFIRMED' AND failure_code IS NULL)),
    CONSTRAINT ck_inventory_attempt_time
        CHECK (completed_at >= started_at AND created_at >= completed_at)
);

CREATE INDEX ix_inventory_attempt_reservation
    ON inventory.reservation_attempt (tenant_id, reservation_id, attempt_number);

ALTER TABLE inventory.inventory_lot
    ADD CONSTRAINT uq_inventory_lot_tenant_pool_id
    UNIQUE (tenant_id, supply_pool_id, id);

CREATE TABLE inventory.allocation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    order_line_id uuid NOT NULL,
    source_quotation_line_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    quantity_unit varchar(20) NOT NULL,
    supply_type varchar(40) NOT NULL,
    allocation_mode varchar(30) NOT NULL,
    supply_pool_id uuid NOT NULL,
    lot_id uuid NOT NULL,
    allocated_quantity numeric(19,6) NOT NULL,
    released_quantity numeric(19,6) NOT NULL,
    consumed_quantity numeric(19,6) NOT NULL,
    remaining_reserved_quantity numeric(19,6) NOT NULL,
    warehouse_priority integer NOT NULL,
    warehouse_version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_inventory_allocation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_allocation_movement_identity
        UNIQUE (tenant_id, id, reservation_id, order_line_id, lot_id, quantity_unit),
    CONSTRAINT uq_inventory_allocation_line_lot
        UNIQUE (tenant_id, reservation_id, order_line_id, lot_id),
    CONSTRAINT fk_inventory_allocation_reservation FOREIGN KEY (tenant_id, reservation_id)
        REFERENCES inventory.reservation (tenant_id, id),
    CONSTRAINT fk_inventory_allocation_pool FOREIGN KEY (tenant_id, supply_pool_id)
        REFERENCES inventory.supply_pool (tenant_id, id),
    CONSTRAINT fk_inventory_allocation_lot
        FOREIGN KEY (tenant_id, supply_pool_id, lot_id)
        REFERENCES inventory.inventory_lot (tenant_id, supply_pool_id, id),
    CONSTRAINT ck_inventory_allocation_unit CHECK (quantity_unit IN ('CASE', 'BOTTLE')),
    CONSTRAINT ck_inventory_allocation_supply_type CHECK
        (supply_type IN ('DOMESTIC_ON_HAND', 'BONDED_ON_HAND', 'HONG_KONG_ON_HAND')),
    CONSTRAINT ck_inventory_allocation_mode
        CHECK (allocation_mode IN ('ROUTE_ELIGIBLE_AUTO', 'FIXED_POOL')),
    CONSTRAINT ck_inventory_allocation_positive CHECK (allocated_quantity > 0),
    CONSTRAINT ck_inventory_allocation_non_negative
        CHECK (released_quantity >= 0
            AND consumed_quantity >= 0
            AND remaining_reserved_quantity >= 0),
    CONSTRAINT ck_inventory_allocation_conservation
        CHECK (allocated_quantity = released_quantity
            + consumed_quantity + remaining_reserved_quantity),
    CONSTRAINT ck_inventory_allocation_warehouse_evidence
        CHECK (warehouse_priority >= 0 AND warehouse_version >= 0)
);

CREATE INDEX ix_inventory_allocation_reservation
    ON inventory.allocation (tenant_id, reservation_id, order_line_id, lot_id, id);

CREATE TABLE inventory.inventory_movement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    allocation_id uuid NOT NULL,
    order_line_id uuid NOT NULL,
    lot_id uuid NOT NULL,
    movement_type varchar(20) NOT NULL,
    quantity numeric(19,6) NOT NULL,
    quantity_unit varchar(20) NOT NULL,
    business_key varchar(180) NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uq_inventory_movement_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_movement_business_key UNIQUE (tenant_id, business_key),
    CONSTRAINT fk_inventory_movement_allocation
        FOREIGN KEY
            (tenant_id, allocation_id, reservation_id, order_line_id, lot_id, quantity_unit)
        REFERENCES inventory.allocation
            (tenant_id, id, reservation_id, order_line_id, lot_id, quantity_unit),
    CONSTRAINT ck_inventory_movement_type
        CHECK (movement_type IN ('RESERVE', 'RELEASE', 'CONSUME')),
    CONSTRAINT ck_inventory_movement_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inventory_movement_unit CHECK (quantity_unit IN ('CASE', 'BOTTLE')),
    CONSTRAINT ck_inventory_movement_business_key CHECK (btrim(business_key) <> '')
);

CREATE INDEX ix_inventory_movement_reservation
    ON inventory.inventory_movement
        (tenant_id, reservation_id, allocation_id, occurred_at, id);

CREATE TABLE inventory.shortage_snapshot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    order_line_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    quantity_unit varchar(20) NOT NULL,
    requested_quantity numeric(19,6) NOT NULL,
    available_quantity numeric(19,6) NOT NULL,
    shortage_quantity numeric(19,6) NOT NULL,
    failure_code varchar(80) NOT NULL,
    supply_pool_id uuid,
    supply_type varchar(40),
    observed_at timestamptz NOT NULL,
    CONSTRAINT uq_inventory_shortage_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_shortage_line UNIQUE (tenant_id, reservation_id, order_line_id),
    CONSTRAINT fk_inventory_shortage_reservation FOREIGN KEY (tenant_id, reservation_id)
        REFERENCES inventory.reservation (tenant_id, id),
    CONSTRAINT fk_inventory_shortage_pool FOREIGN KEY (tenant_id, supply_pool_id)
        REFERENCES inventory.supply_pool (tenant_id, id),
    CONSTRAINT ck_inventory_shortage_unit CHECK (quantity_unit IN ('CASE', 'BOTTLE')),
    CONSTRAINT ck_inventory_shortage_requested CHECK (requested_quantity > 0),
    CONSTRAINT ck_inventory_shortage_available CHECK (available_quantity >= 0),
    CONSTRAINT ck_inventory_shortage_quantity
        CHECK (shortage_quantity > 0
            AND requested_quantity >= available_quantity
            AND shortage_quantity = requested_quantity - available_quantity),
    CONSTRAINT ck_inventory_shortage_failure CHECK (btrim(failure_code) <> ''),
    CONSTRAINT ck_inventory_shortage_supply_type CHECK
        (supply_type IS NULL OR supply_type IN
            ('DOMESTIC_ON_HAND', 'BONDED_ON_HAND', 'HONG_KONG_ON_HAND',
             'IN_TRANSIT_PRESALE', 'OVERSEAS_SOURCING'))
);

CREATE INDEX ix_inventory_shortage_reservation
    ON inventory.shortage_snapshot (tenant_id, reservation_id, order_line_id, id);
