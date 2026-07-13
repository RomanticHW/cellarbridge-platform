CREATE SCHEMA trade_planning;

CREATE TABLE trade_planning.evaluation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    partner_id uuid NOT NULL,
    policy_version varchar(80) NOT NULL,
    input_hash varchar(64) NOT NULL,
    input_summary jsonb NOT NULL,
    recommended_route_code varchar(50),
    selected_route_code varchar(50),
    override_reason varchar(500),
    override_actor_id uuid,
    override_at timestamptz,
    original_recommendation varchar(50),
    evaluated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_trade_planning_evaluation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_trade_planning_recommended_route CHECK
        (recommended_route_code IS NULL OR recommended_route_code IN
            ('SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE')),
    CONSTRAINT ck_trade_planning_selected_route CHECK
        (selected_route_code IS NULL OR selected_route_code IN
            ('SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE')),
    CONSTRAINT ck_trade_planning_override_complete CHECK
        ((override_reason IS NULL AND override_actor_id IS NULL AND override_at IS NULL
          AND original_recommendation IS NULL)
         OR
         (length(trim(override_reason)) >= 5 AND override_actor_id IS NOT NULL
          AND override_at IS NOT NULL AND original_recommendation IS NOT NULL
          AND selected_route_code <> original_recommendation))
);

CREATE INDEX ix_trade_planning_evaluation_subject
    ON trade_planning.evaluation (tenant_id, partner_id, evaluated_at DESC, id);

CREATE TABLE trade_planning.candidate_result (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    evaluation_id uuid NOT NULL,
    route_code varchar(50) NOT NULL CHECK
        (route_code IN ('SH_GENERAL_TRADE', 'NB_BONDED_B2B', 'HK_FREE_TRADE')),
    eligibility varchar(20) NOT NULL CHECK (eligibility IN ('ELIGIBLE', 'REJECTED')),
    cost_score numeric(7,2),
    lead_time_score numeric(7,2),
    supply_confidence_score numeric(7,2),
    simplicity_score numeric(7,2),
    total_score numeric(7,2),
    estimated_delivery_date date,
    estimated_charges numeric(19,4),
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    rejections jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_trade_planning_candidate UNIQUE (tenant_id, evaluation_id, route_code),
    CONSTRAINT fk_trade_planning_candidate_evaluation FOREIGN KEY (tenant_id, evaluation_id)
        REFERENCES trade_planning.evaluation (tenant_id, id),
    CONSTRAINT ck_trade_planning_candidate_shape CHECK
        ((eligibility = 'REJECTED' AND total_score IS NULL
          AND estimated_delivery_date IS NULL AND estimated_charges IS NULL
          AND jsonb_array_length(rejections) > 0)
         OR
         (eligibility = 'ELIGIBLE' AND cost_score BETWEEN 0 AND 100
          AND lead_time_score BETWEEN 0 AND 100
          AND supply_confidence_score BETWEEN 0 AND 100
          AND simplicity_score BETWEEN 0 AND 100
          AND total_score BETWEEN 0 AND 100
          AND estimated_delivery_date IS NOT NULL AND estimated_charges >= 0
          AND jsonb_array_length(rejections) = 0))
);

CREATE INDEX ix_trade_planning_candidate_evaluation
    ON trade_planning.candidate_result (tenant_id, evaluation_id, route_code);

COMMENT ON TABLE trade_planning.evaluation IS
    'Immutable, versioned and explainable demo route evaluation. It is not legal, tax or customs advice.';
