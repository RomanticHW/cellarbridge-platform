ALTER TABLE quotation.quotation_revision
    ADD CONSTRAINT uq_quotation_revision_binding
        UNIQUE (tenant_id, id, quotation_id);

ALTER TABLE quotation.portal_access
    ADD COLUMN partner_id uuid,
    ADD COLUMN purpose varchar(60),
    ADD COLUMN allowed_actions text[],
    ADD COLUMN terms_version varchar(50),
    ADD COLUMN supplier_public_id varchar(80),
    ADD COLUMN supplier_display_name varchar(160),
    ADD COLUMN quotation_expires_at timestamptz;

-- V7 links granted preview only and did not freeze decision terms or supplier identity. Revoke
-- every legacy capability instead of silently adding decision authority, inventing snapshots, or
-- extending its lifetime. A decision-capable link must be issued explicitly by the V8 application.
UPDATE quotation.portal_access
   SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       version = version + 1;

UPDATE quotation.portal_access AS portal
   SET partner_id = quote.partner_id,
       purpose = 'LEGACY_QUOTATION_PREVIEW',
       allowed_actions = ARRAY['VIEW']::text[],
       quotation_expires_at = revision.expires_at
  FROM quotation.quotation AS quote
  JOIN quotation.quotation_revision AS revision
    ON revision.tenant_id = quote.tenant_id
   AND revision.quotation_id = quote.id
 WHERE portal.tenant_id = quote.tenant_id
   AND portal.quotation_id = quote.id
   AND portal.revision_id = revision.id;

ALTER TABLE quotation.portal_access
    ALTER COLUMN partner_id SET NOT NULL,
    ALTER COLUMN purpose SET NOT NULL,
    ALTER COLUMN allowed_actions SET NOT NULL,
    ALTER COLUMN quotation_expires_at SET NOT NULL,
    ADD CONSTRAINT uq_quotation_portal_access_tenant_id UNIQUE (tenant_id, id),
    ADD CONSTRAINT uq_quotation_portal_access_binding
        UNIQUE (tenant_id, id, quotation_id, revision_id, partner_id),
    ADD CONSTRAINT fk_quotation_portal_revision_binding
        FOREIGN KEY (tenant_id, revision_id, quotation_id)
        REFERENCES quotation.quotation_revision (tenant_id, id, quotation_id),
    ADD CONSTRAINT ck_quotation_portal_access_purpose CHECK
        (purpose IN ('LEGACY_QUOTATION_PREVIEW', 'CUSTOMER_QUOTATION_DECISION')),
    ADD CONSTRAINT ck_quotation_portal_access_legacy_shape CHECK
        (purpose <> 'LEGACY_QUOTATION_PREVIEW'
         OR (revoked_at IS NOT NULL
             AND allowed_actions = ARRAY['VIEW']::text[]
             AND terms_version IS NULL
             AND supplier_public_id IS NULL
             AND supplier_display_name IS NULL)),
    ADD CONSTRAINT ck_quotation_portal_access_actions CHECK
        (cardinality(allowed_actions) > 0
         AND allowed_actions <@ ARRAY['VIEW', 'ACCEPT', 'REJECT']::text[]
         AND ARRAY['VIEW']::text[] <@ allowed_actions),
    ADD CONSTRAINT ck_quotation_portal_access_terms_version CHECK
        (revoked_at IS NOT NULL
         OR (terms_version IS NOT NULL
             AND length(trim(terms_version)) BETWEEN 1 AND 50)),
    ADD CONSTRAINT ck_quotation_portal_access_supplier_snapshot CHECK
        (revoked_at IS NOT NULL
         OR (supplier_public_id IS NOT NULL
             AND length(trim(supplier_public_id)) BETWEEN 1 AND 80
             AND supplier_display_name IS NOT NULL
             AND length(trim(supplier_display_name)) BETWEEN 1 AND 160)),
    ADD CONSTRAINT ck_quotation_portal_access_expiry CHECK
        (revoked_at IS NOT NULL OR quotation_expires_at < expires_at);

COMMENT ON COLUMN quotation.portal_access.expires_at IS
    'Expiry of the customer portal token. It remains distinct from the quotation decision deadline.';
COMMENT ON COLUMN quotation.portal_access.quotation_expires_at IS
    'Frozen quotation acceptance deadline. Customer decisions require now < quotation_expires_at.';
COMMENT ON COLUMN quotation.portal_access.partner_id IS
    'Logical Partner identifier bound to this portal capability. No cross-module foreign key is permitted.';
COMMENT ON COLUMN quotation.portal_access.terms_version IS
    'Frozen terms for V8 decision links. NULL is permitted only for revoked legacy preview links.';

CREATE INDEX ix_quotation_portal_access_binding
    ON quotation.portal_access
        (tenant_id, partner_id, quotation_id, revision_id, purpose);

CREATE TABLE quotation.customer_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    partner_id uuid NOT NULL,
    portal_access_id uuid NOT NULL,
    decision varchar(20) NOT NULL CHECK (decision IN ('ACCEPTED', 'REJECTED')),
    commercial_snapshot jsonb NOT NULL,
    snapshot_hash char(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    buyer_reference varchar(100),
    reason_category varchar(50),
    accepted_terms_version varchar(50),
    idempotency_digest char(64) NOT NULL CHECK (idempotency_digest ~ '^[0-9a-f]{64}$'),
    accepted_event_id uuid,
    decided_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version = 0),
    CONSTRAINT uq_quotation_customer_decision_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_quotation_customer_decision_quote UNIQUE (tenant_id, quotation_id),
    CONSTRAINT uq_quotation_customer_decision_revision
        UNIQUE (tenant_id, quotation_id, revision_id),
    CONSTRAINT uq_quotation_customer_decision_event UNIQUE (accepted_event_id),
    CONSTRAINT fk_quotation_customer_decision_quote
        FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id),
    CONSTRAINT fk_quotation_customer_decision_revision
        FOREIGN KEY (tenant_id, revision_id, quotation_id)
        REFERENCES quotation.quotation_revision (tenant_id, id, quotation_id),
    CONSTRAINT fk_quotation_customer_decision_portal_binding
        FOREIGN KEY (tenant_id, portal_access_id, quotation_id, revision_id, partner_id)
        REFERENCES quotation.portal_access
            (tenant_id, id, quotation_id, revision_id, partner_id),
    CONSTRAINT ck_quotation_customer_decision_snapshot CHECK
        (jsonb_typeof(commercial_snapshot) = 'object'
         AND commercial_snapshot <> '{}'::jsonb
         AND commercial_snapshot ->> 'schemaVersion' = '1'),
    CONSTRAINT ck_quotation_customer_decision_shape CHECK
        ((decision = 'ACCEPTED'
          AND accepted_terms_version IS NOT NULL
          AND length(trim(accepted_terms_version)) BETWEEN 1 AND 50
          AND accepted_event_id IS NOT NULL
          AND reason_category IS NULL)
         OR
         (decision = 'REJECTED'
          AND accepted_terms_version IS NULL
          AND accepted_event_id IS NULL
          AND buyer_reference IS NULL)),
    CONSTRAINT ck_quotation_customer_decision_buyer_reference CHECK
        (buyer_reference IS NULL OR length(trim(buyer_reference)) BETWEEN 1 AND 100),
    CONSTRAINT ck_quotation_customer_decision_reason CHECK
        (reason_category IS NULL
         OR reason_category IN
            ('PRICE', 'DELIVERY_TIMING', 'PAYMENT_TERMS', 'PRODUCT_SELECTION', 'OTHER')),
    CONSTRAINT ck_quotation_customer_decision_audit_times CHECK
        (created_at = decided_at AND updated_at = created_at)
);

COMMENT ON TABLE quotation.customer_decision IS
    'Immutable customer acceptance or rejection of one frozen quotation revision.';
COMMENT ON COLUMN quotation.customer_decision.partner_id IS
    'Logical Partner identifier copied from the portal binding. No cross-module foreign key is permitted.';
COMMENT ON COLUMN quotation.customer_decision.commercial_snapshot IS
    'Customer-visible immutable commercial snapshot used to create the accepted integration event.';

CREATE OR REPLACE FUNCTION quotation.reject_customer_decision_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'quotation.customer_decision is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER customer_decision_append_only
    BEFORE UPDATE OR DELETE ON quotation.customer_decision
    FOR EACH ROW
    EXECUTE FUNCTION quotation.reject_customer_decision_mutation();

CREATE TABLE quotation.http_idempotency (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    partner_id uuid NOT NULL,
    operation varchar(80) NOT NULL,
    key_hash char(64) NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status varchar(20) NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED')),
    decision_id uuid,
    response_status smallint CHECK (response_status BETWEEN 200 AND 599),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_http_idempotency_scope
        UNIQUE (tenant_id, partner_id, operation, key_hash),
    CONSTRAINT fk_quotation_http_idempotency_decision
        FOREIGN KEY (tenant_id, decision_id)
        REFERENCES quotation.customer_decision (tenant_id, id),
    CONSTRAINT ck_quotation_http_idempotency_operation CHECK
        (length(trim(operation)) BETWEEN 1 AND 80),
    CONSTRAINT ck_quotation_http_idempotency_result CHECK
        ((status = 'PROCESSING' AND decision_id IS NULL AND response_status IS NULL)
         OR
         (status = 'COMPLETED' AND decision_id IS NOT NULL AND response_status IS NOT NULL)),
    CONSTRAINT ck_quotation_http_idempotency_expiry CHECK
        (expires_at > created_at AND updated_at >= created_at)
);

COMMENT ON COLUMN quotation.http_idempotency.key_hash IS
    'SHA-256 digest of the scoped Idempotency-Key; the raw key is never stored.';
COMMENT ON COLUMN quotation.http_idempotency.request_hash IS
    'SHA-256 digest of the normalized request; the request body is never stored.';

CREATE INDEX ix_quotation_http_idempotency_retention
    ON quotation.http_idempotency (expires_at, tenant_id, id);

CREATE TABLE quotation.expiration_work_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    due_at timestamptz NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'CLAIMED', 'COMPLETED')),
    claim_owner varchar(120),
    claim_until timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    completion_outcome varchar(30),
    completed_at timestamptz,
    last_error_code varchar(80),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_quotation_expiration_work_subject
        UNIQUE (tenant_id, quotation_id, revision_id),
    CONSTRAINT fk_quotation_expiration_work_quote
        FOREIGN KEY (tenant_id, quotation_id)
        REFERENCES quotation.quotation (tenant_id, id),
    CONSTRAINT fk_quotation_expiration_work_revision
        FOREIGN KEY (tenant_id, revision_id, quotation_id)
        REFERENCES quotation.quotation_revision (tenant_id, id, quotation_id),
    CONSTRAINT ck_quotation_expiration_work_state CHECK
        ((status = 'PENDING'
          AND claim_owner IS NULL AND claim_until IS NULL
          AND completion_outcome IS NULL AND completed_at IS NULL)
         OR
         (status = 'CLAIMED'
          AND claim_owner IS NOT NULL AND claim_until IS NOT NULL
          AND completion_outcome IS NULL AND completed_at IS NULL)
         OR
         (status = 'COMPLETED'
          AND claim_owner IS NULL AND claim_until IS NULL
          AND completion_outcome IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_quotation_expiration_work_claim CHECK
        (claim_until IS NULL OR claim_until > updated_at),
    CONSTRAINT ck_quotation_expiration_work_completion CHECK
        (completion_outcome IS NULL
         OR completion_outcome IN ('EXPIRED', 'SKIPPED_FINAL'))
);

CREATE INDEX ix_quotation_expiration_work_claim
    ON quotation.expiration_work_item
        (status, due_at, claim_until, tenant_id, id);

INSERT INTO quotation.expiration_work_item
    (id, tenant_id, quotation_id, revision_id, due_at, status,
     attempts, created_at, created_by, updated_at, updated_by, version)
SELECT portal.id,
       portal.tenant_id,
       portal.quotation_id,
       portal.revision_id,
       portal.quotation_expires_at,
       'PENDING',
       0,
       portal.created_at,
       portal.created_by,
       portal.updated_at,
       portal.updated_by,
       0
  FROM quotation.portal_access AS portal
  JOIN quotation.quotation AS quote
    ON quote.tenant_id = portal.tenant_id
   AND quote.id = portal.quotation_id
 WHERE quote.status = 'SENT'
ON CONFLICT (tenant_id, quotation_id, revision_id) DO NOTHING;

ALTER TABLE quotation.audit_entry
    ADD COLUMN actor_type varchar(30) NOT NULL DEFAULT 'INTERNAL_USER',
    ADD CONSTRAINT ck_quotation_audit_actor_type CHECK
        (actor_type IN ('INTERNAL_USER', 'CUSTOMER_TOKEN', 'SYSTEM'));

COMMENT ON COLUMN quotation.audit_entry.actor_type IS
    'Actor classification. CUSTOMER_TOKEN entries use the portal access id as actor_id, never the raw token.';

CREATE SCHEMA platform_event;

CREATE TABLE platform_event.event_publication (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL CHECK (event_version > 0),
    spec_version varchar(20) NOT NULL DEFAULT '1.0' CHECK (spec_version = '1.0'),
    producer varchar(100) NOT NULL,
    subject_type varchar(80) NOT NULL,
    subject_id uuid NOT NULL,
    subject_number varchar(80) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN
          ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED_RETRYABLE', 'FAILED_FINAL')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    claim_owner varchar(120),
    claim_until timestamptz,
    last_error_code varchar(80),
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    correlation_id uuid NOT NULL,
    causation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_platform_event_type_version CHECK
        (event_type ~ '^cellarbridge\.[a-z0-9.-]+\.v[0-9]+$'
         AND event_type ~ ('\.v' || event_version::text || '$')),
    CONSTRAINT ck_platform_event_subject CHECK
        (length(trim(subject_type)) BETWEEN 1 AND 80
         AND length(trim(subject_number)) BETWEEN 1 AND 80),
    CONSTRAINT ck_platform_event_state CHECK
        ((status IN ('PENDING', 'FAILED_RETRYABLE', 'FAILED_FINAL')
          AND claim_owner IS NULL AND claim_until IS NULL
          AND published_at IS NULL)
         OR
         (status = 'CLAIMED'
          AND claim_owner IS NOT NULL AND claim_until IS NOT NULL
          AND published_at IS NULL)
         OR
         (status = 'PUBLISHED'
          AND claim_owner IS NULL AND claim_until IS NULL
          AND published_at IS NOT NULL)),
    CONSTRAINT ck_platform_event_claim CHECK
        (claim_until IS NULL OR claim_until > updated_at),
    CONSTRAINT ck_platform_event_envelope CHECK
        (jsonb_typeof(payload) = 'object'
         AND payload ?& ARRAY[
             'id', 'type', 'specVersion', 'occurredAt', 'tenantId', 'producer',
             'subject', 'correlationId', 'causationId', 'payload'
         ]::text[]
         AND payload ->> 'id' = event_id::text
         AND payload ->> 'type' = event_type
         AND payload ->> 'specVersion' = spec_version
         AND (payload ->> 'occurredAt')::timestamptz = occurred_at
         AND payload ->> 'tenantId' = tenant_id::text
         AND payload ->> 'producer' = producer
         AND jsonb_typeof(payload -> 'subject') = 'object'
         AND (payload -> 'subject') ?& ARRAY['type', 'id', 'number']::text[]
         AND payload #>> '{subject,type}' = subject_type
         AND payload #>> '{subject,id}' = subject_id::text
         AND payload #>> '{subject,number}' = subject_number
         AND payload ->> 'correlationId' = correlation_id::text
         AND payload ->> 'causationId' = causation_id::text
         AND jsonb_typeof(payload -> 'payload') = 'object')
);

COMMENT ON TABLE platform_event.event_publication IS
    'Transactional pending integration-event envelope. It has no foreign keys to business modules.';
COMMENT ON COLUMN platform_event.event_publication.payload IS
    'Complete versioned event envelope, not an ORM entity or an unstructured request body.';

CREATE UNIQUE INDEX uq_platform_event_quotation_accepted
    ON platform_event.event_publication
        (tenant_id, subject_type, subject_id, event_type)
    WHERE event_type = 'cellarbridge.quotation.accepted.v1';

CREATE INDEX ix_platform_event_publication_claim
    ON platform_event.event_publication
        (status, next_attempt_at, claim_until, occurred_at, event_id);
