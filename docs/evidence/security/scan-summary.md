# Reproducible supply-chain review

The security workflow performs four independent gates: full-history gitleaks scanning, pull-request dependency review with high-severity and incompatible-license rejection, CycloneDX SBOM generation for both runtime images, and Grype image scans that fail on high or critical findings. Machine-readable SBOM and scan reports are uploaded as a 14-day CI artifact; raw local dumps are intentionally not committed.

Reproduce the source and runtime checks with:

```bash
./mvnw --batch-mode --no-transfer-progress -pl backend -am verify
corepack pnpm --dir frontend audit --prod --audit-level high
make verify-container-security
```

The Spring Boot Maven build emits `backend/target/classes/META-INF/sbom/application.cdx.json` using its managed CycloneDX 1.6 plugin. CI additionally creates image-level `backend.cdx.json` and `frontend.cdx.json`, then scans the exact images. Review CI artifacts for the current result; this document does not freeze a time-sensitive vulnerability dump.

License review covers direct build dependencies and container boundary decisions. Runtime libraries introduced by Task 13 are Apache-2.0. Grafana/Tempo remain unmodified upstream runtime containers and are documented separately in `NOTICE`; they are not linked into the CellarBridge application artifact. Dependency Review rejects newly introduced GPL/AGPL application dependencies.

Task 16 adds Spring AI MCP Server `2.0.0` (Apache License 2.0) and MCP Java SDK `2.0.0`
(MIT). It pins NetworkNT JSON Schema Validator `3.0.6` (Apache License 2.0) because MCP Java SDK 2
requires the Jackson 3/dialect API while the Spring Boot BOM otherwise selects an incompatible 1.x
line. The dependency tree and CycloneDX SBOM must show no model-provider SDK, vector database or
RAG integration. The transitive `spring-ai-model` artifact is a provider-neutral API used by the
Spring AI starter; it is not a model provider and no inference call exists in the application.

Protocol evidence uses the pinned official `@modelcontextprotocol/conformance@0.1.16` runner against
MCP specification `2025-11-25`. Because that runner does not accept arbitrary authorization headers,
the repository's loopback-only smoke proxy injects the already-obtained short-lived Bearer Token; it
does not log or persist the token.
