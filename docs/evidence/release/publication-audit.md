# v1.0.0 publication audit

Status: release-candidate controls implemented; final tag evidence is produced by the release
workflow.

## Current tree

Every candidate file is listed in `tracked-file-inventory.tsv` and classified as source, test,
contract, doc, generated, release_asset or evidence. The inventory is regenerated from Git rather
than maintained by hand, and public validation rejects drift.

The review found no committed build output, local database, IDE state, `.env`, private key, token,
raw research image, private prompt, handoff, task ledger, command transcript or private repository
address. `.env.example` contains replaceable local demonstration values only and is intentionally
tracked. Generated OpenAPI TypeScript is reproducible and retained because it is the checked client
boundary. Design history and accepted ADRs are retained as legitimate engineering evidence.

## History and identity review

All reachable refs were checked for prohibited filenames and private-control path/content markers.
No private-control material or absolute local path was found. Commit metadata contains normal
contributor-selected Git names and email attribution; no business customer or employee identity is
used as fixture data. No history rewrite is required or authorized.

The repository security workflow performs a full-history gitleaks scan on the final candidate.
Any future confirmed credential exposure requires rotation first and explicit Owner approval before
history rewriting or force push.

## Data, binary and license review

- application and E2E fixtures use fixed synthetic tenants or `example.test` contacts;
- the source tree contains no PNG/JPEG/video/PDF/ZIP release binary; the committed latency chart is
  deterministic SVG text;
- six UI screenshots are generated from the exact tagged synthetic journey, uploaded as CI/release
  artifacts and never capture sign-in credentials or capability-token URLs;
- Apache-2.0 application dependencies and separately running upstream container licenses are
  documented in `NOTICE`; the dependency gate rejects incompatible new application licenses;
- source, runtime image and application SBOMs plus high/critical image scans are attached to the
  release and covered by `SHA256SUMS`.

## Public boundaries

The public repository contains the approved product, code, contracts and reproducible evidence.
Private execution controls, unpublished research, local paths, credentials and conversation records
remain outside it. The public repository is public; the project-control repository is independently
verified private during release closeout.
