#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="${1:-smoke}"

if [[ "${PROFILE}" != "smoke" && "${PROFILE}" != "full" ]]; then
  printf 'Usage: %s [smoke|full]\n' "$0" >&2
  exit 2
fi

cd "${ROOT_DIR}"
exec python3 performance/run_experiments.py \
  --profile "${PROFILE}" \
  --output "target/performance-evidence/${PROFILE}"
