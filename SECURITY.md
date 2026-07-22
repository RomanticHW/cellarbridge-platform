# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| 1.0.x | Yes |
| < 1.0 | No |

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability that could expose credentials, personal data, authorization bypasses, tenant data, or supply-chain integrity. Use GitHub private vulnerability reporting when enabled for the repository. Include:

- affected commit or release;
- environment and prerequisites;
- reproducible steps;
- expected and observed behavior;
- impact assessment;
- a minimal proof of concept without real data.

The maintainer will acknowledge a valid report, assess severity, prepare a fix, and publish remediation notes appropriate to the risk.

## Security design principles

- OIDC authentication with Authorization Code and PKCE;
- backend authorization on every protected operation;
- tenant isolation before filtering and pagination;
- least-privilege permissions and field-level restrictions;
- immutable audit evidence for sensitive state changes;
- secrets supplied at runtime and never committed;
- dependency, container, and secret scanning in CI;
- synthetic demo data and non-production credentials only;
- secure-by-default local configuration with clearly separated demo shortcuts.

The detailed threat model and controls are documented in `docs/03-architecture/06-security-and-tenancy.md`.
