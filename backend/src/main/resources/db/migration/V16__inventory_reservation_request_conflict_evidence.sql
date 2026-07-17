CREATE TABLE inventory.reservation_request_conflict (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    existing_request_hash varchar(64) NOT NULL,
    conflicting_request_hash varchar(64) NOT NULL,
    source_event_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    observed_at timestamptz NOT NULL,
    failure_code varchar(80) NOT NULL,
    CONSTRAINT uq_inventory_request_conflict_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_request_conflict_business_key
        UNIQUE (tenant_id, order_id, conflicting_request_hash),
    CONSTRAINT uq_inventory_request_conflict_source_event
        UNIQUE (tenant_id, source_event_id),
    CONSTRAINT fk_inventory_request_conflict_reservation_identity
        FOREIGN KEY (tenant_id, reservation_id, existing_request_hash)
        REFERENCES inventory.reservation (tenant_id, id, request_hash),
    CONSTRAINT fk_inventory_request_conflict_reservation_order
        FOREIGN KEY (tenant_id, order_id, existing_request_hash)
        REFERENCES inventory.reservation (tenant_id, order_id, request_hash),
    CONSTRAINT ck_inventory_request_conflict_existing_hash
        CHECK (existing_request_hash ~ '^[0-9a-f]{64}'
            AND char_length(existing_request_hash) = 64),
    CONSTRAINT ck_inventory_request_conflict_incoming_hash
        CHECK (conflicting_request_hash ~ '^[0-9a-f]{64}'
            AND char_length(conflicting_request_hash) = 64),
    CONSTRAINT ck_inventory_request_conflict_distinct_hash
        CHECK (existing_request_hash <> conflicting_request_hash),
    CONSTRAINT ck_inventory_request_conflict_failure
        CHECK (failure_code = 'RESERVATION_REQUEST_CONFLICT')
);

CREATE INDEX ix_inventory_request_conflict_reservation
    ON inventory.reservation_request_conflict
        (tenant_id, reservation_id, observed_at, id);
