# v1.0.0 reviewer assessment

## 10-minute screening

Finding: the earlier README mixed implemented capabilities with target-release language and left
performance/public-demo status as Planned. The v1.0.0 README now opens with the business problem,
non-CRUD distinction, architecture and complete flow, then links one-command startup, evidence and
known limits. A reviewer can reach architecture, source and release proof without a meeting.

## 30-minute senior Java review

Finding: the older technical guide still described Inventory, dashboards and full observability as
future work. The guide now points to the aggregate/policy code, PostgreSQL conditional updates,
reliable event delivery, accepted ADRs, migration ownership and the real concurrency/recovery tests.
The explicit Kafka/Redis absence prevents distributed-system overclaiming.

## 60-minute runtime review

Finding: the repository had many valid slice-specific E2E commands but no single release story or
safe destructive reset. `make demo`, `make demo-reset`, core/full smoke and `frontend/e2e/demo.spec.ts`
now provide one bounded path. The journey covers customer activation, SKU search, route approval,
customer acceptance, one order, reservation, timeout, Exception recovery, completion, receivable,
payment/reversal, dashboard, audit, Buyer filtering and cross-tenant denial. It emits six synthetic
screenshots for a truthful fallback when a reviewer cannot run Docker.

## Remaining limits

The release does not claim live payment/carrier/WMS integration, multi-region operation, external
broker behavior, production penetration testing or production capacity. Those boundaries are
visible in README, release notes and evidence reports instead of being inferred from local tests.
