#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
FULL_COMPOSE_FILE="${ROOT_DIR}/deploy/compose/full.compose.yaml"
PROJECT_NAME="cellarbridge-full-smoke"

export POSTGRES_DB=cellarbridge
export POSTGRES_USER=cellarbridge
export POSTGRES_PASSWORD=full-smoke-postgres-only
export KEYCLOAK_ADMIN_USERNAME=full-smoke-admin
export KEYCLOAK_ADMIN_PASSWORD=full-smoke-keycloak-only
export GRAFANA_ADMIN_USER=full-smoke-observer
export GRAFANA_ADMIN_PASSWORD=full-smoke-grafana-only

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file /dev/null \
    --file "${CORE_COMPOSE_FILE}" --file "${FULL_COMPOSE_FILE}" "$@"
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

wait_for_health() {
  local service="$1"
  local container_id
  container_id="$(compose ps -q "${service}")"
  for _ in {1..120}; do
    if [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")" == "healthy" ]]; then
      return 0
    fi
    sleep 2
  done
  compose logs "${service}"
  return 1
}

wait_for_http() {
  local name="$1"
  local url="$2"
  local pattern="$3"
  local body
  for _ in {1..60}; do
    body="$(curl --fail --silent --show-error "${url}" 2>/dev/null || true)"
    if grep --quiet --extended-regexp "${pattern}" <<<"${body}"; then
      return 0
    fi
    sleep 2
  done
  printf '%s did not become ready at %s.\n' "${name}" "${url}" >&2
  return 1
}

trap cleanup EXIT
cleanup
compose up --detach --build
for service in postgres keycloak otel-collector backend frontend prometheus tempo grafana; do
  wait_for_health "${service}"
done
wait_for_http backend http://127.0.0.1:8080/actuator/health/readiness '"status":"UP"'
wait_for_http prometheus http://127.0.0.1:9090/-/ready 'Prometheus Server is Ready'
wait_for_http grafana http://127.0.0.1:3000/api/health '"database"[[:space:]]*:[[:space:]]*"ok"'
wait_for_http tempo http://127.0.0.1:3200/ready '^ready$'
printf '%s\n' 'Full smoke passed: core runtime, Collector, Tempo, Prometheus, and Grafana are healthy.'
