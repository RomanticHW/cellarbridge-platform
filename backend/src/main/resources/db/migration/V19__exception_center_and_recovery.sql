CREATE SCHEMA exception_center;

CREATE SEQUENCE exception_center.case_number_seq START WITH 1;

CREATE TABLE exception_center.exception_case (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    number varchar(40) NOT NULL,
    source_type varchar(80) NOT NULL,
    source_id uuid NOT NULL,
    source_number varchar(120) NOT NULL,
    category varchar(80) NOT NULL,
    dedup_key varchar(160) NOT NULL,
    severity varchar(20) NOT NULL
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status varchar(30) NOT NULL
        CHECK (status IN
          ('OPEN', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS',
           'RECOVERY_PENDING', 'RESOLVED', 'CLOSED')),
    assignee_id uuid,
    primary_case_id uuid,
    due_at timestamptz,
    summary varchar(240) NOT NULL,
    safe_details jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(safe_details) = 'object'),
    correlation_id uuid NOT NULL,
    causation_id uuid NOT NULL,
    opened_at timestamptz NOT NULL,
    resolved_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_exception_case_number UNIQUE (tenant_id, number),
    CONSTRAINT uq_exception_case_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_exception_case_primary FOREIGN KEY (tenant_id, primary_case_id)
        REFERENCES exception_center.exception_case(tenant_id, id),
    CONSTRAINT ck_exception_case_text CHECK
        (length(trim(source_type)) BETWEEN 2 AND 80
         AND length(trim(source_number)) BETWEEN 1 AND 120
         AND length(trim(category)) BETWEEN 2 AND 80
         AND length(trim(dedup_key)) BETWEEN 16 AND 160
         AND length(trim(summary)) BETWEEN 5 AND 240),
    CONSTRAINT ck_exception_case_resolution CHECK
        ((status IN ('RESOLVED', 'CLOSED')) = (resolved_at IS NOT NULL)
         AND ((status = 'CLOSED') = (closed_at IS NOT NULL))),
    CONSTRAINT ck_exception_case_times CHECK
        (updated_at >= created_at
         AND created_at >= opened_at
         AND (resolved_at IS NULL OR resolved_at >= opened_at)
         AND (closed_at IS NULL OR closed_at >= resolved_at)),
    CONSTRAINT ck_exception_case_primary CHECK
        (primary_case_id IS NULL OR primary_case_id <> id)
);

CREATE UNIQUE INDEX uq_exception_case_active_dedup
    ON exception_center.exception_case (tenant_id, dedup_key)
    WHERE status <> 'CLOSED';

CREATE INDEX ix_exception_case_queue
    ON exception_center.exception_case
        (tenant_id, status, severity, due_at, updated_at DESC, id);

CREATE INDEX ix_exception_case_source
    ON exception_center.exception_case (tenant_id, source_type, source_id, opened_at DESC);

CREATE INDEX ix_exception_case_primary
    ON exception_center.exception_case (tenant_id, primary_case_id)
    WHERE primary_case_id IS NOT NULL;

CREATE TABLE exception_center.case_occurrence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    case_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    detected_at timestamptz NOT NULL,
    evidence jsonb NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_case_occurrence_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES exception_center.exception_case(tenant_id, id),
    CONSTRAINT uq_case_occurrence_event UNIQUE (tenant_id, source_event_id, event_type)
);

CREATE INDEX ix_case_occurrence_timeline
    ON exception_center.case_occurrence (tenant_id, case_id, detected_at, id);

CREATE TABLE exception_center.case_history (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    case_id uuid NOT NULL,
    action varchar(80) NOT NULL,
    actor_type varchar(30) NOT NULL
        CHECK (actor_type IN ('SYSTEM', 'INTERNAL_USER')),
    actor_id uuid,
    previous_status varchar(30),
    new_status varchar(30) NOT NULL,
    reason_code varchar(80),
    safe_reason varchar(500),
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_case_history_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES exception_center.exception_case(tenant_id, id),
    CONSTRAINT ck_case_history_actor CHECK
        ((actor_type = 'SYSTEM' AND actor_id IS NULL)
         OR (actor_type = 'INTERNAL_USER' AND actor_id IS NOT NULL)),
    CONSTRAINT ck_case_history_reason CHECK
        (safe_reason IS NULL OR length(trim(safe_reason)) BETWEEN 3 AND 500)
);

CREATE INDEX ix_case_history_timeline
    ON exception_center.case_history (tenant_id, case_id, occurred_at, id);

CREATE TABLE exception_center.recovery_attempt (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    case_id uuid NOT NULL,
    action varchar(80) NOT NULL,
    requester_id uuid NOT NULL,
    idempotency_key_hash char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    input_summary jsonb NOT NULL CHECK (jsonb_typeof(input_summary) = 'object'),
    requested_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_recovery_attempt_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES exception_center.exception_case(tenant_id, id),
    CONSTRAINT uq_recovery_attempt_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_recovery_attempt_key UNIQUE (tenant_id, case_id, idempotency_key_hash),
    CONSTRAINT ck_recovery_attempt_hashes CHECK
        (length(trim(idempotency_key_hash)) = 64
         AND translate(trim(idempotency_key_hash), '0123456789abcdef', '') = ''
         AND length(trim(request_hash)) = 64
         AND translate(trim(request_hash), '0123456789abcdef', '') = '')
);

CREATE INDEX ix_recovery_attempt_case
    ON exception_center.recovery_attempt (tenant_id, case_id, requested_at, id);

CREATE TABLE exception_center.recovery_outcome (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    result_code varchar(80) NOT NULL,
    safe_result varchar(500) NOT NULL,
    source_state varchar(80),
    completed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_recovery_outcome_attempt FOREIGN KEY (tenant_id, attempt_id)
        REFERENCES exception_center.recovery_attempt(tenant_id, id),
    CONSTRAINT uq_recovery_outcome_attempt UNIQUE (tenant_id, attempt_id),
    CONSTRAINT ck_recovery_outcome_text CHECK
        (length(trim(result_code)) BETWEEN 3 AND 80
         AND length(trim(safe_result)) BETWEEN 3 AND 500)
);

CREATE TABLE exception_center.work_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    case_id uuid NOT NULL,
    assignee_id uuid,
    status varchar(20) NOT NULL CHECK (status IN ('OPEN', 'COMPLETED')),
    due_at timestamptz,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_work_item_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES exception_center.exception_case(tenant_id, id),
    CONSTRAINT uq_work_item_case UNIQUE (tenant_id, case_id),
    CONSTRAINT ck_work_item_completion CHECK
        ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE TABLE exception_center.notification_fact (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    case_id uuid NOT NULL,
    notification_key varchar(180) NOT NULL,
    audience varchar(80) NOT NULL,
    safe_message varchar(240) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_notification_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES exception_center.exception_case(tenant_id, id),
    CONSTRAINT uq_notification_key UNIQUE (tenant_id, notification_key)
);
