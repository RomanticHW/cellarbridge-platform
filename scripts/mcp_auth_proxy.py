#!/usr/bin/env python3
"""Loopback-only test proxy that adds one Bearer token for MCP conformance checks."""

from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from urllib.parse import urlsplit


LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = int(os.environ["CB_MCP_PROXY_PORT"])
UPSTREAM = urlsplit(os.environ.get("CB_MCP_PROXY_UPSTREAM", "http://127.0.0.1:8080"))
ACCESS_TOKEN = os.environ.pop("CB_MCP_PROXY_TOKEN")
MAX_REQUEST_BYTES = 1_048_576
HOP_BY_HOP = {
    "connection",
    "content-length",
    "host",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
}


class McpProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        self._forward()

    def do_POST(self) -> None:  # noqa: N802
        self._forward()

    def do_DELETE(self) -> None:  # noqa: N802
        self.send_response(405)
        self.send_header("Allow", "GET, POST")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, format: str, *args: object) -> None:
        return

    def _forward(self) -> None:
        request_target = urlsplit(self.path)
        if request_target.path != "/mcp" or request_target.query:
            self.send_error(404)
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400)
            return
        if content_length < 0 or content_length > MAX_REQUEST_BYTES:
            self.send_error(413)
            return

        body = self.rfile.read(content_length) if content_length else None
        headers = {
            name: value
            for name, value in self.headers.items()
            if name.lower() not in HOP_BY_HOP and name.lower() != "authorization"
        }
        headers["Authorization"] = f"Bearer {ACCESS_TOKEN}"
        headers["Host"] = (
            UPSTREAM.hostname
            if UPSTREAM.port is None
            else f"{UPSTREAM.hostname}:{UPSTREAM.port}"
        )

        connection = HTTPConnection(UPSTREAM.hostname, UPSTREAM.port or 80, timeout=30)
        try:
            connection.request(self.command, "/mcp", body=body, headers=headers)
            upstream_response = connection.getresponse()
            response_body = upstream_response.read()
            self.send_response(upstream_response.status)
            for name, value in upstream_response.getheaders():
                if name.lower() not in HOP_BY_HOP:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)
        finally:
            connection.close()


if __name__ == "__main__":
    if (
        UPSTREAM.scheme != "http"
        or UPSTREAM.hostname not in {"127.0.0.1", "localhost"}
        or UPSTREAM.username is not None
        or UPSTREAM.password is not None
        or UPSTREAM.path not in {"", "/"}
        or UPSTREAM.query
        or UPSTREAM.fragment
    ):
        raise SystemExit("The conformance proxy upstream must be a loopback HTTP origin.")
    ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), McpProxyHandler).serve_forever()
