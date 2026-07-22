CREATE SCHEMA fulfillment;

CREATE SEQUENCE fulfillment.plan_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE fulfillment.template_version (
    id uuid PRIMARY KEY,
    code varchar(80) NOT NULL,
    version varchar(30) NOT NULL,
    route_code varchar(50) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    status varchar(20) NOT NULL,
    CONSTRAINT uq_fulfillment_template_code_version UNIQUE (code, version),
    CONSTRAINT uq_fulfillment_template_route_period UNIQUE (route_code, effective_from),
    CONSTRAINT ck_fulfillment_template_route
        CHECK (route_code IN ('SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE')),
    CONSTRAINT ck_fulfillment_template_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_fulfillment_template_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE TABLE fulfillment.template_step (
    id uuid PRIMARY KEY,
    template_id uuid NOT NULL REFERENCES fulfillment.template_version(id),
    code varchar(80) NOT NULL,
    name varchar(120) NOT NULL,
    sequence_number smallint NOT NULL,
    owner_role varchar(60) NOT NULL,
    planned_duration_minutes integer NOT NULL,
    customer_visible boolean NOT NULL,
    optional boolean NOT NULL,
    skippable boolean NOT NULL,
    CONSTRAINT uq_fulfillment_template_step_code UNIQUE (template_id, code),
    CONSTRAINT uq_fulfillment_template_step_sequence UNIQUE (template_id, sequence_number),
    CONSTRAINT uq_fulfillment_template_step_identity UNIQUE (template_id, id),
    CONSTRAINT ck_fulfillment_template_step_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_fulfillment_template_step_duration CHECK (planned_duration_minutes > 0),
    CONSTRAINT ck_fulfillment_template_step_text
        CHECK (btrim(code) <> '' AND btrim(name) <> '' AND btrim(owner_role) <> ''),
    CONSTRAINT ck_fulfillment_template_step_skip CHECK (NOT skippable OR optional)
);

CREATE TABLE fulfillment.template_step_dependency (
    template_id uuid NOT NULL,
    step_id uuid NOT NULL,
    depends_on_step_id uuid NOT NULL,
    PRIMARY KEY (template_id, step_id, depends_on_step_id),
    CONSTRAINT fk_fulfillment_template_dependency_step
        FOREIGN KEY (template_id, step_id)
        REFERENCES fulfillment.template_step(template_id, id),
    CONSTRAINT fk_fulfillment_template_dependency_required
        FOREIGN KEY (template_id, depends_on_step_id)
        REFERENCES fulfillment.template_step(template_id, id),
    CONSTRAINT ck_fulfillment_template_dependency_self CHECK (step_id <> depends_on_step_id)
);

CREATE TABLE fulfillment.fulfillment_plan (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    number varchar(80) NOT NULL,
    order_id uuid NOT NULL,
    order_number varchar(80) NOT NULL,
    reservation_id uuid NOT NULL,
    route_code varchar(50) NOT NULL,
    template_code varchar(80) NOT NULL,
    template_version varchar(30) NOT NULL,
    template_snapshot jsonb NOT NULL,
    status varchar(30) NOT NULL,
    due_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fulfillment_plan_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_fulfillment_plan_tenant_number UNIQUE (tenant_id, number),
    CONSTRAINT uq_fulfillment_plan_tenant_order UNIQUE (tenant_id, order_id),
    CONSTRAINT uq_fulfillment_plan_tenant_reservation UNIQUE (tenant_id, reservation_id),
    CONSTRAINT ck_fulfillment_plan_route
        CHECK (route_code IN ('SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE')),
    CONSTRAINT ck_fulfillment_plan_snapshot CHECK (jsonb_typeof(template_snapshot) = 'object'),
    CONSTRAINT ck_fulfillment_plan_status
        CHECK (status IN ('PLANNED', 'READY', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED',
                          'CANCELLING', 'CANCELLATION_FAILED', 'CANCELLED')),
    CONSTRAINT ck_fulfillment_plan_version CHECK (version >= 0),
    CONSTRAINT ck_fulfillment_plan_time
        CHECK (due_at >= created_at AND updated_at >= created_at
            AND ((status = 'COMPLETED' AND completed_at IS NOT NULL AND completed_at >= created_at)
              OR (status <> 'COMPLETED' AND completed_at IS NULL)))
);

CREATE INDEX ix_fulfillment_plan_work
    ON fulfillment.fulfillment_plan (tenant_id, status, due_at, id);

CREATE TABLE fulfillment.fulfillment_step (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    code varchar(80) NOT NULL,
    name varchar(120) NOT NULL,
    sequence_number smallint NOT NULL,
    owner_role varchar(60) NOT NULL,
    status varchar(20) NOT NULL,
    overdue_from_status varchar(20),
    planned_start_at timestamptz NOT NULL,
    due_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    failure_code varchar(80),
    safe_message varchar(500),
    customer_visible boolean NOT NULL,
    optional boolean NOT NULL,
    skippable boolean NOT NULL,
    attempt integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fulfillment_step_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_fulfillment_step_plan_code UNIQUE (tenant_id, plan_id, code),
    CONSTRAINT uq_fulfillment_step_plan_sequence UNIQUE (tenant_id, plan_id, sequence_number),
    CONSTRAINT uq_fulfillment_step_plan_identity UNIQUE (tenant_id, plan_id, id),
    CONSTRAINT fk_fulfillment_step_plan
        FOREIGN KEY (tenant_id, plan_id)
        REFERENCES fulfillment.fulfillment_plan(tenant_id, id),
    CONSTRAINT ck_fulfillment_step_status
        CHECK (status IN ('BLOCKED', 'READY', 'IN_PROGRESS', 'COMPLETED', 'FAILED',
                          'OVERDUE', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_fulfillment_step_overdue
        CHECK ((status = 'OVERDUE' AND overdue_from_status IN ('READY', 'IN_PROGRESS'))
            OR (status <> 'OVERDUE' AND overdue_from_status IS NULL)),
    CONSTRAINT ck_fulfillment_step_time
        CHECK (planned_start_at <= due_at
            AND (completed_at IS NULL OR (started_at IS NOT NULL AND completed_at >= started_at))),
    CONSTRAINT ck_fulfillment_step_failure
        CHECK ((status = 'FAILED' AND failure_code IS NOT NULL AND safe_message IS NOT NULL)
            OR (status <> 'FAILED' AND failure_code IS NULL)),
    CONSTRAINT ck_fulfillment_step_skip CHECK (NOT skippable OR optional),
    CONSTRAINT ck_fulfillment_step_attempt CHECK (attempt >= 0),
    CONSTRAINT ck_fulfillment_step_version CHECK (version >= 0)
);

CREATE INDEX ix_fulfillment_step_sla
    ON fulfillment.fulfillment_step (status, due_at, tenant_id, plan_id, id);

CREATE TABLE fulfillment.fulfillment_step_dependency (
    tenant_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    step_id uuid NOT NULL,
    depends_on_step_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, plan_id, step_id, depends_on_step_id),
    CONSTRAINT fk_fulfillment_dependency_step
        FOREIGN KEY (tenant_id, plan_id, step_id)
        REFERENCES fulfillment.fulfillment_step(tenant_id, plan_id, id),
    CONSTRAINT fk_fulfillment_dependency_required
        FOREIGN KEY (tenant_id, plan_id, depends_on_step_id)
        REFERENCES fulfillment.fulfillment_step(tenant_id, plan_id, id),
    CONSTRAINT ck_fulfillment_dependency_self CHECK (step_id <> depends_on_step_id)
);

CREATE TABLE fulfillment.milestone (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    step_id uuid NOT NULL,
    code varchar(80) NOT NULL,
    label varchar(160) NOT NULL,
    occurred_at timestamptz NOT NULL,
    customer_visible boolean NOT NULL,
    CONSTRAINT uq_fulfillment_milestone_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_fulfillment_milestone_step UNIQUE (tenant_id, plan_id, step_id),
    CONSTRAINT fk_fulfillment_milestone_step
        FOREIGN KEY (tenant_id, plan_id, step_id)
        REFERENCES fulfillment.fulfillment_step(tenant_id, plan_id, id)
);

CREATE TABLE fulfillment.step_action_command (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    step_id uuid NOT NULL,
    action varchar(20) NOT NULL,
    key_hash varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    result_snapshot jsonb,
    actor_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT uq_fulfillment_command_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_fulfillment_command_key UNIQUE (tenant_id, plan_id, key_hash),
    CONSTRAINT fk_fulfillment_command_step
        FOREIGN KEY (tenant_id, plan_id, step_id)
        REFERENCES fulfillment.fulfillment_step(tenant_id, plan_id, id),
    CONSTRAINT ck_fulfillment_command_action CHECK (action IN ('START', 'COMPLETE', 'FAIL', 'RETRY', 'SKIP')),
    CONSTRAINT ck_fulfillment_command_hashes
        CHECK (key_hash ~ '^[0-9a-f]{64}' AND request_hash ~ '^[0-9a-f]{64}'),
    CONSTRAINT ck_fulfillment_command_result
        CHECK ((result_snapshot IS NULL AND completed_at IS NULL)
            OR (jsonb_typeof(result_snapshot) = 'object' AND completed_at >= created_at))
);

CREATE TABLE fulfillment.simulated_adapter_attempt (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    step_id uuid NOT NULL,
    command_id uuid NOT NULL,
    scenario varchar(20) NOT NULL,
    outcome varchar(20) NOT NULL,
    reference varchar(120) NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uq_fulfillment_adapter_command UNIQUE (tenant_id, command_id),
    CONSTRAINT fk_fulfillment_adapter_command
        FOREIGN KEY (tenant_id, command_id)
        REFERENCES fulfillment.step_action_command(tenant_id, id),
    CONSTRAINT fk_fulfillment_adapter_step
        FOREIGN KEY (tenant_id, plan_id, step_id)
        REFERENCES fulfillment.fulfillment_step(tenant_id, plan_id, id),
    CONSTRAINT ck_fulfillment_adapter_scenario CHECK (scenario IN ('SUCCESS', 'FAILURE', 'DELAY')),
    CONSTRAINT ck_fulfillment_adapter_outcome CHECK (outcome IN ('CONFIRMED', 'FAILED', 'DELAYED'))
);

INSERT INTO fulfillment.template_version
    (id, code, version, route_code, effective_from, status)
VALUES
    ('81000000-0000-4000-8000-000000000001', 'SH_GENERAL_TRADE', '2026.1', 'SH_GENERAL_TRADE', '2026-01-01T00:00:00Z', 'ACTIVE'),
    ('81000000-0000-4000-8000-000000000002', 'NB_BONDED_B2B', '2026.1', 'NB_BONDED_B2B', '2026-01-01T00:00:00Z', 'ACTIVE'),
    ('81000000-0000-4000-8000-000000000003', 'HK_FREE_TRADE', '2026.1', 'HK_FREE_TRADE', '2026-01-01T00:00:00Z', 'ACTIVE');

INSERT INTO fulfillment.template_step
    (id, template_id, code, name, sequence_number, owner_role, planned_duration_minutes,
     customer_visible, optional, skippable)
VALUES
    ('82000000-0000-4000-8000-000000000001', '81000000-0000-4000-8000-000000000001', 'ORDER_CONFIRMATION', 'Order confirmation', 1, 'TRADE_OPERATOR', 60, true, false, false),
    ('82000000-0000-4000-8000-000000000002', '81000000-0000-4000-8000-000000000001', 'WAREHOUSE_PICKING', 'Warehouse picking', 2, 'WAREHOUSE_OPERATOR', 480, false, false, false),
    ('82000000-0000-4000-8000-000000000003', '81000000-0000-4000-8000-000000000001', 'DISPATCH', 'Dispatch', 3, 'WAREHOUSE_OPERATOR', 240, true, false, false),
    ('82000000-0000-4000-8000-000000000004', '81000000-0000-4000-8000-000000000001', 'LINEHAUL_TRANSPORT', 'Linehaul transport', 4, 'TRADE_OPERATOR', 1440, false, false, false),
    ('82000000-0000-4000-8000-000000000005', '81000000-0000-4000-8000-000000000001', 'SIGNED_DELIVERY', 'Signed delivery', 5, 'TRADE_OPERATOR', 240, true, false, false),
    ('82000000-0000-4000-8000-000000000011', '81000000-0000-4000-8000-000000000002', 'ORDER_CONFIRMATION', 'Order confirmation', 1, 'TRADE_OPERATOR', 60, true, false, false),
    ('82000000-0000-4000-8000-000000000012', '81000000-0000-4000-8000-000000000002', 'BONDED_WAREHOUSE_OPERATION', 'Bonded warehouse operation', 2, 'WAREHOUSE_OPERATOR', 480, false, false, false),
    ('82000000-0000-4000-8000-000000000013', '81000000-0000-4000-8000-000000000002', 'RELEASE_SIMULATION', 'Release simulation', 3, 'TRADE_OPERATOR', 240, false, false, false),
    ('82000000-0000-4000-8000-000000000014', '81000000-0000-4000-8000-000000000002', 'DISPATCH', 'Dispatch', 4, 'WAREHOUSE_OPERATOR', 240, true, false, false),
    ('82000000-0000-4000-8000-000000000015', '81000000-0000-4000-8000-000000000002', 'SIGNED_DELIVERY', 'Signed delivery', 5, 'TRADE_OPERATOR', 1440, true, false, false),
    ('82000000-0000-4000-8000-000000000021', '81000000-0000-4000-8000-000000000003', 'ORDER_CONFIRMATION', 'Order confirmation', 1, 'TRADE_OPERATOR', 60, true, false, false),
    ('82000000-0000-4000-8000-000000000022', '81000000-0000-4000-8000-000000000003', 'HONG_KONG_WAREHOUSE_OPERATION', 'Hong Kong warehouse operation', 2, 'WAREHOUSE_OPERATOR', 480, false, false, false),
    ('82000000-0000-4000-8000-000000000023', '81000000-0000-4000-8000-000000000003', 'CROSS_BORDER_TRANSPORT_SIMULATION', 'Cross-border transport simulation', 3, 'TRADE_OPERATOR', 1440, false, false, false),
    ('82000000-0000-4000-8000-000000000024', '81000000-0000-4000-8000-000000000003', 'DESTINATION_DELIVERY', 'Destination delivery', 4, 'TRADE_OPERATOR', 480, true, false, false),
    ('82000000-0000-4000-8000-000000000025', '81000000-0000-4000-8000-000000000003', 'SIGNED_DELIVERY', 'Signed delivery', 5, 'TRADE_OPERATOR', 240, true, false, false);

INSERT INTO fulfillment.template_step_dependency (template_id, step_id, depends_on_step_id)
VALUES
    ('81000000-0000-4000-8000-000000000001', '82000000-0000-4000-8000-000000000002', '82000000-0000-4000-8000-000000000001'),
    ('81000000-0000-4000-8000-000000000001', '82000000-0000-4000-8000-000000000003', '82000000-0000-4000-8000-000000000002'),
    ('81000000-0000-4000-8000-000000000001', '82000000-0000-4000-8000-000000000004', '82000000-0000-4000-8000-000000000003'),
    ('81000000-0000-4000-8000-000000000001', '82000000-0000-4000-8000-000000000005', '82000000-0000-4000-8000-000000000004'),
    ('81000000-0000-4000-8000-000000000002', '82000000-0000-4000-8000-000000000012', '82000000-0000-4000-8000-000000000011'),
    ('81000000-0000-4000-8000-000000000002', '82000000-0000-4000-8000-000000000013', '82000000-0000-4000-8000-000000000012'),
    ('81000000-0000-4000-8000-000000000002', '82000000-0000-4000-8000-000000000014', '82000000-0000-4000-8000-000000000013'),
    ('81000000-0000-4000-8000-000000000002', '82000000-0000-4000-8000-000000000015', '82000000-0000-4000-8000-000000000014'),
    ('81000000-0000-4000-8000-000000000003', '82000000-0000-4000-8000-000000000022', '82000000-0000-4000-8000-000000000021'),
    ('81000000-0000-4000-8000-000000000003', '82000000-0000-4000-8000-000000000023', '82000000-0000-4000-8000-000000000022'),
    ('81000000-0000-4000-8000-000000000003', '82000000-0000-4000-8000-000000000024', '82000000-0000-4000-8000-000000000023'),
    ('81000000-0000-4000-8000-000000000003', '82000000-0000-4000-8000-000000000025', '82000000-0000-4000-8000-000000000024');
