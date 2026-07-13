INSERT INTO identity_access.tenant
    (id, code, display_name, status, created_at, created_by, updated_at, updated_by, version)
VALUES
    ('10000000-0000-4000-8000-000000000001', 'north-cellars', 'North Cellars', 'ACTIVE',
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('20000000-0000-4000-8000-000000000001', 'harbor-cellars', 'Harbor Cellars', 'ACTIVE',
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0)
ON CONFLICT DO NOTHING;

INSERT INTO identity_access.role_template
    (tenant_id, code, display_name, permission_codes,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('10000000-0000-4000-8000-000000000001', 'sales-representative', 'Sales Representative',
     ARRAY['partner:read', 'partner:create', 'partner:submit', 'catalog:read',
           'quotation:read', 'quotation:create', 'quotation:submit', 'quotation:issue',
           'order:read', 'reporting:read']::varchar[],
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('10000000-0000-4000-8000-000000000001', 'customer-buyer', 'Customer Buyer',
     ARRAY['catalog:read', 'quotation:read', 'order:read', 'fulfillment:read']::varchar[],
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('20000000-0000-4000-8000-000000000001', 'sales-manager', 'Sales Manager',
     ARRAY['partner:read', 'partner:create', 'partner:submit', 'partner:review',
           'catalog:read', 'quotation:read', 'quotation:create', 'quotation:submit',
           'quotation:approve', 'quotation:issue', 'quotation:read-commercial-sensitive',
           'order:read', 'order:cancel', 'reporting:read', 'audit:read']::varchar[],
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0)
ON CONFLICT DO NOTHING;

INSERT INTO identity_access.user_mapping
    (id, user_id, tenant_id, issuer, external_subject, username, display_name, status,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('11100000-0000-4000-8000-000000000001', '11200000-0000-4000-8000-000000000001',
     '10000000-0000-4000-8000-000000000001', 'http://localhost:8081/realms/cellarbridge',
     '11000000-0000-4000-8000-000000000001', 'north.sales', 'North Sales', 'ACTIVE',
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('11100000-0000-4000-8000-000000000002', '11200000-0000-4000-8000-000000000002',
     '10000000-0000-4000-8000-000000000001', 'http://localhost:8081/realms/cellarbridge',
     '11000000-0000-4000-8000-000000000002', 'north.buyer', 'North Buyer', 'ACTIVE',
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('11100000-0000-4000-8000-000000000099', '11200000-0000-4000-8000-000000000099',
     '10000000-0000-4000-8000-000000000001', 'http://localhost:8081/realms/cellarbridge',
     '11000000-0000-4000-8000-000000000099', 'north.suspended', 'Suspended Demo User', 'SUSPENDED',
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('21100000-0000-4000-8000-000000000001', '21200000-0000-4000-8000-000000000001',
     '20000000-0000-4000-8000-000000000001', 'http://localhost:8081/realms/cellarbridge',
     '22000000-0000-4000-8000-000000000001', 'harbor.manager', 'Harbor Manager', 'ACTIVE',
     '2026-07-13T00:00:00Z', 'demo-seed', '2026-07-13T00:00:00Z', 'demo-seed', 0)
ON CONFLICT DO NOTHING;

INSERT INTO identity_access.user_mapping_role
    (tenant_id, user_mapping_id, role_code,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('10000000-0000-4000-8000-000000000001', '11100000-0000-4000-8000-000000000001',
     'sales-representative', '2026-07-13T00:00:00Z', 'demo-seed',
     '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('10000000-0000-4000-8000-000000000001', '11100000-0000-4000-8000-000000000002',
     'customer-buyer', '2026-07-13T00:00:00Z', 'demo-seed',
     '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('10000000-0000-4000-8000-000000000001', '11100000-0000-4000-8000-000000000099',
     'sales-representative', '2026-07-13T00:00:00Z', 'demo-seed',
     '2026-07-13T00:00:00Z', 'demo-seed', 0),
    ('20000000-0000-4000-8000-000000000001', '21100000-0000-4000-8000-000000000001',
     'sales-manager', '2026-07-13T00:00:00Z', 'demo-seed',
     '2026-07-13T00:00:00Z', 'demo-seed', 0)
ON CONFLICT DO NOTHING;
