ALTER TABLE fulfillment.simulated_adapter_attempt
    DROP CONSTRAINT ck_fulfillment_adapter_scenario;

ALTER TABLE fulfillment.simulated_adapter_attempt
    ADD CONSTRAINT ck_fulfillment_adapter_scenario
        CHECK (scenario IN ('SUCCESS', 'FAILURE', 'DELAY', 'TIMEOUT'));
