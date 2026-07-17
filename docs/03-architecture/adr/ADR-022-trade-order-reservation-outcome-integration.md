# ADR-022: Trade Order reservation outcome integration

- Status: Accepted
- Date: 2026-07-17
- Owners: Trade Order, Inventory
- Scope: Task 08 B2

## Context

Inventory reliably publishes `InventoryReservationConfirmedV1` or
`InventoryReservationFailedV1` after the atomic reservation attempt completes. Trade Order must
consume those facts at least once, verify that they belong to the exact order request and frozen
Supply Decision, and move `PENDING_RESERVATION` to one terminal reservation state.

The integration must also handle different event IDs carrying the same business outcome. The
platform Inbox alone deduplicates an event ID, so it cannot prevent a later conflicting success or
failure from overwriting the first terminal result. Task 08 migration ownership belongs to
Inventory; B2 must not add an unapproved Trade Order schema migration.

## Decision

Trade Order consumes both Inventory outcome event types through transactional local-event
handlers and applies these rules:

1. Resolve and lock the order by `(tenant_id, order_id)` before inspecting or changing its state.
2. Validate the versioned envelope, Inventory producer and reservation subject, order identity,
   correlation/causation chain, and monotonic outcome time.
3. Reconstruct Inventory Reservation Request Hash V1 from the immutable Trade Order snapshot. The
   canonical hash implementation is a published Inventory module API and B1 delegates to the same
   implementation, avoiding parallel algorithms.
4. Current orders require the exact frozen Supply Decision hash. Legacy orders accept only the
   explicit `SUPPLY_DECISION_MISSING` failure with no decision hash.
5. Validate successful allocations against every order line and require their sums to equal the
   requested quantities. Validate failures against a controlled reason-code set and consistent
   line/shortage summaries.
6. Persist the first result by updating the existing Trade Order status and appending one
   Trade-Order-owned internal timeline entry. The entry stores the source event, controlled reason
   code and a canonical semantic evidence hash, not the raw payload, Pool, Lot or decision details.
7. Replays with equivalent semantic evidence are no-ops. Any different outcome for the same order,
   including success after failure or failure after success, fails closed and cannot overwrite the
   first terminal state.

No database migration is required: `trade_order.trade_order` already owns the lifecycle status and
optimistic version, while `trade_order.timeline_entry` is append-only and already enforces unique
event identity. The row lock serializes different event IDs for one order.

## Consequences

- Trade Order reaches `RESERVED` or `RESERVATION_FAILED` without reading Inventory-owned tables.
- Same-event and same-business-outcome replay are safe, including concurrent delivery.
- Buyer and Customer projections expose only the existing safe order status; internal outcome
  evidence remains excluded by timeline visibility and existing DTO allow-lists.
- A retry command may later move `RESERVATION_FAILED` back to `PENDING_RESERVATION`, but its request
  identity and attempt semantics require a separate authorized design.
- Release/consume operations and Reservation API/UI remain outside B2.

## Rejected alternatives

- **Use only the Consumer Inbox.** This does not detect conflicting outcomes with different event
  IDs.
- **Read the canonical Reservation from Inventory tables.** This violates module ownership and
  makes the event non-self-contained.
- **Add Trade Order reservation columns in Task 08.** Existing status and timeline structures are
  sufficient, and Task 08 has no Trade Order migration ownership authorization.
