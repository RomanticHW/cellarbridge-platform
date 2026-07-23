# MCP smoke and conformance evidence

Date: 2026-07-23

## Verified runtime path

The repository verification path built the Java 21 backend image, started PostgreSQL 18 and
Keycloak 26, obtained short-lived access tokens through Authorization Code + PKCE, and exercised
the authenticated `/mcp` endpoint. Tokens were neither printed nor retained as evidence.

```bash
make mcp-conformance
```

The pre-conformance smoke passed with:

- MCP protocol `2025-11-25`;
- real OIDC Bearer authentication;
- exactly 6 tools, 3 resources and 3 prompts;
- initialize, discovery, tool call and resource read;
- unauthenticated `401`, invalid Origin `403`, tenant-aware identity and Buyer supply denial;
- no access token in backend logs.

## Official runner

Runner: `@modelcontextprotocol/conformance@0.1.16`

| Scenario | Result | Checks |
|---|---:|---:|
| `server-initialize` | PASS | 1/1 |
| `ping` | PASS | 1/1 |
| `tools-list` | PASS | 1/1 |
| `resources-list` | PASS | 1/1 |
| `prompts-list` | PASS | 1/1 |

Aggregate result: 5 scenarios passed, 0 failed and 0 warnings.

The runner used a loopback-only authentication proxy that accepted only the exact `/mcp` path and
GET/POST methods, replaced any inbound Authorization header with the short-lived test token, and
did not log the token. Raw runner output remains a disposable build artifact under
`target/mcp-conformance`; CI uploads it with a 30-day retention period.

## Claim boundary

This evidence covers the server capabilities declared by the current read-only implementation. It
does not claim complete MCP OAuth discovery, dynamic client registration, write tools, model
sampling, RAG, vector storage or autonomous business actions.
