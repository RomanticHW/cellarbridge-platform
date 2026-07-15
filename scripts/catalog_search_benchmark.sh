#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-catalog-benchmark"
RESULT_DIR="${ROOT_DIR}/target/catalog-search-benchmark"
RUNS="${CATALOG_BENCHMARK_RUNS:-30}"

if ! command -v jq >/dev/null 2>&1; then
  printf 'jq is required to read PostgreSQL JSON plans.\n' >&2
  exit 1
fi
if [[ ! "${RUNS}" =~ ^[1-9][0-9]*$ ]]; then
  printf 'CATALOG_BENCHMARK_RUNS must be a positive integer.\n' >&2
  exit 1
fi

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" --file "${COMPOSE_FILE}" "$@"
}

psql_file() {
  compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --set ON_ERROR_STOP=on --file - < "$1"
}

explain_json() {
  compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --no-align --tuples-only --quiet --file - < "${ROOT_DIR}/scripts/sql/catalog_search_benchmark_query.sql"
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

trap cleanup EXIT
cleanup
compose up --detach postgres

container_id="$(compose ps -q postgres)"
for _ in {1..60}; do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
  if [[ "${status}" == "healthy" ]]; then
    break
  fi
  sleep 1
done
if [[ "${status}" != "healthy" ]]; then
  compose logs postgres
  exit 1
fi

psql_file "${ROOT_DIR}/backend/src/main/resources/db/migration/V4__catalog_products_and_search_projection.sql"
psql_file "${ROOT_DIR}/backend/src/main/resources/db/migration/V5__inventory_supply_model.sql"
psql_file "${ROOT_DIR}/backend/src/main/resources/db/migration/V10__inventory_quantity_unit_and_warehouse_priority.sql"
psql_file "${ROOT_DIR}/backend/src/main/resources/db/migration/V11__catalog_supply_projection_quantity_unit.sql"
psql_file "${ROOT_DIR}/scripts/sql/catalog_search_benchmark_seed.sql"

mkdir -p "${RESULT_DIR}"
unit_plan_file="${RESULT_DIR}/catalog-unit-filter-plan.json"
compose exec -T postgres psql --username cellarbridge --dbname cellarbridge \
  --no-align --tuples-only --quiet --file - \
  < "${ROOT_DIR}/scripts/sql/catalog_unit_filter_evidence.sql" > "${unit_plan_file}"
if ! jq -e \
  '.. | objects | select(."Index Name"? == "ix_catalog_supply_projection_unit_filter") | select(."Index Cond" | contains("quantity_unit"))' \
  "${unit_plan_file}" >/dev/null; then
  printf 'Unit filter plan did not use the unit-aware projection index.\n' >&2
  exit 1
fi
times_file="${RESULT_DIR}/execution-times-ms.txt"
: > "${times_file}"

for _ in {1..5}; do
  explain_json >/dev/null
done

for run in $(seq 1 "${RUNS}"); do
  plan="$(explain_json)"
  if [[ "${run}" == "1" ]]; then
    printf '%s\n' "${plan}" > "${RESULT_DIR}/catalog-search-plan.json"
  fi
  printf '%s\n' "${plan}" | jq -er '.[0]."Execution Time"' >> "${times_file}"
done

sort -n "${times_file}" > "${times_file}.sorted"
mv "${times_file}.sorted" "${times_file}"
p50="$(awk '{ values[NR] = $1 } END { idx = int(NR * 0.50 + 0.999999); print values[idx] }' "${times_file}")"
p95="$(awk '{ values[NR] = $1 } END { idx = int(NR * 0.95 + 0.999999); print values[idx] }' "${times_file}")"
counts="$(compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --no-align --tuples-only --command "SELECT (SELECT count(*) FROM catalog.sku), (SELECT count(*) FROM catalog.sku_supply_projection), (SELECT count(*) FROM inventory.inventory_lot);")"
dual_unit_counts="$(compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --no-align --tuples-only --command "SELECT (SELECT count(*) FROM (SELECT tenant_id, sku_id, supply_pool_id FROM catalog.sku_supply_projection GROUP BY 1, 2, 3 HAVING count(DISTINCT quantity_unit) = 2) groups), (SELECT count(*) FROM (SELECT tenant_id, sku_id, supply_pool_id FROM inventory.inventory_lot GROUP BY 1, 2, 3 HAVING count(DISTINCT quantity_unit) = 2) groups);")"
if [[ "${counts}" != "12000|36000|36000" || "${dual_unit_counts}" != "4000|4000" ]]; then
  printf 'Benchmark fixture cardinality mismatch: rows=%s dual-unit-groups=%s\n' "${counts}" "${dual_unit_counts}" >&2
  exit 1
fi
postgres_version="$(compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --no-align --tuples-only --command 'SHOW server_version;')"

printf 'SKU rows | supply projection rows | inventory lot rows: %s\n' "${counts}"
printf 'Projection dual-unit groups | lot dual-unit groups: %s\n' "${dual_unit_counts}"
printf 'Warm runs: 5; measured runs: %s\n' "${RUNS}"
printf 'Execution time p50: %s ms\n' "${p50}"
printf 'Execution time p95: %s ms\n' "${p95}"
printf 'PostgreSQL: %s\n' "${postgres_version}"
printf 'Docker resources: %s CPUs; %s bytes memory\n' \
  "$(docker info --format '{{.NCPU}}')" \
  "$(docker info --format '{{.MemTotal}}')"
printf 'Host: %s\n' "$(uname -a)"
printf 'Plan: %s\n' "${RESULT_DIR}/catalog-search-plan.json"
printf 'Unit filter plan: %s\n' "${unit_plan_file}"
