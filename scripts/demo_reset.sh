#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
FULL_COMPOSE_FILE="${ROOT_DIR}/deploy/compose/full.compose.yaml"
PROJECT_NAME="cellarbridge-demo"
PROFILE="${DEMO_PROFILE:-core}"

case "${PROFILE}" in
  core)
    ENV_FILE="${DEMO_ENV_FILE:-$(if [[ -f "${ROOT_DIR}/.env" ]]; then printf '%s' "${ROOT_DIR}/.env"; else printf '%s' "${ROOT_DIR}/.env.example"; fi)}"
    COMPOSE_FILES=(--file "${CORE_COMPOSE_FILE}")
    ;;
  full)
    ENV_FILE="${DEMO_ENV_FILE:-${ROOT_DIR}/.env}"
    [[ -f "${ENV_FILE}" ]] || { printf 'Full profile reset requires %s.\n' "${ENV_FILE}" >&2; exit 1; }
    COMPOSE_FILES=(--file "${CORE_COMPOSE_FILE}" --file "${FULL_COMPOSE_FILE}")
    ;;
  *)
    printf 'Unsupported DEMO_PROFILE=%s; use core or full.\n' "${PROFILE}" >&2
    exit 1
    ;;
esac

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" "${COMPOSE_FILES[@]}" "$@"
}

backend_id="$(compose ps -q backend 2>/dev/null || true)"
if [[ -n "${backend_id}" ]]; then
  backend_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${backend_id}")"
  grep --quiet '^SPRING_PROFILES_ACTIVE=demo$' <<<"${backend_environment}"
  grep --quiet '^CELLARBRIDGE_DB_URL=jdbc:postgresql://postgres:5432/cellarbridge$' <<<"${backend_environment}"
fi

printf '%s\n' \
  'Reset scope: Docker Compose project cellarbridge-demo only.' \
  'This removes its local volumes and all synthetic demo data, then starts a fresh seeded profile.'
compose down --volumes --remove-orphans
DEMO_PROFILE="${PROFILE}" DEMO_ENV_FILE="${ENV_FILE}" "${ROOT_DIR}/scripts/demo.sh"
