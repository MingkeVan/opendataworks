#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PYTHON_BIN="${DATAAGENT_EVAL_PYTHON_BIN:-$REPO_ROOT/dataagent/dataagent-backend/.venv-py313/bin/python}"
if [[ ! -x "$PYTHON_BIN" ]]; then
    PYTHON_BIN=python3
fi
exec "$PYTHON_BIN" "$REPO_ROOT/tools/dataagent-evals/history/report.py" "$@"
