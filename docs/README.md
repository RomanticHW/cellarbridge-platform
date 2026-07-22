# Documentation Index

The documentation is organized from evidence to delivery. Each layer narrows the solution while preserving traceability to the layer before it.

## Reading order

1. **Research:** what is known, inferred, and intentionally added.
2. **Product:** who uses the platform, what outcomes it supports, and what remains out of scope.
3. **Domain:** bounded contexts, aggregates, invariants, state machines, and events.
4. **Architecture:** module boundaries, data ownership, security, eventing, performance, and deployment.
5. **Contracts:** API, event, data, permissions, idempotency, and audit conventions.
6. **Delivery:** vertical-slice roadmap, definition of done, tests, demo, and publication policy.

## Document map

### Research — `docs/00-research/`

- [Executive summary](00-research/00-executive-summary.md)
- [Company profile and evidence](00-research/01-company-profile-and-evidence.md)
- [Evidence matrix](00-research/02-evidence-matrix.md)
- [Business model analysis](00-research/03-business-model-analysis.md)
- [Product and service landscape](00-research/04-product-and-service-landscape.md)
- [Supply-chain and trade modes](00-research/05-supply-chain-and-trade-modes.md)
- [Domain risks and unknowns](00-research/06-domain-risks-and-unknowns.md)
- [Scenario selection](00-research/07-scenario-selection.md)
- [Real, inferred, and demo boundary](00-research/08-real-inferred-demo-boundary.md)
- [Sources](00-research/09-sources.md)

### Product — `docs/01-product/`

- [Product vision](01-product/00-product-vision.md)
- [Personas and stakeholders](01-product/01-personas-and-stakeholders.md)
- [Scope and product principles](01-product/02-scope-and-principles.md)
- [Capability map](01-product/03-capability-map.md)
- [Use-case catalog](01-product/04-use-case-catalog.md)
- [User journeys](01-product/05-user-journeys.md)
- [Business processes](01-product/06-business-processes.md)
- [Functional requirements](01-product/07-functional-requirements.md)
- [Non-functional requirements](01-product/08-non-functional-requirements.md)
- [Page specifications](01-product/09-page-specifications.md)
- [Notifications and work queue](01-product/10-notifications-and-work-queue.md)
- [Demonstration scenario](01-product/11-demonstration-scenario.md)
- [Acceptance criteria](01-product/12-acceptance-criteria.md)
- [Product roadmap](01-product/13-product-roadmap.md)

### Domain — `docs/02-domain/`

- [Ubiquitous language](02-domain/00-ubiquitous-language.md)
- [Context map](02-domain/01-context-map.md)
- [Bounded contexts](02-domain/02-bounded-contexts.md)
- [Aggregate catalog](02-domain/03-aggregate-catalog.md)
- [Aggregates and invariants](02-domain/04-aggregates-and-invariants.md)
- [State machines](02-domain/05-state-machines.md)
- [Domain event catalog](02-domain/06-domain-event-catalog.md)
- [Policies and process managers](02-domain/07-policies-and-process-managers.md)
- [Application use cases](02-domain/08-application-use-cases.md)
- [Errors, compensation, and recovery](02-domain/09-errors-compensation-and-recovery.md)
- [Domain diagrams](02-domain/10-domain-diagrams.md)

### Architecture — `docs/03-architecture/`

- [Architecture overview](03-architecture/00-architecture-overview.md)
- [Modular monolith rationale](03-architecture/01-modular-monolith-rationale.md)
- [Module dependencies](03-architecture/02-module-dependency-rules.md)
- [Layering and package structure](03-architecture/03-layering-and-package-structure.md)
- [Data ownership and transactions](03-architecture/04-data-ownership-and-transactions.md)
- [Events and reliable publication](03-architecture/05-events-and-reliable-publication.md)
- [Security and tenancy](03-architecture/06-security-and-tenancy.md)
- [Observability](03-architecture/07-observability.md)
- [Performance and scalability](03-architecture/08-performance-and-scalability.md)
- [Resilience and failure model](03-architecture/09-resilience-and-failure-model.md)
- [Deployment topology](03-architecture/10-deployment-topology.md)
- [Frontend architecture](03-architecture/11-frontend-architecture.md)
- [Testing architecture](03-architecture/12-testing-architecture.md)
- [Technology baseline](03-architecture/13-technology-baseline.md)
- [Architecture fitness functions](03-architecture/14-architecture-fitness-functions.md)
- [Architecture Decision Records](03-architecture/adr/)

### Contracts — `docs/04-contracts/` and `contracts/`

- [API guidelines](04-contracts/00-api-guidelines.md)
- [Error catalog](04-contracts/01-error-catalog.md)
- [Idempotency](04-contracts/02-idempotency.md)
- [Pagination, filtering, and concurrency](04-contracts/03-pagination-filtering-and-concurrency.md)
- [Event contract rules](04-contracts/04-event-contract-rules.md)
- [Database design](04-contracts/05-database-design.md)
- [Permission matrix](04-contracts/06-permission-matrix.md)
- [Audit model](04-contracts/07-audit-model.md)
- [Reporting read models](04-contracts/08-reporting-read-models.md)
- [Data classification and retention](04-contracts/09-data-classification-and-retention.md)
- [OpenAPI](../contracts/openapi/cellarbridge-api.yaml)
- [AsyncAPI](../contracts/asyncapi/cellarbridge-events.yaml)

### Delivery — `docs/05-delivery/`

- [Implementation roadmap](05-delivery/00-implementation-roadmap.md)
- [Work breakdown and dependencies](05-delivery/01-work-breakdown-and-dependencies.md)
- [Definition of done](05-delivery/02-definition-of-done.md)
- [Testing strategy](05-delivery/03-testing-strategy.md)
- [Commit and pull-request strategy](05-delivery/04-commit-and-pr-strategy.md)
- [Release strategy](05-delivery/05-release-strategy.md)
- [Synthetic demo data](05-delivery/06-synthetic-demo-data.md)
- [Demo script](05-delivery/07-demo-script.md)
- [Technical review guide](05-delivery/08-technical-review-guide.md)
- [Publication and repository hygiene](05-delivery/09-publication-and-repository-hygiene.md)
- [Implementation status](05-delivery/10-implementation-status.md)
- [Requirement traceability](05-delivery/11-requirement-traceability.md)
- [Technical reviewer scorecard](05-delivery/12-reviewer-scorecard.md)
- [Identity and access runbook](05-delivery/13-identity-access-runbook.md)
- [Partner onboarding runbook](05-delivery/14-partner-onboarding-runbook.md)
- [Unit-aware catalog supply search runbook](05-delivery/15-catalog-supply-search-runbook.md)
- [Quotation and trade-planning runbook](05-delivery/16-quotation-trade-planning-runbook.md)
- [Customer quotation acceptance runbook](05-delivery/17-customer-quotation-acceptance-runbook.md)
- [Trade order conversion runbook](05-delivery/18-trade-order-conversion-runbook.md)
- [Fulfillment orchestration runbook](05-delivery/19-fulfillment-orchestration-runbook.md)
- [Exception Center and recovery runbook](05-delivery/20-exception-center-runbook.md)

## Change control

`docs/design-baseline.yaml` identifies the approved baseline. A behavior change must update its requirement, domain, contract, test, and implementation status together. Architecture changes require an ADR before code changes.

Task 07C follows [ADR-016](03-architecture/adr/ADR-016-route-supply-decision-readiness-layers.md): Planning, Quotation freeze, and [Propagation](03-architecture/adr/ADR-018-pre-1-0-supply-decision-event-propagation.md) are available. Task 08 Inventory reservation and Task 09 Fulfillment orchestration are available. Task 10 Exception Center is implemented in review with source-verified recovery and controlled event replay.
