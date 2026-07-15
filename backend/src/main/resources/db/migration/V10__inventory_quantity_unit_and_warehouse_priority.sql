ALTER TABLE inventory.inventory_lot
    ADD COLUMN quantity_unit varchar(20);

UPDATE inventory.inventory_lot
   SET quantity_unit = 'CASE'
 WHERE quantity_unit IS NULL;

ALTER TABLE inventory.inventory_lot
    ALTER COLUMN quantity_unit SET NOT NULL;

ALTER TABLE inventory.inventory_lot
    ADD CONSTRAINT ck_inventory_lot_quantity_unit
    CHECK (quantity_unit IN ('CASE', 'BOTTLE'));

ALTER TABLE inventory.warehouse
    ADD COLUMN allocation_priority integer NOT NULL DEFAULT 100;

ALTER TABLE inventory.warehouse
    ADD CONSTRAINT ck_inventory_warehouse_allocation_priority
    CHECK (allocation_priority >= 0);

CREATE INDEX ix_inventory_lot_tenant_sku_unit_available
    ON inventory.inventory_lot
        (tenant_id, sku_id, quantity_unit, status, available_from, supply_pool_id, id);
