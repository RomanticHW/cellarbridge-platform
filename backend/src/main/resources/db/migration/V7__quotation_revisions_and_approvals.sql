CREATE SCHEMA quotation;

CREATE SEQUENCE quotation.quotation_number_seq START WITH 1;

CREATE TABLE quotation.price_reference (
    tenant_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    list_case_price numeric(19,4) NOT NULL CHECK (list_case_price > 0),
    cost_case_price numeric(19,4) NOT NULL CHECK
        (cost_case_price > 0 AND cost_case_price <= list_case_price),
    price_version varchar(80) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, sku_id, currency, price_version),
    CONSTRAINT ck_quotation_price_effectivity CHECK
        (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX ix_quotation_price_reference_current
    ON quotation.price_reference (tenant_id, sku_id, currency, effective_from DESC);

CREATE TABLE quotation.quotation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    number varchar(30) NOT NULL,
    partner_id uuid NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN
        ('DRAFT', 'PENDING_APPROVAL', 'CHANGES_REQUESTED', 'APPROVED', 'SENT',
         'ACCEPTED', 'REJECTED', 'REJECTED_BY_CUSTOMER', 'WITHDRAWN', 'EXPIRED',
         'CONVERTED', 'CANCELLED')),
    current_revision_no integer NOT NULL CHECK (current_revision_no > 0),
    current_revision_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    submitted_by_id uuid,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_quotation_tenant_number UNIQUE (tenant_id, number)
);

CREATE INDEX ix_quotation_list
    ON quotation.quotation (tenant_id, created_at DESC, number, id);
CREATE INDEX ix_quotation_owner_list
    ON quotation.quotation (tenant_id, owner_id, created_at DESC, number, id);

CREATE TABLE quotation.quotation_revision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    partner_number varchar(30) NOT NULL,
    partner_display_name varchar(100) NOT NULL,
    partner_payment_term_days integer NOT NULL CHECK (partner_payment_term_days BETWEEN 0 AND 180),
    partner_source_version integer NOT NULL CHECK (partner_source_version > 0),
    partner_captured_at timestamptz NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    requested_delivery_date date NOT NULL,
    expires_at timestamptz NOT NULL,
    payment_term_days integer NOT NULL CHECK (payment_term_days BETWEEN 0 AND 180),
    delivery_country_code varchar(2) NOT NULL CHECK (delivery_country_code ~ '^[A-Z]{2}$'),
    delivery_province varchar(100) NOT NULL,
    delivery_city varchar(100) NOT NULL,
    delivery_district varchar(100),
    delivery_line1 varchar(200) NOT NULL,
    delivery_postal_code varchar(20),
    route_evaluation_id uuid,
    route_policy_version varchar(80),
    recommended_route_code varchar(50),
    selected_route_code varchar(50),
    route_override_reason varchar(500),
    price_policy_version varchar(80) NOT NULL,
    approval_policy_version varchar(80) NOT NULL,
    subtotal numeric(19,4) NOT NULL CHECK (subtotal >= 0),
    total numeric(19,4) NOT NULL CHECK (total >= 0),
    total_cost numeric(19,4) NOT NULL CHECK (total_cost >= 0),
    estimated_margin_rate numeric(9,4) NOT NULL,
    route_charges numeric(19,4) NOT NULL CHECK (route_charges >= 0),
    frozen_at timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_revision_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_quotation_revision_number UNIQUE (tenant_id, quotation_id, revision_no),
    CONSTRAINT fk_quotation_revision_quotation FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id),
    CONSTRAINT ck_quotation_revision_route CHECK
        (selected_route_code IS NULL OR selected_route_code IN
            ('SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE')),
    CONSTRAINT ck_quotation_revision_route_evaluation CHECK
        ((route_evaluation_id IS NULL AND route_policy_version IS NULL
          AND recommended_route_code IS NULL AND selected_route_code IS NULL)
         OR
         (route_evaluation_id IS NOT NULL AND route_policy_version IS NOT NULL
          AND recommended_route_code IS NOT NULL AND selected_route_code IS NOT NULL))
);

CREATE TABLE quotation.quotation_line (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    sku_code varchar(80) NOT NULL,
    display_name varchar(200) NOT NULL,
    producer_name varchar(160) NOT NULL,
    region_name varchar(160) NOT NULL,
    country_code varchar(2) NOT NULL,
    category varchar(30) NOT NULL,
    vintage varchar(20) NOT NULL,
    volume_ml integer NOT NULL CHECK (volume_ml > 0),
    units_per_case integer NOT NULL CHECK (units_per_case > 0),
    package_type varchar(40) NOT NULL,
    sku_source_version bigint NOT NULL CHECK (sku_source_version >= 0),
    sku_captured_at timestamptz NOT NULL,
    quantity numeric(19,6) NOT NULL CHECK (quantity > 0),
    quantity_unit varchar(20) NOT NULL CHECK (quantity_unit IN ('CASE', 'BOTTLE')),
    preferred_supply_pool_id uuid,
    supply_type varchar(40),
    currency varchar(3) NOT NULL,
    list_unit_price numeric(19,4) NOT NULL CHECK (list_unit_price > 0),
    discount_rate numeric(9,4) NOT NULL CHECK (discount_rate >= 0 AND discount_rate < 1),
    net_unit_price numeric(19,4) NOT NULL CHECK (net_unit_price > 0),
    allocated_charges numeric(19,4) NOT NULL CHECK (allocated_charges >= 0),
    line_total numeric(19,4) NOT NULL CHECK (line_total >= 0),
    cost_unit_price numeric(19,4) NOT NULL CHECK (cost_unit_price > 0),
    line_cost numeric(19,4) NOT NULL CHECK (line_cost >= 0),
    estimated_margin_rate numeric(9,4) NOT NULL,
    manual_price boolean NOT NULL,
    price_source_version varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_line_sku UNIQUE (tenant_id, revision_id, sku_id),
    CONSTRAINT fk_quotation_line_revision FOREIGN KEY (tenant_id, revision_id)
        REFERENCES quotation.quotation_revision (tenant_id, id)
);

CREATE INDEX ix_quotation_line_revision
    ON quotation.quotation_line (tenant_id, revision_id, id);

CREATE TABLE quotation.approval_requirement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    rule_id varchar(80) NOT NULL,
    code varchar(80) NOT NULL,
    actual_value varchar(100) NOT NULL,
    threshold varchar(100) NOT NULL,
    message varchar(300) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_approval_requirement UNIQUE (tenant_id, revision_id, rule_id),
    CONSTRAINT fk_quotation_requirement_revision FOREIGN KEY (tenant_id, revision_id)
        REFERENCES quotation.quotation_revision (tenant_id, id)
);

CREATE TABLE quotation.approval_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    decision varchar(30) NOT NULL CHECK (decision IN ('APPROVE', 'REQUEST_CHANGES', 'REJECT')),
    reviewer_id uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) >= 5),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_reviewer_decision UNIQUE (tenant_id, revision_id, reviewer_id),
    CONSTRAINT fk_quotation_decision_quotation FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id),
    CONSTRAINT fk_quotation_decision_revision FOREIGN KEY (tenant_id, revision_id)
        REFERENCES quotation.quotation_revision (tenant_id, id)
);

CREATE TABLE quotation.portal_access (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_portal_access UNIQUE (tenant_id, quotation_id),
    CONSTRAINT fk_quotation_portal_quotation FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id),
    CONSTRAINT fk_quotation_portal_revision FOREIGN KEY (tenant_id, revision_id)
        REFERENCES quotation.quotation_revision (tenant_id, id)
);

CREATE TABLE quotation.approval_work_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    candidate_permission varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_approval_work_item UNIQUE (tenant_id, revision_id),
    CONSTRAINT fk_quotation_work_item_quotation FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id)
);

CREATE TABLE quotation.audit_entry (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    action varchar(60) NOT NULL,
    previous_state varchar(30),
    new_state varchar(30),
    safe_reason varchar(500),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_quotation_audit_quotation FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id)
);

CREATE INDEX ix_quotation_audit_timeline
    ON quotation.audit_entry (tenant_id, quotation_id, occurred_at, id);

CREATE TABLE quotation.local_event_publication (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
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
    CONSTRAINT fk_quotation_publication_quotation FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id)
);

COMMENT ON COLUMN quotation.quotation.partner_id IS
    'Logical Partner identifier. The immutable partner snapshot is owned by the revision.';
COMMENT ON COLUMN quotation.quotation_line.sku_id IS
    'Logical Catalog SKU identifier. No cross-module foreign key is permitted.';
COMMENT ON COLUMN quotation.quotation_revision.route_evaluation_id IS
    'Logical Trade Planning evaluation identifier. No cross-module foreign key is permitted.';
