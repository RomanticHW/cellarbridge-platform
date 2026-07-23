# Observability and security runbook

## Local profiles

`make dev-core` remains the fastest business profile and uses documented synthetic local credentials. `make dev-full` requires a local `.env` file and adds the OpenTelemetry Collector, Tempo, Prometheus and Grafana. The full compose file uses required-variable interpolation for database, identity and Grafana administrative credentials; it does not provide production defaults.

The application, frontend and custom Keycloak-provider images declare non-root users. Backend and
frontend services run with read-only root filesystems plus bounded tmpfs mounts. Compose CPU/memory
values are reviewable examples, not measured production requests or limits.

## Diagnostic flow

- Readiness: `/actuator/health/readiness` with details suppressed.
- MCP diagnostic: `/actuator/health/mcp` reports `DISABLED`, `MISCONFIGURED`,
  `AUTHORIZATION_SERVER_UNAVAILABLE`, `RATE_LIMITED`, `BULKHEAD_SATURATED`,
  `DOWNSTREAM_TIMEOUT` or `UP`; it is deliberately not part of main readiness.
- Metrics: Prometheus scrapes backend `/actuator/prometheus` on the private compose network.
- MCP metrics: `cellarbridge.mcp.requests`, `.latency` and `.response.bytes` use only controlled
  protocol/client-class/capability/outcome/rejection values. Never add identity, tenant, UUID,
  cursor, correlation, Origin, clientInfo, arguments or payload as tags/trace attributes.
- Traces: backend OTLP/HTTP → Collector → Tempo; Grafana queries Tempo.
- Logs: ECS JSON on stdout with service/version, trace/span and safe MDC correlation fields.
- Business evidence: audit/timeline APIs remain the authoritative business record. Telemetry is diagnostic and may be sampled or unavailable.

## Security checks

Run `make validate`, `make test`, `make mcp-production-security`,
`make verify-container-security`, frontend production dependency audit and the browser journeys. CI
additionally performs full-history secret scanning, dependency review, image-level CycloneDX
generation and Grype scanning, with machine-readable artifacts retained for 14 days.

The authorization evidence matrix maps list/detail/action samples across tenant, role, ownership, state and field boundaries to executable integration suites. Safe 404 behavior intentionally prevents foreign-tenant existence disclosure. There is no demo reset endpoint in the runtime.

## Production boundary

The local stack does not claim TLS termination, WAF, shared rate limiting, managed secret storage, HA, backup/PITR, trace/metric retention, production sampling, on-call routing or external security certification. Those controls must be designed for the actual environment before deployment.
