#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/core.compose.yaml"
ENV_FILE="${ROOT_DIR}/.env.example"
PROJECT_NAME="cellarbridge-mcp-smoke"
MCP_URL="${CB_MCP_URL:-http://localhost:8080/mcp}"
KEYCLOAK_URL="${CB_KEYCLOAK_URL:-http://localhost:8081}"
MCP_ORIGIN="${CB_MCP_ORIGIN:-http://localhost:5173}"
DEMO_PASSWORD="${CB_DEMO_PASSWORD:-CellarBridge-Demo-2026!}"
PROTOCOL_VERSION="2025-11-25"
TEMP_DIR=""
PROXY_PID=""

compose() {
  docker compose \
    --project-name "${PROJECT_NAME}" \
    --env-file "${ENV_FILE}" \
    --file "${COMPOSE_FILE}" \
    "$@"
}

cleanup() {
  if [[ -n "${PROXY_PID}" ]]; then
    kill "${PROXY_PID}" >/dev/null 2>&1 || true
    wait "${PROXY_PID}" 2>/dev/null || true
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  if [[ -n "${TEMP_DIR}" && "${TEMP_DIR}" == /tmp/cellarbridge-mcp-smoke.* && -d "${TEMP_DIR}" ]]; then
    find "${TEMP_DIR}" -depth -delete
  fi
}

run_conformance() {
  local access_token="$1"
  local output_dir="${CB_MCP_CONFORMANCE_OUTPUT:-${ROOT_DIR}/target/mcp-conformance}"
  local proxy_port="${CB_MCP_PROXY_PORT:-}"
  local proxy_url="http://127.0.0.1:${proxy_port}/mcp"
  local proxy_ready="false"
  local scenarios=(
    server-initialize
    ping
    tools-list
    resources-list
    prompts-list
  )

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
  if [[ "${proxy_ready}" != "true" ]]; then
    printf '%s\n' 'The MCP conformance authentication proxy did not become ready.' >&2
    return 1
  fi

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
  local service="$1"
  local container_id
  container_id="$(compose ps -q "${service}")"
  for _ in {1..90}; do
    if [[ -n "${container_id}" ]] \
      && [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")" == "healthy" ]]; then
      return 0
    fi
    sleep 2
  done
  compose logs "${service}"
  return 1
}

oidc_token() {
  local username="$1"
  local suffix="${username//./-}"
  local cookie_jar="${TEMP_DIR}/${suffix}.cookies"
  local login_page="${TEMP_DIR}/${suffix}.login.html"
  local login_headers="${TEMP_DIR}/${suffix}.login.headers"
  local token_response="${TEMP_DIR}/${suffix}.token.json"
  local verifier
  local challenge
  local login_action
  local authorization_code

  verifier="$(openssl rand -base64 72 | tr -d '\n=+/' | cut -c1-80)"
  challenge="$(
    printf '%s' "${verifier}" \
      | openssl dgst -sha256 -binary \
      | openssl base64 -A \
      | tr '+/' '-_' \
      | tr -d '='
  )"

  curl --fail --silent --show-error \
    --cookie-jar "${cookie_jar}" \
    --output "${login_page}" \
    --get "${KEYCLOAK_URL}/realms/cellarbridge/protocol/openid-connect/auth" \
    --data-urlencode "client_id=cellarbridge-console" \
    --data-urlencode "redirect_uri=http://localhost:5173/app" \
    --data-urlencode "response_type=code" \
    --data-urlencode "scope=openid profile" \
    --data-urlencode "state=cellarbridge-mcp-smoke" \
    --data-urlencode "nonce=cellarbridge-mcp-smoke" \
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
    python3 - "${login_headers}" <<'PY'
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import sys

locations = [
    line.split(":", 1)[1].strip()
    for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
    if line.lower().startswith("location:")
]
if not locations:
    raise SystemExit("Keycloak did not return an authorization redirect.")
query = parse_qs(urlparse(locations[-1]).query)
if query.get("state") != ["cellarbridge-mcp-smoke"] or "code" not in query:
    raise SystemExit("Keycloak authorization redirect was invalid.")
print(query["code"][0])
PY
  )"

  curl --fail --silent --show-error \
    --output "${token_response}" \
    --data-urlencode "grant_type=authorization_code" \
    --data-urlencode "client_id=cellarbridge-console" \
    --data-urlencode "redirect_uri=http://localhost:5173/app" \
    --data-urlencode "code=${authorization_code}" \
    --data-urlencode "code_verifier=${verifier}" \
    "${KEYCLOAK_URL}/realms/cellarbridge/protocol/openid-connect/token"

  python3 - "${token_response}" <<'PY'
from pathlib import Path
import json
import sys

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
token = payload.get("access_token")
if payload.get("token_type") != "Bearer" or not token:
    raise SystemExit("Keycloak token response did not contain a Bearer access token.")
print(token)
PY
}

mcp_post() {
  local output_name="$1"
  local access_token="$2"
  local origin="$3"
  local payload="$4"
  local output_file="${TEMP_DIR}/${output_name}.json"
  local http_status

  http_status="$(
    curl --silent --show-error \
      --output "${output_file}" \
      --write-out '%{http_code}' \
      --request POST "${MCP_URL}" \
      --header "Authorization: Bearer ${access_token}" \
      --header "Origin: ${origin}" \
      --header "Content-Type: application/json" \
      --header "Accept: application/json, text/event-stream" \
      --header "MCP-Protocol-Version: ${PROTOCOL_VERSION}" \
      --data "${payload}"
  )"
  if [[ "${http_status}" != "200" ]]; then
    printf 'MCP request %s returned HTTP %s.\n' "${output_name}" "${http_status}" >&2
    return 1
  fi
}

assert_http_status() {
  local expected="$1"
  local access_token="$2"
  local origin="$3"
  local status
  local request_headers=(
    --header "Origin: ${origin}"
    --header "Content-Type: application/json"
    --header "Accept: application/json, text/event-stream"
    --header "MCP-Protocol-Version: ${PROTOCOL_VERSION}"
  )
  if [[ -n "${access_token}" ]]; then
    request_headers+=(--header "Authorization: Bearer ${access_token}")
  fi
  status="$(
    curl --silent --show-error \
      --output /dev/null \
      --write-out '%{http_code}' \
      --request POST "${MCP_URL}" \
      "${request_headers[@]}" \
      --data '{"jsonrpc":"2.0","id":"status-check","method":"tools/list","params":{}}'
  )"
  if [[ "${status}" != "${expected}" ]]; then
    printf 'Expected HTTP %s but received %s.\n' "${expected}" "${status}" >&2
    return 1
  fi
}

trap cleanup EXIT
mode="${1:-smoke}"
if [[ "${mode}" != "smoke" && "${mode}" != "--conformance" ]]; then
  printf 'Usage: %s [--conformance]\n' "$0" >&2
  exit 2
fi
compose down --volumes --remove-orphans >/dev/null 2>&1 || true
TEMP_DIR="$(mktemp -d /tmp/cellarbridge-mcp-smoke.XXXXXX)"
compose up --detach --build postgres keycloak backend
wait_for_health postgres
wait_for_health keycloak
wait_for_health backend

oidc_token north.sales >"${TEMP_DIR}/north-sales.access-token"
sales_token="$(<"${TEMP_DIR}/north-sales.access-token")"
oidc_token north.buyer >"${TEMP_DIR}/north-buyer.access-token"
buyer_token="$(<"${TEMP_DIR}/north-buyer.access-token")"

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
mcp_post session "${sales_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"session","method":"resources/read","params":{"uri":"cellarbridge://session/me"}}'
mcp_post buyer_supply "${buyer_token}" "${MCP_ORIGIN}" \
  '{"jsonrpc":"2.0","id":"buyer-supply","method":"tools/call","params":{"name":"cellarbridge_search_supply","arguments":{"keyword":"Moonlit Terrace"}}}'

python3 - "${TEMP_DIR}" <<'PY'
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])

def load(name):
    return json.loads((root / f"{name}.json").read_text(encoding="utf-8"))

initialize = load("initialize")["result"]
assert initialize["protocolVersion"] == "2025-11-25"
assert initialize["serverInfo"]["name"] == "cellarbridge-operations"

expected_tools = {
    "cellarbridge_current_user",
    "cellarbridge_search_supply",
    "cellarbridge_list_work_items",
    "cellarbridge_get_dashboard",
    "cellarbridge_get_timeline",
    "cellarbridge_search_audit",
}
tools = load("tools")["result"]["tools"]
assert {tool["name"] for tool in tools} == expected_tools
for tool in tools:
    annotations = tool["annotations"]
    assert annotations["readOnlyHint"] is True
    assert annotations["destructiveHint"] is False
    assert annotations["openWorldHint"] is False

resources = load("resources")["result"]["resources"]
assert {resource["uri"] for resource in resources} == {"cellarbridge://session/me"}
templates = load("resource_templates")["result"]["resourceTemplates"]
assert {template["uriTemplate"] for template in templates} == {
    "cellarbridge://catalog/skus/{skuId}",
    "cellarbridge://timeline/{subjectType}/{subjectId}",
}
prompts = load("prompts")["result"]["prompts"]
assert {prompt["name"] for prompt in prompts} == {
    "cellarbridge_daily_operations_brief",
    "cellarbridge_supply_search_brief",
    "cellarbridge_trace_business_history",
}

current_user = load("current_user")["result"]["structuredContent"]
assert current_user["isError"] is False
assert current_user["sourceKind"] == "SESSION"
assert current_user["data"]["displayName"]
assert current_user["data"]["tenant"]["status"] == "ACTIVE"
assert "Sales Representative" in current_user["data"]["roles"]

session_text = load("session")["result"]["contents"][0]["text"]
session = json.loads(session_text)
assert session["isError"] is False
assert session["data"]["userId"] == current_user["data"]["userId"]

buyer_supply = load("buyer_supply")["result"]["structuredContent"]
assert buyer_supply["isError"] is True
assert buyer_supply["code"] == "ACCESS_DENIED"

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

if [[ "${mode}" == "--conformance" ]]; then
  run_conformance "${sales_token}"
fi
