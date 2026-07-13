CREATE SCHEMA partner;

CREATE SEQUENCE partner.partner_number_seq START WITH 1;

CREATE TABLE partner.partner (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    number varchar(30) NOT NULL,
    legal_name varchar(200),
    normalized_legal_name varchar(200),
    display_name varchar(100),
    registration_identifier varchar(100),
    normalized_registration_identifier varchar(100),
    partner_type varchar(40) CHECK (partner_type IN
        ('RESTAURANT_GROUP', 'DISTRIBUTOR', 'RETAILER', 'CORPORATE_BUYER', 'OTHER')),
    status varchar(30) NOT NULL CHECK (status IN
        ('DRAFT', 'PENDING_REVIEW', 'CHANGES_REQUESTED', 'ACTIVE', 'SUSPENDED', 'REJECTED')),
    default_currency varchar(3),
    requested_payment_term_days integer CHECK
        (requested_payment_term_days BETWEEN 0 AND 180),
    requested_route_codes varchar(50)[] NOT NULL DEFAULT '{}' CHECK
        (requested_route_codes <@ ARRAY[
            'SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE']::varchar[]),
    requested_service_regions varchar(80)[] NOT NULL DEFAULT '{}',
    requested_currencies varchar(3)[] NOT NULL DEFAULT '{}',
    contact_name varchar(100),
    contact_email varchar(254),
    contact_phone varchar(40),
    billing_country_code varchar(2),
    billing_province varchar(100),
    billing_city varchar(100),
    billing_district varchar(100),
    billing_line1 varchar(200),
    billing_postal_code varchar(20),
    duplicate_resolution_note varchar(500),
    sales_owner_id uuid NOT NULL,
    submitted_by_id uuid,
    submitted_at timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_partner_tenant_number UNIQUE (tenant_id, number),
    CONSTRAINT uq_partner_tenant_id UNIQUE (tenant_id, id)
);

CREATE UNIQUE INDEX uq_partner_tenant_registration_identifier
    ON partner.partner (tenant_id, normalized_registration_identifier)
    WHERE normalized_registration_identifier IS NOT NULL;

CREATE INDEX ix_partner_tenant_list
    ON partner.partner (tenant_id, updated_at DESC, number, id);

CREATE INDEX ix_partner_tenant_owner_list
    ON partner.partner (tenant_id, sales_owner_id, updated_at DESC, number, id);

CREATE INDEX ix_partner_tenant_legal_name
    ON partner.partner (tenant_id, normalized_legal_name, id);

CREATE TABLE partner.eligibility_version (
    tenant_id uuid NOT NULL,
    partner_id uuid NOT NULL,
    eligibility_version integer NOT NULL CHECK (eligibility_version > 0),
    allowed_route_codes varchar(50)[] NOT NULL CHECK
        (cardinality(allowed_route_codes) > 0 AND allowed_route_codes <@ ARRAY[
            'SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE']::varchar[]),
    allowed_service_regions varchar(80)[] NOT NULL CHECK (cardinality(allowed_service_regions) > 0),
    allowed_currencies varchar(3)[] NOT NULL CHECK (cardinality(allowed_currencies) > 0),
    payment_term_days integer NOT NULL CHECK (payment_term_days BETWEEN 0 AND 180),
    credit_limit_amount numeric(19,4) CHECK (credit_limit_amount >= 0),
    credit_limit_currency varchar(3),
    effective_from timestamptz NOT NULL,
    approved_by uuid NOT NULL,
    approved_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, partner_id, eligibility_version),
    CONSTRAINT fk_partner_eligibility_partner
        FOREIGN KEY (tenant_id, partner_id) REFERENCES partner.partner (tenant_id, id)
);

CREATE TABLE partner.review_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    partner_id uuid NOT NULL,
    decision varchar(30) NOT NULL CHECK (decision IN ('APPROVE', 'REQUEST_CHANGES', 'REJECT')),
    reason varchar(500) NOT NULL,
    submitted_by_id uuid NOT NULL,
    reviewer_id uuid NOT NULL,
    previous_state varchar(30) NOT NULL,
    new_state varchar(30) NOT NULL,
    source_version bigint NOT NULL,
    decided_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_partner_review_partner
        FOREIGN KEY (tenant_id, partner_id) REFERENCES partner.partner (tenant_id, id)
);

CREATE INDEX ix_partner_review_timeline
    ON partner.review_decision (tenant_id, partner_id, decided_at DESC, id);

CREATE TABLE partner.audit_entry (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    partner_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    action varchar(50) NOT NULL,
    previous_state varchar(30),
    new_state varchar(30),
    safe_reason varchar(500),
    changed_fields jsonb NOT NULL DEFAULT '[]'::jsonb,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_partner_audit_partner
        FOREIGN KEY (tenant_id, partner_id) REFERENCES partner.partner (tenant_id, id)
);

CREATE INDEX ix_partner_audit_timeline
    ON partner.audit_entry (tenant_id, partner_id, occurred_at DESC, id);

CREATE TABLE partner.review_work_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    partner_id uuid NOT NULL,
    partner_number varchar(30) NOT NULL,
    source_event_id uuid NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    candidate_permission varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_partner_review_work_item_source UNIQUE (tenant_id, source_event_id),
    CONSTRAINT fk_partner_work_item_partner
        FOREIGN KEY (tenant_id, partner_id) REFERENCES partner.partner (tenant_id, id)
);

CREATE INDEX ix_partner_review_work_queue
    ON partner.review_work_item (tenant_id, status, created_at, id);

CREATE TABLE partner.local_event_publication (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    partner_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    event_version integer NOT NULL CHECK (event_version > 0),
    payload jsonb NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('COMPLETED')),
    occurred_at timestamptz NOT NULL,
    published_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_partner_publication_partner
        FOREIGN KEY (tenant_id, partner_id) REFERENCES partner.partner (tenant_id, id)
);

CREATE INDEX ix_partner_publication_tenant_subject
    ON partner.local_event_publication (tenant_id, partner_id, occurred_at, event_id);

COMMENT ON TABLE partner.local_event_publication IS
    'Reliable local module publication record. Kafka publication remains disabled in Task 03.';
