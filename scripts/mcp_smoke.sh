#!/usr/bin/env bash
set -euo pipefail
mode="${1:-smoke}"
if [[ "${mode}" != "smoke" && "${mode}" != "--conformance" && "${mode}" != "--production-security" ]]; then
  printf 'Usage: %s [--conformance|--production-security]\n' "$0" >&2
  exit 2
fi
exec python3 "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/mcp_auth_proxy.py" run "${mode}"
