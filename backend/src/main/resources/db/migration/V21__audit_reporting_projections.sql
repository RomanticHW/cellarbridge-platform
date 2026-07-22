CREATE SCHEMA IF NOT EXISTS audit_reporting;

CREATE TABLE audit_reporting.projection_generation (
    tenant_id uuid NOT NULL,
    generation bigint NOT NULL,
    status varchar(20) NOT NULL,
    schema_version integer NOT NULL,
    source_event_count bigint NOT NULL DEFAULT 0,
    data_as_of timestamptz,
    created_at timestamptz NOT NULL,
    activated_at timestamptz,
    PRIMARY KEY (tenant_id, generation),
    CONSTRAINT ck_audit_reporting_generation_positive CHECK (generation > 0),
    CONSTRAINT ck_audit_reporting_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_audit_reporting_generation_count CHECK (source_event_count >= 0),
    CONSTRAINT ck_audit_reporting_generation_status
        CHECK (status IN ('ACTIVE', 'STAGING', 'RETIRED', 'FAILED'))
);

CREATE UNIQUE INDEX uq_audit_reporting_active_generation
    ON audit_reporting.projection_generation (tenant_id) WHERE status = 'ACTIVE';

CREATE TABLE audit_reporting.projector_checkpoint (
    tenant_id uuid NOT NULL,
    projector_name varchar(120) NOT NULL,
    projection_version integer NOT NULL,
    last_event_id uuid,
    last_occurred_at timestamptz,
    processed_count bigint NOT NULL DEFAULT 0,
    duplicate_count bigint NOT NULL DEFAULT 0,
    pending_count bigint NOT NULL DEFAULT 0,
    dead_letter_count bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, projector_name),
    CONSTRAINT ck_audit_reporting_checkpoint_version CHECK (projection_version > 0),
    CONSTRAINT ck_audit_reporting_checkpoint_counts CHECK (
        processed_count >= 0 AND duplicate_count >= 0
        AND pending_count >= 0 AND dead_letter_count >= 0
    )
);

CREATE TABLE audit_reporting.projector_inbox (
    tenant_id uuid NOT NULL,
    projector_name varchar(120) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    payload_hash char(64) NOT NULL,
    event_occurred_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    error_code varchar(100),
    dependency_key varchar(240),
    resolution_action varchar(40),
    retry_count integer NOT NULL DEFAULT 0,
    first_received_at timestamptz NOT NULL,
    processed_at timestamptz,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, projector_name, event_id),
    CONSTRAINT uq_audit_reporting_inbox_event_binding UNIQUE (projector_name, event_id),
    CONSTRAINT ck_audit_reporting_inbox_hash CHECK (payload_hash ~ '^[0-9a-f]{64}'),
    CONSTRAINT ck_audit_reporting_inbox_retry CHECK (retry_count >= 0),
    CONSTRAINT ck_audit_reporting_inbox_status
        CHECK (status IN ('PROCESSING', 'PROCESSED', 'PENDING', 'DEAD_LETTER'))
);

CREATE INDEX ix_audit_reporting_inbox_attention
    ON audit_reporting.projector_inbox (tenant_id, status, updated_at, event_id);

CREATE TABLE audit_reporting.audit_entry (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    module varchar(80) NOT NULL,
    action varchar(120) NOT NULL,
    outcome varchar(30) NOT NULL,
    subject_type varchar(80) NOT NULL,
    subject_id uuid NOT NULL,
    subject_number varchar(80) NOT NULL,
    actor_type varchar(40) NOT NULL,
    actor_id uuid,
    actor_display varchar(160),
    correlation_id uuid NOT NULL,
    causation_id uuid NOT NULL,
    previous_state varchar(80),
    new_state varchar(80),
    safe_reason varchar(500),
    safe_changed_fields jsonb NOT NULL DEFAULT '[]'::jsonb,
    classification varchar(40) NOT NULL,
    schema_version integer NOT NULL,
    entry_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_audit_reporting_audit_source UNIQUE (tenant_id, source_event_id),
    CONSTRAINT ck_audit_reporting_audit_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'OBSERVED')),
    CONSTRAINT ck_audit_reporting_audit_classification
        CHECK (classification IN ('INTERNAL', 'COMMERCIAL_SENSITIVE', 'TECHNICAL_SENSITIVE')),
    CONSTRAINT ck_audit_reporting_audit_schema CHECK (schema_version > 0),
    CONSTRAINT ck_audit_reporting_audit_hash CHECK (entry_hash ~ '^[0-9a-f]{64}')
);

CREATE INDEX ix_audit_reporting_audit_subject
    ON audit_reporting.audit_entry
       (tenant_id, subject_type, subject_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_reporting_audit_correlation
    ON audit_reporting.audit_entry (tenant_id, correlation_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_reporting_audit_actor
    ON audit_reporting.audit_entry (tenant_id, actor_id, occurred_at DESC, id DESC);

CREATE FUNCTION audit_reporting.prevent_audit_entry_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit entries are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER audit_entry_no_update_or_delete
BEFORE UPDATE OR DELETE ON audit_reporting.audit_entry
FOR EACH ROW EXECUTE FUNCTION audit_reporting.prevent_audit_entry_mutation();

CREATE TABLE audit_reporting.timeline_projection (
    tenant_id uuid NOT NULL,
    generation bigint NOT NULL,
    source_event_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    event_type varchar(160) NOT NULL,
    source_module varchar(80) NOT NULL,
    subject_type varchar(80) NOT NULL,
    subject_id uuid NOT NULL,
    subject_number varchar(80) NOT NULL,
    related_order_id uuid,
    related_quotation_id uuid,
    related_partner_id uuid,
    actor_type varchar(40) NOT NULL,
    actor_id uuid,
    safe_summary varchar(500) NOT NULL,
    internal_summary varchar(500),
    visibility varchar(30) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NOT NULL,
    data_as_of timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, generation, source_event_id),
    CONSTRAINT fk_audit_reporting_timeline_generation
        FOREIGN KEY (tenant_id, generation)
        REFERENCES audit_reporting.projection_generation (tenant_id, generation),
    CONSTRAINT ck_audit_reporting_timeline_visibility
        CHECK (visibility IN ('INTERNAL', 'CUSTOMER', 'TECHNICAL'))
);

CREATE INDEX ix_audit_reporting_timeline_subject
    ON audit_reporting.timeline_projection
       (tenant_id, generation, subject_type, subject_id, occurred_at DESC, source_event_id DESC);
CREATE INDEX ix_audit_reporting_timeline_order
    ON audit_reporting.timeline_projection
       (tenant_id, generation, related_order_id, occurred_at DESC, source_event_id DESC)
    WHERE related_order_id IS NOT NULL;
CREATE INDEX ix_audit_reporting_timeline_quotation
    ON audit_reporting.timeline_projection
       (tenant_id, generation, related_quotation_id, occurred_at DESC, source_event_id DESC)
    WHERE related_quotation_id IS NOT NULL;
CREATE INDEX ix_audit_reporting_timeline_partner
    ON audit_reporting.timeline_projection
       (tenant_id, generation, related_partner_id, occurred_at DESC, source_event_id DESC)
    WHERE related_partner_id IS NOT NULL;

CREATE TABLE audit_reporting.subject_state_projection (
    tenant_id uuid NOT NULL,
    generation bigint NOT NULL,
    subject_type varchar(80) NOT NULL,
    subject_id uuid NOT NULL,
    subject_number varchar(80) NOT NULL,
    state varchar(80),
    business_version bigint,
    last_event_id uuid NOT NULL,
    last_event_type varchar(160) NOT NULL,
    last_occurred_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, generation, subject_type, subject_id),
    CONSTRAINT fk_audit_reporting_subject_generation
        FOREIGN KEY (tenant_id, generation)
        REFERENCES audit_reporting.projection_generation (tenant_id, generation),
    CONSTRAINT ck_audit_reporting_subject_version
        CHECK (business_version IS NULL OR business_version >= 0)
);

CREATE TABLE audit_reporting.work_item_projection (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    generation bigint NOT NULL,
    dedup_key varchar(240) NOT NULL,
    type varchar(50) NOT NULL,
    subject_type varchar(80) NOT NULL,
    subject_id uuid NOT NULL,
    subject_number varchar(80) NOT NULL,
    title varchar(240) NOT NULL,
    safe_summary varchar(500),
    priority varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    candidate_permission varchar(100) NOT NULL,
    candidate_role varchar(80),
    assignee_user_id uuid,
    owner_user_id uuid,
    due_at timestamptz,
    source_event_id uuid NOT NULL,
    source_occurred_at timestamptz NOT NULL,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    PRIMARY KEY (tenant_id, generation, id),
    CONSTRAINT uq_audit_reporting_work_dedup UNIQUE (tenant_id, generation, dedup_key),
    CONSTRAINT uq_audit_reporting_work_source UNIQUE (tenant_id, generation, source_event_id),
    CONSTRAINT fk_audit_reporting_work_generation
        FOREIGN KEY (tenant_id, generation)
        REFERENCES audit_reporting.projection_generation (tenant_id, generation),
    CONSTRAINT ck_audit_reporting_work_type CHECK (
        type IN ('PARTNER_REVIEW', 'QUOTATION_APPROVAL', 'FULFILLMENT_STEP',
                 'EXCEPTION_ACTION', 'RECEIVABLE_FOLLOW_UP')
    ),
    CONSTRAINT ck_audit_reporting_work_priority
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_audit_reporting_work_status
        CHECK (status IN ('OPEN', 'CLAIMED', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_audit_reporting_work_version CHECK (version >= 0)
);

CREATE INDEX ix_audit_reporting_work_queue
    ON audit_reporting.work_item_projection
       (tenant_id, generation, status, due_at, priority, id);
CREATE INDEX ix_audit_reporting_work_assignee
    ON audit_reporting.work_item_projection
       (tenant_id, generation, assignee_user_id, status, due_at, id);

CREATE TABLE audit_reporting.metric_fact_projection (
    tenant_id uuid NOT NULL,
    generation bigint NOT NULL,
    source_event_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    occurred_date date NOT NULL,
    metric_type varchar(80) NOT NULL,
    outcome varchar(80),
    route_code varchar(80),
    subject_type varchar(80) NOT NULL,
    subject_id uuid NOT NULL,
    owner_user_id uuid,
    duration_ms bigint,
    amount numeric(19,4),
    currency char(3),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (tenant_id, generation, source_event_id, metric_type),
    CONSTRAINT fk_audit_reporting_metric_generation
        FOREIGN KEY (tenant_id, generation)
        REFERENCES audit_reporting.projection_generation (tenant_id, generation),
    CONSTRAINT ck_audit_reporting_metric_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_audit_reporting_metric_amount CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_audit_reporting_metric_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}')
);

CREATE INDEX ix_audit_reporting_metric_range
    ON audit_reporting.metric_fact_projection
       (tenant_id, generation, occurred_date, metric_type, outcome);
CREATE INDEX ix_audit_reporting_metric_route
    ON audit_reporting.metric_fact_projection
       (tenant_id, generation, occurred_date, route_code)
    WHERE route_code IS NOT NULL;
