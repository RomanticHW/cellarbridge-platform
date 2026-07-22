CREATE SCHEMA IF NOT EXISTS settlement;

CREATE TABLE settlement.receivable_trigger_policy (
    id uuid PRIMARY KEY,
    code varchar(80) NOT NULL,
    version integer NOT NULL,
    trigger_type varchar(80) NOT NULL,
    active boolean NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(100) NOT NULL,
    CONSTRAINT uq_settlement_trigger_policy_version UNIQUE (code, version),
    CONSTRAINT ck_settlement_trigger_policy_version CHECK (version > 0),
    CONSTRAINT ck_settlement_trigger_policy_type CHECK (trigger_type IN ('FULFILLMENT_COMPLETED'))
);

CREATE UNIQUE INDEX uq_settlement_trigger_policy_active
    ON settlement.receivable_trigger_policy ((active)) WHERE active;

INSERT INTO settlement.receivable_trigger_policy
    (id, code, version, trigger_type, active, created_at, created_by)
VALUES
    ('90000000-0000-4000-8000-000000000001', 'DEMO-FULFILLMENT-COMPLETED', 1,
     'FULFILLMENT_COMPLETED', true, '2026-07-22T00:00:00Z', 'migration-v20');

CREATE TABLE settlement.order_snapshot (
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    order_number varchar(80) NOT NULL,
    partner_id uuid NOT NULL,
    partner_number varchar(80) NOT NULL,
    partner_name varchar(200) NOT NULL,
    partner_version integer NOT NULL,
    currency char(3) NOT NULL,
    original_amount numeric(19,4) NOT NULL,
    payment_term_days integer NOT NULL,
    trigger_policy_code varchar(80) NOT NULL,
    trigger_policy_version integer NOT NULL,
    trigger_type varchar(80) NOT NULL,
    source_event_id uuid NOT NULL,
    source_snapshot_hash char(64) NOT NULL,
    accepted_at timestamptz NOT NULL,
    captured_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, order_id),
    CONSTRAINT uq_settlement_order_snapshot_number UNIQUE (tenant_id, order_number),
    CONSTRAINT uq_settlement_order_snapshot_source_event UNIQUE (source_event_id),
    CONSTRAINT ck_settlement_order_snapshot_currency CHECK (currency ~ '^[A-Z]{3}'),
    CONSTRAINT ck_settlement_order_snapshot_amount CHECK (original_amount >= 0),
    CONSTRAINT ck_settlement_order_snapshot_term CHECK (payment_term_days BETWEEN 0 AND 180),
    CONSTRAINT ck_settlement_order_snapshot_partner_version CHECK (partner_version >= 0),
    CONSTRAINT ck_settlement_order_snapshot_policy_version CHECK (trigger_policy_version > 0),
    CONSTRAINT ck_settlement_order_snapshot_hash CHECK (source_snapshot_hash ~ '^[0-9a-f]{64}')
);

CREATE TABLE settlement.receivable_number_sequence (
    tenant_id uuid NOT NULL,
    period char(6) NOT NULL,
    last_value bigint NOT NULL,
    PRIMARY KEY (tenant_id, period),
    CONSTRAINT ck_settlement_receivable_sequence_period CHECK (period ~ '^[0-9]{6}'),
    CONSTRAINT ck_settlement_receivable_sequence_value CHECK (last_value > 0)
);

CREATE TABLE settlement.receivable (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    number varchar(80) NOT NULL,
    order_id uuid NOT NULL,
    order_number varchar(80) NOT NULL,
    partner_id uuid NOT NULL,
    partner_number varchar(80) NOT NULL,
    partner_name varchar(200) NOT NULL,
    partner_version integer NOT NULL,
    currency char(3) NOT NULL,
    original_amount numeric(19,4) NOT NULL,
    paid_net_amount numeric(19,4) NOT NULL,
    outstanding_amount numeric(19,4) NOT NULL,
    due_date date NOT NULL,
    status varchar(30) NOT NULL,
    trigger_policy_code varchar(80) NOT NULL,
    trigger_policy_version integer NOT NULL,
    trigger_type varchar(80) NOT NULL,
    trigger_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version bigint NOT NULL,
    CONSTRAINT uq_settlement_receivable_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_settlement_receivable_number UNIQUE (tenant_id, number),
    CONSTRAINT uq_settlement_receivable_order_policy
        UNIQUE (tenant_id, order_id, trigger_policy_code, trigger_policy_version),
    CONSTRAINT uq_settlement_receivable_trigger UNIQUE (tenant_id, trigger_type, trigger_id),
    CONSTRAINT ck_settlement_receivable_currency CHECK (currency ~ '^[A-Z]{3}'),
    CONSTRAINT ck_settlement_receivable_original CHECK (original_amount > 0),
    CONSTRAINT ck_settlement_receivable_paid CHECK (paid_net_amount >= 0 AND paid_net_amount <= original_amount),
    CONSTRAINT ck_settlement_receivable_outstanding
        CHECK (outstanding_amount >= 0 AND outstanding_amount = original_amount - paid_net_amount),
    CONSTRAINT ck_settlement_receivable_status
        CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'PAID', 'OVERDUE')),
    CONSTRAINT ck_settlement_receivable_policy_version CHECK (trigger_policy_version > 0),
    CONSTRAINT ck_settlement_receivable_version CHECK (version >= 0)
);

CREATE INDEX ix_settlement_receivable_queue
    ON settlement.receivable (tenant_id, status, due_date, id);
CREATE INDEX ix_settlement_receivable_partner
    ON settlement.receivable (tenant_id, partner_id, status, due_date, id);

CREATE TABLE settlement.payment_record (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    receivable_id uuid NOT NULL,
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL,
    method varchar(40) NOT NULL,
    external_reference varchar(100) NOT NULL,
    occurred_on date NOT NULL,
    note varchar(500),
    actor_id uuid NOT NULL,
    idempotency_key_hash char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    correlation_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT uq_settlement_payment_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_settlement_payment_receivable_id UNIQUE (tenant_id, receivable_id, id),
    CONSTRAINT uq_settlement_payment_reference UNIQUE (tenant_id, external_reference),
    CONSTRAINT uq_settlement_payment_idempotency UNIQUE (tenant_id, idempotency_key_hash),
    CONSTRAINT fk_settlement_payment_receivable
        FOREIGN KEY (tenant_id, receivable_id)
        REFERENCES settlement.receivable (tenant_id, id),
    CONSTRAINT ck_settlement_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_settlement_payment_currency CHECK (currency ~ '^[A-Z]{3}'),
    CONSTRAINT ck_settlement_payment_method
        CHECK (method IN ('BANK_TRANSFER', 'CASH_SIMULATION', 'OTHER_SIMULATION')),
    CONSTRAINT ck_settlement_payment_key_hash CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}'),
    CONSTRAINT ck_settlement_payment_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}')
);

CREATE INDEX ix_settlement_payment_timeline
    ON settlement.payment_record (tenant_id, receivable_id, recorded_at, id);

CREATE TABLE settlement.payment_reversal (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    receivable_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL,
    reason varchar(500) NOT NULL,
    actor_id uuid NOT NULL,
    idempotency_key_hash char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    correlation_id uuid NOT NULL,
    reversed_at timestamptz NOT NULL,
    CONSTRAINT uq_settlement_reversal_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_settlement_reversal_idempotency UNIQUE (tenant_id, idempotency_key_hash),
    CONSTRAINT fk_settlement_reversal_receivable
        FOREIGN KEY (tenant_id, receivable_id)
        REFERENCES settlement.receivable (tenant_id, id),
    CONSTRAINT fk_settlement_reversal_payment
        FOREIGN KEY (tenant_id, receivable_id, payment_id)
        REFERENCES settlement.payment_record (tenant_id, receivable_id, id),
    CONSTRAINT ck_settlement_reversal_amount CHECK (amount > 0),
    CONSTRAINT ck_settlement_reversal_currency CHECK (currency ~ '^[A-Z]{3}'),
    CONSTRAINT ck_settlement_reversal_reason CHECK (char_length(trim(reason)) BETWEEN 5 AND 500),
    CONSTRAINT ck_settlement_reversal_key_hash CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}'),
    CONSTRAINT ck_settlement_reversal_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}')
);

CREATE INDEX ix_settlement_reversal_timeline
    ON settlement.payment_reversal (tenant_id, receivable_id, reversed_at, id);
CREATE INDEX ix_settlement_reversal_payment
    ON settlement.payment_reversal (tenant_id, payment_id, reversed_at, id);

CREATE TABLE settlement.receivable_history (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    receivable_id uuid NOT NULL,
    action varchar(40) NOT NULL,
    previous_status varchar(30),
    new_status varchar(30) NOT NULL,
    amount numeric(19,4),
    currency char(3),
    actor_id uuid,
    safe_reason varchar(500),
    source_event_id uuid,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT fk_settlement_history_receivable
        FOREIGN KEY (tenant_id, receivable_id)
        REFERENCES settlement.receivable (tenant_id, id),
    CONSTRAINT ck_settlement_history_action
        CHECK (action IN ('RECEIVABLE_CREATED', 'PAYMENT_RECORDED', 'PAYMENT_REVERSED', 'MARKED_OVERDUE')),
    CONSTRAINT ck_settlement_history_status
        CHECK (new_status IN ('OPEN', 'PARTIALLY_PAID', 'PAID', 'OVERDUE')),
    CONSTRAINT ck_settlement_history_amount CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT ck_settlement_history_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}')
);

CREATE INDEX ix_settlement_history_timeline
    ON settlement.receivable_history (tenant_id, receivable_id, occurred_at, id);
CREATE UNIQUE INDEX uq_settlement_history_source_action
    ON settlement.receivable_history (tenant_id, receivable_id, action, source_event_id)
    WHERE source_event_id IS NOT NULL;

CREATE RULE payment_record_no_update
AS ON UPDATE TO settlement.payment_record DO INSTEAD NOTHING;
CREATE RULE payment_record_no_delete
AS ON DELETE TO settlement.payment_record DO INSTEAD NOTHING;

CREATE RULE payment_reversal_no_update
AS ON UPDATE TO settlement.payment_reversal DO INSTEAD NOTHING;
CREATE RULE payment_reversal_no_delete
AS ON DELETE TO settlement.payment_reversal DO INSTEAD NOTHING;

CREATE RULE receivable_history_no_update
AS ON UPDATE TO settlement.receivable_history DO INSTEAD NOTHING;
CREATE RULE receivable_history_no_delete
AS ON DELETE TO settlement.receivable_history DO INSTEAD NOTHING;
