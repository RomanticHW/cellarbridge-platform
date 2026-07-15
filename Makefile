SHELL := /bin/bash
PYTHON ?= python3
PNPM ?= corepack pnpm
COMPOSE_FILE := deploy/compose/core.compose.yaml
ENV_FILE ?= $(if $(wildcard .env),.env,.env.example)

.PHONY: help validate validate-docs validate-contracts validate-public validate-backend \
	validate-frontend validate-compose test test-backend test-frontend test-migration-history \
	dev-core stop-core smoke-core \
	identity-e2e partner-e2e catalog-e2e quotation-e2e acceptance-e2e order-e2e \
	catalog-benchmark generate-api-client

help:
	@printf '%s\n' \
	  'CellarBridge workspace commands:' \
	  '  make validate            Run all repository checks' \
	  '  make test                Run backend and frontend tests' \
	  '  make dev-core            Start PostgreSQL, Keycloak, backend, and frontend' \
	  '  make stop-core           Stop the core development profile' \
	  '  make smoke-core          Build, verify, and clean an isolated core profile' \
	  '  make identity-e2e        Verify real OIDC login and two-tenant isolation' \
	  '  make partner-e2e         Verify partner onboarding and independent review' \
	  '  make catalog-e2e         Verify catalog search and local quote selection' \
	  '  make quotation-e2e       Verify quotation routing, approval, issue, and preview' \
	  '  make acceptance-e2e      Verify customer acceptance idempotency and refresh safety' \
	  '  make order-e2e           Verify quote-to-order conversion and Buyer-safe access' \
	  '  make catalog-benchmark   Seed and benchmark PostgreSQL catalog search' \
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

test-migration-history:
	PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m unittest -v scripts/test_validate_migration_history.py

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

identity-e2e:
	./scripts/identity_access_e2e.sh

partner-e2e:
	./scripts/partner_onboarding_e2e.sh

catalog-e2e:
	./scripts/catalog_supply_e2e.sh

quotation-e2e:
	./scripts/quotation_trade_planning_e2e.sh

acceptance-e2e:
	./scripts/customer_quotation_acceptance_e2e.sh

order-e2e:
	./scripts/trade_order_conversion_e2e.sh

catalog-benchmark:
	./scripts/catalog_search_benchmark.sh

generate-api-client:
	cd frontend && $(PNPM) generate:api
