CREATE SCHEMA inventory;

CREATE TABLE inventory.warehouse (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(60) NOT NULL,
    name varchar(160) NOT NULL,
    country_code varchar(2) NOT NULL,
    city varchar(100) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_inventory_warehouse_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_warehouse_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_inventory_warehouse_country CHECK (country_code ~ '^[A-Z]{2}$')
);

CREATE TABLE inventory.supply_pool (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    code varchar(80) NOT NULL,
    supply_type varchar(40) NOT NULL,
    route_code varchar(50),
    currency varchar(3) NOT NULL,
    available_from timestamptz,
    confidence varchar(20) NOT NULL,
    policy_version varchar(80) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    automatically_reservable boolean GENERATED ALWAYS AS
        (supply_type IN ('DOMESTIC_ON_HAND', 'BONDED_ON_HAND', 'HONG_KONG_ON_HAND')) STORED,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_inventory_supply_pool_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_supply_pool_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_inventory_supply_pool_warehouse FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id),
    CONSTRAINT ck_inventory_supply_pool_type CHECK
        (supply_type IN ('DOMESTIC_ON_HAND', 'BONDED_ON_HAND', 'HONG_KONG_ON_HAND',
                         'IN_TRANSIT_PRESALE', 'OVERSEAS_SOURCING')),
    CONSTRAINT ck_inventory_supply_pool_route CHECK
        (route_code IS NULL OR route_code IN
            ('SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE')),
    CONSTRAINT ck_inventory_supply_pool_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_inventory_supply_pool_confidence CHECK
        (confidence IN ('HIGH', 'MEDIUM', 'LOW'))
);

CREATE TABLE inventory.inventory_lot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    supply_pool_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    lot_code varchar(80) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('AVAILABLE', 'FROZEN', 'DEPLETED')),
    on_hand_quantity numeric(19,6) NOT NULL CHECK (on_hand_quantity >= 0),
    reserved_quantity numeric(19,6) NOT NULL CHECK (reserved_quantity >= 0),
    available_from timestamptz,
    received_at timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_inventory_lot_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inventory_lot_code UNIQUE (tenant_id, supply_pool_id, lot_code),
    CONSTRAINT fk_inventory_lot_supply_pool FOREIGN KEY (tenant_id, supply_pool_id)
        REFERENCES inventory.supply_pool (tenant_id, id),
    CONSTRAINT ck_inventory_lot_reservation CHECK (reserved_quantity <= on_hand_quantity)
);

CREATE INDEX ix_inventory_lot_tenant_sku_available
    ON inventory.inventory_lot
        (tenant_id, sku_id, status, available_from, supply_pool_id, id);
CREATE INDEX ix_inventory_lot_tenant_pool
    ON inventory.inventory_lot (tenant_id, supply_pool_id, status, id);

CREATE TABLE inventory.warehouse_assignment (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, user_id, warehouse_id),
    CONSTRAINT fk_inventory_assignment_warehouse FOREIGN KEY (tenant_id, warehouse_id)
        REFERENCES inventory.warehouse (tenant_id, id)
);

CREATE INDEX ix_inventory_assignment_user
    ON inventory.warehouse_assignment (tenant_id, user_id, warehouse_id);

COMMENT ON COLUMN inventory.inventory_lot.sku_id IS
    'Logical Catalog SKU identifier. No cross-module foreign key is permitted.';
