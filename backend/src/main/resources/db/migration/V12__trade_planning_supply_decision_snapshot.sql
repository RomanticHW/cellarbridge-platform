ALTER TABLE trade_planning.evaluation
    ADD COLUMN supply_decision_schema_version integer,
    ADD COLUMN supply_decision_policy_version varchar(80),
    ADD COLUMN supply_decision_at timestamptz,
    ADD COLUMN supply_decision_hash varchar(64),
    ADD COLUMN supply_decision_summary jsonb,
    ADD CONSTRAINT ck_trade_planning_supply_decision_completeness CHECK (
        (supply_decision_schema_version IS NULL
         AND supply_decision_policy_version IS NULL
         AND supply_decision_at IS NULL
         AND supply_decision_hash IS NULL
         AND supply_decision_summary IS NULL)
        OR
        (supply_decision_schema_version IS NOT NULL
         AND supply_decision_policy_version IS NOT NULL
         AND supply_decision_at IS NOT NULL
         AND supply_decision_hash IS NOT NULL
         AND supply_decision_summary IS NOT NULL)
    ),
    ADD CONSTRAINT ck_trade_planning_supply_decision_schema CHECK (
        supply_decision_schema_version IS NULL OR supply_decision_schema_version > 0
    ),
    ADD CONSTRAINT ck_trade_planning_supply_decision_policy CHECK (
        supply_decision_policy_version IS NULL
        OR length(btrim(supply_decision_policy_version)) BETWEEN 1 AND 80
    ),
    ADD CONSTRAINT ck_trade_planning_supply_decision_hash CHECK (
        supply_decision_hash IS NULL
        OR supply_decision_hash ~ ('^[0-9a-f]{64}' || chr(36))
    ),
    ADD CONSTRAINT ck_trade_planning_supply_decision_json CHECK (
        supply_decision_summary IS NULL
        OR (jsonb_typeof(supply_decision_summary) = 'object'
            AND supply_decision_summary <> '{}'::jsonb)
    ),
    ADD CONSTRAINT ck_trade_planning_supply_decision_time CHECK (
        supply_decision_at IS NULL OR supply_decision_at = evaluated_at
    ),
    ADD CONSTRAINT ck_trade_planning_supply_decision_selected_route CHECK (
        supply_decision_schema_version IS NULL OR selected_route_code IS NOT NULL
    ),
    ADD CONSTRAINT ck_trade_planning_route_2026_03_decision CHECK (
        policy_version <> 'ROUTE-2026-03' OR supply_decision_schema_version IS NOT NULL
    ),
    ADD CONSTRAINT ck_trade_planning_supply_decision_root_json CHECK (
        supply_decision_summary IS NULL
        OR ((jsonb_typeof(supply_decision_summary) = 'object'
            AND supply_decision_summary ?& ARRAY[
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
            AND jsonb_typeof(supply_decision_summary -> 'schemaVersion') = 'number'
            AND jsonb_typeof(supply_decision_summary -> 'policyVersion') = 'string'
            AND jsonb_typeof(supply_decision_summary -> 'decidedAt') = 'string'
            AND jsonb_typeof(supply_decision_summary -> 'sourceRouteEvaluationId') = 'string'
            AND jsonb_typeof(supply_decision_summary -> 'sourceRouteInputHash') = 'string'
            AND jsonb_typeof(supply_decision_summary -> 'selectedRouteCode') = 'string'
            AND jsonb_typeof(supply_decision_summary -> 'inventoryDataAsOf') = 'string'
            AND jsonb_typeof(supply_decision_summary -> 'decisionHash') = 'string'
            AND jsonb_typeof(supply_decision_summary -> 'lineDecisions') = 'array'
            AND CASE
                    WHEN jsonb_typeof(supply_decision_summary -> 'lineDecisions') = 'array'
                    THEN jsonb_array_length(supply_decision_summary -> 'lineDecisions') > 0
                    ELSE FALSE
                END
            AND supply_decision_summary ->> 'schemaVersion'
                = supply_decision_schema_version::text
            AND supply_decision_summary ->> 'policyVersion'
                = supply_decision_policy_version
            AND supply_decision_summary ->> 'decisionHash'
                = supply_decision_hash
            AND supply_decision_summary ->> 'sourceRouteEvaluationId'
                = id::text
            AND supply_decision_summary ->> 'sourceRouteInputHash'
                = input_hash
            AND supply_decision_summary ->> 'selectedRouteCode'
                = selected_route_code
            AND supply_decision_summary ->> 'sourceRouteInputHash'
                ~ ('^[0-9a-f]{64}' || chr(36))
            AND supply_decision_summary ->> 'decisionHash'
                ~ ('^[0-9a-f]{64}' || chr(36))) IS TRUE)
    );
