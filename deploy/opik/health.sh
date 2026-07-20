#!/usr/bin/env bash

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_PORT="$(sed -n 's/^OPIK_HOST_PORT=//p' "$DEPLOY_DIR/opik.env")"
HOST_PORT="${HOST_PORT:-5173}"

for _attempt in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:${HOST_PORT}/health" >/dev/null; then
        echo "Opik 2.1.32 is healthy at http://127.0.0.1:${HOST_PORT}"
        exit 0
    fi
    sleep 1
done

echo "Opik health check timed out" >&2
exit 2
