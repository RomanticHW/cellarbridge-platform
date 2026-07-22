#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
FULL_COMPOSE_FILE="${ROOT_DIR}/deploy/compose/full.compose.yaml"
PROJECT_NAME="cellarbridge-demo"
PROFILE="${DEMO_PROFILE:-core}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$1" >&2
    exit 1
  fi
}

require_command docker
require_command curl
docker info >/dev/null
docker compose version >/dev/null

case "${PROFILE}" in
  core)
    if [[ -n "${DEMO_ENV_FILE:-}" ]]; then
      ENV_FILE="${DEMO_ENV_FILE}"
    elif [[ -f "${ROOT_DIR}/.env" ]]; then
      ENV_FILE="${ROOT_DIR}/.env"
    else
      ENV_FILE="${ROOT_DIR}/.env.example"
    fi
    COMPOSE_FILES=(--file "${CORE_COMPOSE_FILE}")
    HEALTH_SERVICES=(postgres keycloak backend frontend)
    ;;
  full)
    ENV_FILE="${DEMO_ENV_FILE:-${ROOT_DIR}/.env}"
    if [[ ! -f "${ENV_FILE}" ]]; then
      printf 'Full profile requires an untracked .env file. Copy .env.example and replace its full-profile passwords.\n' >&2
      exit 1
    fi
    if grep -Eq '^GRAFANA_ADMIN_PASSWORD=(replace-before-full-profile)?$' "${ENV_FILE}"; then
      printf 'Replace GRAFANA_ADMIN_PASSWORD in %s before starting the full profile.\n' "${ENV_FILE}" >&2
      exit 1
    fi
    COMPOSE_FILES=(--file "${CORE_COMPOSE_FILE}" --file "${FULL_COMPOSE_FILE}")
    HEALTH_SERVICES=(postgres keycloak otel-collector backend frontend prometheus tempo grafana)
    ;;
  *)
    printf 'Unsupported DEMO_PROFILE=%s; use core or full.\n' "${PROFILE}" >&2
    exit 1
    ;;
esac

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" "${COMPOSE_FILES[@]}" "$@"
}

wait_for_health() {
  local service="$1"
  local container_id
  container_id="$(compose ps -q "${service}")"
  if [[ -z "${container_id}" ]]; then
    printf 'Service %s did not start.\n' "${service}" >&2
    return 1
  fi
  for _ in {1..120}; do
    if [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")" == "healthy" ]]; then
      return 0
    fi
    sleep 2
  done
  printf 'Service %s did not become healthy before the deadline.\n' "${service}" >&2
  return 1
}

cleanup_on_failure() {
  local status=$?
  if [[ "${status}" -ne 0 ]]; then
    compose logs --no-color 2>&1 \
      | sed -E 's#/portal/(quotes|quotations)/[A-Za-z0-9_-]{40,100}#/portal/\1/[redacted]#g' >&2 || true
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "${status}"
}

trap cleanup_on_failure EXIT
CELLARBRIDGE_COMMIT="$(git -C "${ROOT_DIR}" rev-parse --short=12 HEAD)"
export CELLARBRIDGE_COMMIT
compose up --detach --build
for service in "${HEALTH_SERVICES[@]}"; do
  wait_for_health "${service}"
done

latest_migration="$(
  # Variables in this command intentionally expand inside the PostgreSQL container.
  # shellcheck disable=SC2016
  compose exec -T postgres sh -lc \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"'
)"
if [[ "${latest_migration}" != "22" ]]; then
  printf 'Expected Flyway V22, observed %s.\n' "${latest_migration:-none}" >&2
  exit 1
fi

seeded_users="$(
  # Variables in this command intentionally expand inside the PostgreSQL container.
  # shellcheck disable=SC2016
  compose exec -T postgres sh -lc \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM identity_access.user_mapping WHERE username LIKE '\''north.%'\'' OR username LIKE '\''harbor.%'\''"'
)"
if [[ "${seeded_users}" -lt 10 ]]; then
  printf 'Expected at least 10 synthetic demo users, observed %s.\n' "${seeded_users}" >&2
  exit 1
fi

curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness | grep --quiet '"status":"UP"'
curl --fail --silent --show-error http://127.0.0.1:5173/app | grep --quiet 'CellarBridge Operations'

trap - EXIT
printf '%s\n' \
  "CellarBridge v1.0.0 ${PROFILE} demo is ready." \
  'Application: http://localhost:5173/app' \
  'Backend readiness: http://localhost:8080/actuator/health/readiness' \
  'Keycloak: http://localhost:8081' \
  'Roles: north.sales, north.manager, north.buyer, north.trade, north.warehouse, north.admin, north.finance, north.auditor, north.operator, harbor.manager' \
  'Demo-only password: CellarBridge-Demo-2026!' \
  'All accounts and business records are synthetic and local-only.' \
  'Stop with: make stop-demo'
if [[ "${PROFILE}" == "full" ]]; then
  printf '%s\n' \
    'Grafana: http://localhost:3000' \
    'Prometheus: http://localhost:9090' \
    'Tempo: http://localhost:3200'
fi
