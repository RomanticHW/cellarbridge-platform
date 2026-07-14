# Repository Engineering Guide

This file defines repository-wide rules for human and automated contributors. More specific `AGENTS.md` files may be added under `backend/` and `frontend/` during implementation; a nested file may strengthen local rules but must not weaken this document.

## 1. Source of truth and reading order

Before changing the repository, read the following in order:

1. `README.md`
2. `docs/design-baseline.yaml`
3. the relevant product requirements under `docs/01-product/`
4. the relevant domain model under `docs/02-domain/`
5. the relevant architecture rules and ADRs under `docs/03-architecture/`
6. the API, event, data, permission, and audit contracts under `docs/04-contracts/` and `contracts/`
7. the delivery task and acceptance criteria under `docs/05-delivery/`

When sources disagree, use this precedence:

1. accepted ADR;
2. approved contract or state machine;
3. aggregate invariants;
4. functional requirement;
5. narrative examples.

Do not silently choose between conflicting sources. Stop the affected work, describe the conflict, and propose an ADR or documentation correction.

## 2. Product boundary

CellarBridge is an independent demonstration of B2B wine trade orchestration. It is not an official system of any company mentioned in the research record.

All business statements carry one of three evidence classes:

- `CONFIRMED`: supported by a cited public or owner-supplied source;
- `INFERRED`: a reasoned interpretation of the available evidence;
- `DEMO_ENHANCEMENT`: intentionally added to create a coherent, reviewable technical product.

Never present an inferred or demonstration capability as a confirmed capability of the researched company. Never add real customer, product, price, inventory, order, credential, or employee data. Use synthetic data only.

## 3. Architecture boundary

The approved architecture is a domain-oriented modular monolith.

- Do not convert the system to microservices without an accepted ADR.
- Do not create direct dependencies on another module's internal packages.
- Cross-module Java access is limited to published module APIs and named interfaces.
- Cross-module workflow coordination uses versioned events or explicit public facades.
- Each module owns its tables, migrations, repositories, and business rules.
- Do not add cross-module foreign keys. Store stable identifiers and required snapshots instead.
- A module must not query another module's tables, views, or repositories directly.
- `shared`, `common`, and `utils` must not become dumping grounds for domain concepts.
- Domain objects must not depend on web controllers, persistence entities, messaging clients, or framework-specific transport classes.
- New production dependencies require a documented problem, alternatives, license check, security check, and approval in the task report or ADR.

The following module direction is authoritative:

```text
identity/access ───────────────┐
partner ──────────────────────┤
catalog ──────────────────────┤
inventory ────────────────────┤
quotation ──> trade-planning ─┤
quotation ──event──> order ───┤
order ──────event──> inventory│
inventory ─event──> fulfillment
fulfillment ───────> exception-center
order/fulfillment ─> settlement
all modules ───────> audit/reporting through events
```

No dependency may point back from a downstream module to an upstream module's internals.

## 4. Domain implementation rules

- Aggregate methods express business actions; do not expose setters that bypass invariants.
- Every state transition must be declared in `docs/02-domain/05-state-machines.md`.
- Use value objects for money, currency, quantity, date ranges, addresses, route scores, and identifiers where behavior or validation exists.
- Never represent money with `float` or `double`.
- Use an injected `Clock`; do not call the system clock directly inside domain logic.
- Accepted quotation and order commercial terms are immutable snapshots.
- Idempotency is required for commands marked in `docs/04-contracts/02-idempotency.md`.
- Database uniqueness and atomic conditions are the final correctness guard; an application-level pre-check is not sufficient.
- Inventory reservation must not depend on cache, a distributed lock, or an eventually consistent read model.
- A domain event describes a completed fact in past tense and carries the minimum stable data required by consumers.
- Integration events are versioned. Do not change an existing event payload incompatibly; publish a new version.
- Corrections to financial or audit records use reversal or superseding records, not destructive updates.

## 5. Backend rules

These rules become active when `backend/` is created.

- Baseline: Java 21, Spring Boot 4.1, Spring Modulith 2.1, Maven Wrapper.
- Organize code by business module, then by domain/application/infrastructure/interface concerns inside the module.
- Keep public module types at the module root or in explicitly named interfaces; implementation types belong under `internal`.
- Controllers translate transport input to application commands and never contain domain policy.
- Application services orchestrate use cases and transactions; they do not reimplement aggregate rules.
- Repositories return domain aggregates or explicit projections, never controller DTOs.
- The current persistence baseline is Spring JDBC / SQL-first. Use explicit SQL where tenant predicates, ordering, snapshots, event processing, or concurrency semantics must remain directly reviewable.
- JPA is a future optional adapter for an approved simple aggregate, not the default current stack. Its introduction requires the dependency and canonical-write-path controls in ADR-012.
- Flyway migrations are immutable after merge. Correct an applied migration with a new migration.
- Use `application/problem+json` for API failures and stable error codes from `docs/04-contracts/01-error-catalog.md`.
- Validate module structure with Spring Modulith and ArchUnit in every build.
- Generated code must be reproducible and kept outside handwritten source paths. Do not edit generated files.

## 6. Frontend rules

These rules become active when `frontend/` is created.

- Baseline: React 19.2, TypeScript, Vite 8, pnpm.
- Organize features by business module; mirror backend vocabulary without copying backend implementation details.
- TanStack Query owns server state. Do not duplicate API data into a global store.
- Add a client-state store only for a documented cross-page client concern.
- Forms use React Hook Form and Zod. Server validation remains authoritative.
- API types come from the approved OpenAPI contract; do not hand-maintain duplicate interfaces.
- Route and action authorization must be visible in the UI, but backend permission checks remain mandatory.
- Every page must define loading, empty, error, forbidden, and stale/concurrent-update behavior.
- Use semantic HTML, keyboard-accessible controls, and meaningful labels. Critical demo flows target WCAG 2.1 AA.
- Do not hide business errors behind generic notifications. Show the stable error code and a useful recovery action.

## 7. Contract and data rules

- OpenAPI and AsyncAPI contracts are reviewed assets, not generated afterthoughts.
- A contract-breaking change requires an explicit versioning decision.
- Public identifiers are UUIDs; human-facing documents also receive immutable business numbers.
- All business tables include `tenant_id`, audit timestamps, and optimistic version fields where mutable.
- Cross-module references are IDs plus immutable snapshots when historical meaning must survive source changes.
- JSONB is appropriate for event envelopes, policy snapshots, and denormalized evidence. Core searchable business fields remain relational.
- Tenant filters and permission predicates must be applied before pagination.
- Logs, traces, events, and API responses must not expose cost, margin, personal data, tokens, or stack traces to unauthorized users.

## 8. Testing and verification

A task is incomplete until its relevant verification passes.

Minimum expectations:

- pure unit tests for aggregate invariants and policies;
- application-service tests for orchestration and transaction behavior;
- PostgreSQL Testcontainers tests for persistence, migrations, and atomic inventory logic;
- module integration tests for event-driven collaboration;
- architecture tests for boundaries and dependency direction;
- API tests for happy paths, validation, authorization, idempotency, and conflict handling;
- frontend component tests for meaningful interaction, not implementation details;
- Playwright coverage for the designated end-to-end demo path;
- concurrency tests for reservation and duplicate order conversion;
- duplicate-event tests for every external consumer;
- security tests for tenant isolation and field-level exposure.

Never make a build pass by deleting a test, weakening an assertion, ignoring a failure, or reducing a quality threshold without a reviewed explanation.

## 9. Git and review discipline

- Use short-lived branches and Conventional Commits.
- Keep each commit coherent and reviewable; do not mix unrelated formatting, dependencies, generated files, and business changes.
- Do not force-push shared branches, rewrite published history, or delete remote branches without explicit authorization.
- Do not commit secrets, `.env` files, IDE state, local databases, build output, raw private research material, or internal task prompts.
- Update the requirement, contract, ADR, and implementation status in the same pull request when behavior changes.
- A pull request description must include scope, design references, risks, migrations, screenshots when relevant, commands executed, and observed results.

## 10. Stop conditions

Stop and report before proceeding when any of the following is true:

- a required design or contract is missing or contradictory;
- a requested change violates an aggregate invariant or module boundary;
- a migration could destroy or reinterpret existing data;
- a command cannot be made idempotent under the current contract;
- a security decision requires credentials, private data, or unsafe defaults;
- a dependency has unclear licensing or no compatible stable release;
- tests fail for reasons outside the permitted task scope;
- repository ownership, remote target, or GitHub authentication is ambiguous.

A stop report must state the blocking fact, affected files or requirements, up to three viable options, the recommended option, and the ADR or contract change required.

## 11. Completion report

Every implementation task must conclude with:

1. concise summary of delivered behavior;
2. changed files grouped by purpose;
3. requirements and design references satisfied;
4. migrations and compatibility notes;
5. commands actually executed;
6. test and lint results;
7. screenshots or endpoint examples when applicable;
8. security and tenant-isolation checks;
9. remaining limitations and follow-up work;
10. exact commit hashes created.

Do not claim success for commands that were not run or artifacts that were not inspected.
