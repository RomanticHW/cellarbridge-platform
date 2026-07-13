\set ON_ERROR_STOP on

\set tenant_id '90000000-0000-4000-8000-000000000001'
\set actor_id '90000000-0000-4000-8000-000000000002'

INSERT INTO catalog.producer
    (id, tenant_id, name, normalized_name, country_code,
     created_at, created_by, updated_at, updated_by, version)
SELECT md5('catalog-benchmark-producer-' || sequence)::uuid,
       :'tenant_id'::uuid,
       'Benchmark Producer ' || lpad(sequence::text, 3, '0'),
       'benchmark producer ' || lpad(sequence::text, 3, '0'),
       CASE sequence % 5 WHEN 0 THEN 'FR' WHEN 1 THEN 'CN' WHEN 2 THEN 'NZ'
            WHEN 3 THEN 'IT' ELSE 'ES' END,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid, 0
  FROM generate_series(1, 100) AS sequence;

INSERT INTO catalog.region
    (id, tenant_id, name, normalized_name, country_code,
     created_at, created_by, updated_at, updated_by, version)
SELECT md5('catalog-benchmark-region-' || sequence)::uuid,
       :'tenant_id'::uuid,
       'Benchmark Region ' || lpad(sequence::text, 2, '0'),
       'benchmark region ' || lpad(sequence::text, 2, '0'),
       CASE sequence % 5 WHEN 0 THEN 'FR' WHEN 1 THEN 'CN' WHEN 2 THEN 'NZ'
            WHEN 3 THEN 'IT' ELSE 'ES' END,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid, 0
  FROM generate_series(1, 50) AS sequence;

INSERT INTO catalog.wine_product
    (id, tenant_id, producer_id, region_id, name, normalized_name,
     category, description, tags,
     created_at, created_by, updated_at, updated_by, version)
SELECT md5('catalog-benchmark-product-' || sequence)::uuid,
       :'tenant_id'::uuid,
       md5('catalog-benchmark-producer-' || ((sequence - 1) % 100 + 1))::uuid,
       md5('catalog-benchmark-region-' || ((sequence - 1) % 50 + 1))::uuid,
       'Benchmark Cuvée ' || lpad(sequence::text, 5, '0'),
       'benchmark cuvee ' || lpad(sequence::text, 5, '0'),
       CASE sequence % 6 WHEN 0 THEN 'RED' WHEN 1 THEN 'WHITE' WHEN 2 THEN 'ROSE'
            WHEN 3 THEN 'SPARKLING' WHEN 4 THEN 'FORTIFIED' ELSE 'DESSERT' END,
       'Deterministic synthetic performance fixture.',
       CASE WHEN sequence % 50 = 0
            THEN ARRAY['starling', 'benchmark']
            ELSE ARRAY['benchmark', 'cellar'] END,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid, 0
  FROM generate_series(1, 4000) AS sequence;

INSERT INTO catalog.sku
    (id, tenant_id, product_id, code, vintage_code, volume_ml, units_per_case,
     package_type, status, search_text, activated_at, deactivated_at,
     created_at, created_by, updated_at, updated_by, version)
SELECT md5('catalog-benchmark-sku-' || product_sequence || '-' || variant)::uuid,
       :'tenant_id'::uuid,
       md5('catalog-benchmark-product-' || product_sequence)::uuid,
       'BENCH-' || lpad(product_sequence::text, 5, '0') || '-' ||
           CASE variant WHEN 1 THEN '2019-750X6' WHEN 2 THEN '2021-1500X3' ELSE 'NV-750X12' END,
       CASE variant WHEN 1 THEN '2019' WHEN 2 THEN '2021' ELSE 'NV' END,
       CASE variant WHEN 2 THEN 1500 ELSE 750 END,
       CASE variant WHEN 1 THEN 6 WHEN 2 THEN 3 ELSE 12 END,
       CASE variant WHEN 2 THEN 'WOODEN_CASE' WHEN 3 THEN 'GIFT_BOX' ELSE 'CASE' END,
       'ACTIVE',
       'benchmark cuvee ' || lpad(product_sequence::text, 5, '0') ||
           ' producer ' || lpad((((product_sequence - 1) % 100) + 1)::text, 3, '0') ||
           ' region ' || lpad((((product_sequence - 1) % 50) + 1)::text, 2, '0') ||
           CASE WHEN product_sequence % 50 = 0 THEN ' starling structured' ELSE ' cellar mineral' END,
       '2026-07-13T00:00:00Z', NULL,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid, 1
  FROM generate_series(1, 4000) AS product_sequence
 CROSS JOIN generate_series(1, 3) AS variant;

INSERT INTO inventory.warehouse
    (id, tenant_id, code, name, country_code, city, status,
     created_at, created_by, updated_at, updated_by, version)
SELECT md5('catalog-benchmark-warehouse-' || sequence)::uuid,
       :'tenant_id'::uuid,
       'BENCH-WH-' || sequence,
       'Benchmark Warehouse ' || sequence,
       CASE sequence WHEN 1 THEN 'CN' WHEN 2 THEN 'CN' WHEN 3 THEN 'HK' ELSE 'FR' END,
       CASE sequence WHEN 1 THEN 'Shanghai' WHEN 2 THEN 'Ningbo'
            WHEN 3 THEN 'Hong Kong' ELSE 'Lyon' END,
       'ACTIVE',
       '2026-07-13T00:00:00Z', :'actor_id'::uuid,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid, 0
  FROM generate_series(1, 5) AS sequence;

INSERT INTO inventory.supply_pool
    (id, tenant_id, warehouse_id, code, supply_type, route_code, currency,
     available_from, confidence, policy_version, status,
     created_at, created_by, updated_at, updated_by, version)
SELECT md5('catalog-benchmark-pool-' || sequence)::uuid,
       :'tenant_id'::uuid,
       md5('catalog-benchmark-warehouse-' || sequence)::uuid,
       'BENCH-POOL-' || sequence,
       CASE sequence WHEN 1 THEN 'DOMESTIC_ON_HAND' WHEN 2 THEN 'BONDED_ON_HAND'
            WHEN 3 THEN 'HONG_KONG_ON_HAND' WHEN 4 THEN 'IN_TRANSIT_PRESALE'
            ELSE 'OVERSEAS_SOURCING' END,
       CASE sequence WHEN 1 THEN 'SH_GENERAL_TRADE' WHEN 2 THEN 'NB_BONDED_B2B'
            WHEN 3 THEN 'HK_FREE_TRADE' ELSE NULL END,
       CASE WHEN sequence = 3 THEN 'HKD' WHEN sequence > 3 THEN 'EUR' ELSE 'CNY' END,
       '2026-07-13T00:00:00Z',
       CASE WHEN sequence <= 3 THEN 'HIGH' WHEN sequence = 4 THEN 'MEDIUM' ELSE 'LOW' END,
       'BENCH-2026-01', 'ACTIVE',
       '2026-07-13T00:00:00Z', :'actor_id'::uuid,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid, 0
  FROM generate_series(1, 5) AS sequence;

INSERT INTO catalog.sku_supply_projection
    (tenant_id, sku_id, supply_pool_id, supply_type, location_label,
     availability_class, display_quantity_band, automatically_reservable,
     estimated_available_at, data_as_of, projection_version,
     created_at, created_by, updated_at, updated_by, version)
SELECT :'tenant_id'::uuid,
       md5('catalog-benchmark-sku-' || product_sequence || '-' || variant)::uuid,
       md5('catalog-benchmark-pool-' || pool_sequence)::uuid,
       CASE pool_sequence WHEN 1 THEN 'DOMESTIC_ON_HAND' WHEN 2 THEN 'BONDED_ON_HAND'
            ELSE 'IN_TRANSIT_PRESALE' END,
       'Benchmark Warehouse ' || pool_sequence,
       CASE WHEN pool_sequence <= 2 THEN 'AVAILABLE' ELSE 'REQUIRES_CONFIRMATION' END,
       CASE WHEN pool_sequence <= 2 THEN 'HIGH' ELSE 'CONFIRMATION_REQUIRED' END,
       pool_sequence <= 2,
       CASE WHEN pool_sequence = 4 THEN '2026-08-01T00:00:00Z'::timestamptz ELSE NULL END,
       '2026-07-13T00:00:00Z', 1,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid, 0
  FROM generate_series(1, 4000) AS product_sequence
 CROSS JOIN generate_series(1, 3) AS variant
 CROSS JOIN (VALUES (1), (2), (4)) AS pools(pool_sequence);

INSERT INTO inventory.inventory_lot
    (id, tenant_id, supply_pool_id, sku_id, lot_code, status,
     on_hand_quantity, reserved_quantity, available_from, received_at,
     created_at, created_by, updated_at, updated_by, version)
SELECT md5('catalog-benchmark-lot-' || product_sequence || '-' || variant || '-' || pool_sequence)::uuid,
       :'tenant_id'::uuid,
       md5('catalog-benchmark-pool-' || pool_sequence)::uuid,
       md5('catalog-benchmark-sku-' || product_sequence || '-' || variant)::uuid,
       'BENCH-LOT-' || product_sequence || '-' || variant || '-' || pool_sequence,
       'AVAILABLE', 48 + variant, variant,
       '2026-07-13T00:00:00Z', '2026-07-01T00:00:00Z',
       '2026-07-13T00:00:00Z', :'actor_id'::uuid,
       '2026-07-13T00:00:00Z', :'actor_id'::uuid, 0
  FROM generate_series(1, 4000) AS product_sequence
 CROSS JOIN generate_series(1, 3) AS variant
 CROSS JOIN (VALUES (1), (2), (4)) AS pools(pool_sequence);

ANALYZE catalog.sku;
ANALYZE catalog.wine_product;
ANALYZE catalog.sku_supply_projection;
ANALYZE inventory.inventory_lot;
