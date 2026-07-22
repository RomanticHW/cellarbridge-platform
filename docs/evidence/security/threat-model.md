# Security threat model

This focused STRIDE review covers the public quotation token boundary, authenticated operations APIs, tenant-owned database rows, local reliable events, observability signals, containers, and the dependency pipeline. It is a demonstration control review, not a production penetration test.

| Threat | Sensitive path | Control | Automated evidence |
| --- | --- | --- | --- |
| Spoofing | OIDC bearer identity and portal capability token | JWT issuer/audience validation; opaque token digest lookup; bounded token syntax; no token logging | `IdentityAccessApiIntegrationTest`, `CustomerQuotationApiIntegrationTest`, `PublicQuotationRateLimitFilterTest` |
| Tampering | State-changing commands, event envelope, audit history | permission and state guards; idempotency hashes; immutable event facts; audit immutability trigger | module integration suites, migration integrity workflow, `AuditReportingIntegrationTest` |
| Repudiation | Acceptance, recovery, payment and reversal | correlation/causation/event IDs; actor-scoped audit entries; ECS JSON logs; W3C trace context | telemetry tests, reporting integration suite, browser correlation check |
| Information disclosure | Cross-tenant detail, safe portal fields, errors and logs | tenant predicates before hydration; field projections; tenant-safe 404; fixed problem details; sanitizer denylist; no request body logging | authorization matrix, API integration suites, `StructuredLogRedactionTest` |
| Denial of service | Public capability-token endpoint and event backlog | application and nginx token-boundary rate limits; bounded pages/batches/retries; backlog and readiness alerts | rate-limit tests, dispatcher tests, Prometheus rules |
| Elevation of privilege | Review/action APIs and runtime containers | permission plus ownership/state checks; independent-review rules; deny-by-default routes; non-root images and read-only filesystems | authorization matrix, security header/CORS tests, container security script |

## Boundary decisions

- CSRF is disabled because the API is stateless and accepts bearer tokens in the `Authorization` header; browser credentials are not cookie-authenticated. The SPA uses OIDC Authorization Code with PKCE and does not place bearer tokens in URLs.
- Capability tokens remain in the portal URL by contract. Frontend and backend access logs suppress token-bearing paths; responses are `no-store`/`no-referrer`; rate limiting exists at both application and reverse-proxy boundaries.
- The repository contains no demo reset endpoint. Therefore there is no production bean to guard or accidentally expose. Any future reset function must be a demo-profile-only bean, require an explicit reset permission, and reject non-demo database targets.
- Actuator health/info are public but reveal no details. Prometheus is authenticated by network placement in the full profile and is not exposed through the frontend proxy.

## Residual risk

See [accepted risks](accepted-risks.md). Production deployment still requires TLS termination, secret management, managed identity administration, WAF/capacity controls, backup/PITR, observability access control, retention policy and an external security assessment.
