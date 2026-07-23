#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-mcp-smoke"
MCP_URL="${CB_MCP_URL:-http://localhost:8080/mcp}"
API_BASE="${CB_API_BASE:-${MCP_URL%/mcp}}"
KEYCLOAK_URL="${CB_KEYCLOAK_URL:-http://localhost:8081}"
KEYCLOAK_ALT_URL="${CB_KEYCLOAK_ALT_URL:-http://127.0.0.1:8081}"
MCP_ORIGIN="${CB_MCP_ORIGIN:-http://localhost:5173}"
MCP_RESOURCE="${CB_MCP_RESOURCE:-https://mcp.cellarbridge.example/mcp}"
MCP_METADATA_URL="${CB_MCP_METADATA_URL:-http://localhost:8080/.well-known/oauth-protected-resource/mcp}"
MCP_METADATA_ID="${CB_MCP_METADATA_ID:-${MCP_RESOURCE%/mcp}/.well-known/oauth-protected-resource/mcp}"
MCP_CLIENT_ID="cellarbridge-mcp-host"
MCP_REDIRECT_URI="http://localhost:5173/app"
MCP_SCOPE="openid profile mcp:read"
DEMO_PASSWORD="${CB_DEMO_PASSWORD:-CellarBridge-Demo-2026!}"
PROTOCOL_VERSION="2025-11-25"
TEMP_DIR=""
PROXY_PID=""
LOCK_PID=""
LOCK_APP=""
compose() {
  docker compose \
    --project-name "${PROJECT_NAME}" \
    --env-file "${ENV_FILE}" \
    --file "${COMPOSE_FILE}" \
    "$@"
}
cleanup() {
  ((BASH_SUBSHELL == 0)) || return
  if [[ -n "${LOCK_APP}" ]]; then
    postgres_scalar \
      "SELECT pg_terminate_backend(pid) FROM pg_stat_activity
        WHERE application_name = '${LOCK_APP}' AND pid <> pg_backend_pid()" \
      >/dev/null 2>&1 || true
  fi
  if [[ -n "${LOCK_PID}" ]]; then
    kill "${LOCK_PID}" >/dev/null 2>&1 || true
    wait "${LOCK_PID}" 2>/dev/null || true
  fi
  if [[ -n "${PROXY_PID}" ]]; then
    kill "${PROXY_PID}" >/dev/null 2>&1 || true
    wait "${PROXY_PID}" 2>/dev/null || true
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  if [[ "${CB_MCP_KEEP_TEMP:-false}" != "true" && -n "${TEMP_DIR}" \
    && "${TEMP_DIR}" == /tmp/cellarbridge-mcp-smoke.* && -d "${TEMP_DIR}" ]]; then
    find "${TEMP_DIR}" -depth -delete
  fi
}
run_conformance() {
  local access_token="$1" proxy_ready="false"
  local output_dir="${CB_MCP_CONFORMANCE_OUTPUT:-${ROOT_DIR}/target/mcp-conformance}"
  local proxy_port="${CB_MCP_PROXY_PORT:-}"
  local proxy_url="http://127.0.0.1:${proxy_port}/mcp"
  local scenarios=(server-initialize ping tools-list resources-list prompts-list)
  if [[ -z "${proxy_port}" ]]; then
    proxy_port="$(
      python3 - <<'PY'
import socket
with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    print(listener.getsockname()[1])
PY
    )"
    proxy_url="http://127.0.0.1:${proxy_port}/mcp"
  fi
  mkdir -p "${output_dir}"
  CB_MCP_PROXY_TOKEN="${access_token}" \
    CB_MCP_PROXY_PORT="${proxy_port}" \
    CB_MCP_PROXY_UPSTREAM="http://127.0.0.1:8080" \
    python3 "${ROOT_DIR}/scripts/mcp_auth_proxy.py" \
      >"${TEMP_DIR}/mcp-auth-proxy.log" 2>&1 &
  PROXY_PID=$!
  for _ in {1..30}; do
    if curl --silent --output /dev/null "${proxy_url}"; then
      proxy_ready="true"
      break
    fi
    if ! kill -0 "${PROXY_PID}" 2>/dev/null; then
      printf '%s\n' 'The MCP conformance authentication proxy stopped unexpectedly.' >&2
      return 1
    fi
    sleep 1
  done
  [[ "${proxy_ready}" == "true" ]] || {
    printf '%s\n' 'The MCP conformance authentication proxy did not become ready.' >&2
    return 1
  }
  for scenario in "${scenarios[@]}"; do
    npx --yes "@modelcontextprotocol/conformance@0.1.16" server \
      --url "${proxy_url}" \
      --scenario "${scenario}" \
      --spec-version "${PROTOCOL_VERSION}" \
      --output-dir "${output_dir}/${scenario}"
  done
  printf 'PASS mcp-conformance version=0.1.16 protocol=%s scenarios=%s output=%s\n' \
    "${PROTOCOL_VERSION}" "${#scenarios[@]}" "${output_dir}"
}
wait_for_health() {
  local service="$1" container_id
  for _ in {1..90}; do
    container_id="$(compose ps -q "${service}")"
    if [[ -n "${container_id}" ]] \
      && [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}" 2>/dev/null)" == "healthy" ]]; then
      return 0
    fi
    sleep 2
  done
  compose logs "${service}"
  return 1
}
postgres_scalar() {
  compose exec -T postgres psql -U cellarbridge -d cellarbridge -Atq -c "$1"
}
wait_for_database_state() {
  local sql="$1" expected="$2" description="$3" observed=""
  local deadline=$((SECONDS + 20))
  while ((SECONDS < deadline)); do
    observed="$(postgres_scalar "${sql}" 2>/dev/null || true)"
    [[ "${observed}" == "${expected}" ]] && return 0
    [[ -z "${LOCK_PID}" ]] || kill -0 "${LOCK_PID}" 2>/dev/null || break
    sleep 0.2
  done
  printf 'Timed out waiting for PostgreSQL state: %s (observed=%s).\n' \
    "${description}" "${observed}" >&2
  compose exec -T postgres psql -U cellarbridge -d cellarbridge -P pager=off \
    -c "SELECT application_name,state,wait_event_type,wait_event FROM pg_stat_activity
         WHERE datname=current_database() ORDER BY application_name,pid" >&2 || true
  return 1
}
start_database_lock() {
  local app="$1" relation="$2"
  [[ "${app}" =~ ^cb_mcp_[a-z0-9_]+$ && "${relation}" =~ ^[a-z_]+\.[a-z_]+$ ]]
  LOCK_APP="${app}"
  compose exec -T -e "PGAPPNAME=${app}" postgres \
    psql -U cellarbridge -d cellarbridge -v ON_ERROR_STOP=1 \
    -c "BEGIN; LOCK TABLE ${relation} IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(120); COMMIT;" \
    >"${TEMP_DIR}/${app}.log" 2>&1 &
  LOCK_PID=$!
  wait_for_database_state \
    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity a JOIN pg_locks l USING(pid)
      WHERE a.application_name='${app}' AND l.relation='${relation}'::regclass
        AND l.mode='AccessExclusiveLock' AND l.granted)" \
    "t" "${app} owns ${relation}"
}
wait_for_database_waiter() {
  local app="$1" relation="$2"
  wait_for_database_state \
    "SELECT EXISTS (SELECT 1 FROM pg_locks w JOIN pg_stat_activity wa USING(pid)
      WHERE w.relation='${relation}'::regclass AND NOT w.granted AND wa.wait_event_type='Lock'
        AND EXISTS (SELECT 1 FROM pg_locks h JOIN pg_stat_activity ho USING(pid)
          WHERE ho.application_name='${app}' AND h.relation=w.relation
            AND h.mode='AccessExclusiveLock' AND h.granted))" \
    "t" "an MCP query waits behind ${app}"
}
release_database_lock() {
  local app="$1"
  postgres_scalar "SELECT count(*) FROM (SELECT pg_terminate_backend(pid)
    FROM pg_stat_activity WHERE application_name='${app}' AND pid<>pg_backend_pid()) x" >/dev/null
  wait "${LOCK_PID}" 2>/dev/null || true
  LOCK_PID=""
  wait_for_database_state "SELECT count(*) FROM pg_stat_activity
    WHERE application_name='${app}'" "0" "${app} terminated"
  LOCK_APP=""
}
oidc_token() {
  local flow="$1" username="$2" client_id="$3" redirect_uri="$4" scope="$5"
  local authorization_resource="$6" token_resource="$7" expected_error="${8:-}" verifier_override="${9:-}"
  local suffix="${flow//./-}" verifier challenge login_action authorization_code http_status
  local cookie_jar="${TEMP_DIR}/${suffix}.cookies" login_page="${TEMP_DIR}/${suffix}.login.html"
  local login_headers="${TEMP_DIR}/${suffix}.login.headers"
  local token_response="${TEMP_DIR}/${suffix}.token.json" state="cellarbridge-${suffix}"
  local -a authorization_args=(
    --data-urlencode "client_id=${client_id}"
    --data-urlencode "redirect_uri=${redirect_uri}"
    --data-urlencode "response_type=code"
    --data-urlencode "scope=${scope}"
    --data-urlencode "state=${state}"
    --data-urlencode "nonce=${state}"
  )
  verifier="$(openssl rand -base64 72 | tr -d '\n=+/' | cut -c1-80)"
  challenge="$(
    printf '%s' "${verifier}" \
      | openssl dgst -sha256 -binary \
      | openssl base64 -A \
      | tr '+/' '-_' \
      | tr -d '='
  )"
  if [[ -n "${authorization_resource}" ]]; then
    authorization_args+=(--data-urlencode "resource=${authorization_resource}")
  fi
  curl --fail --silent --show-error \
    --cookie-jar "${cookie_jar}" \
    --output "${login_page}" \
    --get "${KEYCLOAK_URL}/realms/cellarbridge/protocol/openid-connect/auth" \
    "${authorization_args[@]}" \
    --data-urlencode "code_challenge=${challenge}" \
    --data-urlencode "code_challenge_method=S256"
  login_action="$(
    python3 - "${login_page}" <<'PY'
from html.parser import HTMLParser
from pathlib import Path
import sys
class LoginFormParser(HTMLParser):
    action = None
    def handle_starttag(self, tag, attrs):
        if tag == "form" and self.action is None:
            self.action = dict(attrs).get("action")
parser = LoginFormParser()
parser.feed(Path(sys.argv[1]).read_text(encoding="utf-8"))
if not parser.action:
    raise SystemExit("Keycloak login form action was not found.")
print(parser.action)
PY
  )"
  curl --silent --show-error \
    --cookie "${cookie_jar}" \
    --cookie-jar "${cookie_jar}" \
    --dump-header "${login_headers}" \
    --output /dev/null \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${DEMO_PASSWORD}" \
    --data-urlencode "credentialId=" \
    "${login_action}"
  authorization_code="$(
    python3 - "${login_headers}" "${state}" <<'PY'
import sys; from pathlib import Path
from urllib.parse import parse_qs, urlparse
headers = Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
location = next((line.split(":", 1)[1].strip() for line in headers if line.lower().startswith("location:")), None)
if not location: raise SystemExit("Keycloak did not return an authorization redirect.")
query = parse_qs(urlparse(location).query)
if query.get("state") != [sys.argv[2]] or "code" not in query: raise SystemExit("Keycloak authorization redirect was invalid.")
print(query["code"][0])
PY
  )"
  local -a token_args=(
    --data-urlencode "grant_type=authorization_code"
    --data-urlencode "client_id=${client_id}"
    --data-urlencode "redirect_uri=${redirect_uri}"
    --data-urlencode "code=${authorization_code}"
    --data-urlencode "code_verifier=${verifier_override:-${verifier}}"
  )
  if [[ "${token_resource}" == "__duplicate__" ]]; then
    token_args+=(--data-urlencode "resource=${MCP_RESOURCE}" --data-urlencode "resource=${MCP_RESOURCE}")
  elif [[ -n "${token_resource}" ]]; then
    token_args+=(--data-urlencode "resource=${token_resource}")
  fi
  http_status="$(
    curl --silent --show-error \
      --output "${token_response}" \
      --write-out '%{http_code}' \
      "${token_args[@]}" \
      "${KEYCLOAK_URL}/realms/cellarbridge/protocol/openid-connect/token"
  )"
  if [[ -n "${expected_error}" ]]; then
    python3 - "${token_response}" "${http_status}" "${expected_error}" <<'PY'
from pathlib import Path
import json, sys
assert sys.argv[2] == "400", f"expected OAuth HTTP 400, received {sys.argv[2]}"
payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")); assert payload["error"] == sys.argv[3], (Path(sys.argv[1]).name, payload["error"])
PY
    return
  fi
  [[ "${http_status}" == "200" ]] || {
    printf 'Token exchange %s returned HTTP %s.\n' "${flow}" "${http_status}" >&2
    return 1
  }
  python3 - "${token_response}" <<'PY'
from pathlib import Path
import json, sys
payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
token = payload.get("access_token")
if payload.get("token_type") != "Bearer" or not token:
    raise SystemExit("Keycloak token response did not contain a Bearer access token.")
print(token)
PY
}
refresh_token() {
  local flow="$1" resource="$2" expected_error="${3:-}" refresh status output="${TEMP_DIR}/${1}.refresh.json"
  local -a args=(
    --data-urlencode "grant_type=refresh_token"
    --data-urlencode "client_id=${MCP_CLIENT_ID}"
  )
  refresh="$(TOKEN_FILE="${TEMP_DIR}/${flow}.token.json" python3 -c \
    'import json,os; print(json.load(open(os.environ["TOKEN_FILE"]))["refresh_token"])')"
  args+=(--data-urlencode "refresh_token=${refresh}")
  if [[ "${resource}" == "__duplicate__" ]]; then
    args+=(--data-urlencode "resource=${MCP_RESOURCE}" --data-urlencode "resource=${MCP_RESOURCE}")
  elif [[ -n "${resource}" ]]; then
    args+=(--data-urlencode "resource=${resource}")
  fi
  status="$(curl --silent --show-error --output "${output}" --write-out '%{http_code}' \
    "${args[@]}" "${KEYCLOAK_URL}/realms/cellarbridge/protocol/openid-connect/token")"
  python3 - "${output}" "${status}" "${expected_error}" <<'PY'
from pathlib import Path
import json, sys
payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if sys.argv[3]:
    assert sys.argv[2] == "400" and payload.get("error") == sys.argv[3], (Path(sys.argv[1]).name, sys.argv[2], payload.get("error"))
else:
    assert sys.argv[2] == "200" and payload.get("access_token"); print(payload["access_token"])
PY
}
keycloak_admin() {
  compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" \
    --config /tmp/cellarbridge-mcp-smoke-kcadm.config
}
real_expired_token() {
  local lifetime token
  # shellcheck disable=SC2016
  compose exec -T keycloak sh -eu -c \
    '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/cellarbridge-mcp-smoke-kcadm.config --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' \
    >/dev/null
  lifetime="$(keycloak_admin get realms/cellarbridge --fields accessTokenLifespan |
    jq -er '.accessTokenLifespan')"
  keycloak_admin update realms/cellarbridge -s accessTokenLifespan=-120 >/dev/null
  token="$(oidc_token mcp-expired north.sales "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
    "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}")"
  keycloak_admin update realms/cellarbridge -s "accessTokenLifespan=${lifetime}" >/dev/null
  python3 - "${TEMP_DIR}/mcp-expired.token.json" <<'PY'
import base64, json, pathlib, sys, time
token = json.loads(pathlib.Path(sys.argv[1]).read_text())["access_token"].split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(token + "=" * (-len(token) % 4)))
assert claims["exp"] < time.time() - 60
PY
  printf '%s' "${token}"
}
assert_authorization_target_rejected() {
  local name="$1" resource="$2" expected_error="${3:-invalid_target}" pkce_method="${4:-S256}" status
  local body="${TEMP_DIR}/${name}.auth.body" headers="${TEMP_DIR}/${name}.auth.headers"
  local -a args=(
    --data-urlencode "client_id=${MCP_CLIENT_ID}"
    --data-urlencode "redirect_uri=${MCP_REDIRECT_URI}"
    --data-urlencode "response_type=code"
    --data-urlencode "scope=${MCP_SCOPE}"
    --data-urlencode "state=${name}"
  )
  if [[ "${pkce_method}" != "missing" ]]; then
    args+=(--data-urlencode "code_challenge=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" --data-urlencode "code_challenge_method=${pkce_method}")
  fi
  if [[ "${resource}" == "__duplicate__" ]]; then
    args+=(--data-urlencode "resource=${MCP_RESOURCE}" --data-urlencode "resource=${MCP_RESOURCE}")
  elif [[ -n "${resource}" ]]; then
    args+=(--data-urlencode "resource=${resource}")
  fi
  status="$(curl --silent --show-error --output "${body}" --dump-header "${headers}" \
    --write-out '%{http_code}' --get \
    "${KEYCLOAK_URL}/realms/cellarbridge/protocol/openid-connect/auth" "${args[@]}")"
  python3 - "${body}" "${headers}" "${status}" "${expected_error}" <<'PY'
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import json
import sys
body = Path(sys.argv[1]).read_text(encoding="utf-8")
locations = [line.split(":", 1)[1].strip() for line in Path(sys.argv[2]).read_text().splitlines()
             if line.lower().startswith("location:")]
error = parse_qs(urlparse(locations[-1]).query).get("error", [None])[0] if locations else None
if not error and body.startswith("{"):
    error = json.loads(body).get("error")
assert sys.argv[3] in {"302", "303", "400"} and error == sys.argv[4]
PY
}
mcp_capture() {
  local output_name="$1" access_token="$2" origin="$3" payload="$4"
  shift 4
  local -a headers=(
    --header "Content-Type: application/json"
    --header "Accept: application/json, text/event-stream"
    --header "MCP-Protocol-Version: ${PROTOCOL_VERSION}"
  )
  [[ -n "${access_token}" ]] && headers+=(--header "Authorization: Bearer ${access_token}")
  [[ -n "${origin}" ]] && headers+=(--header "Origin: ${origin}")
  curl --silent --show-error --max-time "${CB_MCP_HTTP_TIMEOUT_SECONDS:-20}" \
    --output "${TEMP_DIR}/${output_name}.json" \
    --dump-header "${TEMP_DIR}/${output_name}.headers" \
    --write-out '%{http_code}' \
    --request POST "${MCP_URL}" \
    "${headers[@]}" "$@" \
    --data "${payload}"
}
mcp_post() {
  local http_status
  http_status="$(mcp_capture "$@")"
  if [[ "${http_status}" != "200" ]]; then
    printf 'MCP request %s returned HTTP %s.\n' "$1" "${http_status}" >&2
    return 1
  fi
}
assert_http_status() {
  local expected="$1" access_token="$2" origin="$3" status
  status="$(mcp_capture "status-${expected}-${RANDOM}" "${access_token}" "${origin}" \
    '{"jsonrpc":"2.0","id":"status-check","method":"tools/list","params":{}}')"
  if [[ "${status}" != "${expected}" ]]; then
    printf 'Expected HTTP %s but received %s.\n' "${expected}" "${status}" >&2
    return 1
  fi
}
exercise_database_timeout() {
  local phase="$1" relation="$2" access_token="$3"
  local api_token="$4" payload="$5" verify_rest="$6"
  local app="cb_mcp_${phase}_lock"
  local backend_id slow_pid status expected_status="200"
  [[ "${phase}" != "request" ]] || expected_status="504"
  backend_id="$(compose ps -q backend)"
  start_database_lock "${app}" "${relation}"
  (mcp_capture "security-${phase}-timeout" "${access_token}" "${MCP_ORIGIN}" "${payload}" \
    >"${TEMP_DIR}/security-${phase}-timeout.status") &
  slow_pid=$!
  wait_for_database_waiter "${app}" "${relation}"
  status="$(mcp_capture "security-${phase}-capacity" "${access_token}" "${MCP_ORIGIN}" \
    '{"jsonrpc":"2.0","id":"capacity","method":"tools/list","params":{}}')"
  [[ "${status}" == "503" ]]
  curl --fail --silent --show-error \
    "${API_BASE}/actuator/health/mcp" >"${TEMP_DIR}/health-${phase}-bulkhead.json"
  if [[ "${verify_rest}" == "true" ]]; then
    curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
      --header "Authorization: Bearer ${api_token}" "${API_BASE}/api/v1/me" \
      >"${TEMP_DIR}/rest-during-${phase}.status"
  fi
  wait "${slow_pid}"
  [[ "$(<"${TEMP_DIR}/security-${phase}-timeout.status")" == "${expected_status}" ]]
  curl --fail --silent --show-error \
    "${API_BASE}/actuator/health/mcp" >"${TEMP_DIR}/health-${phase}-timeout.json"
  release_database_lock "${app}"
  [[ "$(compose ps -q backend)" == "${backend_id}" ]]
  mcp_post "security-${phase}-recovered" "${access_token}" "${MCP_ORIGIN}" "${payload}"
  [[ "$(compose ps -q backend)" == "${backend_id}" ]]
}
wait_for_mcp_success() {
  local name="$1" access_token="$2" payload="$3" retry_status="${4:-429}"
  local deadline=$((SECONDS + 30)) status=""
  while ((SECONDS < deadline)); do
    status="$(mcp_capture "${name}" "${access_token}" "${MCP_ORIGIN}" "${payload}")"
    [[ "${status}" == "200" ]] && return 0
    [[ "${status}" == "429" || "${status}" == "${retry_status}" ]] || break
    sleep 0.2
  done
  printf 'MCP request %s did not recover before the deadline (HTTP %s).\n' \
    "${name}" "${status}" >&2
  return 1
}
run_production_security() {
  local rpc='{"jsonrpc":"2.0","id":"security","method":"tools/list","params":{}}'
  local supply_rpc='{"jsonrpc":"2.0","id":"slow","method":"tools/call","params":{"name":"cellarbridge_search_supply","arguments":{"keyword":"Moonlit Terrace"}}}'
  local api_token no_scope_token expired_token wrong_issuer_token refreshed_token oversized status recovered_token trusted_keycloak_url="${KEYCLOAK_URL}"
  local phase1_since phase2_since rate_status="" target_error
  sales_token="$(oidc_token mcp-sales north.sales "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
    "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}")"
  assert_authorization_target_rejected authorization-missing ""
  assert_authorization_target_rejected authorization-wrong "https://invalid.example/mcp"
  assert_authorization_target_rejected authorization-case "https://mcp.cellarbridge.example/MCP"
  assert_authorization_target_rejected authorization-duplicate "__duplicate__" invalid_request
  assert_authorization_target_rejected pkce-missing "${MCP_RESOURCE}" invalid_request missing
  assert_authorization_target_rejected pkce-plain "${MCP_RESOURCE}" invalid_request plain
  for target in "" "https://invalid.example/mcp" "https://mcp.cellarbridge.example/MCP" "__duplicate__"; do
    target_error=invalid_target
    [[ "${target}" != "__duplicate__" ]] || target_error=invalid_request
    oidc_token "token-${target//[^a-z]/x}" north.sales "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
      "${MCP_SCOPE}" "${MCP_RESOURCE}" "${target}" "${target_error}"
    oidc_token "refresh-${target//[^a-z]/x}" north.sales "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
      "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}" >/dev/null
    refresh_token "refresh-${target//[^a-z]/x}" "${target}" "${target_error}"
  done
  refreshed_token="$(refresh_token mcp-sales "${MCP_RESOURCE}")"
  oidc_token pkce-wrong-verifier north.sales "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
    "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}" invalid_grant wrong-verifier

  api_token="$(oidc_token api-sales north.sales cellarbridge-console "${MCP_REDIRECT_URI}" \
    "openid profile" "" "")"
  no_scope_token="$(oidc_token mcp-no-scope north.sales "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
    "openid profile" "${MCP_RESOURCE}" "${MCP_RESOURCE}")"
  expired_token="$(real_expired_token)"
  keycloak_admin update realms/cellarbridge -s "attributes.frontendUrl=${KEYCLOAK_ALT_URL}" >/dev/null; KEYCLOAK_URL="${KEYCLOAK_ALT_URL}"
  wrong_issuer_token="$(oidc_token mcp-wrong-issuer north.sales "${MCP_CLIENT_ID}" \
    "${MCP_REDIRECT_URI}" "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}")"
  KEYCLOAK_URL="${trusted_keycloak_url}"; keycloak_admin update realms/cellarbridge -s "attributes.frontendUrl=${trusted_keycloak_url}" >/dev/null
  export CELLARBRIDGE_MCP_MAX_CONCURRENCY=2 CELLARBRIDGE_MCP_MAX_CLIENT_CONCURRENCY=1
  compose up --detach --no-deps --force-recreate backend
  wait_for_health backend
  phase1_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  curl --fail --silent --show-error \
    "${API_BASE}/actuator/prometheus" >"${TEMP_DIR}/metadata-metrics-before.txt"
  curl --silent --show-error --output "${TEMP_DIR}/metadata-forwarded.json" \
    --write-out '%{http_code}' --header 'Host: localhost:8080' \
    --header 'Forwarded: host=attacker.example;proto=http' \
    --header "Authorization: Bearer ${sales_token}" "${MCP_METADATA_URL}" \
    >"${TEMP_DIR}/metadata-forwarded.status"
  curl --silent --show-error --output "${TEMP_DIR}/metadata-host.json" \
    --write-out '%{http_code}' --header 'Host: attacker.example' \
    --header "Authorization: Bearer ${sales_token}" "${MCP_METADATA_URL}" \
    >"${TEMP_DIR}/metadata-host.status"
  curl --fail --silent --show-error \
    "${API_BASE}/actuator/prometheus" >"${TEMP_DIR}/metadata-metrics-after.txt"
  [[ "$(mcp_capture security-unauthenticated "" "${MCP_ORIGIN}" "${rpc}")" == "401" ]]
  [[ "$(mcp_capture security-api-token "${api_token}" "${MCP_ORIGIN}" "${rpc}")" == "401" ]]
  [[ "$(mcp_capture security-bad-signature "${sales_token}x" "${MCP_ORIGIN}" "${rpc}")" == "401" ]]
  [[ "$(mcp_capture security-expired "${expired_token}" "${MCP_ORIGIN}" "${rpc}")" == "401" ]]
  [[ "$(mcp_capture security-wrong-issuer "${wrong_issuer_token}" "${MCP_ORIGIN}" "${rpc}")" == "401" ]]
  [[ "$(mcp_capture security-no-scope "${no_scope_token}" "${MCP_ORIGIN}" "${rpc}")" == "403" ]]
  [[ "$(mcp_capture security-native "${sales_token}" "" "${rpc}")" == "200" ]]
  [[ "$(mcp_capture security-refreshed "${refreshed_token}" "${MCP_ORIGIN}" "${rpc}")" == "200" ]]
  [[ "$(mcp_capture security-origin "${sales_token}" "https://invalid.example" "${rpc}")" == "403" ]]
  [[ "$(mcp_capture security-host "${sales_token}" "${MCP_ORIGIN}" "${rpc}" \
    --header 'Host: attacker.example')" == "421" ]]
  curl --silent --show-error --output "${TEMP_DIR}/api-console.json" --write-out '%{http_code}' \
    --header "Authorization: Bearer ${api_token}" "${API_BASE}/api/v1/me" \
    >"${TEMP_DIR}/api-console.status"
  curl --silent --show-error --output "${TEMP_DIR}/api-mcp.json" --write-out '%{http_code}' \
    --header "Authorization: Bearer ${sales_token}" "${API_BASE}/api/v1/me" \
    >"${TEMP_DIR}/api-mcp.status"
  oversized="$(python3 -c 'print("x" * 65537)')"
  [[ "$(mcp_capture security-length "${sales_token}" "${MCP_ORIGIN}" "${oversized}")" == "413" ]]
  [[ "$(mcp_capture security-chunked "${sales_token}" "${MCP_ORIGIN}" "${oversized}" \
    --header 'Transfer-Encoding: chunked')" == "413" ]]
  exercise_database_timeout request identity_access.user_mapping "${sales_token}" \
    "${api_token}" "${rpc}" false
  curl --fail --silent --show-error \
    "${API_BASE}/actuator/prometheus" >"${TEMP_DIR}/prometheus-ingress.txt"
  compose logs --no-color --since "${phase1_since}" backend >"${TEMP_DIR}/backend-phase1.log" 2>&1
  export CELLARBRIDGE_MCP_REQUEST_TIMEOUT=PT5S CELLARBRIDGE_MCP_STATEMENT_TIMEOUT=PT2S \
    CELLARBRIDGE_MCP_MAX_CONCURRENCY=1 CELLARBRIDGE_MCP_MAX_CLIENT_CONCURRENCY=1
  compose up --detach --no-deps --force-recreate backend
  wait_for_health backend
  phase2_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  exercise_database_timeout statement catalog.sku "${sales_token}" \
    "${api_token}" "${supply_rpc}" true
  for index in {0..100}; do
    marker="CB_CARDINALITY_${index}"
    status="$(mcp_capture "cardinality-${index}" "${sales_token}" "${MCP_ORIGIN}" \
      "{\"jsonrpc\":\"2.0\",\"id\":\"${marker}\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"${PROTOCOL_VERSION}\",\"capabilities\":{\"experimental\":{\"arguments\":\"${marker}\"}},\"clientInfo\":{\"name\":\"${marker}\",\"version\":\"1\"}}}" \
      --header "X-Correlation-ID: ${marker}")"
    [[ "${status}" == "200" ]]
    if [[ "${index}" == "0" ]]; then
      curl --fail --silent --show-error \
        "${API_BASE}/actuator/prometheus" >"${TEMP_DIR}/cardinality-before.txt"
    fi
  done
  curl --fail --silent --show-error \
    "${API_BASE}/actuator/prometheus" >"${TEMP_DIR}/cardinality-after.txt"
  for _ in {1..100}; do
    rate_status="$(mcp_capture security-rate "${sales_token}" "${MCP_ORIGIN}" "${rpc}")"
    [[ "${rate_status}" == "429" ]] && break
    [[ "${rate_status}" == "200" ]] || return 1
  done
  [[ "${rate_status}" == "429" ]]
  curl --fail --silent --show-error \
    "${API_BASE}/actuator/prometheus" >"${TEMP_DIR}/prometheus-rate.txt"
  compose stop keycloak
  wait_for_mcp_success security-keycloak-outage "${sales_token}" "${rpc}"
  curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --header "Authorization: Bearer ${api_token}" "${API_BASE}/api/v1/me" \
    >"${TEMP_DIR}/rest-keycloak-outage.status"
  curl --silent --show-error --output "${TEMP_DIR}/readiness-outage.json" --write-out '%{http_code}' \
    "${API_BASE}/actuator/health/readiness" >"${TEMP_DIR}/readiness-outage.status"
  compose logs --no-color --since "${phase2_since}" backend >"${TEMP_DIR}/backend-phase2-warm.log" 2>&1
  compose up --detach --no-deps --force-recreate backend
  wait_for_health backend
  [[ "$(mcp_capture security-keycloak-cold "${sales_token}" "${MCP_ORIGIN}" "${rpc}")" == "401" ]]
  curl --fail --silent --show-error \
    "${API_BASE}/actuator/health/mcp" >"${TEMP_DIR}/health-authorization-server.json"
  curl --silent --show-error --output "${TEMP_DIR}/readiness-cold.json" --write-out '%{http_code}' \
    "${API_BASE}/actuator/health/readiness" >"${TEMP_DIR}/readiness-cold.status"
  compose start keycloak
  wait_for_health keycloak
  recovered_token="$(oidc_token mcp-recovery north.sales "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
    "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}")"
  wait_for_mcp_success security-keycloak-recovery "${recovered_token}" "${rpc}" 401
  curl --silent --show-error --output "${TEMP_DIR}/readiness-recovery.json" --write-out '%{http_code}' \
    "${API_BASE}/actuator/health/readiness" >"${TEMP_DIR}/readiness-recovery.status"
  curl --fail --silent --show-error "${API_BASE}/actuator/prometheus" >"${TEMP_DIR}/prometheus.txt"
  compose logs --no-color --since "${phase2_since}" backend >"${TEMP_DIR}/backend-phase2-cold.log" 2>&1
  python3 - "${TEMP_DIR}" "${MCP_RESOURCE}" "${MCP_METADATA_ID}" \
    "${KEYCLOAK_URL}/realms/cellarbridge" "${ROOT_DIR}/target/mcp-production-security" <<'PY'
from pathlib import Path
from datetime import datetime, timezone
import base64, hashlib, json, sys
r = Path(sys.argv[1])
load = lambda name: json.loads((r / (name if name.endswith(".json") else name + ".json")).read_text())
text = lambda name: (r / name).read_text(encoding="utf-8")
metadata = {"resource": sys.argv[2], "authorization_servers": [sys.argv[4]],
    "bearer_methods_supported": ["header"], "scopes_supported": ["mcp:read"]}
assert text("metadata-forwarded.status") == text("metadata-host.status") == "200"
assert load("metadata-forwarded") == load("metadata-host") == metadata
metric_total = lambda name: sum(float(line.rsplit(" ", 1)[1]) for line in text(name).splitlines()
    if line.startswith("cellarbridge_mcp_requests_total{"))
assert metric_total("metadata-metrics-before.txt") == metric_total("metadata-metrics-after.txt")
assert text("api-console.status") == "200" and text("api-mcp.status") == "401"
assert text("rest-during-statement.status") == text("rest-keycloak-outage.status") == "200"
assert text("readiness-outage.status") == text("readiness-cold.status") == text("readiness-recovery.status") == "200"
assert load("health-authorization-server")["status"] == "AUTHORIZATION_SERVER_UNAVAILABLE"
for name, error in (("security-unauthenticated", None), ("security-api-token", "invalid_token"),
                    ("security-expired", "invalid_token"), ("security-wrong-issuer", "invalid_token"),
                    ("security-keycloak-cold", "invalid_token")):
    challenge = text(f"{name}.headers")
    assert f'resource_metadata="{sys.argv[3]}"' in challenge and 'scope="mcp:read"' in challenge, (name, challenge)
    assert (f'error="{error}"' in challenge) if error else ('error=' not in challenge)
assert 'error="insufficient_scope"' in text("security-no-scope.headers")
for name, code in (("security-length", -32001), ("security-chunked", -32001),
                   ("security-request-capacity", -32003),
                   ("security-statement-capacity", -32003), ("security-rate", -32002)):
    assert load(name)["error"]["code"] == code
assert "Retry-After: 1" in text("security-rate.headers")
for phase in ("request", "statement"):
    assert load(f"health-{phase}-bulkhead")["status"] == "BULKHEAD_SATURATED"
    assert load(f"health-{phase}-timeout")["status"] == "DOWNSTREAM_TIMEOUT"
    assert "result" in load(f"security-{phase}-recovered")
assert load("security-request-timeout")["error"]["code"] == -32004
assert load("security-statement-timeout")["result"]["structuredContent"]["code"] == "DOWNSTREAM_TIMEOUT"
metrics = text("prometheus-ingress.txt") + text("prometheus-rate.txt") + text("prometheus.txt")
series = lambda name: sum(line.startswith("cellarbridge_mcp_") for line in text(name).splitlines())
assert series("cardinality-before.txt") == series("cardinality-after.txt")
for name in ("cellarbridge_mcp_requests_total", "cellarbridge_mcp_latency_seconds",
             "cellarbridge_mcp_response_bytes"):
    assert name in metrics
for rejection in ("invalid_host", "invalid_origin", "body_too_large", "rate_limited", "bulkhead"):
    assert f'rejection="{rejection}"' in metrics, rejection
assert "north.sales" not in metrics
def jwt(name):
    token = load(name)["access_token"]; part = token.split(".")[1]
    return token, json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
sales, sales_claims = jwt("mcp-sales.token.json")
_, refreshed_claims = jwt("mcp-sales.refresh.json")
api, api_claims = jwt("api-sales.token.json")
_, wrong_issuer_claims = jwt("mcp-wrong-issuer.token.json")
for claims in (sales_claims, refreshed_claims):
    assert claims["iss"] == sys.argv[4] and claims["resource"] == sys.argv[2]
    assert claims["azp"] == "cellarbridge-mcp-host" and sys.argv[2] in claims["aud"]
assert api_claims["azp"] == "cellarbridge-console" and "cellarbridge-api" in api_claims["aud"]
assert wrong_issuer_claims["iss"] != sys.argv[4]
current = load("current_user.json")["result"]["structuredContent"]["data"]
hash16 = lambda value: hashlib.sha256(value.encode()).hexdigest()[:16]
hashes = (hash16(sales_claims["sub"]), hash16(current["tenant"]["id"]))
logs = text("backend-phase1.log") + text("backend-phase2-warm.log") + text("backend-phase2-cold.log")
sensitive = [value for file in r.glob("*.*.json") for key, value in load(file.name).items()
             if key in {"access_token", "refresh_token", "id_token"} and isinstance(value, str)] + list(hashes)
for forbidden in (*sensitive, "SELECT ", "INSERT ", "java.lang.", " at com.",
                  "clientInfo", "CB_CARDINALITY_", "Authorization: Bearer", "Bearer eyJ"):
    assert forbidden not in logs, "credential_or_identity" if forbidden in sensitive else forbidden
artifact = Path(sys.argv[5]); artifact.mkdir(parents=True, exist_ok=True)
for old in artifact.glob("*"): old.unlink() if old.is_file() else None
evidence = ("metadata-forwarded.json", "metadata-host.json", "health-request-timeout.json",
            "health-statement-timeout.json", "health-authorization-server.json",
            "readiness-outage.json", "cardinality-after.txt")
manifest = {"schemaVersion": "1.0", "result": "PASS",
    "generatedAt": datetime.now(timezone.utc).isoformat(), "protocolVersion": "2025-11-25",
    "resource": sys.argv[2], "authorizationServer": sys.argv[4],
    "controls": ["pkce-resource-binding", "api-mcp-token-isolation",
        "ingress-limits", "per-client-bulkhead", "global-bulkhead", "request-deadline",
        "postgres-statement-timeout", "same-process-recovery", "rate-limit",
        "signed-token-validation", "authorization-server-outage", "bounded-telemetry",
        "log-leak-scan"],
    "evidenceSha256": {name: hashlib.sha256((r / name).read_bytes()).hexdigest()
        for name in evidence}}
(artifact / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print("PASS mcp-production-security manifest=" + str(artifact / "manifest.json"))
PY
}
trap cleanup EXIT
mode="${1:-smoke}"
if [[ "${mode}" != "smoke" && "${mode}" != "--conformance" && "${mode}" != "--production-security" ]]; then
  printf 'Usage: %s [--conformance|--production-security]\n' "$0" >&2
  exit 2
fi
export CELLARBRIDGE_MCP_ENABLED=true
if [[ "${mode}" == "--production-security" ]]; then
  export CELLARBRIDGE_MCP_RATE_CAPACITY=160
  export CELLARBRIDGE_MCP_RATE_REFILL_PER_SECOND=1
  export CELLARBRIDGE_MCP_REQUEST_TIMEOUT=PT2S
  export CELLARBRIDGE_MCP_STATEMENT_TIMEOUT=PT4S
fi
compose down --volumes --remove-orphans >/dev/null 2>&1 || true
TEMP_DIR="$(mktemp -d /tmp/cellarbridge-mcp-smoke.XXXXXX)"
compose up --detach --build postgres keycloak backend
wait_for_health postgres
wait_for_health keycloak
wait_for_health backend
sales_token="$(oidc_token mcp-sales north.sales "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
  "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}")"
buyer_token="$(oidc_token mcp-buyer north.buyer "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
  "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}")"
harbor_token="$(oidc_token mcp-harbor harbor.manager "${MCP_CLIENT_ID}" "${MCP_REDIRECT_URI}" \
  "${MCP_SCOPE}" "${MCP_RESOURCE}" "${MCP_RESOURCE}")"
assert_http_status 401 "" "${MCP_ORIGIN}"
assert_http_status 403 "${sales_token}" "https://invalid.example"
mcp_post initialize "${sales_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"initialize","method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"cellarbridge-mcp-smoke","version":"1.0"}}}'
mcp_post tools "${sales_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}'
mcp_post resources "${sales_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"resources","method":"resources/list","params":{}}'
mcp_post resource_templates "${sales_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"resource-templates","method":"resources/templates/list","params":{}}'
mcp_post prompts "${sales_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"prompts","method":"prompts/list","params":{}}'
mcp_post current_user "${sales_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"current-user","method":"tools/call","params":{"name":"cellarbridge_current_user","arguments":{}}}'
mcp_post harbor_user "${harbor_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"harbor-user","method":"tools/call","params":{"name":"cellarbridge_current_user","arguments":{}}}'
mcp_post session "${sales_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"session","method":"resources/read","params":{"uri":"cellarbridge://session/me"}}'
mcp_post buyer_supply "${buyer_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"buyer-supply","method":"tools/call","params":{"name":"cellarbridge_search_supply","arguments":{"keyword":"Moonlit Terrace"}}}'
python3 - "${TEMP_DIR}" <<'PY'
from pathlib import Path
import json, sys
root = Path(sys.argv[1])
def load(name):
    return json.loads((root / f"{name}.json").read_text(encoding="utf-8"))
initialize = load("initialize")["result"]
assert initialize["protocolVersion"] == "2025-11-25"
assert initialize["serverInfo"]["name"] == "cellarbridge-operations"
expected_tools = {
    "cellarbridge_current_user", "cellarbridge_search_supply", "cellarbridge_list_work_items",
    "cellarbridge_get_dashboard", "cellarbridge_get_timeline", "cellarbridge_search_audit",
}
tools = load("tools")["result"]["tools"]
assert {tool["name"] for tool in tools} == expected_tools
for tool in tools:
    annotations = tool["annotations"]
    assert annotations["readOnlyHint"] is True and annotations["destructiveHint"] is False
    assert annotations["openWorldHint"] is False
assert {x["uri"] for x in load("resources")["result"]["resources"]} == {"cellarbridge://session/me"}
assert {x["uriTemplate"] for x in load("resource_templates")["result"]["resourceTemplates"]} == {
    "cellarbridge://catalog/skus/{skuId}",
    "cellarbridge://timeline/{subjectType}/{subjectId}",
}
assert {x["name"] for x in load("prompts")["result"]["prompts"]} == {
    "cellarbridge_daily_operations_brief",
    "cellarbridge_supply_search_brief",
    "cellarbridge_trace_business_history",
}
current_user = load("current_user")["result"]["structuredContent"]
harbor_user = load("harbor_user")["result"]["structuredContent"]
assert current_user["isError"] is False and current_user["sourceKind"] == "SESSION"
assert current_user["data"]["displayName"] and current_user["data"]["tenant"]["status"] == "ACTIVE"
assert "Sales Representative" in current_user["data"]["roles"]
assert harbor_user["data"]["tenant"]["id"] != current_user["data"]["tenant"]["id"]
session = json.loads(load("session")["result"]["contents"][0]["text"])
assert session["isError"] is False and session["data"]["userId"] == current_user["data"]["userId"]
buyer_supply = load("buyer_supply")["result"]["structuredContent"]
assert buyer_supply["isError"] is True and buyer_supply["code"] == "ACCESS_DENIED"
for response in root.glob("*.json"):
    value = response.read_text(encoding="utf-8")
    for forbidden in ("Authorization:", "Bearer eyJ", "SELECT ", "INSERT ", "java.lang.", "at com."):
        assert forbidden not in value
print("PASS mcp-smoke protocol=2025-11-25 tools=6 resources=3 prompts=3 auth=real-oidc")
PY
if compose logs backend 2>/dev/null | grep -Fq "${sales_token}"; then
  printf '%s\n' 'The access token appeared in backend logs.' >&2
  exit 1
fi
if [[ "${mode}" != "smoke" ]]; then
  run_conformance "${sales_token}"
fi
if [[ "${mode}" == "--production-security" ]]; then
  run_production_security
fi
