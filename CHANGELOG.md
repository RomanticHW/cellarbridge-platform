# Changelog

All notable changes to this project are documented in this file. The project follows Semantic Versioning after the first executable release.

## [Unreleased]

### Added

- authenticated read-only MCP Streamable HTTP endpoint at `/mcp`;
- six tenant- and role-scoped business tools, three resources and three reusable operations prompts;
- real OIDC smoke, protocol conformance runner, runbook and bilingual README discovery guidance.

### Security

- every stateless MCP request reuses API JWT audience validation, TenantContext, permissions,
  ownership and field projections;
- strict MCP Origin/CORS policy, no-store responses, bounded inputs and sanitized error envelopes;
- no write tools, model-provider SDK, RAG integration or vector store.

The immutable `v1.0.0` tag and its release assets predate these unreleased MCP capabilities.

## [1.0.0] - 2026-07-22

### Added

- complete tenant-scoped partner, catalog, quotation, customer-decision, order, inventory,
  fulfillment, exception, settlement, audit and reporting vertical slices;
- explainable route evaluation and immutable supply-decision evidence from quotation to order;
- PostgreSQL-backed idempotency, local reliable publication, Consumer Inbox handling, atomic
  reservation, concurrency and recovery proofs;
- React operations and customer workflows with generated OpenAPI types, accessible ECharts views
  and a complete Playwright reviewer journey;
- core and observable full Compose profiles, deterministic demo/reset commands and synthetic-only
  role accounts;
- OpenTelemetry, Prometheus, Grafana, structured logging, threat-model, SBOM, dependency, secret and
  container gates;
- versioned performance/resilience profiles, release evidence manifest, checksums and public review
  paths.

### Security

- runtime containers use non-root identities and read-only filesystems;
- production configuration has no default database or cursor-signing secret;
- capability-token URLs are excluded from service logs and raw trace URLs.

### Known limitations

- the runtime uses PostgreSQL local publication and does not deploy Kafka or Redis;
- payment, carrier and warehouse integrations are deterministic simulations, not live providers;
- performance evidence is single-host, warm-cache component evidence rather than a production SLA;
- production operation still requires managed secrets, TLS/WAF, backup/PITR, capacity planning and
  an external security assessment.

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
