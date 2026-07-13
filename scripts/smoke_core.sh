#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-smoke"

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" --file "${COMPOSE_FILE}" "$@"
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

wait_for_health() {
  local service="$1"
  local container_id
  container_id="$(compose ps -q "${service}")"
  for _ in {1..90}; do
    if [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")" == "healthy" ]]; then
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

compose exec -T postgres pg_isready -U cellarbridge -d cellarbridge >/dev/null
curl --fail --silent --show-error http://127.0.0.1:8081/realms/master/.well-known/openid-configuration | grep --quiet '"issuer"'
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness | grep --quiet 'UP'
curl --fail --silent --show-error http://127.0.0.1:5173/app | grep --quiet 'CellarBridge Operations'

printf '%s\n' 'Core smoke passed: PostgreSQL, Keycloak, backend readiness, and frontend are healthy.'
