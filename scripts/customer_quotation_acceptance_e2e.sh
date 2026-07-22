#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-acceptance-e2e"

compose() {
  docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" --file "${COMPOSE_FILE}" "$@"
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

assert_portal_tokens_absent_from_logs() {
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
  if ! assert_portal_tokens_absent_from_logs; then
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
  compose logs "${service}"
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
# Preserve the Playwright exit status while preventing a failed navigation message from
# echoing a capability-bearing portal URL into local or CI output.
if command -v corepack >/dev/null 2>&1; then
  package_manager=(corepack pnpm)
elif command -v pnpm >/dev/null 2>&1; then
  package_manager=(pnpm)
else
  printf 'corepack or pnpm is required to run the acceptance E2E suite.\n' >&2
  exit 1
fi
"${package_manager[@]}" test:e2e:acceptance 2>&1 \
  | sed -E 's#/portal/(quotes|quotations)/[A-Za-z0-9_-]{40,100}#/portal/\1/[redacted]#g'

journey_correlation_id='74000000-0000-4000-8000-000000000013'
correlated_events="$(
  compose exec -T postgres psql -U cellarbridge -d cellarbridge -Atc \
    "SELECT count(*) FROM platform_event.event_publication
      WHERE correlation_id = '${journey_correlation_id}'
        AND event_type = 'cellarbridge.quotation.accepted.v1'"
)"
traceable_events="$(
  compose exec -T postgres psql -U cellarbridge -d cellarbridge -Atc \
    "SELECT count(*) FROM platform_event.event_publication
      WHERE correlation_id = '${journey_correlation_id}'
        AND event_type = 'cellarbridge.quotation.accepted.v1'
        AND length(payload #>> '{metadata,traceparent}') = 55
        AND left(payload #>> '{metadata,traceparent}', 3) = '00-'
        AND substring(payload #>> '{metadata,traceparent}' FROM 36 FOR 1) = '-'
        AND substring(payload #>> '{metadata,traceparent}' FROM 53 FOR 1) = '-'
        AND substring(payload #>> '{metadata,traceparent}' FROM 4 FOR 32) <> repeat('0', 32)
        AND substring(payload #>> '{metadata,traceparent}' FROM 37 FOR 16) <> repeat('0', 16)
        AND translate(
              replace(payload #>> '{metadata,traceparent}', '-', ''),
              '0123456789abcdef',
              '') = ''"
)"
if [[ "${correlated_events}" != "1" || "${traceable_events}" != "1" ]]; then
  trace_shape="$(
    compose exec -T postgres psql -U cellarbridge -d cellarbridge -Atc \
      "SELECT COALESCE(length(payload #>> '{metadata,traceparent}'), 0) || ':' ||
              COALESCE(left(payload #>> '{metadata,traceparent}', 3), 'missing') || ':' ||
              COALESCE((substring(payload #>> '{metadata,traceparent}' FROM 36 FOR 1) = '-')::text, 'missing') || ':' ||
              COALESCE((substring(payload #>> '{metadata,traceparent}' FROM 53 FOR 1) = '-')::text, 'missing') || ':' ||
              COALESCE(right(payload #>> '{metadata,traceparent}', 2), 'missing') || ':' ||
              COALESCE(length(translate(
                replace(payload #>> '{metadata,traceparent}', '-', ''),
                '0123456789abcdef',
                ''))::text, 'missing')
         FROM platform_event.event_publication
        WHERE correlation_id = '${journey_correlation_id}'
          AND event_type = 'cellarbridge.quotation.accepted.v1'"
  )"
  printf 'The accepted quotation trace assertion failed (correlated=%s, traceable=%s).\n' \
    "${correlated_events}" "${traceable_events}" >&2
  printf 'Observed trace metadata shape (length:version:separator36:separator53:flags:invalidChars): %s\n' \
    "${trace_shape}" >&2
  exit 1
fi
printf 'Verified browser-to-event correlation and trace context: %s\n' "${journey_correlation_id}"
