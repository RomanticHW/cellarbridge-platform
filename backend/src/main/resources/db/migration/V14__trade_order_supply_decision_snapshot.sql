ALTER TABLE trade_order.trade_order
    ADD COLUMN supply_decision_status varchar(40),
    ADD COLUMN supply_decision_schema_version integer,
    ADD COLUMN supply_decision_policy_version varchar(80),
    ADD COLUMN supply_decision_at timestamptz,
    ADD COLUMN supply_decision_hash varchar(64),
    ADD COLUMN supply_decision_snapshot jsonb;

UPDATE trade_order.trade_order
   SET supply_decision_status = 'LEGACY_UNVERIFIED';

SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE trade_order.trade_order
    ALTER COLUMN supply_decision_status SET NOT NULL,
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

CREATE OR REPLACE FUNCTION trade_order.protect_commercial_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.number IS DISTINCT FROM OLD.number
       OR NEW.source_quotation_id IS DISTINCT FROM OLD.source_quotation_id
       OR NEW.source_quotation_number IS DISTINCT FROM OLD.source_quotation_number
       OR NEW.source_revision_id IS DISTINCT FROM OLD.source_revision_id
       OR NEW.source_revision_number IS DISTINCT FROM OLD.source_revision_number
       OR NEW.source_event_id IS DISTINCT FROM OLD.source_event_id
       OR NEW.source_owner_id IS DISTINCT FROM OLD.source_owner_id
       OR NEW.acceptance_id IS DISTINCT FROM OLD.acceptance_id
       OR NEW.accepted_at IS DISTINCT FROM OLD.accepted_at
       OR NEW.partner_id IS DISTINCT FROM OLD.partner_id
       OR NEW.partner_number IS DISTINCT FROM OLD.partner_number
       OR NEW.partner_display_name IS DISTINCT FROM OLD.partner_display_name
       OR NEW.partner_source_version IS DISTINCT FROM OLD.partner_source_version
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.total_amount IS DISTINCT FROM OLD.total_amount
       OR NEW.payment_term_days IS DISTINCT FROM OLD.payment_term_days
       OR NEW.route_code IS DISTINCT FROM OLD.route_code
       OR NEW.route_policy_version IS DISTINCT FROM OLD.route_policy_version
       OR NEW.route_estimated_delivery_date IS DISTINCT FROM OLD.route_estimated_delivery_date
       OR NEW.accepted_terms_version IS DISTINCT FROM OLD.accepted_terms_version
       OR NEW.requested_delivery_date IS DISTINCT FROM OLD.requested_delivery_date
       OR NEW.delivery_address IS DISTINCT FROM OLD.delivery_address
       OR NEW.commercial_snapshot IS DISTINCT FROM OLD.commercial_snapshot
       OR NEW.snapshot_schema_version IS DISTINCT FROM OLD.snapshot_schema_version
       OR NEW.snapshot_hash IS DISTINCT FROM OLD.snapshot_hash
       OR NEW.supply_decision_status IS DISTINCT FROM OLD.supply_decision_status
       OR NEW.supply_decision_schema_version IS DISTINCT FROM OLD.supply_decision_schema_version
       OR NEW.supply_decision_policy_version IS DISTINCT FROM OLD.supply_decision_policy_version
       OR NEW.supply_decision_at IS DISTINCT FROM OLD.supply_decision_at
       OR NEW.supply_decision_hash IS DISTINCT FROM OLD.supply_decision_hash
       OR NEW.supply_decision_snapshot IS DISTINCT FROM OLD.supply_decision_snapshot
       OR NEW.created_event_id IS DISTINCT FROM OLD.created_event_id
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'trade_order commercial snapshot is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
