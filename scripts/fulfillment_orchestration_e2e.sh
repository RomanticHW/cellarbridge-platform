#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-fulfillment-e2e"

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" --file "${COMPOSE_FILE}" "$@"
}

redact_capabilities() {
  sed -E 's#/portal/(quotes|quotations)/[A-Za-z0-9_-]{40,100}#/portal/\1/[redacted]#g'
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

assert_capabilities_absent_from_logs() {
  local logs
  logs="$(compose logs frontend backend 2>/dev/null || true)"
  if grep -Eq '/portal/(quotes|quotations)/[A-Za-z0-9_-]{40,100}' <<<"${logs}"; then
    printf '%s\n' 'A customer portal capability token was found in service logs.' >&2
    return 1
  fi
}

finalize() {
  local status=$?
  trap - EXIT
  if ! assert_capabilities_absent_from_logs; then
    status=1
  fi
  cleanup
  exit "${status}"
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
  compose logs "${service}" 2>&1 | redact_capabilities
  return 1
}

trap finalize EXIT
cleanup
compose up --detach --build

wait_for_health postgres
wait_for_health keycloak
wait_for_health backend
wait_for_health frontend

cd "${ROOT_DIR}/frontend"
# The dedicated Playwright configuration disables screenshots and traces. Redaction keeps a
# capability-bearing portal URL out of local and CI output even when Playwright reports a failure.
if command -v corepack >/dev/null 2>&1; then
  corepack pnpm test:e2e:fulfillment 2>&1 | redact_capabilities
elif command -v pnpm >/dev/null 2>&1; then
  pnpm test:e2e:fulfillment 2>&1 | redact_capabilities
else
  printf 'corepack or pnpm is required to run the Fulfillment E2E suite.\n' >&2
  exit 1
fi
