#!/usr/bin/env python3
"""MCP smoke runner, production-security verifier, and loopback auth proxy."""
import base64, hashlib, json, os, secrets, shutil, socket, subprocess, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from html.parser import HTMLParser
from http.client import HTTPConnection, HTTPResponse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import urlopen
from uuid import UUID
if not __debug__: raise SystemExit("MCP verification does not support optimized Python mode")
PROTOCOL = "2025-11-25"
RESOURCE = "https://mcp.cellarbridge.example/mcp"
HOST_CLIENT, PROBE_CLIENT = "cellarbridge-mcp-host", "cellarbridge-mcp-probe"
UNTRUSTED_CLIENT = "cellarbridge-mcp-untrusted"
REDIRECT, ORIGIN = "http://localhost:5173/app", "http://localhost:5173"
SCOPE = "openid profile mcp:read"
LIST_RPC = {"jsonrpc": "2.0", "id": "security", "method": "tools/list", "params": {}}
SUPPLY_RPC = { "jsonrpc": "2.0", "id": "slow", "method": "tools/call", "params":
    {"name": "cellarbridge_search_supply", "arguments": {"keyword": "Moonlit Terrace"}}, }
CURRENT_RPC = { "jsonrpc": "2.0", "id": "trace", "method": "tools/call",
    "params": {"name": "cellarbridge_current_user", "arguments": {}}, }
def encoded(value): return json.dumps(value, separators=(",", ":"))
def load(root, name):
    path = Path(root) / (name if name.endswith(".json") else f"{name}.json")
    return json.loads(path.read_text(encoding="utf-8"))
def text(root, name): return (Path(root) / name).read_text(encoding="utf-8")
def write(root, name, value): (Path(root) / name).write_text(value, encoding="utf-8")
def response_header(root, name, key):
    values = [line.split(":", 1)[1].strip() for line in text(root, f"{name}.headers").splitlines()
              if line.lower().startswith(f"{key.lower()}:")]
    assert values
    return values[-1]
def claims(payload):
    part = payload["access_token"].split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
def has_audience(token, expected): return token.get("aud", []) in (expected, [expected])
def is_error_response(payload):
    if payload.get("error") is not None: return True
    result = payload.get("result", {})
    if not isinstance(result, dict): return False
    candidates = [result.get("structuredContent")]
    candidates += [item.get("text") for key in ("content", "contents") for item in (result.get(key) or []) if isinstance(item, dict)]
    for candidate in candidates:
        try: envelope = json.loads(candidate) if isinstance(candidate, str) else candidate
        except json.JSONDecodeError: continue
        if isinstance(envelope, dict) and envelope.get("isError"): return True
    return bool(result.get("isError"))
def verify_basic(root):
    initialize, tools = load(root, "initialize")["result"], load(root, "tools")["result"]["tools"]
    assert initialize["protocolVersion"] == PROTOCOL
    assert initialize["serverInfo"]["name"] == "cellarbridge-operations"
    expected_tools = {
        "cellarbridge_current_user", "cellarbridge_search_supply", "cellarbridge_list_work_items",
        "cellarbridge_get_dashboard", "cellarbridge_get_timeline", "cellarbridge_search_audit", }
    assert {tool["name"] for tool in tools} == expected_tools
    assert all(tool["annotations"]["readOnlyHint"] and not tool["annotations"]["destructiveHint"]
               and not tool["annotations"]["openWorldHint"] for tool in tools)
    discovery = { "resources": ("resources", "uri", {"cellarbridge://session/me"}),
        "resource_templates": ("resourceTemplates", "uriTemplate", {
            "cellarbridge://catalog/skus/{skuId}", "cellarbridge://timeline/{subjectType}/{subjectId}", }),
        "prompts": ("prompts", "name", { "cellarbridge_daily_operations_brief",
            "cellarbridge_supply_search_brief", "cellarbridge_trace_business_history", }), }
    for name, (collection, key, expected) in discovery.items():
        assert {item[key] for item in load(root, name)["result"][collection]} == expected
    current, harbor = (load(root, name)["result"]["structuredContent"]
                       for name in ("current_user", "harbor_user"))
    assert not current["isError"] and current["sourceKind"] == "SESSION"
    assert current["data"]["displayName"] and current["data"]["tenant"]["status"] == "ACTIVE"
    assert "Sales Representative" in current["data"]["roles"]
    assert harbor["data"]["tenant"]["id"] != current["data"]["tenant"]["id"]
    session = json.loads(load(root, "session")["result"]["contents"][0]["text"])
    assert not session["isError"] and session["data"]["userId"] == current["data"]["userId"]
    assert load(root, "buyer_supply")["result"]["structuredContent"]["code"] == "ACCESS_DENIED"
    forbidden = ("Authorization:", "Bearer eyJ", "SELECT ", "INSERT ", "java.lang.", "at com.")
    assert not any(marker in path.read_text(encoding="utf-8")
                   for path in Path(root).glob("*.json") for marker in forbidden)
    print("PASS mcp-smoke protocol=2025-11-25 tools=6 resources=3 prompts=3 auth=real-oidc")
def find_span(payload, span_id):
    resources = payload.get("trace", payload).get("resourceSpans", [])
    for resource in resources:
        for scope in resource.get("scopeSpans", []):
            for span in scope.get("spans", []):
                value = span["spanId"]
                is_hex = len(value) == 16 and all(c in "0123456789abcdefABCDEF" for c in value)
                if (value.lower() if is_hex else base64.b64decode(value).hex()) == span_id.lower():
                    return span
    raise AssertionError(f"span {span_id} was not exported")
def fetch_trace(tempo, trace_id, span_id):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            with urlopen(f"{tempo}/api/v2/traces/{trace_id}", timeout=3) as response:
                payload = json.load(response)
                find_span(payload, span_id)
                return payload
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError, AssertionError):
            time.sleep(0.5)
    raise AssertionError(f"Tempo did not return trace {trace_id} before the deadline")
def trace_ids(logs, correlations):
    found = {}
    for line in logs.splitlines():
        try:
            record = json.loads(line[line.index("{"):])
        except (ValueError, json.JSONDecodeError):
            continue
        if not str(record.get("message", "")).startswith("mcpRequestCompleted"):
            continue
        for label, correlation in correlations.items():
            if record.get("correlationId") == correlation:
                found[label] = { "traceId": record.get("trace.id") or record.get("traceId"),
                    "spanId": record.get("span.id") or record.get("spanId"), }
    assert found.keys() == correlations.keys()
    assert all(item["traceId"] and item["spanId"] for item in found.values())
    return found
def verify_production(root, metadata_id, issuer, artifact, tempo, correlations):
    metadata = {"resource": RESOURCE, "authorization_servers": [issuer],
        "bearer_methods_supported": ["header"], "scopes_supported": ["mcp:read"]}
    assert text(root, "metadata-forwarded.status") == text(root, "metadata-host.status") == "200"
    assert load(root, "metadata-forwarded") == load(root, "metadata-host") == metadata
    def total(name):
        return sum(float(line.rsplit(" ", 1)[1]) for line in text(root, name).splitlines()
                   if line.startswith("cellarbridge_mcp_requests_total{"))
    assert total("metadata-metrics-before.txt") == total("metadata-metrics-after.txt")
    assert load(root, "health-authorization-server")["status"] == "AUTHORIZATION_SERVER_UNAVAILABLE"
    assert load(root, "health-admission-rate")["status"] == "RATE_LIMITED"
    assert "cache-control: no-store" in text(
        root, "metadata-resource-misconfigured.headers").lower()
    challenges = {"security-unauthenticated": None, "security-api-token": "invalid_token",
                  "security-untrusted-client": "invalid_token",
                  "security-bad-signature": "invalid_token", "security-expired": "invalid_token",
                  "security-wrong-issuer": "invalid_token"}
    for name, error in challenges.items():
        header = text(root, f"{name}.headers")
        assert f'resource_metadata="{metadata_id}"' in header and 'scope="mcp:read"' in header
        assert (f'error="{error}"' in header) if error else "error=" not in header
    assert 'error="insufficient_scope"' in text(root, "security-no-scope.headers")
    errors = {"security-length": -32001, "security-chunked": -32001, "security-rate": -32002,
        "security-request-timeout": -32004, "security-request-client-capacity": -32003,
        "security-statement-global-capacity": -32003, "security-resource-misconfigured": -32003,
        "security-pool-misconfigured": -32003, "security-keycloak-cold": -32003}
    assert all(load(root, name)["error"]["code"] == code for name, code in errors.items())
    successes = ("security-request-peer", "security-request-transport-recovered", "security-native",
                 "security-refreshed", "security-refresh-final", "security-pool-valid")
    assert all("result" in load(root, name) for name in successes)
    assert "Retry-After: 1" in text(root, "security-rate.headers")
    for phase in ("request", "statement"):
        assert load(root, f"health-{phase}-bulkhead")["status"] == "BULKHEAD_SATURATED"
        assert load(root, f"health-{phase}-timeout")["status"] == "DOWNSTREAM_TIMEOUT"
        assert "result" in load(root, f"security-{phase}-recovered")
    assert all(load(root, name)["result"]["structuredContent"]["code"] == "DOWNSTREAM_TIMEOUT"
               for name in ("security-request-client-slow", "security-statement-timeout"))
    metrics = "".join(text(root, name)
                      for name in ("prometheus-ingress.txt", "prometheus-rate.txt", "prometheus.txt"))
    series = lambda name: sum(line.startswith("cellarbridge_mcp_")
                              for line in text(root, name).splitlines())
    assert series("cardinality-before.txt") == series("cardinality-after.txt")
    metric_names = ("cellarbridge_mcp_requests_total", "cellarbridge_mcp_latency_seconds",
                    "cellarbridge_mcp_response_bytes")
    rejections = ("invalid_host", "invalid_origin", "body_too_large", "rate_limited",
                  "client_bulkhead", "global_bulkhead")
    assert all(name in metrics for name in metric_names)
    assert all(f'rejection="{reason}"' in metrics for reason in rejections)
    request_samples = [line for line in metrics.splitlines()
                       if line.startswith("cellarbridge_mcp_requests_total{")]
    required_labels = ("protocol", "client", "capability", "outcome", "rejection")
    assert request_samples and all(all(f'{key}="' in line for key in required_labels)
                                   for line in request_samples)
    expected_samples = (('protocol="2025-11-25"', 'client="registered"',
        'capability="cellarbridge_current_user"', 'outcome="success"', 'rejection="none"'),
        ('protocol="2025-11-25"', 'client="unknown"', 'capability="unknown"',
         'outcome="rejected"', 'rejection="invalid_origin"'), )
    assert all(any(all(value in line for value in expected) for line in request_samples)
               for expected in expected_samples)
    def response_sum(name):
        return sum(float(line.rsplit(" ", 1)[1]) for line in text(root, name).splitlines()
                   if line.startswith("cellarbridge_mcp_response_bytes_sum{")
                   and 'rejection="invalid_origin"' in line)
    response_bytes = (Path(root) / "trace-rejection.json").stat().st_size
    metric_bytes = response_sum("prometheus.txt") - response_sum("response-bytes-before.txt")
    assert metric_bytes == response_bytes
    markers = [json.loads(line) for line in text(root, "cardinality-markers.jsonl").splitlines()]
    marker_values = [value for row in markers for value in row.values()]
    assert len(markers) >= 101 and not any(value in metrics for value in marker_values)
    token_files = ("mcp-sales.token.json", "refresh-rotation-2.refresh.json",
                   "api-sales.token.json", "mcp-wrong-issuer.token.json")
    sales, refreshed, api, wrong = (claims(load(root, name)) for name in token_files)
    assert all(token["iss"] == issuer and token["resource"] == RESOURCE
               and token["azp"] == HOST_CLIENT and has_audience(token, RESOURCE)
               for token in (sales, refreshed))
    assert api["azp"] == "cellarbridge-console" and has_audience(api, "cellarbridge-api")
    assert wrong["iss"] != issuer
    untrusted = claims(load(root, "mcp-untrusted.token.json"))
    assert (untrusted["iss"] == issuer and untrusted["resource"] == RESOURCE
            and untrusted["azp"] == UNTRUSTED_CLIENT and has_audience(untrusted, RESOURCE)
            and "mcp:read" in untrusted["scope"].split())
    current = load(root, "current_user")["result"]["structuredContent"]["data"]
    identities = [sales["sub"], current["userId"], current["tenant"]["id"], current.get("displayName"),
                  current["tenant"].get("name"), *current.get("roles", [])]
    identities = [value for value in identities if isinstance(value, str) and value]
    hashes = [hashlib.sha256(value.encode()).hexdigest()[:16] for value in identities]
    log_files = ("backend-lifecycle.log", "service-security.log", "mcp-auth-proxy.log")
    logs = "".join(text(root, name) for name in log_files)
    credentials = text(root, "credentials.values").splitlines()
    internal = [str(Path(root)), str(Path.cwd()), "/workspace/", "/app/app.jar", "backend/src/",
        "Traceback (most recent call last)", "org.postgresql.", "java.lang.", " at com."]
    payload_markers = ["north.sales", "Moonlit Terrace", "Authorization:", "Bearer ", "eyJ",
        "SELECT ", "INSERT ", "clientInfo", '"jsonrpc"', "tools/call"]
    forbidden_logs = credentials + identities + hashes + marker_values + internal + payload_markers
    assert not any(value and value in logs for value in forbidden_logs)
    response_files = [path for path in Path(root).glob("security-*.json") if text(root, f"{path.stem}.status") != "200" or is_error_response(load(root, path.name))]
    response_files += [Path(root) / "trace-rejection.json", Path(root) / "buyer_supply.json"]
    response_files += [path for path in Path(root).glob("*.json") if is_error_response(load(root, path.name))]
    response_files += list(Path(root).glob("*.auth.body"))
    responses = "".join(path.read_text(encoding="utf-8", errors="replace") for path in dict.fromkeys(response_files))
    forbidden_responses = credentials + identities + hashes + marker_values + internal + [value for value in payload_markers if value != '"jsonrpc"']
    assert not any(value and value in responses for value in forbidden_responses)
    expected = { "success": ("registered", "cellarbridge_current_user", "success", "none"),
        "rejection": ("unknown", "unknown", "rejected", "invalid_origin"), }
    forbidden_trace = credentials + identities + hashes + marker_values + list(correlations.values())
    forbidden_trace += ["north.sales", "Moonlit Terrace", '"jsonrpc"', "Authorization:", "Bearer ",
                        ORIGIN, "https://invalid.example", "attacker.example",
                        "Forwarded: host=attacker.example;proto=http"]
    for label, ids in trace_ids(logs, correlations).items():
        payload = fetch_trace(tempo, ids["traceId"], ids["spanId"])
        assert not any(value and value in json.dumps(payload) for value in forbidden_trace)
        span = find_span(payload, ids["spanId"])
        attrs = {item["key"]: next(iter(item["value"].values())) for item in span["attributes"]}
        keys = ("mcp.client.class", "mcp.capability", "mcp.outcome", "mcp.rejection")
        observed, size = tuple(attrs[key] for key in keys), attrs["mcp.response.size.class"]
        assert attrs["mcp.protocol.version"] == PROTOCOL and observed == expected[label]
        actual_bytes = (Path(root) / f"trace-{label}.json").stat().st_size
        expected_size = ("empty" if actual_bytes == 0 else "small" if actual_bytes <= 4096
                         else "medium" if actual_bytes <= 65536 else "large")
        assert size == expected_size
        assert int(span["endTimeUnixNano"]) > int(span["startTimeUnixNano"])
    refreshes = [load(root, name)["refresh_token"] for name in ("mcp-sales.token.json",
        "refresh-rotation-1.refresh.json", "refresh-rotation-2.refresh.json")]
    assert len(set(refreshes)) == 3
    assert load(root, "refresh-replay.refresh.json")["error"] == "invalid_grant"
    assert all(load(root, name)["result"] == "PASS" for name in
               ("admission-before-db", "slow-body", "jwk-timeout", "misconfiguration"))
    assert "disabled" in text(root, "keycloak-feature.txt").lower()
    artifact = Path(artifact)
    shutil.rmtree(artifact, ignore_errors=True)
    artifact.mkdir(parents=True)
    preimages = [ "metadata-forwarded.json", "metadata-host.json", "health-request-timeout.json",
        "health-statement-timeout.json", "health-authorization-server.json", "readiness-outage.json",
        "cardinality-after.txt", "response-bytes-before.txt", "prometheus.txt", "trace-rejection.json",
        "admission-before-db.json", "admission-waiters.txt", "security-scope-before-db.json",
        "security-scope-before-db.status", "security-scope-before-db.headers", "security-rate-before-db.json",
        "security-rate-before-db.status", "security-rate-before-db.headers", "slow-body.json",
        "slow-body.response", "jwk-timeout.json", "security-keycloak-cold.json",
        "security-keycloak-cold.status", "security-keycloak-cold.headers",
        "metadata-resource-misconfigured.status", "metadata-resource-misconfigured.headers",
        "misconfiguration.json", "keycloak-feature.txt", ]
    evidence_files = [Path(root) / name for name in preimages]
    conformance = Path(os.getenv("CB_MCP_CONFORMANCE_OUTPUT", artifact.parent / "mcp-conformance"))
    evidence_files += [path for path in conformance.rglob("*") if path.is_file()]
    archived = "".join(path.read_text(encoding="utf-8", errors="replace") for path in evidence_files)
    forbidden_archive = credentials + identities + hashes + marker_values + internal
    forbidden_archive += [value for value in payload_markers if value not in ('"jsonrpc"', "Bearer ")]
    assert not any(value and value in archived for value in forbidden_archive)
    for name in preimages:
        shutil.copyfile(Path(root) / name, artifact / name)
    manifest = {"schemaVersion": "1.0", "result": "PASS", "protocolVersion": PROTOCOL,
                "resource": RESOURCE, "authorizationServer": issuer, "controls": [
            "pkce-resource-binding", "refresh-rotation-replay", "api-mcp-token-isolation",
            "ingress-limits", "per-client-bulkhead", "global-bulkhead", "request-deadline",
            "postgres-statement-timeout", "same-process-recovery", "rate-limit",
            "signed-token-validation", "authorization-server-outage", "bounded-telemetry",
            "trace-log-correlation", "log-leak-scan", "admission-before-tenant-database",
            "misconfiguration-rest-headroom", "pool-reserve-validation", "slow-upload-timeout",
            "bounded-jwk-timeout", "native-resource-indicators-disabled"], "evidenceSha256": {
            name: hashlib.sha256((artifact / name).read_bytes()).hexdigest() for name in preimages}}
    write(artifact, "manifest.json", json.dumps(manifest, indent=2) + "\n")
    print(f"PASS mcp-production-security manifest={artifact / 'manifest.json'}")
class LoginParser(HTMLParser):
    action = None
    def handle_starttag(self, tag, attrs):
        if tag == "form" and self.action is None:
            self.action = dict(attrs).get("action")
class Smoke:
    def __init__(self, mode):
        assert mode in {"smoke", "--conformance", "--production-security"}
        self.mode, self.root = mode, Path(__file__).resolve().parents[1]
        self.temp = Path(tempfile.mkdtemp(prefix="cellarbridge-mcp-smoke.", dir="/tmp"))
        self.project, self.proxy, self.lock, self.lock_app = "cellarbridge-mcp-smoke", None, None, None
        self.mcp_url = os.getenv("CB_MCP_URL", "http://localhost:8080/mcp")
        self.api = os.getenv("CB_API_BASE", self.mcp_url.removesuffix("/mcp"))
        self.keycloak = os.getenv("CB_KEYCLOAK_URL", "http://localhost:8081")
        self.alt_keycloak = os.getenv("CB_KEYCLOAK_ALT_URL", "http://127.0.0.1:8081")
        self.password, self.env = os.getenv("CB_DEMO_PASSWORD", "CellarBridge-Demo-2026!"), os.environ.copy()
        self.credentials = [self.password] + [ self.env.get(name, fallback) for name, fallback in {
                "KEYCLOAK_ADMIN_PASSWORD": "local-admin-change-me",
                "POSTGRES_PASSWORD": "cellarbridge_local", "KEYCLOAK_DB_PASSWORD": "keycloak_local",
                "GRAFANA_ADMIN_PASSWORD": "replace-before-full-profile", }.items() ]
        self.env["CELLARBRIDGE_MCP_ENABLED"] = "true"
        if mode == "--production-security":
            generated = {name: secrets.token_urlsafe(24) for name in (
                "POSTGRES_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD", "KEYCLOAK_DB_PASSWORD",
                "GRAFANA_ADMIN_PASSWORD")}
            self.env.update(generated)
            self.credentials += list(generated.values())
            self.env.update( SPRING_PROFILES_ACTIVE="demo",
                CELLARBRIDGE_MCP_ALLOWED_CLIENTS=f"{HOST_CLIENT},{PROBE_CLIENT}",
                CELLARBRIDGE_MCP_RATE_CAPACITY="260", CELLARBRIDGE_MCP_RATE_REFILL_PER_SECOND="1",
                CELLARBRIDGE_MCP_REQUEST_TIMEOUT="PT2S", CELLARBRIDGE_MCP_STATEMENT_TIMEOUT="PT4S", )
        self.files = [self.root / "deploy/compose/core.compose.yaml"]
        if mode == "--production-security":
            self.files.append(self.root / "deploy/compose/full.compose.yaml")
    def command(self, args, **kwargs):
        kwargs.setdefault("check", True)
        kwargs.setdefault("text", True)
        kwargs.setdefault("timeout", 180)
        try:
            return subprocess.run(args, **kwargs)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"{Path(args[0]).name} timed out") from None
        except subprocess.CalledProcessError as failure:
            raise RuntimeError(
                f"{Path(args[0]).name} failed with exit {failure.returncode}") from None
    def compose_args(self, *args):
        command = ["docker", "compose", "--project-name", self.project,
                   "--env-file", str(self.root / ".env.example")]
        for path in self.files:
            command += ["--file", str(path)]
        return command + list(args)
    def compose(self, *args, capture=False, **kwargs):
        if capture:
            kwargs.setdefault("stdout", subprocess.PIPE)
        return self.command(self.compose_args(*args), cwd=self.root, env=self.env, **kwargs)
    def curl(self, url, *, data=(), output=None, headers=None, method=None,
             extra=(), timeout=20, fail=False):
        args = ["curl", "-sS", "--connect-timeout", "2", "--max-time", str(timeout)]
        args += ["--fail"] if fail else []
        args += ["--output", str(output), "--write-out", "%{http_code}"] if output else []
        args += ["--dump-header", str(headers)] if headers else []
        args += ["--request", method] if method else []
        for key, value in data:
            args += ["--data-urlencode", f"{key}={value}"]
        return self.command(args + list(extra) + [url], stdout=subprocess.PIPE).stdout.strip()
    def evidence(self, name, url, **kwargs):
        status = self.curl(url, output=self.temp / f"{name}.json", **kwargs)
        write(self.temp, f"{name}.status", status)
        return status
    def pass_evidence(self, name, **observed):
        write(self.temp, f"{name}.json",
              encoded({"schemaVersion": "1.0", "result": "PASS", **observed}) + "\n")
    def remember(self, *values):
        self.credentials += [value for value in values if isinstance(value, str) and value]
        path = self.temp / "credentials.values"
        path.write_text("\n".join(self.credentials) + "\n", encoding="utf-8")
        path.chmod(0o600)
    def save_tokens(self, payload):
        self.remember(*(payload.get(key) for key in ("access_token", "refresh_token", "id_token")))
    @staticmethod
    def resource(data, value):
        if value == "__duplicate__":
            data += [("resource", RESOURCE), ("resource", RESOURCE)]
        elif value:
            data.append(("resource", value))
    def token(self, name, data, expected=None, suffix="token"):
        output = self.temp / f"{name}.{suffix}.json"
        url = f"{self.keycloak}/realms/cellarbridge/protocol/openid-connect/token"
        status, payload = self.curl(url, data=data, output=output), load(self.temp, output.name)
        if expected:
            assert status == "400" and payload.get("error") == expected
            return None
        assert status == "200" and payload.get("access_token")
        self.save_tokens(payload)
        return payload
    def oidc(self, flow, user, client=HOST_CLIENT, scope=SCOPE, auth_resource=RESOURCE,
             token_resource=RESOURCE, expected=None, verifier_override=None):
        name = flow.replace(".", "-")
        state = f"cellarbridge-{name}-{secrets.token_hex(12)}"
        verifier = secrets.token_urlsafe(64)[:80]
        self.remember(state, verifier, verifier_override)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
        cookies, page, headers = (self.temp / f"{name}.{suffix}"
                                  for suffix in ("cookies", "login.html", "login.headers"))
        data = [ ("client_id", client), ("redirect_uri", REDIRECT), ("response_type", "code"),
            ("scope", scope), ("state", state), ("nonce", state),
            ("code_challenge", challenge), ("code_challenge_method", "S256"), ]
        self.resource(data, auth_resource)
        auth = f"{self.keycloak}/realms/cellarbridge/protocol/openid-connect/auth"
        self.curl(auth, data=data, output=page, extra=("--get", "--cookie-jar", str(cookies)), fail=True)
        parser = LoginParser()
        parser.feed(page.read_text(encoding="utf-8"))
        assert parser.action
        self.curl(parser.action, data=(("username", user), ("password", self.password), ("credentialId", "")),
                  output=Path(os.devnull), headers=headers,
                  extra=("--cookie", str(cookies), "--cookie-jar", str(cookies)))
        locations = [line.split(":", 1)[1].strip() for line in headers.read_text().splitlines()
                     if line.lower().startswith("location:")]
        query = parse_qs(urlsplit(locations[-1]).query)
        assert query.get("state") == [state] and query.get("code")
        self.remember(query["code"][0])
        data = [ ("grant_type", "authorization_code"), ("client_id", client),
            ("redirect_uri", REDIRECT), ("code", query["code"][0]),
            ("code_verifier", verifier_override or verifier), ]
        self.resource(data, token_resource)
        payload = self.token(name, data, expected)
        assert expected or payload.get("token_type") == "Bearer"
        return None if expected else payload["access_token"]
    def refresh(self, name, value, resource=RESOURCE, expected=None):
        data = [("grant_type", "refresh_token"), ("client_id", HOST_CLIENT), ("refresh_token", value)]
        self.resource(data, resource)
        payload = self.token(name, data, expected, "refresh")
        assert expected or payload.get("refresh_token")
        return payload
    def reject_auth(self, name, resource, expected, pkce="S256"):
        body, headers = self.temp / f"{name}.auth.body", self.temp / f"{name}.auth.headers"
        state = f"cellarbridge-{name}-{secrets.token_hex(12)}"
        self.remember(state)
        data = [("client_id", HOST_CLIENT), ("redirect_uri", REDIRECT), ("response_type", "code"),
                ("scope", SCOPE), ("state", state)]
        if pkce != "missing":
            data += [("code_challenge", "A" * 43), ("code_challenge_method", pkce)]
        self.resource(data, resource)
        url = f"{self.keycloak}/realms/cellarbridge/protocol/openid-connect/auth"
        status = self.curl(url, data=data, output=body, headers=headers, extra=("--get",))
        locations = [line.split(":", 1)[1].strip() for line in headers.read_text().splitlines()
                     if line.lower().startswith("location:")]
        error = parse_qs(urlsplit(locations[-1]).query).get("error", [None])[0] if locations else None
        if not error and body.read_text().startswith("{"):
            error = json.loads(body.read_text(encoding="utf-8")).get("error")
        assert status in {"302", "303", "400"} and error == expected
    def mcp(self, name, token, origin, payload, extra=(), timeout=None):
        args = ["--header", "Content-Type: application/json", "--header",
                "Accept: application/json, text/event-stream", "--header",
                f"MCP-Protocol-Version: {PROTOCOL}"]
        args += ["--header", f"Authorization: Bearer {token}"] if token else []
        args += ["--header", f"Origin: {origin}"] if origin else []
        args += list(extra) + ["--data", payload]
        return self.evidence(name, self.mcp_url, headers=self.temp / f"{name}.headers",
                             method="POST", extra=args,
                             timeout=timeout or int(os.getenv("CB_MCP_HTTP_TIMEOUT_SECONDS", "20")))
    def post(self, name, token, payload):
        status = self.mcp(name, token, ORIGIN, payload)
        assert status == "200", (name, status)
    @staticmethod
    def wait(probe, seconds, message, interval=1, accepted=bool, ignored=()):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                observed = probe()
            except ignored:
                observed = None
            if accepted(observed):
                return observed
            time.sleep(interval)
        raise AssertionError(message)
    def wait_success(self, name, token, payload, retry=("429",)):
        status = self.wait(lambda: self.mcp(name, token, ORIGIN, payload), 30,
                           f"{name} did not recover before deadline", 0.2,
                           lambda value: value == "200" or value not in retry)
        assert status == "200", (name, status)
    def scalar(self, sql):
        return self.compose("exec", "-T", "postgres", "psql", "-U", "cellarbridge",
                            "-d", "cellarbridge", "-Atq", "-c", sql, capture=True, timeout=5).stdout.strip()
    def wait_db(self, sql, expected):
        self.wait(lambda: self.scalar(sql), 20, f"PostgreSQL state did not reach {expected!r}",
                  0.2, lambda value: value == expected,
                  (subprocess.CalledProcessError, subprocess.TimeoutExpired))
    def start_lock(self, app, relation):
        assert app.replace("_", "").isalnum()
        assert all(part.replace("_", "").isalnum() for part in relation.split("."))
        self.lock_app = app
        sql = f"BEGIN; LOCK TABLE {relation} IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(120); COMMIT;"
        args = self.compose_args("exec", "-T", "-e", f"PGAPPNAME={app}", "postgres",
                                 "psql", "-U", "cellarbridge", "-d", "cellarbridge",
                                 "-v", "ON_ERROR_STOP=1", "-c", sql)
        log = (self.temp / f"{app}.log").open("w", encoding="utf-8")
        self.lock = subprocess.Popen(args, cwd=self.root, env=self.env, stdout=log,
                                     stderr=subprocess.STDOUT, text=True)
        owned = ("SELECT EXISTS (SELECT 1 FROM pg_stat_activity a JOIN pg_locks l USING(pid) "
                 f"WHERE a.application_name='{app}' AND l.relation='{relation}'::regclass "
                 "AND l.mode='AccessExclusiveLock' AND l.granted)")
        self.wait_db(owned, "t")
    def release_lock(self):
        if not self.lock_app:
            return
        app = self.lock_app
        self.scalar("SELECT count(*) FROM (SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    f"WHERE application_name='{app}' AND pid<>pg_backend_pid()) x")
        try:
            self.lock.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.lock.terminate()
            self.lock.wait(timeout=5)
        self.wait_db(f"SELECT count(*) FROM pg_stat_activity WHERE application_name='{app}'", "0")
        self.lock = self.lock_app = None
    @staticmethod
    def waiter(app, relation):
        return ("SELECT count(*) FROM pg_locks w JOIN pg_stat_activity wa USING(pid) "
                f"WHERE w.relation='{relation}'::regclass AND NOT w.granted "
                "AND wa.wait_event_type='Lock' AND EXISTS (SELECT 1 FROM pg_locks h "
                f"JOIN pg_stat_activity ho USING(pid) WHERE ho.application_name='{app}' "
                "AND h.relation=w.relation AND h.mode='AccessExclusiveLock' AND h.granted)")
    def locked(self, name, token, payload, relation, assertion):
        app, backend = f"cb_mcp_{name.replace('-', '_')}_lock", self.container_id()
        self.start_lock(app, relation)
        try:
            with ThreadPoolExecutor(max_workers=1) as pool:
                request = pool.submit(self.mcp, name, token, ORIGIN, payload)
                self.wait_db(self.waiter(app, relation), "1")
                assertion()
                status = request.result(timeout=20)
        finally:
            self.release_lock()
        assert self.container_id() == backend
        return status, backend
    def container_id(self):
        return self.compose("ps", "-q", "backend", capture=True).stdout.strip()
    def health(self, name):
        self.curl(f"{self.api}/actuator/health/mcp", output=self.temp / f"{name}.json", fail=True)
    def transport_timeout(self, token, payload):
        status, backend = self.locked("security-request-timeout", token, payload,
                                      "identity_access.user_mapping", lambda: None)
        assert status == "504"
        self.health("health-request-timeout")
        self.post("security-request-transport-recovered", token, payload)
        assert self.container_id() == backend
    def database_gate(self, phase, first, second, api_token, slow_payload, peer_payload):
        slow_name = "security-request-client-slow" if phase == "request" else "security-statement-timeout"
        def capacity():
            if phase == "request":
                assert self.mcp("security-request-client-capacity", first, ORIGIN, slow_payload) == "503"
                self.health(f"health-{phase}-bulkhead")
                assert self.mcp("security-request-peer", second, ORIGIN, peer_payload) == "200"
            else:
                assert self.mcp("security-statement-global-capacity", second, ORIGIN, peer_payload) == "503"
                self.health(f"health-{phase}-bulkhead")
                status = self.curl(f"{self.api}/api/v1/me", output=Path(os.devnull),
                                   extra=("--header", f"Authorization: Bearer {api_token}"))
                write(self.temp, "rest-during-statement.status", status)
                assert status == "200"
        status, backend = self.locked(slow_name, first, slow_payload, "catalog.sku", capacity)
        assert status == "200"
        assert load(self.temp, slow_name)["result"]["structuredContent"]["code"] == "DOWNSTREAM_TIMEOUT"
        if phase == "statement":
            self.health("health-statement-timeout")
        self.post(f"security-{phase}-recovered", first, slow_payload)
        assert self.container_id() == backend
    def slow_body(self, token):
        target = urlsplit(self.mcp_url)
        started = time.monotonic()
        with socket.create_connection((target.hostname, target.port or 80), timeout=2) as stream:
            stream.settimeout(5)
            request = (
                f"POST {target.path or '/mcp'} HTTP/1.1\r\nHost: {target.netloc}\r\n"
                f"Authorization: Bearer {token}\r\nContent-Type: application/json\r\n"
                f"Accept: application/json, text/event-stream\r\nMCP-Protocol-Version: {PROTOCOL}\r\n"
                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n100\r\n{"
            )
            stream.sendall(request.encode())
            try:
                for _ in range(20):
                    stream.sendall(b"{")
                    time.sleep(0.15)
            except OSError:
                pass
            try:
                response = HTTPResponse(stream)
                response.begin()
                body = response.read()
            except TimeoutError as exception:
                raise AssertionError("slow chunked upload exceeded its transport deadline") from exception
        elapsed = time.monotonic() - started
        assert 1.5 < elapsed < 4
        assert response.status == 504 and json.loads(body)["error"]["code"] == -32004
        (self.temp / "slow-body.response").write_bytes(body)
        self.pass_evidence("slow-body", elapsedMs=round(elapsed * 1000),
                           transportOutcome=str(response.status))
    def admission_gate(self, no_scope, token, payload):
        app, relation = "cb_mcp_admission_lock", "identity_access.user_mapping"
        self.start_lock(app, relation)
        try:
            started = time.monotonic()
            assert self.mcp("security-scope-before-db", no_scope, ORIGIN, payload) == "403"
            scope_ms = round((time.monotonic() - started) * 1000)
            scope_waiters = int(self.scalar(self.waiter(app, relation)))
            assert scope_ms < 1500 and scope_waiters == 0
            with ThreadPoolExecutor(max_workers=1) as pool:
                blocked = pool.submit(self.mcp, "security-rate-admitted", token, ORIGIN, payload)
                self.wait_db(self.waiter(app, relation), "1")
                waiters_before = int(self.scalar(self.waiter(app, relation)))
                started = time.monotonic()
                assert self.mcp("security-rate-before-db", token, ORIGIN, payload) == "429"
                rate_ms = round((time.monotonic() - started) * 1000)
                waiters_after = int(self.scalar(self.waiter(app, relation)))
                assert rate_ms < 1500 and waiters_after == waiters_before
                self.health("health-admission-rate")
                self.release_lock()
                assert blocked.result(timeout=5) == "200"
        finally:
            self.release_lock()
        write(self.temp, "admission-waiters.txt",
              f"scope={scope_waiters}\nrate-before={waiters_before}\nrate-after={waiters_after}\n")
        self.pass_evidence("admission-before-db", scopeRejectionMs=scope_ms,
            rateRejectionMs=rate_ms, databaseWaitersBeforeRateRejection=waiters_before,
            databaseWaitersAfterRateRejection=waiters_after,
            tenantDatabaseWaitersForRejectedRequests=waiters_after - waiters_before)
    def misconfiguration_gate(self, token, api_token, payload):
        self.recreate_backend(CELLARBRIDGE_MCP_RESOURCE="not-a-uri",
                              CELLARBRIDGE_MCP_AUDIENCE=RESOURCE,
                              CELLARBRIDGE_MCP_MAX_CONCURRENCY="8")
        assert self.mcp("security-resource-misconfigured", token, ORIGIN, payload) == "503"
        assert self.evidence("metadata-resource-misconfigured",
                             f"{self.api}/.well-known/oauth-protected-resource/mcp",
                             headers=self.temp / "metadata-resource-misconfigured.headers") == "503"
        self.health("health-resource-misconfigured")
        assert load(self.temp, "health-resource-misconfigured")["status"] == "MISCONFIGURED"
        assert self.evidence("readiness-resource-misconfigured",
                             f"{self.api}/actuator/health/readiness") == "200"
        assert self.evidence("api-resource-misconfigured", f"{self.api}/api/v1/me",
                             extra=("--header", f"Authorization: Bearer {api_token}")) == "200"
        self.recreate_backend(CELLARBRIDGE_MCP_RESOURCE=RESOURCE,
                              CELLARBRIDGE_MCP_AUDIENCE=RESOURCE,
                              CELLARBRIDGE_DB_POOL_SIZE="10",
                              CELLARBRIDGE_MCP_REST_CONNECTION_RESERVE="2",
                              CELLARBRIDGE_MCP_MAX_CONCURRENCY="9")
        assert self.mcp("security-pool-misconfigured", token, ORIGIN, payload) == "503"
        self.health("health-pool-misconfigured")
        assert load(self.temp, "health-pool-misconfigured")["status"] == "MISCONFIGURED"
        self.recreate_backend(CELLARBRIDGE_MCP_MAX_CONCURRENCY="8")
        self.post("security-pool-valid", token, payload)
        self.pass_evidence("misconfiguration", invalidResourceMcp=503, invalidResourceRest=200,
            invalidResourceReadiness=200, pool10Reserve2RejectedConcurrency=9,
            pool10Reserve2AcceptedConcurrency=8)
    def admin(self, *args, capture=False, input_text=None):
        return self.compose("exec", "-T", "keycloak", "/opt/keycloak/bin/kcadm.sh", *args,
                            "--config", "/tmp/cellarbridge-mcp-smoke-kcadm.config",
                            capture=capture, input=input_text)
    def admin_json(self, *args):
        return json.loads(self.admin(*args, capture=True).stdout)
    def configure_admin(self):
        command = ("/opt/keycloak/bin/kcadm.sh config credentials "
                   "--config /tmp/cellarbridge-mcp-smoke-kcadm.config "
                   "--server http://localhost:8080 --realm master "
                   '--user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"')
        self.compose("exec", "-T", "keycloak", "sh", "-eu", "-c", command, stdout=subprocess.DEVNULL)
    def create_probe_client(self):
        query = ("clients", "-r", "cellarbridge", "-q")
        host = self.admin_json("get", *query, f"clientId={HOST_CLIENT}")[0]
        model = self.admin_json("get", f"clients/{host['id']}", "-r", "cellarbridge")
        for key in ("id", "secret", "registrationAccessToken"):
            model.pop(key, None)
        for mapper in model.get("protocolMappers", []):
            mapper.pop("id", None)
        model.setdefault("attributes", {})["cellarbridge.mcp.resource-enforced"] = "true"
        clients = ((PROBE_CLIENT, "CellarBridge MCP isolation probe"),
                   (UNTRUSTED_CLIENT, "CellarBridge MCP untrusted-client probe"))
        for client, name in clients:
            model.update(clientId=client, name=name)
            for item in self.admin_json("get", *query, f"clientId={client}"):
                self.admin("delete", f"clients/{item['id']}", "-r", "cellarbridge")
            self.admin("create", "clients", "-r", "cellarbridge",
                       "-f", "-", input_text=json.dumps(model))
            created = self.admin_json("get", *query, f"clientId={client}")[0]
            check = self.admin_json("get", f"clients/{created['id']}", "-r", "cellarbridge")
            assert check["attributes"]["cellarbridge.mcp.resource-enforced"] == "true"
    def expired_token(self):
        realm = "realms/cellarbridge"
        lifetime = self.admin_json("get", realm, "--fields", "accessTokenLifespan")["accessTokenLifespan"]
        self.admin("update", realm, "-s", "accessTokenLifespan=-120")
        try:
            token = self.oidc("mcp-expired", "north.sales")
        finally:
            self.admin("update", realm, "-s", f"accessTokenLifespan={lifetime}")
        assert claims(load(self.temp, "mcp-expired.token.json"))["exp"] < time.time() - 60
        return token
    def recreate_backend(self, **settings):
        self.env.update(settings)
        self.log_services("backend-lifecycle.log", "backend", append=True)
        self.compose("up", "-d", "--no-deps", "--force-recreate", "--wait",
                     "--wait-timeout", "180", "backend")
    def log_services(self, name, *services, since=None, append=False):
        args = ["logs", "--no-color"] + (["--since", since] if since else []) + list(services)
        output = self.compose(*args, capture=True, stderr=subprocess.STDOUT).stdout
        with (self.temp / name).open("a" if append else "w", encoding="utf-8") as log:
            log.write(output)
    def metrics(self, name):
        self.curl(f"{self.api}/actuator/prometheus", output=self.temp / name, fail=True)
    def feature_gate(self):
        config = self.compose("exec", "-T", "keycloak", "/opt/keycloak/bin/kc.sh",
                              "show-config", capture=True).stdout
        observed = [line.strip() for line in config.splitlines()
                    if "resource-indicators" in line.lower()]
        assert observed and any("disabled" in line.lower() for line in observed)
        write(self.temp, "keycloak-feature.txt", "\n".join(observed) + "\n")
    def conformance(self, token):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        env = self.env | {"CB_MCP_PROXY_TOKEN": token, "CB_MCP_PROXY_PORT": str(port),
                          "CB_MCP_PROXY_UPSTREAM": "http://127.0.0.1:8080"}
        log = (self.temp / "mcp-auth-proxy.log").open("w", encoding="utf-8")
        self.proxy = subprocess.Popen([sys.executable, __file__, "proxy"], env=env,
                                      stdout=log, stderr=subprocess.STDOUT)
        url = f"http://127.0.0.1:{port}/mcp"
        def ready():
            assert self.proxy.poll() is None
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=1):
                    return True
            except OSError:
                return False
        self.wait(ready, 30, "MCP conformance proxy did not become ready")
        output = Path(os.getenv("CB_MCP_CONFORMANCE_OUTPUT", self.root / "target/mcp-conformance"))
        for scenario in ("server-initialize", "ping", "tools-list", "resources-list", "prompts-list"):
            self.command(["npx", "--yes", "@modelcontextprotocol/conformance@0.1.16", "server",
                          "--url", url, "--scenario", scenario, "--spec-version", PROTOCOL,
                          "--output-dir", str(output / scenario)], timeout=300)
        print(f"PASS mcp-conformance version=0.1.16 protocol={PROTOCOL} scenarios=5 output={output}")
    def basic(self, sales, buyer, harbor):
        assert self.mcp("status-401", "", ORIGIN, encoded(LIST_RPC)) == "401"
        assert self.mcp("status-403", sales, "https://invalid.example", encoded(LIST_RPC)) == "403"
        calls = { "initialize": (sales, "initialize", { "protocolVersion": PROTOCOL, "capabilities": {},
                "clientInfo": {"name": "cellarbridge-mcp-smoke", "version": "1.0"}}),
            "tools": (sales, "tools/list", {}), "resources": (sales, "resources/list", {}),
            "resource_templates": (sales, "resources/templates/list", {}),
            "prompts": (sales, "prompts/list", {}), "current_user": (sales, "tools/call", {
                "name": "cellarbridge_current_user", "arguments": {}}),
            "harbor_user": (harbor, "tools/call", { "name": "cellarbridge_current_user", "arguments": {}}),
            "session": (sales, "resources/read", {"uri": "cellarbridge://session/me"}),
            "buyer_supply": (buyer, "tools/call", SUPPLY_RPC["params"]), }
        for name, (token, method, params) in calls.items():
            self.post(name, token, encoded({
                "jsonrpc": "2.0", "id": name, "method": method, "params": params}))
        verify_basic(self.temp)
    def resource_matrix(self):
        for case in ( ("authorization-missing", "", "invalid_target", "S256"),
            ("authorization-wrong", "https://invalid.example/mcp", "invalid_target", "S256"),
            ("authorization-case", "https://mcp.cellarbridge.example/MCP", "invalid_target", "S256"),
            ("authorization-duplicate", "__duplicate__", "invalid_request", "S256"),
            ("pkce-missing", RESOURCE, "invalid_request", "missing"),
            ("pkce-plain", RESOURCE, "invalid_request", "plain"), ):
            self.reject_auth(*case)
        for index, target in enumerate(( "", "https://invalid.example/mcp",
            "https://mcp.cellarbridge.example/MCP", "__duplicate__", )):
            self.oidc(f"token-negative-{index}", "north.sales", token_resource=target,
                      expected="invalid_request" if target == "__duplicate__" else "invalid_grant")
            source = f"refresh-source-{index}"
            self.oidc(source, "north.sales")
            self.refresh(f"refresh-negative-{index}", load(self.temp, f"{source}.token.json")["refresh_token"],
                         target, "invalid_request" if target == "__duplicate__" else "invalid_target")
    def cardinality(self, sales):
        rows = [{key: f"CB_{key.upper()}_{secrets.token_hex(16)}"
                 for key in ("argument", "correlation", "clientInfo")} for _ in range(101)]
        write(self.temp, "cardinality-markers.jsonl", "".join(encoded(row) + "\n" for row in rows))
        for index, row in enumerate(rows):
            messages = { "init": ("initialize", { "protocolVersion": PROTOCOL, "capabilities": {},
                    "clientInfo": {"name": row["clientInfo"], "version": "1"}}), "call": ("tools/call", {
                    "name": "cellarbridge_search_supply", "arguments": {"keyword": row["argument"]}}), }
            header = ("--header", f"X-Correlation-ID: {row['correlation']}")
            for kind, (method, params) in messages.items():
                payload = encoded({"jsonrpc": "2.0", "id": f"{kind}-{index}",
                                   "method": method, "params": params})
                assert self.mcp(f"cardinality-{kind}-{index}", sales,
                                ORIGIN, payload, extra=header) == "200"
            if index == 0:
                self.metrics("cardinality-before.txt")
        self.metrics("cardinality-after.txt")
    def production(self, sales):
        rpc, supply = encoded(LIST_RPC), encoded(SUPPLY_RPC)
        self.resource_matrix()
        initial = load(self.temp, "mcp-sales.token.json")["refresh_token"]
        rotation1 = self.refresh("refresh-rotation-1", initial)
        rotation2 = self.refresh("refresh-rotation-2", rotation1["refresh_token"])
        self.refresh("refresh-replay", initial, expected="invalid_grant")
        self.post("security-refresh-final", rotation2["access_token"], rpc)
        self.oidc("pkce-wrong-verifier", "north.sales", expected="invalid_grant",
                  verifier_override="wrong-verifier")
        api_token = self.oidc("api-sales", "north.sales", client="cellarbridge-console",
                              scope="openid profile", auth_resource="", token_resource="")
        no_scope = self.oidc("mcp-no-scope", "north.sales", scope="openid profile")
        self.configure_admin()
        expired = self.expired_token()
        self.create_probe_client()
        probe = self.oidc("mcp-probe", "north.sales", client=PROBE_CLIENT)
        untrusted = self.oidc("mcp-untrusted", "north.sales", client=UNTRUSTED_CLIENT)
        trusted, realm = self.keycloak, "realms/cellarbridge"
        self.admin("update", realm, "-s", f"attributes.frontendUrl={self.alt_keycloak}")
        self.keycloak = self.alt_keycloak
        try:
            wrong_issuer = self.oidc("mcp-wrong-issuer", "north.sales")
        finally:
            self.keycloak = trusted
            self.admin("update", realm, "-s", f"attributes.frontendUrl={trusted}")
        self.misconfiguration_gate(sales, api_token, rpc)
        self.recreate_backend(CELLARBRIDGE_MCP_REQUEST_TIMEOUT="PT2S",
                              CELLARBRIDGE_MCP_STATEMENT_TIMEOUT="PT4S",
                              CELLARBRIDGE_MCP_MAX_CONCURRENCY="2",
                              CELLARBRIDGE_MCP_MAX_CLIENT_CONCURRENCY="2",
                              CELLARBRIDGE_MCP_RATE_CAPACITY="1",
                              CELLARBRIDGE_MCP_RATE_REFILL_PER_SECOND="0.01")
        self.slow_body(sales)
        self.admission_gate(no_scope, sales, rpc)
        self.recreate_backend(CELLARBRIDGE_MCP_MAX_CLIENT_CONCURRENCY="1",
                              CELLARBRIDGE_MCP_RATE_CAPACITY="260",
                              CELLARBRIDGE_MCP_RATE_REFILL_PER_SECOND="1")
        self.metrics("metadata-metrics-before.txt")
        metadata_url = f"{self.api}/.well-known/oauth-protected-resource/mcp"
        for name, extra in { "metadata-forwarded": ("--header", "Host: localhost:8080", "--header",
                                   "Forwarded: host=attacker.example;proto=http"),
            "metadata-host": ("--header", "Host: attacker.example"), }.items():
            self.evidence(name, metadata_url, extra=extra)
        self.metrics("metadata-metrics-after.txt")
        cases = ( ("security-unauthenticated", "", ORIGIN, "401"),
            ("security-api-token", api_token, ORIGIN, "401"),
            ("security-untrusted-client", untrusted, ORIGIN, "401"),
            ("security-bad-signature", sales + "x", ORIGIN, "401"),
            ("security-expired", expired, ORIGIN, "401"),
            ("security-wrong-issuer", wrong_issuer, ORIGIN, "401"),
            ("security-no-scope", no_scope, ORIGIN, "403"), ("security-native", sales, "", "200"),
            ("security-refreshed", rotation2["access_token"], ORIGIN, "200"),
            ("security-origin", sales, "https://invalid.example", "403"), )
        assert all(self.mcp(name, token, origin, rpc) == status for name, token, origin, status in cases)
        assert self.mcp("security-host", sales, ORIGIN, rpc,
                        extra=("--header", "Host: attacker.example")) == "421"
        for name, token, expected in (
                ("api-console", api_token, "200"), ("api-mcp", sales, "401")):
            assert self.evidence(name, f"{self.api}/api/v1/me",
                extra=("--header", f"Authorization: Bearer {token}")) == expected
        oversized = "x" * 65537
        assert self.mcp("security-length", sales, ORIGIN, oversized) == "413"
        assert self.mcp("security-chunked", sales, ORIGIN, oversized,
                        extra=("--header", "Transfer-Encoding: chunked")) == "413"
        self.transport_timeout(sales, rpc)
        self.database_gate("request", sales, probe, api_token, supply, rpc)
        self.metrics("prometheus-ingress.txt")
        self.recreate_backend(CELLARBRIDGE_MCP_REQUEST_TIMEOUT="PT5S",
                              CELLARBRIDGE_MCP_STATEMENT_TIMEOUT="PT2S",
                              CELLARBRIDGE_MCP_MAX_CONCURRENCY="1",
                              CELLARBRIDGE_MCP_MAX_CLIENT_CONCURRENCY="1")
        self.database_gate("statement", sales, probe, api_token, supply, rpc)
        self.cardinality(sales)
        status = ""
        for _ in range(100):
            status = self.mcp("security-rate", sales, ORIGIN, rpc)
            if status == "429":
                break
            assert status == "200"
        assert status == "429"
        self.metrics("prometheus-rate.txt")
        self.compose("pause", "keycloak")
        try:
            self.wait_success("security-keycloak-outage", sales, rpc)
            status = self.curl(f"{self.api}/api/v1/me", output=Path(os.devnull),
                               extra=("--header", f"Authorization: Bearer {api_token}"))
            write(self.temp, "rest-keycloak-outage.status", status)
            assert status == "200"
            assert self.evidence("readiness-outage",
                                 f"{self.api}/actuator/health/readiness") == "200"
            self.recreate_backend()
            started = time.monotonic()
            assert self.mcp("security-keycloak-cold", sales, ORIGIN, rpc) == "503"
            jwk_ms = round((time.monotonic() - started) * 1000)
            assert jwk_ms < 6000
            self.pass_evidence("jwk-timeout", pausedAuthorizationServer=True,
                configuredTotalTimeoutMs=5000, coldDecoderRejectionMs=jwk_ms)
            self.health("health-authorization-server")
            assert self.evidence("readiness-cold",
                                 f"{self.api}/actuator/health/readiness") == "200"
        finally:
            self.compose("unpause", "keycloak")
        self.wait(lambda: self.command(
            ["curl", "-fsS", "--connect-timeout", "1", "--max-time", "2",
             f"{self.keycloak}/realms/cellarbridge/.well-known/openid-configuration"],
            check=False, stdout=subprocess.DEVNULL).returncode == 0,
            30, "Keycloak did not recover after unpause")
        recovered = self.oidc("mcp-recovery", "north.sales")
        self.wait_success("security-keycloak-recovery", recovered, rpc, ("429", "401"))
        assert self.evidence("readiness-recovery",
                             f"{self.api}/actuator/health/readiness") == "200"
        current = load(self.temp, "current_user")["result"]["structuredContent"]["data"]
        incoming = {"success": current["userId"], "rejection": current["tenant"]["id"]}
        trace_rpc = encoded(CURRENT_RPC)
        self.wait_success("trace-success", recovered, trace_rpc)
        assert self.mcp("trace-success", recovered, ORIGIN, trace_rpc,
                        extra=("--header", f"X-Correlation-ID: {incoming['success']}")) == "200"
        self.metrics("response-bytes-before.txt")
        assert self.mcp("trace-rejection", recovered, "https://invalid.example", rpc,
                        extra=("--header", f"X-Correlation-ID: {incoming['rejection']}")) == "403"
        self.metrics("prometheus.txt")
        correlations = {label: response_header(self.temp, f"trace-{label}", "X-Correlation-ID")
                        for label in incoming}
        assert all(str(UUID(value)) == value and value != incoming[label]
                   for label, value in correlations.items())
        envelope = load(self.temp, "trace-success")["result"]["structuredContent"]
        assert envelope["correlationId"] == correlations["success"]
        self.log_services("backend-lifecycle.log", "backend", append=True)
        self.log_services("service-security.log", "postgres", "keycloak-postgres",
                          "keycloak", "otel-collector", "tempo")
        metadata_id = os.getenv( "CB_MCP_METADATA_ID",
            "https://mcp.cellarbridge.example/.well-known/oauth-protected-resource/mcp")
        verify_production(self.temp, metadata_id, f"{trusted}/realms/cellarbridge",
                          self.root / "target/mcp-production-security",
                          "http://127.0.0.1:3200", correlations)
    def execute(self):
        try:
            for output in (self.root / "target/mcp-conformance",
                           self.root / "target/mcp-production-security"):
                shutil.rmtree(output, ignore_errors=True)
            self.compose("down", "--volumes", "--remove-orphans", check=False,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            services = ["postgres", "keycloak", "backend"]
            if self.mode == "--production-security":
                services[2:2] = ["tempo", "otel-collector"]
            self.compose("up", "-d", "--build", "--wait", "--wait-timeout", "180", *services, timeout=900)
            if self.mode == "--production-security":
                def tempo():
                    return self.command( ["curl", "-fsS", "-o", os.devnull, "--connect-timeout", "1",
                         "--max-time", "2", "http://127.0.0.1:3200/ready"], check=False).returncode == 0
                self.wait(tempo, 60, "Tempo did not become ready")
                self.feature_gate()
            sales, buyer, harbor = ( self.oidc(name, user) for name, user in (
                    ("mcp-sales", "north.sales"), ("mcp-buyer", "north.buyer"),
                    ("mcp-harbor", "harbor.manager")))
            self.basic(sales, buyer, harbor)
            if self.mode != "smoke":
                self.conformance(sales)
            if self.mode == "--production-security":
                self.production(sales)
            else:
                self.log_services("backend-phase0.log", "backend")
                logs = text(self.temp, "backend-phase0.log")
                forbidden = self.credentials + [ "Moonlit Terrace", "Authorization", "Bearer ", "eyJ",
                    "clientInfo", '"jsonrpc"', "SELECT ", "INSERT ", ]
                assert not any(value and value in logs for value in forbidden)
        finally:
            self.cleanup()
    def cleanup(self):
        try:
            self.release_lock()
        except Exception:
            pass
        if self.proxy:
            self.proxy.terminate()
            try:
                self.proxy.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proxy.kill()
        try:
            self.compose("down", "--volumes", "--remove-orphans", check=False,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except (OSError, subprocess.SubprocessError):
            pass
        for pattern in ("credentials.values", "*.cookies", "*.login.*", "*.auth.*",
                        "*.token.json", "*.refresh.json", "trace-log-ids.json"):
            for path in self.temp.glob(pattern):
                path.unlink(missing_ok=True)
        if os.getenv("CB_MCP_KEEP_TEMP", "false") != "true":
            shutil.rmtree(self.temp, ignore_errors=True)
        else:
            print(f"MCP verification workspace retained at {self.temp}", file=sys.stderr)
HOP_HEADERS = { "connection", "content-length", "host", "keep-alive", "proxy-authenticate",
    "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", }
class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, _format, *_args):
        return
    def forward(self):
        target = urlsplit(self.path)
        if target.path != "/mcp" or target.query:
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400)
            return
        if length < 0 or length > 1_048_576:
            self.send_error(413)
            return
        headers = {name: value for name, value in self.headers.items()
                   if name.lower() not in HOP_HEADERS and name.lower() != "authorization"}
        upstream = self.server.upstream
        headers["Authorization"] = f"Bearer {self.server.access_token}"
        headers["Host"] = (upstream.hostname if upstream.port is None
                           else f"{upstream.hostname}:{upstream.port}")
        with closing(HTTPConnection(upstream.hostname, upstream.port or 80, timeout=30)) as connection:
            connection.request(self.command, "/mcp",
                               body=self.rfile.read(length) if length else None, headers=headers)
            response = connection.getresponse()
            body = response.read()
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() not in HOP_HEADERS:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    do_GET = do_POST = forward
    def do_DELETE(self):
        self.send_response(405)
        self.send_header("Allow", "GET, POST")
        self.send_header("Content-Length", "0")
        self.end_headers()
def proxy():
    upstream = urlsplit(os.environ["CB_MCP_PROXY_UPSTREAM"])
    invalid = (upstream.scheme != "http" or upstream.hostname not in {"127.0.0.1", "localhost"}
               or upstream.username or upstream.password
               or upstream.path not in {"", "/"} or upstream.query or upstream.fragment)
    if invalid: raise SystemExit("The conformance proxy upstream must be a loopback HTTP origin")
    server = ThreadingHTTPServer(("127.0.0.1", int(os.environ["CB_MCP_PROXY_PORT"])), Proxy)
    server.upstream, server.access_token = upstream, os.environ.pop("CB_MCP_PROXY_TOKEN")
    server.handle_error = lambda *_: None
    server.serve_forever()
if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "proxy":
        proxy()
    elif len(sys.argv) == 3 and sys.argv[1] == "run":
        Smoke(sys.argv[2]).execute()
    else:
        raise SystemExit( f"Usage: {sys.argv[0]} run [smoke|--conformance|--production-security] | proxy")
