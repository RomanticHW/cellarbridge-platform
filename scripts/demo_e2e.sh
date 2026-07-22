#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-demo-e2e"
EVIDENCE_DIR="${CELLARBRIDGE_E2E_EVIDENCE_DIR:-${ROOT_DIR}/target/release-evidence/screenshots}"

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" --file "${COMPOSE_FILE}" "$@"
}

redact_capabilities() {
  sed -E 's#/portal/(quotes|quotations)/[A-Za-z0-9_-]{40,100}#/portal/\1/[redacted]#g'
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

finalize() {
  local status=$?
  trap - EXIT
  local logs
  logs="$(compose logs frontend backend 2>/dev/null || true)"
  if grep -Eq '/portal/(quotes|quotations)/[A-Za-z0-9_-]{40,100}' <<<"${logs}"; then
    printf '%s\n' 'A customer portal capability token was found in service logs.' >&2
    status=1
  fi
  cleanup
  exit "${status}"
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
  compose logs "${service}" 2>&1 | redact_capabilities
  return 1
}

mkdir -p "${EVIDENCE_DIR}"
find "${EVIDENCE_DIR}" -maxdepth 1 -type f -name '*.png' -delete
trap finalize EXIT
cleanup
compose up --detach --build
for service in postgres keycloak backend frontend; do
  wait_for_health "${service}"
done

cd "${ROOT_DIR}/frontend"
if command -v corepack >/dev/null 2>&1; then
  package_manager=(corepack pnpm)
elif command -v pnpm >/dev/null 2>&1; then
  package_manager=(pnpm)
else
  printf 'corepack or pnpm is required to run the demonstration E2E suite.\n' >&2
  exit 1
fi
CELLARBRIDGE_E2E_EVIDENCE_DIR="${EVIDENCE_DIR}" "${package_manager[@]}" test:e2e:demo 2>&1 \
  | redact_capabilities
test "$(find "${EVIDENCE_DIR}" -maxdepth 1 -type f -name '*.png' | wc -l | tr -d ' ')" -eq 6
printf 'Complete reviewer journey passed; six synthetic screenshots are in %s.\n' "${EVIDENCE_DIR}"
