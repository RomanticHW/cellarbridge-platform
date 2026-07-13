# Frontend working agreement

This directory contains the single React operations console. Keep feature code under
`src/features/<feature>` and shared application composition under `src/app`.

- Use Node 24 and the committed pnpm lockfile. Run `pnpm install --frozen-lockfile` in CI.
- Run `pnpm generate:api` after an OpenAPI change. Never hand-edit `src/api/generated/`.
- Run `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm test`, and `pnpm build`
  before committing frontend changes.
- Add Playwright coverage for observable user journeys and keep accessibility assertions in
  smoke tests.
- Treat the generated client as the REST boundary. Do not copy server models into handwritten
  TypeScript interfaces.
- Do not add routes or controls for business capabilities until their backend slice is executable.
- Do not place credentials, personal data, or real commercial data in fixtures, logs, or browser
  storage.

These rules strengthen and do not replace the repository root `AGENTS.md`.
