# Backend Engineering Rules

These rules strengthen the repository root `AGENTS.md` for `backend/`.

## Commands

- `../mvnw -pl backend -am verify` runs compilation, tests, architecture verification, formatting, and coverage checks.
- `../mvnw -pl backend spring-boot:run` starts the application when PostgreSQL is available.
- Run all commands with Java 21. Maven Enforcer rejects other Java feature versions.

## Boundaries

- `com.rom.cellarbridge` is the application root and Maven `groupId`.
- Business modules are direct child packages of the application root.
- Module implementation belongs under that module's `internal` package.
- `com.rom.cellarbridge.platform` contains technical support only and must not contain business concepts.
- Do not create `common`, `shared`, or `utils` domain packages.
- A module must not import another module's `internal` classes or access another module's schema.

## Generated code and migrations

- Generated sources must have a repeatable command and remain outside handwritten source paths.
- Never edit a merged Flyway migration; add a new module-owned migration instead.
- Task 01 intentionally contains no business migration or business table.

## Tests

- Keep Spring Modulith and ArchUnit verification in every build.
- Persistence and migration behavior must be tested with the frozen PostgreSQL image through Testcontainers.
- Tests must prove observable behavior and must not be disabled to make a build pass.
