ALTER TABLE identity_access.user_mapping
    ADD COLUMN partner_id uuid;

COMMENT ON COLUMN identity_access.user_mapping.partner_id IS
    'Optional logical Partner scope for customer users. No cross-module foreign key is permitted.';

CREATE INDEX ix_user_mapping_tenant_partner
    ON identity_access.user_mapping (tenant_id, partner_id, status, id)
    WHERE partner_id IS NOT NULL;

CREATE SCHEMA trade_order;

CREATE SEQUENCE trade_order.order_number_seq START WITH 1;

CREATE TABLE trade_order.trade_order (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    number varchar(30) NOT NULL,
    source_quotation_id uuid NOT NULL,
    source_quotation_number varchar(30) NOT NULL,
    source_revision_id uuid NOT NULL,
    source_revision_number integer NOT NULL CHECK (source_revision_number > 0),
    source_event_id uuid NOT NULL,
    source_owner_id uuid,
    acceptance_id uuid NOT NULL,
    accepted_at timestamptz NOT NULL,
    partner_id uuid NOT NULL,
    partner_number varchar(80) NOT NULL,
    partner_display_name varchar(160) NOT NULL,
    partner_source_version bigint NOT NULL CHECK (partner_source_version >= 0),
    status varchar(40) NOT NULL CHECK (status IN
      ('PENDING_RESERVATION', 'RESERVED', 'RESERVATION_FAILED',
       'READY_FOR_FULFILLMENT', 'IN_FULFILLMENT', 'FULFILLED',
       'CANCELLATION_PENDING', 'CANCELLATION_FAILED', 'CANCELLED')),
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_amount numeric(19,4) NOT NULL CHECK (total_amount >= 0),
    payment_term_days integer NOT NULL CHECK (payment_term_days BETWEEN 0 AND 180),
    route_code varchar(80) NOT NULL,
    route_policy_version varchar(80) NOT NULL,
    route_estimated_delivery_date date NOT NULL,
    accepted_terms_version varchar(50) NOT NULL,
    requested_delivery_date date NOT NULL,
    delivery_address jsonb NOT NULL,
    commercial_snapshot jsonb NOT NULL,
    snapshot_schema_version varchar(20) NOT NULL DEFAULT '1',
    snapshot_hash varchar(160) NOT NULL,
    created_event_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_trade_order_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_trade_order_number UNIQUE (tenant_id, number),
    CONSTRAINT uq_trade_order_source_quotation UNIQUE (tenant_id, source_quotation_id),
    CONSTRAINT uq_trade_order_source_event UNIQUE (tenant_id, source_event_id),
    CONSTRAINT uq_trade_order_created_event UNIQUE (created_event_id),
    CONSTRAINT ck_trade_order_source_subject CHECK
        (length(trim(source_quotation_number)) BETWEEN 1 AND 30),
    CONSTRAINT ck_trade_order_partner_snapshot CHECK
        (length(trim(partner_number)) BETWEEN 1 AND 80
         AND length(trim(partner_display_name)) BETWEEN 1 AND 160),
    CONSTRAINT ck_trade_order_route_snapshot CHECK
        (length(trim(route_code)) BETWEEN 1 AND 80
         AND length(trim(route_policy_version)) BETWEEN 1 AND 80),
    CONSTRAINT ck_trade_order_terms_snapshot CHECK
        (length(trim(accepted_terms_version)) BETWEEN 1 AND 50),
    CONSTRAINT ck_trade_order_delivery_address CHECK
        (jsonb_typeof(delivery_address) = 'object'
         AND delivery_address <> '{}'::jsonb),
    CONSTRAINT ck_trade_order_commercial_snapshot CHECK
        (jsonb_typeof(commercial_snapshot) = 'object'
         AND commercial_snapshot <> '{}'::jsonb
         AND length(trim(snapshot_schema_version)) BETWEEN 1 AND 20),
    CONSTRAINT ck_trade_order_snapshot_hash CHECK
        (length(trim(snapshot_hash)) BETWEEN 1 AND 160),
    CONSTRAINT ck_trade_order_audit_times CHECK
        (created_at >= accepted_at AND updated_at >= created_at)
);

COMMENT ON TABLE trade_order.trade_order IS
    'Trade Order aggregate created from one immutable accepted-quotation event snapshot.';
COMMENT ON COLUMN trade_order.trade_order.source_quotation_id IS
    'Logical Quotation identifier. There is intentionally no cross-module foreign key.';
COMMENT ON COLUMN trade_order.trade_order.source_revision_id IS
    'Logical Quotation revision identifier. There is intentionally no cross-module foreign key.';
COMMENT ON COLUMN trade_order.trade_order.partner_id IS
    'Logical Partner identifier copied from the accepted event. There is no cross-module foreign key.';
COMMENT ON COLUMN trade_order.trade_order.source_owner_id IS
    'Optional logical IAM user identifier copied from the accepted event. Legacy accepted events may omit it; there is no cross-module foreign key.';
COMMENT ON COLUMN trade_order.trade_order.snapshot_hash IS
    'Opaque producer-supplied snapshot identity. Consumers compare it exactly and never recompute it.';

CREATE INDEX ix_trade_order_list
    ON trade_order.trade_order (tenant_id, created_at DESC, id DESC);

CREATE INDEX ix_trade_order_partner_list
    ON trade_order.trade_order (tenant_id, partner_id, created_at DESC, id DESC);

CREATE INDEX ix_trade_order_owner_list
    ON trade_order.trade_order (tenant_id, source_owner_id, created_at DESC, id DESC);

CREATE TABLE trade_order.order_line (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    source_quotation_line_id uuid NOT NULL,
    line_number integer NOT NULL CHECK (line_number > 0),
    sku_id uuid NOT NULL,
    sku_code varchar(80) NOT NULL,
    description varchar(240) NOT NULL,
    quantity numeric(19,6) NOT NULL CHECK (quantity > 0),
    quantity_unit varchar(20) NOT NULL CHECK (quantity_unit IN ('CASE', 'BOTTLE')),
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    net_unit_price numeric(19,4) NOT NULL CHECK (net_unit_price >= 0),
    line_total numeric(19,4) NOT NULL CHECK (line_total >= 0),
    supply_pool_id uuid,
    supply_type varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version = 0),
    CONSTRAINT uq_trade_order_line_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_trade_order_line_number UNIQUE (tenant_id, order_id, line_number),
    CONSTRAINT uq_trade_order_line_source UNIQUE
        (tenant_id, order_id, source_quotation_line_id),
    CONSTRAINT fk_trade_order_line_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES trade_order.trade_order (tenant_id, id),
    CONSTRAINT ck_trade_order_line_text CHECK
        (length(trim(sku_code)) BETWEEN 1 AND 80
         AND length(trim(description)) BETWEEN 1 AND 240),
    CONSTRAINT ck_trade_order_line_supply CHECK
        (supply_type IN
            ('DOMESTIC_ON_HAND', 'BONDED_ON_HAND', 'HONG_KONG_ON_HAND',
             'IN_TRANSIT_PRESALE', 'OVERSEAS_SOURCING')),
    CONSTRAINT ck_trade_order_line_audit_times CHECK (updated_at = created_at)
);

COMMENT ON COLUMN trade_order.order_line.sku_id IS
    'Logical Catalog SKU identifier. There is intentionally no cross-module foreign key.';
COMMENT ON COLUMN trade_order.order_line.supply_pool_id IS
    'Logical Inventory supply-pool identifier frozen from the source event; no cross-module foreign key.';

CREATE INDEX ix_trade_order_line_order
    ON trade_order.order_line (tenant_id, order_id, line_number, id);

CREATE OR REPLACE FUNCTION trade_order.assert_order_lines_match_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_tenant_id uuid;
    checked_order_id uuid;
    expected_line_count integer;
    actual_line_count integer;
BEGIN
    IF TG_TABLE_NAME = 'trade_order' THEN
        checked_tenant_id := NEW.tenant_id;
        checked_order_id := NEW.id;
    ELSE
        checked_tenant_id := NEW.tenant_id;
        checked_order_id := NEW.order_id;
    END IF;

    SELECT jsonb_array_length(commercial_snapshot -> 'lines')
      INTO expected_line_count
      FROM trade_order.trade_order
     WHERE tenant_id = checked_tenant_id
       AND id = checked_order_id;

    SELECT count(*)
      INTO actual_line_count
      FROM trade_order.order_line
     WHERE tenant_id = checked_tenant_id
       AND order_id = checked_order_id;

    IF expected_line_count IS NULL OR actual_line_count <> expected_line_count THEN
        RAISE EXCEPTION 'trade_order lines must exactly match the sealed commercial snapshot'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trade_order_snapshot_line_count
    AFTER INSERT OR UPDATE ON trade_order.trade_order
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION trade_order.assert_order_lines_match_snapshot();

CREATE CONSTRAINT TRIGGER trade_order_line_snapshot_count
    AFTER INSERT ON trade_order.order_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION trade_order.assert_order_lines_match_snapshot();

CREATE TABLE trade_order.timeline_entry (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    status varchar(40) NOT NULL,
    code varchar(80) NOT NULL,
    message varchar(300) NOT NULL,
    visibility varchar(20) NOT NULL CHECK (visibility IN ('INTERNAL', 'CUSTOMER')),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version = 0),
    CONSTRAINT uq_trade_order_timeline_event UNIQUE (tenant_id, order_id, event_id),
    CONSTRAINT fk_trade_order_timeline_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES trade_order.trade_order (tenant_id, id),
    CONSTRAINT ck_trade_order_timeline_text CHECK
        (length(trim(event_type)) BETWEEN 1 AND 160
         AND length(trim(code)) BETWEEN 1 AND 80
         AND length(trim(message)) BETWEEN 1 AND 300),
    CONSTRAINT ck_trade_order_timeline_status CHECK (status IN
      ('PENDING_RESERVATION', 'RESERVED', 'RESERVATION_FAILED',
       'READY_FOR_FULFILLMENT', 'IN_FULFILLMENT', 'FULFILLED',
       'CANCELLATION_PENDING', 'CANCELLATION_FAILED', 'CANCELLED')),
    CONSTRAINT ck_trade_order_timeline_audit_times CHECK (updated_at = created_at)
);

CREATE INDEX ix_trade_order_timeline
    ON trade_order.timeline_entry (tenant_id, order_id, occurred_at, id);

CREATE OR REPLACE FUNCTION trade_order.reject_order_line_or_timeline_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'trade_order snapshot rows are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trade_order_line_append_only
    BEFORE UPDATE OR DELETE ON trade_order.order_line
    FOR EACH ROW
    EXECUTE FUNCTION trade_order.reject_order_line_or_timeline_mutation();

CREATE TRIGGER trade_order_timeline_append_only
    BEFORE UPDATE OR DELETE ON trade_order.timeline_entry
    FOR EACH ROW
    EXECUTE FUNCTION trade_order.reject_order_line_or_timeline_mutation();

CREATE OR REPLACE FUNCTION trade_order.protect_commercial_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.number IS DISTINCT FROM OLD.number
       OR NEW.source_quotation_id IS DISTINCT FROM OLD.source_quotation_id
       OR NEW.source_quotation_number IS DISTINCT FROM OLD.source_quotation_number
       OR NEW.source_revision_id IS DISTINCT FROM OLD.source_revision_id
       OR NEW.source_revision_number IS DISTINCT FROM OLD.source_revision_number
       OR NEW.source_event_id IS DISTINCT FROM OLD.source_event_id
       OR NEW.source_owner_id IS DISTINCT FROM OLD.source_owner_id
       OR NEW.acceptance_id IS DISTINCT FROM OLD.acceptance_id
       OR NEW.accepted_at IS DISTINCT FROM OLD.accepted_at
       OR NEW.partner_id IS DISTINCT FROM OLD.partner_id
       OR NEW.partner_number IS DISTINCT FROM OLD.partner_number
       OR NEW.partner_display_name IS DISTINCT FROM OLD.partner_display_name
       OR NEW.partner_source_version IS DISTINCT FROM OLD.partner_source_version
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.total_amount IS DISTINCT FROM OLD.total_amount
       OR NEW.payment_term_days IS DISTINCT FROM OLD.payment_term_days
       OR NEW.route_code IS DISTINCT FROM OLD.route_code
       OR NEW.route_policy_version IS DISTINCT FROM OLD.route_policy_version
       OR NEW.route_estimated_delivery_date IS DISTINCT FROM OLD.route_estimated_delivery_date
       OR NEW.accepted_terms_version IS DISTINCT FROM OLD.accepted_terms_version
       OR NEW.requested_delivery_date IS DISTINCT FROM OLD.requested_delivery_date
       OR NEW.delivery_address IS DISTINCT FROM OLD.delivery_address
       OR NEW.commercial_snapshot IS DISTINCT FROM OLD.commercial_snapshot
       OR NEW.snapshot_schema_version IS DISTINCT FROM OLD.snapshot_schema_version
       OR NEW.snapshot_hash IS DISTINCT FROM OLD.snapshot_hash
       OR NEW.created_event_id IS DISTINCT FROM OLD.created_event_id
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'trade_order commercial snapshot is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trade_order_commercial_snapshot_immutable
    BEFORE UPDATE ON trade_order.trade_order
    FOR EACH ROW
    EXECUTE FUNCTION trade_order.protect_commercial_snapshot();

CREATE TRIGGER trade_order_no_physical_delete
    BEFORE DELETE ON trade_order.trade_order
    FOR EACH ROW
    EXECUTE FUNCTION trade_order.reject_order_line_or_timeline_mutation();

CREATE TABLE quotation.order_link (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    acceptance_id uuid NOT NULL,
    order_id uuid NOT NULL,
    order_number varchar(30) NOT NULL,
    snapshot_hash varchar(160) NOT NULL,
    source_event_id uuid NOT NULL,
    converted_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version = 0),
    CONSTRAINT uq_quotation_order_link_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_quotation_order_link_quote UNIQUE (tenant_id, quotation_id),
    CONSTRAINT uq_quotation_order_link_order UNIQUE (tenant_id, order_id),
    CONSTRAINT uq_quotation_order_link_source_event UNIQUE (tenant_id, source_event_id),
    CONSTRAINT fk_quotation_order_link_quote FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id),
    CONSTRAINT fk_quotation_order_link_revision FOREIGN KEY
        (tenant_id, revision_id, quotation_id)
        REFERENCES quotation.quotation_revision (tenant_id, id, quotation_id),
    CONSTRAINT fk_quotation_order_link_acceptance FOREIGN KEY (tenant_id, acceptance_id)
        REFERENCES quotation.customer_decision (tenant_id, id),
    CONSTRAINT ck_quotation_order_link_text CHECK
        (length(trim(order_number)) BETWEEN 1 AND 30
         AND length(trim(snapshot_hash)) BETWEEN 1 AND 160),
    CONSTRAINT ck_quotation_order_link_audit_times CHECK
        (created_at = converted_at AND updated_at = created_at)
);

COMMENT ON TABLE quotation.order_link IS
    'Quotation-owned immutable read link learned from TradeOrderCreatedV1; order_id has no cross-module foreign key.';

CREATE OR REPLACE FUNCTION quotation.reject_order_link_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'quotation.order_link is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER quotation_order_link_append_only
    BEFORE UPDATE OR DELETE ON quotation.order_link
    FOR EACH ROW
    EXECUTE FUNCTION quotation.reject_order_link_mutation();

CREATE TABLE platform_event.event_inbox (
    tenant_id uuid NOT NULL,
    consumer_name varchar(120) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    payload_hash char(64) NOT NULL,
    status varchar(30) NOT NULL CHECK
        (status IN ('PROCESSING', 'PROCESSED', 'FAILED_RETRYABLE', 'FAILED_FINAL')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts > 0),
    duplicate_count integer NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
    next_attempt_at timestamptz,
    result_reference varchar(160),
    result_hash char(64),
    last_error_code varchar(80),
    first_received_at timestamptz NOT NULL,
    last_attempt_at timestamptz NOT NULL,
    processed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, consumer_name, event_id),
    CONSTRAINT uq_platform_event_inbox_global_event UNIQUE (consumer_name, event_id),
    CONSTRAINT ck_platform_event_inbox_consumer CHECK
        (length(trim(consumer_name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_platform_event_inbox_type CHECK
        (event_type ~ '^cellarbridge\.[a-z0-9.-]+\.v[0-9]+$'),
    CONSTRAINT ck_platform_event_inbox_hashes CHECK
        (payload_hash ~ '^[0-9a-f]{64}$'
         AND (result_hash IS NULL OR result_hash ~ '^[0-9a-f]{64}$')
         AND ((result_reference IS NULL) = (result_hash IS NULL))),
    CONSTRAINT ck_platform_event_inbox_state CHECK
        ((status = 'PROCESSING'
          AND next_attempt_at IS NULL AND processed_at IS NULL
          AND last_error_code IS NULL AND result_reference IS NULL AND result_hash IS NULL)
         OR
         (status = 'PROCESSED'
          AND next_attempt_at IS NULL AND processed_at IS NOT NULL
          AND last_error_code IS NULL)
         OR
         (status = 'FAILED_RETRYABLE'
          AND next_attempt_at IS NOT NULL AND processed_at IS NULL
          AND last_error_code IS NOT NULL
          AND result_reference IS NULL AND result_hash IS NULL)
         OR
         (status = 'FAILED_FINAL'
          AND next_attempt_at IS NULL AND processed_at IS NULL
          AND last_error_code IS NOT NULL
          AND result_reference IS NULL AND result_hash IS NULL)),
    CONSTRAINT ck_platform_event_inbox_times CHECK
        (last_attempt_at >= first_received_at
         AND updated_at >= created_at
         AND (processed_at IS NULL OR processed_at >= first_received_at))
);

COMMENT ON TABLE platform_event.event_inbox IS
    'Per-consumer idempotency and bounded-retry record for local at-least-once delivery. It does not acknowledge external publication.';

CREATE INDEX ix_platform_event_inbox_retry
    ON platform_event.event_inbox
        (status, next_attempt_at, consumer_name, tenant_id, event_id)
    WHERE status = 'FAILED_RETRYABLE';

CREATE UNIQUE INDEX uq_platform_event_trade_order_created
    ON platform_event.event_publication
        (tenant_id, subject_type, subject_id, event_type)
    WHERE event_type = 'cellarbridge.order.created.v1';

CREATE OR REPLACE FUNCTION platform_event.protect_publication_envelope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.event_type IS DISTINCT FROM OLD.event_type
       OR NEW.event_version IS DISTINCT FROM OLD.event_version
       OR NEW.spec_version IS DISTINCT FROM OLD.spec_version
       OR NEW.producer IS DISTINCT FROM OLD.producer
       OR NEW.subject_type IS DISTINCT FROM OLD.subject_type
       OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
       OR NEW.subject_number IS DISTINCT FROM OLD.subject_number
       OR NEW.payload IS DISTINCT FROM OLD.payload
       OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'platform event publication envelope is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER platform_event_publication_envelope_immutable
    BEFORE UPDATE ON platform_event.event_publication
    FOR EACH ROW
    EXECUTE FUNCTION platform_event.protect_publication_envelope();
