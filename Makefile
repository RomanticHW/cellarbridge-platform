SHELL := /bin/bash
PYTHON ?= python3
PNPM ?= corepack pnpm
COMPOSE_FILE := deploy/compose/core.compose.yaml
FULL_COMPOSE_FILE := deploy/compose/full.compose.yaml
ENV_FILE ?= $(if $(wildcard .env),.env,.env.example)
FULL_ENV_FILE ?= .env
DEMO_PROFILE ?= core

.PHONY: help validate validate-docs validate-contracts validate-public validate-backend \
	validate-frontend validate-compose validate-full-compose test test-backend test-frontend test-migration-history \
	dev-core stop-core dev-full stop-full smoke-core verify-container-security \
	demo demo-reset stop-demo demo-e2e smoke-full \
	identity-e2e partner-e2e catalog-e2e quotation-e2e acceptance-e2e order-e2e fulfillment-e2e exception-e2e settlement-e2e reporting-e2e \
	catalog-benchmark performance-smoke performance-full generate-api-client

help:
	@printf '%s\n' \
	  'CellarBridge workspace commands:' \
	  '  make validate            Run all repository checks' \
	  '  make test                Run backend and frontend tests' \
	  '  make dev-core            Start PostgreSQL, Keycloak, backend, and frontend' \
	  '  make stop-core           Stop the core development profile' \
	  '  make dev-full            Start core plus the local observability profile using .env' \
	  '  make stop-full           Stop the complete local profile' \
	  '  make smoke-core          Build, verify, and clean an isolated core profile' \
	  '  make smoke-full          Build and verify an isolated observable full profile' \
	  '  make demo                Start the deterministic core demo (DEMO_PROFILE=full for full)' \
	  '  make demo-reset          Destroy only synthetic demo volumes and start fresh' \
	  '  make stop-demo           Stop the demo profile without deleting its data' \
	  '  make demo-e2e            Run the complete reviewer journey and capture evidence' \
	  '  make identity-e2e        Verify real OIDC login and two-tenant isolation' \
	  '  make partner-e2e         Verify partner onboarding and independent review' \
	  '  make catalog-e2e         Verify catalog search and local quote selection' \
	  '  make quotation-e2e       Verify quotation routing, approval, issue, and preview' \
	  '  make acceptance-e2e      Verify customer acceptance idempotency and refresh safety' \
	  '  make order-e2e           Verify order conversion, Reservation operations, and Buyer-safe access' \
	  '  make fulfillment-e2e     Verify route plans, dependency actions, milestones, and Buyer-safe access' \
	  '  make exception-e2e       Verify failure, Exception recovery, source state, and reviewed closure' \
	  '  make settlement-e2e      Verify fulfillment-triggered receivables, payments, and reversal' \
	  '  make reporting-e2e       Verify projected work, dashboard, audit, and timeline views' \
	  '  make catalog-benchmark   Seed and benchmark PostgreSQL catalog search' \
	  '  make performance-smoke   Run the correctness-backed 10-minute evidence profile' \
	  '  make performance-full    Run the 30-minute profile including identity outage' \
	  '  make generate-api-client Regenerate TypeScript API types from OpenAPI'

validate: validate-docs validate-contracts validate-public validate-backend validate-frontend validate-compose validate-full-compose

validate-docs:
	$(PYTHON) scripts/validate_repository.py --scope docs

validate-contracts:
	$(PYTHON) scripts/validate_repository.py --scope contracts

validate-public:
	$(PYTHON) scripts/validate_repository.py --scope public
	$(PYTHON) scripts/generate_publication_inventory.py

validate-backend:
	./mvnw -q -pl backend -am -DskipTests compile spotless:check

validate-frontend: generate-api-client
	cd frontend && $(PNPM) typecheck && $(PNPM) lint && $(PNPM) format:check
	git diff --exit-code -- frontend/src/api/generated/schema.d.ts

validate-compose:
	docker compose --env-file $(ENV_FILE) --file $(COMPOSE_FILE) config --quiet

validate-full-compose:
	POSTGRES_DB=cellarbridge POSTGRES_USER=cellarbridge POSTGRES_PASSWORD=validation-only \
	KEYCLOAK_ADMIN_USERNAME=validation KEYCLOAK_ADMIN_PASSWORD=validation-only \
	GRAFANA_ADMIN_USER=validation GRAFANA_ADMIN_PASSWORD=validation-only \
	docker compose --env-file /dev/null --file $(COMPOSE_FILE) --file $(FULL_COMPOSE_FILE) config --quiet

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

dev-full:
	test -f $(FULL_ENV_FILE)
	docker compose --env-file $(FULL_ENV_FILE) --file $(COMPOSE_FILE) --file $(FULL_COMPOSE_FILE) up --detach --build

stop-full:
	docker compose --env-file $(FULL_ENV_FILE) --file $(COMPOSE_FILE) --file $(FULL_COMPOSE_FILE) down --remove-orphans

demo:
	DEMO_PROFILE=$(DEMO_PROFILE) ./scripts/demo.sh

demo-reset:
	DEMO_PROFILE=$(DEMO_PROFILE) ./scripts/demo_reset.sh

stop-demo:
	docker compose --project-name cellarbridge-demo --env-file $(ENV_FILE) --file $(COMPOSE_FILE) down --remove-orphans

demo-e2e:
	./scripts/demo_e2e.sh

verify-container-security:
	./scripts/verify_container_security.sh

smoke-core:
	./scripts/smoke_core.sh

smoke-full:
	./scripts/smoke_full.sh

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

fulfillment-e2e:
	./scripts/fulfillment_orchestration_e2e.sh

exception-e2e:
	./scripts/exception_center_e2e.sh

settlement-e2e:
	./scripts/settlement_e2e.sh

reporting-e2e:
	./scripts/audit_reporting_e2e.sh

catalog-benchmark:
	./scripts/catalog_search_benchmark.sh

performance-smoke:
	./scripts/performance_evidence.sh smoke

performance-full:
	./scripts/performance_evidence.sh full

generate-api-client:
	cd frontend && $(PNPM) generate:api
