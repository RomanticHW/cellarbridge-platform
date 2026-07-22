#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-keycloak-outage"
RESULT_DIR="${PERFORMANCE_RESULT_DIR:-${ROOT_DIR}/target/performance-evidence/full}"

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" --file "${COMPOSE_FILE}" "$@"
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

wait_for_health() {
  local service="$1"
  local container_id
  local status="missing"
  container_id="$(compose ps -q "${service}")"
  for _ in {1..90}; do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
    if [[ "${status}" == "healthy" ]]; then
      return 0
    fi
    sleep 2
  done
  compose logs "${service}"
  return 1
}

trap cleanup EXIT
cleanup
compose up --detach --build
wait_for_health postgres
wait_for_health keycloak
wait_for_health backend
wait_for_health frontend

mkdir -p "${RESULT_DIR}"
cd "${ROOT_DIR}/frontend"
PERFORMANCE_COMPOSE_FILE="${COMPOSE_FILE}" \
PERFORMANCE_ENV_FILE="${ENV_FILE}" \
PERFORMANCE_COMPOSE_PROJECT="${PROJECT_NAME}" \
PERFORMANCE_RESULT_DIR="${RESULT_DIR}" \
  corepack pnpm exec playwright test --config playwright.keycloak-outage.config.ts

test -s "${RESULT_DIR}/keycloak-outage.json"
printf 'Keycloak outage evidence: %s\n' "${RESULT_DIR}/keycloak-outage.json"
