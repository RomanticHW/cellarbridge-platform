# Contracts

This directory contains the machine-readable boundary of CellarBridge.

- `openapi/cellarbridge-api.yaml` defines the public HTTP API planned for the vertical business slices.
- `asyncapi/cellarbridge-events.yaml` defines versioned integration events for the full messaging profile.
- `schemas/events/` contains JSON Schema 2020-12 definitions for event envelopes and payloads.
- `examples/events/` contains synthetic examples that are validated against the schemas.

## Contract policy

1. Contracts are reviewed before their implementation is merged.
2. A breaking API or event change requires an explicit versioning decision.
3. Generated Java and TypeScript artifacts are reproducible build output and must not be edited by hand.
4. Public examples must remain synthetic and must not contain credentials, personal data, customer data, real prices, or real stock information.
5. Event delivery is at-least-once; `id` is the deduplication key and consumers must be idempotent.

Run `make validate-contracts` from the repository root to validate YAML, JSON, references, and event examples.

## Version decisions

### HTTP API 1.4.0 — trade-order response boundary

Task 07 advances the OpenAPI contract from 1.3.0 to 1.4.0. The order list/detail shapes
were approved design placeholders and are replaced with implemented, closed response schemas:

- internal and partner-scoped Buyer endpoints now use separate response types;
- order lines use an immutable `OrderLineSnapshot` instead of the live quotation `PriceLine`;
- customer, route, address, process projection and timeline objects are explicitly typed;
- `/me` returns a required nullable `partnerId` resolved from the identity mapping; and
- the public quotation projection may expose eventual order-conversion state and its safe order link.

This is an explicit breaking response-refinement decision for pre-implementation order clients.
It does not change module boundaries, event delivery semantics or the approved architecture.
`TradeOrderCreatedV1` remains v1: Task 07 adds only optional frozen-snapshot properties, so
existing v1 producers and consumers remain valid. Technical event identifiers remain persisted
for audit and operations, but the ordinary internal order response does not expose them.
