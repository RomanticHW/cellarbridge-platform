CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE SCHEMA catalog;

CREATE TABLE catalog.producer (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    normalized_name varchar(160) NOT NULL,
    country_code varchar(2) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_catalog_producer_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_catalog_producer_name UNIQUE (tenant_id, normalized_name),
    CONSTRAINT ck_catalog_producer_country CHECK (country_code ~ '^[A-Z]{2}$')
);

CREATE TABLE catalog.region (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    normalized_name varchar(160) NOT NULL,
    country_code varchar(2) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_catalog_region_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_catalog_region_name UNIQUE (tenant_id, normalized_name),
    CONSTRAINT ck_catalog_region_country CHECK (country_code ~ '^[A-Z]{2}$')
);

CREATE TABLE catalog.wine_product (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    producer_id uuid NOT NULL,
    region_id uuid NOT NULL,
    name varchar(200) NOT NULL,
    normalized_name varchar(200) NOT NULL,
    category varchar(50) NOT NULL,
    description varchar(1000) NOT NULL DEFAULT '',
    tags varchar(80)[] NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_catalog_product_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_catalog_product_name UNIQUE (tenant_id, producer_id, normalized_name),
    CONSTRAINT fk_catalog_product_producer FOREIGN KEY (tenant_id, producer_id)
        REFERENCES catalog.producer (tenant_id, id),
    CONSTRAINT fk_catalog_product_region FOREIGN KEY (tenant_id, region_id)
        REFERENCES catalog.region (tenant_id, id),
    CONSTRAINT ck_catalog_product_category CHECK
        (category IN ('RED', 'WHITE', 'ROSE', 'SPARKLING', 'FORTIFIED', 'DESSERT', 'OTHER'))
);

CREATE TABLE catalog.sku (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    code varchar(80) NOT NULL,
    vintage_code varchar(4) NOT NULL,
    volume_ml integer NOT NULL CHECK (volume_ml > 0),
    units_per_case integer NOT NULL CHECK (units_per_case > 0),
    package_type varchar(40) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    search_text text NOT NULL,
    search_document tsvector GENERATED ALWAYS AS
        (to_tsvector('simple', search_text)) STORED,
    activated_at timestamptz,
    deactivated_at timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_catalog_sku_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_catalog_sku_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_catalog_sku_definition UNIQUE
        (tenant_id, product_id, vintage_code, volume_ml, units_per_case, package_type),
    CONSTRAINT fk_catalog_sku_product FOREIGN KEY (tenant_id, product_id)
        REFERENCES catalog.wine_product (tenant_id, id),
    CONSTRAINT ck_catalog_sku_vintage CHECK
        (vintage_code = 'NV' OR vintage_code ~ '^(19|20)[0-9]{2}$'),
    CONSTRAINT ck_catalog_sku_package_type CHECK
        (package_type IN ('CASE', 'WOODEN_CASE', 'GIFT_BOX', 'BOTTLE'))
);

CREATE INDEX ix_catalog_sku_tenant_status_updated
    ON catalog.sku (tenant_id, status, updated_at DESC, code, id);
CREATE INDEX ix_catalog_sku_tenant_dimensions
    ON catalog.sku (tenant_id, status, vintage_code, volume_ml, product_id, id);
CREATE INDEX ix_catalog_sku_search_document
    ON catalog.sku USING gin (search_document);
CREATE INDEX ix_catalog_sku_search_trigram
    ON catalog.sku USING gist (search_text gist_trgm_ops(siglen=64));
CREATE INDEX ix_catalog_product_tenant_producer_region
    ON catalog.wine_product (tenant_id, producer_id, region_id, category, id);

CREATE TABLE catalog.sku_supply_projection (
    tenant_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    supply_pool_id uuid NOT NULL,
    supply_type varchar(40) NOT NULL,
    location_label varchar(160) NOT NULL,
    availability_class varchar(30) NOT NULL,
    display_quantity_band varchar(30) NOT NULL,
    automatically_reservable boolean NOT NULL,
    estimated_available_at timestamptz,
    data_as_of timestamptz NOT NULL,
    projection_version bigint NOT NULL CHECK (projection_version >= 0),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, sku_id, supply_pool_id),
    CONSTRAINT fk_catalog_supply_projection_sku FOREIGN KEY (tenant_id, sku_id)
        REFERENCES catalog.sku (tenant_id, id),
    CONSTRAINT ck_catalog_supply_projection_type CHECK
        (supply_type IN ('DOMESTIC_ON_HAND', 'BONDED_ON_HAND', 'HONG_KONG_ON_HAND',
                         'IN_TRANSIT_PRESALE', 'OVERSEAS_SOURCING')),
    CONSTRAINT ck_catalog_supply_projection_availability CHECK
        (availability_class IN ('AVAILABLE', 'LIMITED', 'UNAVAILABLE', 'REQUIRES_CONFIRMATION')),
    CONSTRAINT ck_catalog_supply_projection_band CHECK
        (display_quantity_band IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'CONFIRMATION_REQUIRED')),
    CONSTRAINT ck_catalog_supply_projection_reservability CHECK
        (automatically_reservable =
         (supply_type IN ('DOMESTIC_ON_HAND', 'BONDED_ON_HAND', 'HONG_KONG_ON_HAND')))
);

CREATE INDEX ix_catalog_supply_projection_filter
    ON catalog.sku_supply_projection
        (tenant_id, supply_type, availability_class, automatically_reservable, sku_id);
CREATE INDEX ix_catalog_supply_projection_pool
    ON catalog.sku_supply_projection (tenant_id, supply_pool_id, sku_id);

COMMENT ON TABLE catalog.sku_supply_projection IS
    'Catalog-owned, event-ready non-committing supply search projection. Inventory remains authoritative; data_as_of exposes staleness.';
