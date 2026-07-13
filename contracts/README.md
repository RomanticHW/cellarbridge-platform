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
