# ADR-023: Inventory Reservation Operations

- Status: Accepted
- Date: 2026-07-17
- Owner: Inventory
- Scope: Task 08 Stack C1

## Context

V15 persists confirmed Reservations, Allocations and append-only Movements, and already provides
single-statement Lot release and consume primitives. It does not persist the command-level identity
required to distinguish a safe replay from reuse of the same idempotency key with a different
payload. Movement business keys cannot replace that boundary because one command may cover several
Allocations and must succeed or fail as a unit.

The accepted Reservation state model in ADR-019 exposes `PENDING`, `CONFIRMED`, `FAILED`,
`RELEASED` and `CONSUMED`. An operation may act on only part of the remaining quantity, but P1 does
not mix release and consume within one Reservation.

## Decision

1. Add Inventory-owned V17 with a command record keyed by
   `(tenant_id, reservation_id, operation_type, business_key_hash)`. It stores the canonical request
   hash and a controlled result snapshot. The request hash includes the actor scope, and the raw
   idempotency key is never stored.
2. Add an append-only operation audit fact containing actor, action, controlled outcome/reason,
   key digest, previous/new Reservation state and occurrence time. Both tables reference only
   Inventory-owned facts and add no cross-schema foreign key. A composite foreign key prevents an
   audit fact from disagreeing with its command's Reservation, action, actor or key digest.
3. A command locks the Reservation row, then locks requested Allocations in the established
   order-line/Lot/allocation order. Every Lot and Allocation mutation occurs in one local
   PostgreSQL transaction. Any failed conditional update rolls all Lot, Allocation and Movement
   mutations back to the savepoint; the outer transaction then stores the stable rejection.
4. Release decreases Allocation remaining and Lot reserved quantities by the same amount. Consume
   additionally decreases Lot on-hand by that amount. Each successful item appends one immutable
   Movement whose business key derives from the command and Allocation identities.
5. While any quantity remains reserved, aggregate status stays `CONFIRMED`. A Reservation may use
   only one operation type: when all remaining quantity reaches zero it becomes `RELEASED` or
   `CONSUMED`. Mixing release and consume fails closed.
6. Same key and same canonical request returns the stored original result. Same key and different
   request fails before any quantity mutation. Business rejection is itself a stable stored result;
   unexpected infrastructure failure rolls back the command record and all side effects.
7. Internal reads require `inventory:read`. Exact Pool, Warehouse and Lot evidence additionally
   requires `inventory:read-exact`. Release and consume require `inventory:reserve`; Buyer and other
   external projections receive no Reservation endpoint or operation control.
8. Metrics use only controlled action, outcome and reason-code tags. Tenant, order, Reservation,
   Allocation, SKU, Pool and Lot identifiers are forbidden metric labels.

## Consequences

- Partial commands remain observable through Allocation balances without inventing an additional
  aggregate state or changing the accepted five-state contract.
- V17 becomes the final database arbitration boundary for HTTP idempotency and operation audit.
- C2 may expose a replay header and the original controlled result without retaining request secrets.
- Cancellation orchestration and Fulfillment-triggered commands remain downstream work; Stack C
  exposes the authorized Inventory operation surface only.
