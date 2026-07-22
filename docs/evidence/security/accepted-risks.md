# Accepted demonstration risks

| Risk | Current boundary | Production action |
| --- | --- | --- |
| Single-node PostgreSQL, Tempo and Prometheus | Reproducible local demonstration only | Managed HA storage, backup/PITR, retention and capacity tests |
| 100% trace sampling default | Small synthetic workload; no real personal or commercial data | Tail/head sampling policy and cost budget |
| Local `.env` secrets | `.env` is ignored; committed file contains replaceable demonstration placeholders only | Secret manager and short-lived credentials |
| Portal token in URL | Required by the public portal contract; logging disabled and referrer/cache restricted | Token rotation, expiry monitoring and edge WAF rules |
| No dedicated WAF or distributed limiter | Application and single-node nginx rate limits protect the demo | Shared limiter and abuse telemetry at the production edge |
| Upstream observability container licenses | Grafana and Tempo are unmodified, separately running AGPL images | Preserve notices/source links and re-review before distribution or modification |
