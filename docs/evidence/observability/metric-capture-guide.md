# Metrics and dashboard capture guide

The version-controlled dashboard source is `dashboard.json`; Grafana provisioning loads the operational copy from `deploy/observability/grafana/dashboards/cellarbridge-operations.json`.

To produce a review screenshot without committing environment data:

1. Copy `.env.example` to `.env`, replace all full-profile passwords, then run `make dev-full`.
2. Run the synthetic acceptance, order, fulfillment, exception, settlement and reporting journeys.
3. Confirm `http://localhost:8080/actuator/prometheus` exposes `cellarbridge_quotation_lifecycle_total`, `cellarbridge_reservation_outcome_total`, `cellarbridge_event_publication_backlog`, `cellarbridge_projection_lag_seconds` and `cellarbridge_exception_open_critical`.
4. Open the provisioned **CellarBridge Operations** dashboard and set a one-hour range.
5. Capture only the dashboard viewport. Check that no portal URL, token, username, tenant UUID, order UUID, local filesystem path or password is visible before sharing it.

Alert thresholds in `deploy/observability/alert-rules.yaml` are demonstration examples, not production SLOs. Production thresholds require workload baselines, retention planning and an owned response policy.
