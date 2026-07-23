# Security threat model

This focused STRIDE review covers the public quotation token boundary, authenticated operations APIs,
the authenticated read-only MCP boundary, tenant-owned database rows, local reliable events,
observability signals, containers, and the dependency pipeline. It is a demonstration control review,
not a production penetration test.

| Threat | Sensitive path | Control | Automated evidence |
| --- | --- | --- | --- |
| Spoofing | OIDC bearer identity, MCP Agent Host and portal capability token | RFC 9728 discovery; real Code + S256 PKCE; RFC 8707 resource binding; dedicated resource/audience/scope/client validation on every stateless MCP request; no token logging | `IdentityAccessApiIntegrationTest`, `OperationsMcpApiIntegrationTest`, `scripts/mcp_smoke.sh`, `CustomerQuotationApiIntegrationTest` |
| Tampering | State-changing commands, event envelope, audit history | permission and state guards; idempotency hashes; immutable event facts; audit immutability trigger | module integration suites, migration integrity workflow, `AuditReportingIntegrationTest` |
| Repudiation | Acceptance, recovery, payment and reversal | correlation/causation/event IDs; actor-scoped audit entries; ECS JSON logs; W3C trace context | telemetry tests, reporting integration suite, browser correlation check |
| Information disclosure | Cross-tenant detail, MCP resources/tools, safe portal fields, errors and logs | tenant predicates before hydration; field projections; MCP tenant/actor not client-settable; tenant-safe not-found envelope; sanitizer denylist; no request body logging | authorization matrix, `OperationsMcpApiIntegrationTest`, API integration suites, `StructuredLogRedactionTest` |
| Denial of service | MCP query surface, public capability-token endpoint and event backlog | bounded input/page/body, per-client rate and bulkhead, global MCP bulkhead constrained by the shared Hikari pool minus configured non-MCP headroom, request/SQL timeout; bounded batches/retries | MCP production-security smoke, low-cardinality metrics/health assertions, dispatcher tests |
| Elevation of privilege | MCP discovery/read operations, review/action APIs and runtime containers | six allow-listed read-only tools; existing permission plus ownership/state checks; strict Origin policy; deny-by-default routes; non-root images and read-only filesystems | MCP role/tenant tests, authorization matrix, security header/CORS tests, container security script |

## Boundary decisions

- CSRF is disabled because the API is stateless and accepts bearer tokens in the `Authorization` header; browser credentials are not cookie-authenticated. The SPA uses OIDC Authorization Code with PKCE and does not place bearer tokens in URLs.
- Capability tokens remain in the portal URL by contract. Frontend and backend access logs suppress token-bearing paths; responses are `no-store`/`no-referrer`; rate limiting exists at both application and reverse-proxy boundaries.
- The repository contains no demo reset endpoint. Therefore there is no production bean to guard or accidentally expose. Any future reset function must be a demo-profile-only bean, require an explicit reset permission, and reject non-demo database targets.
- Actuator health/info are public but reveal no details. Prometheus is authenticated by network placement in the full profile and is not exposed through the frontend proxy.
- MCP publishes RFC 9728 Protected Resource Metadata and uses a dedicated resource/audience/scope/
  client. A repository-owned Keycloak provider supplies strict RFC 8707 binding because upstream 26.7
  does not; dynamic client registration, token exchange and credential forwarding remain unsupported.
  Agent Hosts and connected models remain outside the CellarBridge trust boundary.
- `/mcp` exposes only six read-only tools, three resources and three prompts. There is no approval,
  inventory, fulfillment, exception or settlement write tool, and no model-provider, RAG or vector-store
  runtime.

## Residual risk

See [accepted risks](accepted-risks.md). Production deployment still requires TLS termination, secret management, managed identity administration, WAF/capacity controls, backup/PITR, observability access control, retention policy and an external security assessment.
