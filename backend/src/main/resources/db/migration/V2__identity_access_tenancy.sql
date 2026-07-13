CREATE SCHEMA identity_access;

CREATE TABLE identity_access.tenant (
    id uuid PRIMARY KEY,
    code varchar(50) NOT NULL UNIQUE,
    display_name varchar(160) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at timestamptz NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(100) NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);

COMMENT ON TABLE identity_access.tenant IS
    'Global tenant registry. This is the explicit tenant_id exception because each row defines a tenant.';

CREATE TABLE identity_access.user_mapping (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    tenant_id uuid NOT NULL REFERENCES identity_access.tenant (id),
    issuer varchar(300) NOT NULL,
    external_subject varchar(160) NOT NULL,
    username varchar(120) NOT NULL,
    display_name varchar(160) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at timestamptz NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(100) NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_user_mapping_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_user_mapping_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT uq_user_mapping_tenant_subject UNIQUE (tenant_id, issuer, external_subject),
    CONSTRAINT uq_user_mapping_external_identity UNIQUE (issuer, external_subject),
    CONSTRAINT uq_user_mapping_tenant_username UNIQUE (tenant_id, username)
);

CREATE INDEX ix_user_mapping_tenant_status
    ON identity_access.user_mapping (tenant_id, status, username, id);

CREATE TABLE identity_access.role_template (
    tenant_id uuid NOT NULL REFERENCES identity_access.tenant (id),
    code varchar(80) NOT NULL,
    display_name varchar(160) NOT NULL,
    permission_codes varchar(100)[] NOT NULL CHECK (cardinality(permission_codes) > 0),
    created_at timestamptz NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(100) NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, code)
);

CREATE TABLE identity_access.user_mapping_role (
    tenant_id uuid NOT NULL,
    user_mapping_id uuid NOT NULL,
    role_code varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(100) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(100) NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, user_mapping_id, role_code),
    CONSTRAINT fk_user_mapping_role_user
        FOREIGN KEY (tenant_id, user_mapping_id)
        REFERENCES identity_access.user_mapping (tenant_id, id),
    CONSTRAINT fk_user_mapping_role_template
        FOREIGN KEY (tenant_id, role_code)
        REFERENCES identity_access.role_template (tenant_id, code)
);

CREATE INDEX ix_user_mapping_role_tenant_role
    ON identity_access.user_mapping_role (tenant_id, role_code, user_mapping_id);
