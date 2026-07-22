# Inventory Reservation Request Conflict Evidence

## 1. Delivered boundary

This review slice adds immutable evidence for a same-order/different-request-hash conflict without changing the canonical Reservation result.

Delivered:

- `ReservationRequestConflict` domain invariants;
- tenant-scoped append-only Repository;
- Inventory-owned V16 table, checks, unique keys and same-schema foreign keys;
- fresh and historical migration proof;
- replay, tenant isolation, fail-closed and controlled concurrent-insert proof.

Not delivered:

- `TradeOrderCreatedV1` consumption or Current/Legacy/INVALID classification;
- Reservation allocation workflow or outcome publication;
- Trade Order result handling;
- REST/OpenAPI, React, release or consume operations.

Inventory Reservation remains `Designed / In progress`.

## 2. Evidence model

One canonical Reservation remains unique for each tenant/order. A conflict fact records:

- canonical Reservation ID, order ID and existing request hash;
- incoming conflicting request hash;
- first source event, correlation and observed time;
- fixed failure code `RESERVATION_REQUEST_CONFLICT`.

The two hashes must be distinct lowercase SHA-256 values. The Repository exposes no update or delete path. Recording a conflict does not append a canonical Attempt and does not create Shortage, Allocation, Movement or Lot quantity changes.

The business key is `(tenant_id, order_id, conflicting_request_hash)`. A replay returns the first stored observation even if the replay has a new technical event/correlation identity. A replay that claims a different canonical Reservation or existing hash fails closed.

## 3. Migration

- File: `V16__inventory_reservation_request_conflict_evidence.sql`
- SHA-256: `4483f5cc48f9cd17df4771986dc44e08f6d5ddea13a17eb8b594eb0e4a60d3e5`
- Owner: `inventory`
- Modified schema: `inventory` only
- Historical migrations: V2 through V15 unchanged
- Cross-schema foreign keys: none

V16 binds both Reservation identity/hash and order/hash through existing Inventory-owned unique keys. It rejects equal hashes, malformed hashes and any failure-code alias.

## 4. PostgreSQL proof

Runtime: PostgreSQL 18.4 through Testcontainers.

Covered scenarios:

1. fresh V2-to-V16 migration;
2. V15-to-V16 upgrade without rewriting canonical Reservation evidence;
3. domain construction and invalid hash/failure-code rejection;
4. Repository round trip and first-observation replay;
5. explicit tenant isolation;
6. canonical-evidence mismatch fails closed;
7. 12 concurrent observers start through a controlled barrier and persist exactly one conflict fact;
8. canonical Reservation status and child facts remain unchanged.

The concurrency test uses no arbitrary sleep and no Redis, JVM or distributed lock.

## 5. Validation evidence

- Java: OpenJDK 21.0.11.
- Backend: clean verify passed 227 tests with zero failures, errors or skips; Spotless, Modulith, ArchUnit and JaCoCo passed.
- Focused evidence: 9 domain/migration/Repository/concurrency tests passed.
- Frontend regression: Node 24.18.0, pnpm 11.12.0; generated API, typecheck, lint, format, 15 test files / 61 tests, coverage and build passed.
- Frontend coverage: 78.66% statements, 67.23% branches, 74.82% functions and 80.02% lines.
- Repository validation: docs, contracts, public boundary, Backend compile/Spotless, Frontend checks and Compose config passed.
- Migration history: 4/4 validator tests and base-to-head migration history passed.
- Static tooling: actionlint, shellcheck and shell syntax checks passed.
- Existing E2E: Catalog 1/1, Quotation 2/2, Acceptance 1/1 and Order 1/1 passed in Chromium.

## 6. Scope and review boundary

- Changed files: 15 after this evidence record.
- Total churn: below the unchanged 2,200 limit.
- No API, event schema, React production code, Trade Order logic or Inventory Lot update changed.
- No V2-through-V15 file changed.
- No external lock or CASE/BOTTLE conversion was introduced.

This slice supplies only the request-conflict persistence prerequisite. The later atomic-execution slice must consume it within the outer event transaction and reliably publish the controlled failure outcome.
