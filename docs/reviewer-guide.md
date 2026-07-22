# Technical Reviewer Guide

CellarBridge is arranged so that a reviewer can verify the reasoning behind the code instead of inferring it from package names.

## Ten-minute review

1. Read the root `README.md`.
2. Read `docs/03-architecture/00-architecture-overview.md`.
3. Inspect `docs/02-domain/04-aggregates-and-invariants.md`.
4. Run `make demo`, or inspect the six synthetic browser captures attached to the v1.0.0 release.

This path answers: What problem is being solved? Why this architecture? Where is business correctness enforced? What is actually implemented?

## Thirty-minute review

Add:

- `docs/00-research/07-scenario-selection.md` for product rationale;
- `docs/02-domain/05-state-machines.md` for legal transitions;
- `docs/03-architecture/05-events-and-reliable-publication.md` for asynchronous consistency;
- `docs/03-architecture/08-performance-and-scalability.md` for reservation concurrency;
- `docs/03-architecture/14-architecture-fitness-functions.md` for executable boundaries.
- `backend/src/main/java/com/rom/cellarbridge/inventory/internal/infrastructure/JdbcAtomicInventoryLotRepository.java` for the database correctness boundary;
- `backend/src/main/java/com/rom/cellarbridge/platform/internal/LocalEventDeliveryService.java` for at-least-once local collaboration;
- `frontend/e2e/demo.spec.ts` for the executable browser story.

## Sixty-minute review

Add:

- `contracts/openapi/cellarbridge-api.yaml`;
- `contracts/asyncapi/cellarbridge-events.yaml`;
- `docs/04-contracts/05-database-design.md`;
- `docs/04-contracts/06-permission-matrix.md`;
- `docs/05-delivery/03-testing-strategy.md`;
- `backend/src/main/java/com/rom/cellarbridge/quotation/internal/domain/QuotationAggregate.java`,
  `backend/src/main/java/com/rom/cellarbridge/tradeplanning/internal/domain/RouteEvaluationPolicy.java`
  and their focused tests;
- `docs/evidence/performance/report.md`, `docs/evidence/resilience/report.md` and
  `docs/evidence/security/scan-summary.md` for measured limits and security gates.

## Questions the repository should answer without a meeting

- Why is this a modular monolith instead of microservices?
- Which data belongs to each module?
- How is a quote prevented from creating duplicate orders?
- What makes inventory reservation safe under concurrency?
- What happens when an asynchronous listener fails or receives a duplicate event?
- How are customer, cost, margin, and tenant boundaries enforced?
- Why is PostgreSQL search sufficient for this catalog?
- Which company facts are confirmed and which capabilities are demonstration enhancements?
- Which tests prove the most important claims?
- Can the system be started, observed, and demonstrated without private infrastructure?

## Evidence in v1.0.0

- one-command local startup;
- deterministic seed data and role-based demo accounts;
- successful architecture verification report;
- unit and integration test reports;
- concurrency test proving no oversell;
- API contract validation;
- Playwright trace or video for the main demo path;
- metrics and traces for quote conversion and inventory reservation;
- SBOM, dependency scan, and container scan;
- six synthetic-data screenshots and a release note matching the tagged commit;
- a checksummed release manifest plus application/container SBOM and image scan artifacts.

The 60-minute runtime path is `make demo-reset`, then `make demo-e2e`. It covers customer
activation, SKU search, route comparison and approval, customer acceptance, exactly one order,
Inventory reservation, Fulfillment timeout and Exception recovery, completion, receivable/payment,
dashboard, audit, Buyer field filtering and cross-tenant denial.
## 可选评分路径

需要结构化比较产品判断、架构、并发、安全、React 和工程交付时，可使用[技术评审评分卡](05-delivery/12-reviewer-scorecard.md)。需求到实现证据的完整追踪方式见[需求追踪矩阵](05-delivery/11-requirement-traceability.md)。
