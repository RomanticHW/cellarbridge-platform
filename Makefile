SHELL := /bin/bash
PYTHON ?= python3
PNPM ?= corepack pnpm
COMPOSE_FILE := deploy/compose/core.compose.yaml
ENV_FILE ?= $(if $(wildcard .env),.env,.env.example)

.PHONY: help validate validate-docs validate-contracts validate-public validate-backend \
	validate-frontend validate-compose test test-backend test-frontend dev-core stop-core smoke-core \
	generate-api-client

help:
	@printf '%s\n' \
	  'CellarBridge workspace commands:' \
	  '  make validate            Run all repository checks' \
	  '  make test                Run backend and frontend tests' \
	  '  make dev-core            Start PostgreSQL, Keycloak, backend, and frontend' \
	  '  make stop-core           Stop the core development profile' \
	  '  make smoke-core          Build, verify, and clean an isolated core profile' \
	  '  make generate-api-client Regenerate TypeScript API types from OpenAPI'

validate: validate-docs validate-contracts validate-public validate-backend validate-frontend validate-compose

validate-docs:
	$(PYTHON) scripts/validate_repository.py --scope docs

validate-contracts:
	$(PYTHON) scripts/validate_repository.py --scope contracts

validate-public:
	$(PYTHON) scripts/validate_repository.py --scope public

validate-backend:
	./mvnw -q -pl backend -am -DskipTests compile spotless:check

validate-frontend: generate-api-client
	cd frontend && $(PNPM) typecheck && $(PNPM) lint && $(PNPM) format:check
	git diff --exit-code -- frontend/src/api/generated/schema.d.ts

validate-compose:
	docker compose --env-file $(ENV_FILE) --file $(COMPOSE_FILE) config --quiet

test: test-backend test-frontend

test-backend:
	./mvnw -pl backend -am verify

test-frontend:
	cd frontend && $(PNPM) test:coverage && $(PNPM) build

dev-core:
	docker compose --env-file $(ENV_FILE) --file $(COMPOSE_FILE) up --detach --build

stop-core:
	docker compose --env-file $(ENV_FILE) --file $(COMPOSE_FILE) down --remove-orphans

smoke-core:
	./scripts/smoke_core.sh

generate-api-client:
	cd frontend && $(PNPM) generate:api
