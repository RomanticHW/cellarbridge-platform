INSERT INTO partner.partner
    (id, tenant_id, number, legal_name, normalized_legal_name, display_name,
     registration_identifier, normalized_registration_identifier, partner_type, status,
     default_currency, requested_payment_term_days, requested_route_codes,
     requested_service_regions, requested_currencies, contact_name, contact_email,
     contact_phone, billing_country_code, billing_province, billing_city, billing_district,
     billing_line1, billing_postal_code, sales_owner_id,
     submitted_by_id, submitted_at, created_at, created_by, updated_at, updated_by, version)
VALUES
    ('53000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001',
     'PAR-DEMO-QUOTATION', 'Aurora Market Services Ltd.', 'aurora market services ltd',
     'Aurora Market Services', 'SYN-QUO-001', 'syn-quo-001', 'DISTRIBUTOR', 'ACTIVE',
     'CNY', 30, ARRAY['SH_GENERAL_TRADE', 'NB_BONDED_B2B']::varchar[],
     ARRAY['CN-SH', 'CN-ZJ']::varchar[], ARRAY['CNY']::varchar[],
     'Lin Yue', 'lin.yue@example.test', '+86-000-5555-0101',
     'CN', 'Shanghai', 'Shanghai', 'Pudong', '88 Harbor Avenue', '200120',
     '11200000-0000-4000-8000-000000000001',
     '11200000-0000-4000-8000-000000000001', '2026-07-13T00:00:00Z',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO partner.eligibility_version
    (tenant_id, partner_id, eligibility_version, allowed_route_codes,
     allowed_service_regions, allowed_currencies, payment_term_days,
     credit_limit_amount, credit_limit_currency, effective_from, approved_by, approved_at,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('10000000-0000-4000-8000-000000000001',
     '53000000-0000-4000-8000-000000000001', 1,
     ARRAY['SH_GENERAL_TRADE', 'NB_BONDED_B2B']::varchar[],
     ARRAY['CN-SH', 'CN-ZJ']::varchar[], ARRAY['CNY']::varchar[], 30,
     500000.0000, 'CNY', '2026-07-13T00:00:00Z',
     '11200000-0000-4000-8000-000000000003', '2026-07-13T00:00:00Z',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000003',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000003', 0)
ON CONFLICT (tenant_id, partner_id, eligibility_version) DO NOTHING;

INSERT INTO quotation.price_reference
    (tenant_id, sku_id, currency, list_case_price, cost_case_price, price_version,
     effective_from, created_at, created_by, updated_at, updated_by, version)
VALUES
    ('10000000-0000-4000-8000-000000000001', '34000000-0000-4000-8000-000000000001',
     'CNY', 1260.0000, 930.0000, 'PRICE-2026-01', '2026-01-01T00:00:00Z',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004', 0),
    ('10000000-0000-4000-8000-000000000001', '34000000-0000-4000-8000-000000000002',
     'CNY', 1680.0000, 1280.0000, 'PRICE-2026-01', '2026-01-01T00:00:00Z',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004', 0),
    ('10000000-0000-4000-8000-000000000001', '34000000-0000-4000-8000-000000000003',
     'CNY', 980.0000, 720.0000, 'PRICE-2026-01', '2026-01-01T00:00:00Z',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004', 0),
    ('10000000-0000-4000-8000-000000000001', '34000000-0000-4000-8000-000000000004',
     'CNY', 720.0000, 500.0000, 'PRICE-2026-01', '2026-01-01T00:00:00Z',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004', 0),
    ('10000000-0000-4000-8000-000000000001', '34000000-0000-4000-8000-000000000005',
     'CNY', 1100.0000, 810.0000, 'PRICE-2026-01', '2026-01-01T00:00:00Z',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004', 0),
    ('10000000-0000-4000-8000-000000000001', '34000000-0000-4000-8000-000000000006',
     'CNY', 1380.0000, 990.0000, 'PRICE-2026-01', '2026-01-01T00:00:00Z',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004',
     '2026-07-13T00:00:00Z', '11200000-0000-4000-8000-000000000004', 0)
ON CONFLICT (tenant_id, sku_id, currency, price_version) DO NOTHING;
