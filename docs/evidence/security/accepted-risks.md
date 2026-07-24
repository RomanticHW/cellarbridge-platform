# Accepted demonstration risks

| Risk | Current boundary | Production action |
| --- | --- | --- |
| Single-node PostgreSQL, Tempo and Prometheus | Reproducible local demonstration only | Managed HA storage, backup/PITR, retention and capacity tests |
| 100% trace sampling default | Small synthetic workload; no real personal or commercial data | Tail/head sampling policy and cost budget |
| Local `.env` secrets | `.env` is ignored; committed file contains replaceable demonstration placeholders only | Secret manager and short-lived credentials |
| Portal token in URL | Required by the public portal contract; logging disabled and referrer/cache restricted | Token rotation, expiry monitoring and edge WAF rules |
| No dedicated WAF or distributed limiter | Application and single-node nginx rate limits protect the demo | Shared limiter and abuse telemetry at the production edge |
| Upstream observability container licenses | Grafana and Tempo are unmodified, separately running AGPL images | Preserve notices/source links and re-review before distribution or modification |
| MCP uses the existing API audience | Suitable for the current first-party demo; no MCP OAuth discovery or dynamic client registration is claimed | Define a dedicated resource indicator/audience and standards-complete authorization metadata before third-party production access |
| Dormant Keycloak private SPI module | The 26.7 resource-binding Provider is compiled and tested but remains default-off, is absent from runtime images and realm configuration, and supplies no RFC 8707 runtime evidence | Revalidate every Keycloak upgrade; activate only after real PKCE/resource/refresh and rollback evidence, or replace it with a stable upstream capability |
| Agent Host and model are outside the trust boundary | CellarBridge validates every request and returns only role/tenant-scoped data, but cannot govern downstream prompt retention | Approved hosts, managed token storage, DLP, retention policy and human approval rules |
| MCP shares the application process | Keeps authorization and business rules single-sourced; transport load shares the backend fault domain | Capacity limits, gateway controls, isolation decision and load evidence before broad production rollout |
