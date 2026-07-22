# Resilience and failure evidence report

Status: **Available in Task 14**

The evidence favors state integrity and bounded recovery over synthetic fault counts. Every scenario names the real boundary, the injected failure and the post-recovery invariant.

## Scenario matrix

| Boundary | Injection | Expected and asserted result | Evidence |
|---|---|---|---|
| Local event publication/inbox | duplicate delivery, reversed backlog, handler failure before commit | one side effect per event; inbox and next publication share the business transaction; rollback is retried within the configured bound | `LocalEventDeliveryIntegrationTest` |
| PostgreSQL transaction | two transactions update two rows in opposite order | PostgreSQL selects one deadlock victim; the failure is classified retryable; ordered retry completes with preserved balances | `TransactionDeadlockRecoveryIntegrationTest` |
| Quote acceptance/order conversion | same and distinct keys execute concurrently | one accepted decision and one order/publication per source quotation | `CustomerQuotationApiIntegrationTest`, `TradeOrderConversionIntegrationTest` |
| Inventory | hot lot and multi-command contention | no oversell, negative balance, duplicate movement or unexplained terminal result | inventory concurrency integration tests |
| Reporting projector | duplicate, older and closing-before-opening facts; staging rebuild | stale state never replaces newer state; pending fact resolves; rebuild switches an equivalent generation atomically | `AuditReportingIntegrationTest` |
| Fulfillment adapter | deterministic delay, failure, timeout and duplicate timeout command | persisted failed attempt and stable `SIMULATED_ADAPTER_TIMEOUT`; duplicate command adds no adapter attempt; SLA marking and recovery are idempotent | fulfillment and Exception Center integration tests |
| Keycloak/JWK | stop Keycloak after warming the decoder cache | new token endpoint is unavailable; an existing valid token remains verifiable; fresh login/token works after recovery | full profile Playwright evidence |
| Redis | dependency absent | core correctness scenarios pass without Redis because no runtime or backend dependency exists | suite topology assertion plus all correctness scenarios |

The application does not deploy Kafka. “Broker/publication backlog” is therefore exercised at the actual PostgreSQL-backed `event_publication`/Consumer Inbox boundary, without claiming an external-broker outage.

The publication scenarios cover both crash windows represented by the deployed boundary: rollback before business commit leaves neither Inbox completion nor next publication, while a committed business fact remains in the publication table and the reversed backlog is drained after the simulated restart. Inbox completion and the downstream publication commit atomically, so replay after a process loss becomes a recorded duplicate rather than a second side effect.

## Representative full result

The 2026-07-22 local candidate full profile passed every scenario in the matrix. It drained 1,000 publication events delivered twice with exactly 1,000 side effects, produced and recovered one real PostgreSQL deadlock victim, preserved quotation/order/inventory/reporting invariants, rejected non-success adapter scenarios under the production-default flag, recorded deterministic fulfillment timeout/recovery, accepted a cached valid JWT while Keycloak was stopped, and accepted a new login/token after restart. Redis remained absent from both runtime topology and backend dependencies. The ignored `result.json` records the exact environment and that this was a pre-commit working-tree run; clean PR/main workflow artifacts are the release evidence.

## Fault-control safety

Non-success fulfillment simulation is double guarded:

1. `cellarbridge.fulfillment.failure-simulation-enabled` defaults to `false` and is enabled only by `application-demo.yml`;
2. the action path still requires tenant-scoped `FULFILLMENT_OPERATE` authorization and step ownership/state validation.

There is no production fault-control endpoint. The production-style unit test proves that `SUCCESS` remains available while `DELAY`, `FAILURE` and `TIMEOUT` are rejected when the flag is false. Database deadlock, consumer rollback and projector disorder are test-fixture controls, not runtime switches.

## Recovery semantics

- retryable storage failures use stable failure codes and existing bounded delivery policy;
- constraint/programming failures are not reclassified as transient;
- failed handler transactions do not leave an Inbox completion or downstream publication;
- duplicate callbacks/deliveries reuse persisted business keys and cannot duplicate terminal side effects;
- fulfillment timeout is recorded as failure, never converted to a false success;
- recovery reads current source state and remains idempotent;
- Keycloak outage does not bypass signature, issuer, audience, tenant or permission checks.

## Profiles and artifacts

`make performance-smoke` runs all database/state-integrity scenarios and records Keycloak as profile-disabled. `make performance-full` adds the controlled Keycloak stop/restart proof. Health waits are deadline-bounded polls; concurrency coordination uses latches rather than arbitrary sleeps.

The ignored `target/performance-evidence/<profile>/result.json` records scenario status and durations. The full profile additionally writes `keycloak-outage.json`. Logs and JSON contain no access tokens, passwords, local filesystem paths in recorded commands, tenant business payloads or production data.

## Known boundaries

- PostgreSQL process termination and application-process kill/restart are represented by transaction rollback, consumer retry and projector rebuild tests; the profile does not corrupt the database volume.
- There is no real carrier integration. The deterministic adapter proves timeout/failure state handling and duplicate-safe recovery, not a third-party SLA.
- JWK-cache behavior is library/runtime evidence for a bounded outage with an already cached key; key rotation during an outage requires a live identity provider and is correctly unavailable.
