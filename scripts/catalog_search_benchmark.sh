#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-catalog-benchmark"
RESULT_DIR="${PERFORMANCE_RESULT_DIR:-${ROOT_DIR}/target/catalog-search-benchmark}"
RUNS="${CATALOG_BENCHMARK_RUNS:-30}"
WARMUP_RUNS="${CATALOG_BENCHMARK_WARMUP:-5}"
PRODUCTS="${CATALOG_BENCHMARK_PRODUCTS:-4000}"
SEED="${PERFORMANCE_SEED:-140726}"
PROFILE="${PERFORMANCE_PROFILE:-standalone}"

if ! command -v jq >/dev/null 2>&1; then
  printf 'jq is required to read PostgreSQL JSON plans.\n' >&2
  exit 1
fi
for value_name in RUNS WARMUP_RUNS PRODUCTS SEED; do
  value="${!value_name}"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    printf '%s must be a positive integer.\n' "${value_name}" >&2
    exit 1
  fi
done

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" --file "${COMPOSE_FILE}" "$@"
}

psql_file() {
  compose exec -T postgres psql --username cellarbridge --dbname cellarbridge \
    --set ON_ERROR_STOP=on --set product_count="${PRODUCTS}" --file - < "$1"
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

for _ in $(seq 1 "${WARMUP_RUNS}"); do
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
p99="$(awk '{ values[NR] = $1 } END { idx = int(NR * 0.99 + 0.999999); print values[idx] }' "${times_file}")"
total_execution_ms="$(awk '{ total += $1 } END { printf "%.6f", total }' "${times_file}")"
throughput_per_second="$(awk -v runs="${RUNS}" -v elapsed="${total_execution_ms}" 'BEGIN { printf "%.6f", runs * 1000 / elapsed }')"
counts="$(compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --no-align --tuples-only --command "SELECT (SELECT count(*) FROM catalog.sku), (SELECT count(*) FROM catalog.sku_supply_projection), (SELECT count(*) FROM inventory.inventory_lot);")"
dual_unit_counts="$(compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --no-align --tuples-only --command "SELECT (SELECT count(*) FROM (SELECT tenant_id, sku_id, supply_pool_id FROM catalog.sku_supply_projection GROUP BY 1, 2, 3 HAVING count(DISTINCT quantity_unit) = 2) groups), (SELECT count(*) FROM (SELECT tenant_id, sku_id, supply_pool_id FROM inventory.inventory_lot GROUP BY 1, 2, 3 HAVING count(DISTINCT quantity_unit) = 2) groups);")"
expected_counts="$((PRODUCTS * 3))|$((PRODUCTS * 9))|$((PRODUCTS * 9))"
expected_dual_unit_counts="${PRODUCTS}|${PRODUCTS}"
if [[ "${counts}" != "${expected_counts}" || "${dual_unit_counts}" != "${expected_dual_unit_counts}" ]]; then
  printf 'Benchmark fixture cardinality mismatch: rows=%s dual-unit-groups=%s\n' "${counts}" "${dual_unit_counts}" >&2
  exit 1
fi
postgres_version="$(compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --no-align --tuples-only --command 'SHOW server_version;')"
db_config="$(compose exec -T postgres psql --username cellarbridge --dbname cellarbridge --no-align --tuples-only --field-separator '|' --command "SELECT current_setting('max_connections'), current_setting('shared_buffers'), current_setting('work_mem'), current_setting('effective_cache_size'), current_setting('random_page_cost');")"
IFS='|' read -r sku_rows projection_rows lot_rows <<< "${counts}"
IFS='|' read -r projection_dual_unit_groups lot_dual_unit_groups <<< "${dual_unit_counts}"
IFS='|' read -r max_connections shared_buffers work_mem effective_cache_size random_page_cost <<< "${db_config}"
docker_cpus="$(docker info --format '{{.NCPU}}')"
docker_memory_bytes="$(docker info --format '{{.MemTotal}}')"

jq -n \
  --arg profile "${PROFILE}" \
  --argjson seed "${SEED}" \
  --argjson products "${PRODUCTS}" \
  --argjson skuRows "${sku_rows}" \
  --argjson projectionRows "${projection_rows}" \
  --argjson lotRows "${lot_rows}" \
  --argjson projectionDualUnitGroups "${projection_dual_unit_groups}" \
  --argjson lotDualUnitGroups "${lot_dual_unit_groups}" \
  --argjson warmups "${WARMUP_RUNS}" \
  --argjson runs "${RUNS}" \
  --argjson p50Ms "${p50}" \
  --argjson p95Ms "${p95}" \
  --argjson p99Ms "${p99}" \
  --argjson throughputPerSecond "${throughput_per_second}" \
  --arg postgresVersion "${postgres_version}" \
  --arg maxConnections "${max_connections}" \
  --arg sharedBuffers "${shared_buffers}" \
  --arg workMem "${work_mem}" \
  --arg effectiveCacheSize "${effective_cache_size}" \
  --arg randomPageCost "${random_page_cost}" \
  --argjson dockerCpus "${docker_cpus}" \
  --argjson dockerMemoryBytes "${docker_memory_bytes}" \
  '{
    schemaVersion: "cellarbridge.performance-result.v1",
    scenario: "catalog-mixed-search",
    profile: $profile,
    seed: $seed,
    fixture: {
      products: $products,
      skuRows: $skuRows,
      supplyProjectionRows: $projectionRows,
      inventoryLotRows: $lotRows,
      projectionDualUnitGroups: $projectionDualUnitGroups,
      lotDualUnitGroups: $lotDualUnitGroups
    },
    samples: {warmups: $warmups, measured: $runs},
    metrics: {
      p50Ms: $p50Ms,
      p95Ms: $p95Ms,
      p99Ms: $p99Ms,
      throughputPerSecond: $throughputPerSecond,
      errorRate: 0
    },
    invariants: {
      fixtureCardinality: true,
      unitAwareIndexUsed: true,
      allQueriesSucceeded: true
    },
    environment: {
      postgresVersion: $postgresVersion,
      databaseConfig: {
        maxConnections: $maxConnections,
        sharedBuffers: $sharedBuffers,
        workMem: $workMem,
        effectiveCacheSize: $effectiveCacheSize,
        randomPageCost: $randomPageCost
      },
      dockerCpus: $dockerCpus,
      dockerMemoryBytes: $dockerMemoryBytes
    },
    artifacts: {
      searchPlan: "catalog-search-plan.json",
      unitFilterPlan: "catalog-unit-filter-plan.json",
      executionTimes: "execution-times-ms.txt"
    }
  }' > "${RESULT_DIR}/summary.json"

printf 'SKU rows | supply projection rows | inventory lot rows: %s\n' "${counts}"
printf 'Projection dual-unit groups | lot dual-unit groups: %s\n' "${dual_unit_counts}"
printf 'Warm runs: %s; measured runs: %s\n' "${WARMUP_RUNS}" "${RUNS}"
printf 'Execution time p50: %s ms\n' "${p50}"
printf 'Execution time p95: %s ms\n' "${p95}"
printf 'Execution time p99: %s ms\n' "${p99}"
printf 'Throughput: %s queries/s; error rate: 0\n' "${throughput_per_second}"
printf 'PostgreSQL: %s\n' "${postgres_version}"
printf 'PostgreSQL config: max_connections=%s shared_buffers=%s work_mem=%s effective_cache_size=%s random_page_cost=%s\n' \
  "${max_connections}" "${shared_buffers}" "${work_mem}" "${effective_cache_size}" "${random_page_cost}"
printf 'Docker resources: %s CPUs; %s bytes memory\n' \
  "${docker_cpus}" \
  "${docker_memory_bytes}"
printf 'Plan: %s\n' "${RESULT_DIR}/catalog-search-plan.json"
printf 'Unit filter plan: %s\n' "${unit_plan_file}"
printf 'Machine-readable summary: %s\n' "${RESULT_DIR}/summary.json"
