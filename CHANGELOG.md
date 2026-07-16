# Changelog

All notable changes to this project are documented in this file. The project follows Semantic Versioning after the first executable release.

## [Unreleased]

### Added

- runnable identity/access and partner-onboarding vertical slices;
- tenant-scoped wine/SKU and five-mode supply model with PostgreSQL FTS/trigram search;
- permission-aware catalog workspace, local quote selection, OIDC browser verification, and reproducible query benchmark;
- CASE/BOTTLE-aware inventory and Catalog projections, ROUTE-2026-02 exact-unit coverage, versioned warehouse-priority corrections, preservation tests, and true dual-unit benchmark fixtures;
- revisioned quotation, explainable trade-planning, approval, and secure customer-decision slices;
- idempotent accepted-quotation conversion into one immutable Trade Order, backed by reliable local publication and Consumer Inbox handling;
- accepted decisions clarifying SQL-first persistence, reliable local event delivery, the designed Task 08 reservation protocol, and V10+ migration ownership;
- public documentation that distinguishes the executable core from designed capabilities and planned full-profile infrastructure.
- pre-1.0 accepted-quotation contract correction with canonical Snapshot Hash V1 validation.
- ROUTE-2026-03 Planning evidence with one evaluation time, canonical input schema 3, selected-route Supply Decision persistence in V12, and historical ROUTE-2026-01/02 compatibility; Quotation freeze and inventory reservation remain unavailable.
- Quotation-owned route-bound Supply Decision freezing with AUTO/FIXED inputs, fail-closed Legacy handling, quotation-only V13, internal OpenAPI 1.6 evidence, and customer-safe availability language (implemented in review; merge gated by Propagation readiness).
- Additive Current/Legacy V1 propagation, FROZEN/LEGACY_UNVERIFIED Trade Orders, trade_order-only V14, OpenAPI 1.7 and AsyncAPI 1.1 (implemented in stacked review; no reservation).

### Planned

- Inventory reservation, fulfillment, exception, settlement, and reporting slices
- Kafka/Redis/observability full profile, operational dashboards, and public demo release

## [0.1.0-design] - 2026-07-13

### Added

- evidence-backed company and business-model research;
- product vision, scope, personas, workflows, requirements, and page specifications;
- bounded contexts, aggregate invariants, state machines, domain events, and process policies;
- modular-monolith architecture, security, tenancy, eventing, data ownership, and observability design;
- OpenAPI, AsyncAPI, event schemas, data dictionary, permission matrix, and error catalog;
- implementation roadmap, quality gates, demo scenario, reviewer guide, and repository governance.

### Notes

This release is a design baseline. It intentionally contains no application implementation.
