EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT sp.sku_id
  FROM catalog.sku_supply_projection sp
 WHERE sp.tenant_id = '90000000-0000-4000-8000-000000000001'
   AND sp.quantity_unit = 'BOTTLE'
   AND sp.supply_type = 'DOMESTIC_ON_HAND'
   AND sp.availability_class = 'AVAILABLE'
   AND sp.automatically_reservable = true
 ORDER BY sp.sku_id
 LIMIT 100;
