# Contributing

CellarBridge uses a design-first, trunk-oriented workflow. Contributions are welcome when they preserve the product boundary, domain language, and reviewability of the repository.

## Before opening a change

1. Read `AGENTS.md`.
2. Find the relevant requirement, aggregate, contract, and ADR.
3. Confirm the work is present in the implementation roadmap or open a design issue first.
4. Keep public data synthetic and free of third-party confidential material.

## Branches and commits

Use short-lived branches such as:

- `feat/quotation-approval`
- `fix/inventory-reservation-race`
- `docs/adr-route-policy-versioning`
- `chore/upgrade-postgresql`

Commit messages follow Conventional Commits. Examples:

- `feat(quotation): add margin approval policy`
- `fix(inventory): make lot reservation atomic`
- `test(order): cover duplicate conversion requests`
- `docs(architecture): record search strategy decision`

A commit should contain one coherent reason for change. Avoid broad reformatting in a business change.

## Pull request requirements

A pull request must include:

- the business or technical problem;
- requirement and design references;
- behavior before and after;
- affected modules and contracts;
- data migration and rollback notes;
- security and tenancy impact;
- commands executed and results;
- screenshots for visible UI changes;
- explicit remaining limitations.

The pull request template contains the required checklist.

## Design changes

A change requires an ADR before implementation when it alters:

- architecture style or module boundaries;
- data ownership or transaction boundaries;
- public API/event compatibility;
- authentication or authorization model;
- delivery semantics, idempotency, or inventory concurrency;
- production infrastructure or major dependency families.

Do not bury an architectural decision inside an implementation pull request.

## Quality expectations

The repository uses architecture tests, unit tests, integration tests, contract validation, and end-to-end tests as complementary evidence. Coverage is a diagnostic, not a substitute for meaningful assertions. Core invariants and concurrency behavior require explicit tests even when aggregate coverage is already high.

## Public-data policy

Do not submit:

- credentials or access tokens;
- raw source screenshots not approved for redistribution;
- real company customer, employee, product-price, inventory, or order data;
- internal prompts, task ledgers, chat transcripts, or local handoff notes;
- copied proprietary code, designs, logos, or brand assets.

See `docs/05-delivery/09-publication-and-repository-hygiene.md` for the complete policy.
