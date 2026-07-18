CREATE TABLE inventory.reservation_operation_command (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    operation_type varchar(20) NOT NULL,
    business_key_hash varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    status varchar(20) NOT NULL,
    result_code varchar(80),
    result_schema_version smallint,
    result_snapshot jsonb,
    actor_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT uq_inventory_operation_command_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_operation_command_audit_binding
        UNIQUE (tenant_id, id, reservation_id, operation_type, actor_id, business_key_hash),
    CONSTRAINT uq_inventory_operation_command_business_key
        UNIQUE (tenant_id, reservation_id, operation_type, business_key_hash),
    CONSTRAINT fk_inventory_operation_command_reservation
        FOREIGN KEY (tenant_id, reservation_id)
        REFERENCES inventory.reservation (tenant_id, id),
    CONSTRAINT ck_inventory_operation_command_type
        CHECK (operation_type IN ('RELEASE', 'CONSUME')),
    CONSTRAINT ck_inventory_operation_command_key_hash
        CHECK (business_key_hash ~ '^[0-9a-f]{64}' AND char_length(business_key_hash) = 64),
    CONSTRAINT ck_inventory_operation_command_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}' AND char_length(request_hash) = 64),
    CONSTRAINT ck_inventory_operation_command_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'REJECTED')),
    CONSTRAINT ck_inventory_operation_command_result
        CHECK ((status = 'PROCESSING'
                AND result_code IS NULL
                AND result_schema_version IS NULL
                AND result_snapshot IS NULL
                AND completed_at IS NULL)
            OR (status IN ('COMPLETED', 'REJECTED')
                AND result_code IS NOT NULL
                AND btrim(result_code) <> ''
                AND result_schema_version = 1
                AND jsonb_typeof(result_snapshot) = 'object'
                AND completed_at >= created_at))
);

CREATE INDEX ix_inventory_operation_command_reservation
    ON inventory.reservation_operation_command
        (tenant_id, reservation_id, created_at, id);

CREATE TABLE inventory.reservation_operation_audit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    command_id uuid NOT NULL,
    operation_type varchar(20) NOT NULL,
    outcome varchar(20) NOT NULL,
    reason_code varchar(80) NOT NULL,
    actor_id uuid NOT NULL,
    business_key_hash varchar(64) NOT NULL,
    previous_state varchar(20) NOT NULL,
    new_state varchar(20) NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uq_inventory_operation_audit_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_operation_audit_command UNIQUE (tenant_id, command_id),
    CONSTRAINT fk_inventory_operation_audit_command
        FOREIGN KEY
            (tenant_id, command_id, reservation_id, operation_type, actor_id, business_key_hash)
        REFERENCES inventory.reservation_operation_command
            (tenant_id, id, reservation_id, operation_type, actor_id, business_key_hash),
    CONSTRAINT fk_inventory_operation_audit_reservation
        FOREIGN KEY (tenant_id, reservation_id)
        REFERENCES inventory.reservation (tenant_id, id),
    CONSTRAINT ck_inventory_operation_audit_type
        CHECK (operation_type IN ('RELEASE', 'CONSUME')),
    CONSTRAINT ck_inventory_operation_audit_outcome
        CHECK (outcome IN ('COMPLETED', 'REJECTED')),
    CONSTRAINT ck_inventory_operation_audit_reason CHECK (btrim(reason_code) <> ''),
    CONSTRAINT ck_inventory_operation_audit_key_hash
        CHECK (business_key_hash ~ '^[0-9a-f]{64}' AND char_length(business_key_hash) = 64),
    CONSTRAINT ck_inventory_operation_audit_previous_state
        CHECK (previous_state IN ('PENDING', 'CONFIRMED', 'FAILED', 'RELEASED', 'CONSUMED')),
    CONSTRAINT ck_inventory_operation_audit_new_state
        CHECK (new_state IN ('PENDING', 'CONFIRMED', 'FAILED', 'RELEASED', 'CONSUMED'))
);

CREATE INDEX ix_inventory_operation_audit_reservation
    ON inventory.reservation_operation_audit
        (tenant_id, reservation_id, occurred_at, id);
