ALTER TABLE quotation.quotation_revision
    ADD COLUMN supply_decision_status varchar(40),
    ADD COLUMN supply_decision_schema_version integer,
    ADD COLUMN supply_decision_policy_version varchar(80),
    ADD COLUMN supply_decision_at timestamptz,
    ADD COLUMN supply_decision_hash varchar(64),
    ADD COLUMN supply_decision_snapshot jsonb;

UPDATE quotation.quotation_revision
   SET supply_decision_status = CASE
           WHEN route_evaluation_id IS NULL THEN 'UNDECIDED'
           ELSE 'LEGACY_REEVALUATION_REQUIRED'
       END;

ALTER TABLE quotation.quotation_revision
    ALTER COLUMN supply_decision_status SET NOT NULL,
    ADD CONSTRAINT ck_quotation_supply_decision_status CHECK (
        supply_decision_status IN
            ('UNDECIDED', 'FROZEN', 'LEGACY_REEVALUATION_REQUIRED')
    ),
    ADD CONSTRAINT ck_quotation_supply_decision_state CHECK ((
        (supply_decision_status = 'UNDECIDED'
         AND route_evaluation_id IS NULL
         AND supply_decision_schema_version IS NULL
         AND supply_decision_policy_version IS NULL
         AND supply_decision_at IS NULL
         AND supply_decision_hash IS NULL
         AND supply_decision_snapshot IS NULL)
        OR
        (supply_decision_status = 'LEGACY_REEVALUATION_REQUIRED'
         AND route_evaluation_id IS NOT NULL
         AND supply_decision_schema_version IS NULL
         AND supply_decision_policy_version IS NULL
         AND supply_decision_at IS NULL
         AND supply_decision_hash IS NULL
         AND supply_decision_snapshot IS NULL)
        OR
        (supply_decision_status = 'FROZEN'
         AND route_evaluation_id IS NOT NULL
         AND selected_route_code IS NOT NULL
         AND supply_decision_schema_version IS NOT NULL
         AND supply_decision_policy_version IS NOT NULL
         AND supply_decision_at IS NOT NULL
         AND supply_decision_hash IS NOT NULL
         AND supply_decision_snapshot IS NOT NULL)
    ) IS TRUE),
    ADD CONSTRAINT ck_quotation_supply_decision_root CHECK ((
        supply_decision_status <> 'FROZEN'
        OR (supply_decision_schema_version > 0
            AND length(btrim(supply_decision_policy_version)) BETWEEN 1 AND 80
            AND supply_decision_hash ~ ('^[0-9a-f]{64}' || chr(36))
            AND jsonb_typeof(supply_decision_snapshot) = 'object'
            AND supply_decision_snapshot <> '{}'::jsonb)
    ) IS TRUE),
    ADD CONSTRAINT ck_quotation_supply_decision_root_json CHECK ((
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
            AND supply_decision_snapshot ->> 'sourceRouteEvaluationId'
                = route_evaluation_id::text
            AND supply_decision_snapshot ->> 'selectedRouteCode'
                = selected_route_code
            AND supply_decision_snapshot ->> 'sourceRouteInputHash'
                ~ ('^[0-9a-f]{64}' || chr(36))
            AND supply_decision_snapshot ->> 'decisionHash'
                ~ ('^[0-9a-f]{64}' || chr(36)))
    ) IS TRUE);

ALTER TABLE quotation.quotation_line
    ADD COLUMN allocation_mode varchar(40),
    ADD CONSTRAINT ck_quotation_line_allocation_mode CHECK ((
        allocation_mode IS NULL
        OR (allocation_mode = 'FIXED_POOL'
            AND preferred_supply_pool_id IS NOT NULL
            AND supply_type IS NOT NULL)
        OR (allocation_mode = 'ROUTE_ELIGIBLE_AUTO'
            AND preferred_supply_pool_id IS NULL
            AND supply_type IS NOT NULL)
    ) IS TRUE);
