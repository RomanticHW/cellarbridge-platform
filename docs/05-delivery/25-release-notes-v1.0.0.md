# CellarBridge v1.0.0

CellarBridge v1.0.0 is an independent engineering demonstration of explainable B2B wine-trade
orchestration. All accounts, customers, products, prices, inventory, orders and events are
synthetic. It is not an official system or endorsed product of any company mentioned in the
research record.

## Capabilities

- tenant and role-bound partner onboarding, catalog search, quotation, approval and customer
  decision;
- exactly-one quote conversion, immutable commercial and supply-decision snapshots;
- PostgreSQL-atomic Inventory reservation with concurrency, idempotency and conservation evidence;
- route-bound Fulfillment, deterministic timeout/failure, Exception recovery and safe customer
  milestones;
- fulfillment-triggered receivables, external payment evidence, reversals, audit, timelines, work
  queues and ECharts dashboards;
- OpenTelemetry traces, ECS logs, Prometheus/Grafana, threat model, SBOM, dependency/secret/container
  checks and reproducible performance/resilience profiles.

## Architecture

The application is a Java 21/Spring Boot/Spring Modulith modular monolith with SQL-first module-owned
PostgreSQL schemas. React 19 consumes a contract-first OpenAPI boundary. Cross-module collaboration
uses versioned facts in a PostgreSQL reliable-publication and Consumer Inbox path; Kafka and Redis
are intentionally absent from the v1.0.0 runtime.

## Run

```bash
make demo
make demo-e2e
make stop-demo
```

Use `make demo DEMO_PROFILE=full` after copying `.env.example` to an untracked `.env` and replacing
the full-profile passwords. The release workflow verifies core/full smoke, backend/frontend tests,
architecture and migration rules, security gates, the complete browser journey, screenshots, SBOMs
and checksums against the exact annotated tag.

## Evidence

- [reviewer paths](../reviewer-guide.md)
- [performance evidence](../evidence/performance/report.md)
- [resilience evidence](../evidence/resilience/report.md)
- [security scan design](../evidence/security/scan-summary.md)
- [observability walkthrough](../evidence/observability/trace-walkthrough.md)
- [publication audit](../evidence/release/publication-audit.md)

## Known limitations

- Measurements are single-host, warm-cache component evidence, not production SLAs or capacity
  guarantees.
- Payments, carriers and warehouse adapters are deterministic simulations; no live provider is
  contacted.
- Reporting is event-projected and eventually consistent; the UI exposes `dataAsOf` and projection
  status.
- PostgreSQL local publication is the deployed event transport. No Kafka or Redis result is claimed.
- Production deployment still needs managed secrets, TLS/WAF, backup/PITR, identity administration,
  retention/capacity planning and an external security assessment.
- Flyway V2–V22 are immutable. Application rollback after schema publication must preserve them and
  use a forward migration for later schema corrections.
