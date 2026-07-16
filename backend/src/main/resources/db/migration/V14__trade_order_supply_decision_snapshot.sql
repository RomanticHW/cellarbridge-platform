ALTER TABLE trade_order.trade_order
    ADD COLUMN supply_decision_status varchar(40) DEFAULT 'LEGACY_UNVERIFIED' NOT NULL,
    ADD COLUMN supply_decision_schema_version integer,
    ADD COLUMN supply_decision_policy_version varchar(80),
    ADD COLUMN supply_decision_at timestamptz,
    ADD COLUMN supply_decision_hash varchar(64),
    ADD COLUMN supply_decision_snapshot jsonb;

ALTER TABLE trade_order.trade_order
    ALTER COLUMN supply_decision_status DROP DEFAULT,
    ADD CONSTRAINT ck_trade_order_supply_decision_status CHECK (
        supply_decision_status IN ('FROZEN', 'LEGACY_UNVERIFIED')
    ),
    ADD CONSTRAINT ck_trade_order_supply_decision_state CHECK ((
        (supply_decision_status = 'LEGACY_UNVERIFIED'
         AND snapshot_schema_version = '1'
         AND supply_decision_schema_version IS NULL
         AND supply_decision_policy_version IS NULL
         AND supply_decision_at IS NULL
         AND supply_decision_hash IS NULL
         AND supply_decision_snapshot IS NULL)
        OR
        (supply_decision_status = 'FROZEN'
         AND snapshot_schema_version = '2'
         AND route_code IS NOT NULL
         AND supply_decision_schema_version IS NOT NULL
         AND supply_decision_policy_version IS NOT NULL
         AND supply_decision_at IS NOT NULL
         AND supply_decision_hash IS NOT NULL
         AND supply_decision_snapshot IS NOT NULL)
    ) IS TRUE),
    ADD CONSTRAINT ck_trade_order_supply_decision_root CHECK ((
        supply_decision_status <> 'FROZEN'
        OR (supply_decision_schema_version = 1
            AND length(btrim(supply_decision_policy_version)) BETWEEN 1 AND 80
            AND supply_decision_hash ~ ('^[0-9a-f]{64}' || chr(36))
            AND jsonb_typeof(supply_decision_snapshot) = 'object'
            AND supply_decision_snapshot <> '{}'::jsonb
            AND supply_decision_at <= accepted_at)
    ) IS TRUE),
    ADD CONSTRAINT ck_trade_order_supply_decision_root_json CHECK ((
        supply_decision_status <> 'FROZEN'
        OR (supply_decision_snapshot ?& ARRAY[
                'schemaVersion',
                'policyVersion',
                'decidedAt',
                'sourceRouteEvaluationId',
                'sourceRouteInputHash',
                'selectedRouteCode',
                'inventoryDataAsOf',
                'decisionHash',
                'lineDecisions'
            ]::text[]
            AND jsonb_typeof(supply_decision_snapshot -> 'schemaVersion') = 'number'
            AND jsonb_typeof(supply_decision_snapshot -> 'policyVersion') = 'string'
            AND jsonb_typeof(supply_decision_snapshot -> 'decidedAt') = 'string'
            AND jsonb_typeof(supply_decision_snapshot -> 'sourceRouteEvaluationId') = 'string'
            AND jsonb_typeof(supply_decision_snapshot -> 'sourceRouteInputHash') = 'string'
            AND jsonb_typeof(supply_decision_snapshot -> 'selectedRouteCode') = 'string'
            AND jsonb_typeof(supply_decision_snapshot -> 'inventoryDataAsOf') = 'string'
            AND jsonb_typeof(supply_decision_snapshot -> 'decisionHash') = 'string'
            AND jsonb_typeof(supply_decision_snapshot -> 'lineDecisions') = 'array'
            AND CASE
                    WHEN jsonb_typeof(supply_decision_snapshot -> 'lineDecisions') = 'array'
                    THEN jsonb_array_length(supply_decision_snapshot -> 'lineDecisions') > 0
                    ELSE FALSE
                END
            AND supply_decision_snapshot ->> 'schemaVersion'
                = supply_decision_schema_version::text
            AND supply_decision_snapshot ->> 'policyVersion'
                = supply_decision_policy_version
            AND supply_decision_snapshot ->> 'decisionHash'
                = supply_decision_hash
            AND supply_decision_snapshot ->> 'selectedRouteCode' = route_code
            AND supply_decision_snapshot ->> 'sourceRouteInputHash'
                ~ ('^[0-9a-f]{64}' || chr(36))
            AND supply_decision_snapshot ->> 'decisionHash'
                ~ ('^[0-9a-f]{64}' || chr(36))
            AND supply_decision_snapshot ->> 'sourceRouteEvaluationId'
                ~ ('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' || chr(36)))
    ) IS TRUE);

ALTER TABLE trade_order.order_line
    ADD COLUMN allocation_mode varchar(40),
    ADD CONSTRAINT ck_trade_order_line_allocation_mode CHECK ((
        allocation_mode IS NULL
        OR (supply_type IN (
                'DOMESTIC_ON_HAND',
                'BONDED_ON_HAND',
                'HONG_KONG_ON_HAND',
                'IN_TRANSIT_PRESALE',
                'OVERSEAS_SOURCING'
            )
            AND ((allocation_mode = 'FIXED_POOL' AND supply_pool_id IS NOT NULL)
                 OR (allocation_mode = 'ROUTE_ELIGIBLE_AUTO' AND supply_pool_id IS NULL)))
    ) IS TRUE);

CREATE TRIGGER trade_order_supply_decision_immutable
    BEFORE UPDATE OF supply_decision_status,
                     supply_decision_schema_version,
                     supply_decision_policy_version,
                     supply_decision_at,
                     supply_decision_hash,
                     supply_decision_snapshot
    ON trade_order.trade_order
    FOR EACH ROW
    EXECUTE FUNCTION trade_order.reject_order_line_or_timeline_mutation();
