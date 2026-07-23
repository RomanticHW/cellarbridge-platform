# Accepted demonstration risks

| Risk | Current boundary | Production action |
| --- | --- | --- |
| Single-node PostgreSQL, Tempo and Prometheus | Reproducible local demonstration only | Managed HA storage, backup/PITR, retention and capacity tests |
| 100% trace sampling default | Small synthetic workload; no real personal or commercial data | Tail/head sampling policy and cost budget |
| Local `.env` secrets | `.env` is ignored; committed file contains replaceable demonstration placeholders only | Secret manager and short-lived credentials |
| Portal token in URL | Required by the public portal contract; logging disabled and referrer/cache restricted | Token rotation, expiry monitoring and edge WAF rules |
| No dedicated WAF or distributed limiter | Application and single-node nginx rate limits protect the demo | Shared limiter and abuse telemetry at the production edge |
| Upstream observability container licenses | Grafana and Tempo are unmodified, separately running AGPL images | Preserve notices/source links and re-review before distribution or modification |
| Repository-owned Keycloak RFC 8707 provider | Keycloak 26.7 lacks the required native strict profile; the versioned provider is default-off, explicitly enabled in Compose and backed by real PKCE/resource tests | Revalidate the SPI on every Keycloak upgrade; close MCP before provider rollback |
| No MCP dynamic client registration or token exchange | RFC 9728 discovery and a dedicated pre-registered public client are supported | Govern redirect registration and client lifecycle operationally; do not add a generic OAuth proxy |
| Agent Host and model are outside the trust boundary | CellarBridge validates every request and returns only role/tenant-scoped data, but cannot govern downstream prompt retention | Approved hosts, managed token storage, DLP, retention policy and human approval rules |
| MCP shares the application process and Hikari pool | A startup invariant caps MCP concurrency below the pool by configured non-MCP headroom; the remaining connections, JVM, CPU, heap and threads are still shared | Add edge/shared limiting and revisit pool/process isolation from production load evidence |
