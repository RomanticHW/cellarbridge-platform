ALTER TABLE catalog.sku_supply_projection
    ADD COLUMN quantity_unit varchar(20);

UPDATE catalog.sku_supply_projection
   SET quantity_unit = 'CASE'
 WHERE quantity_unit IS NULL;

ALTER TABLE catalog.sku_supply_projection
    ALTER COLUMN quantity_unit SET NOT NULL;

ALTER TABLE catalog.sku_supply_projection
    ADD CONSTRAINT ck_catalog_supply_projection_quantity_unit
    CHECK (quantity_unit IN ('CASE', 'BOTTLE'));

ALTER TABLE catalog.sku_supply_projection
    DROP CONSTRAINT sku_supply_projection_pkey;

ALTER TABLE catalog.sku_supply_projection
    ADD CONSTRAINT pk_catalog_sku_supply_projection
    PRIMARY KEY (tenant_id, sku_id, supply_pool_id, quantity_unit);

CREATE INDEX ix_catalog_supply_projection_unit_filter
    ON catalog.sku_supply_projection
        (tenant_id, quantity_unit, supply_type, availability_class,
         automatically_reservable, sku_id);
