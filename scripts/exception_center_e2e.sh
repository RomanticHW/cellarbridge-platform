#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export CELLARBRIDGE_E2E_PROJECT_NAME="cellarbridge-exception-e2e"
export CELLARBRIDGE_E2E_COMMAND="test:e2e:exception"
exec "${ROOT_DIR}/scripts/fulfillment_orchestration_e2e.sh"
