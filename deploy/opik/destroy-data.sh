#!/usr/bin/env bash

set -euo pipefail

if [[ "${1:-}" != "--confirm-delete-opik-data" ]]; then
    echo "Refusing to delete data. Re-run with --confirm-delete-opik-data." >&2
    exit 2
fi

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v docker >/dev/null 2>&1; then
    CONTAINER_CMD=(docker)
elif command -v podman >/dev/null 2>&1; then
    CONTAINER_CMD=(podman)
elif [[ -x /opt/podman/bin/podman ]]; then
    CONTAINER_CMD=(/opt/podman/bin/podman)
else
    echo "docker or podman CLI is required" >&2
    exit 2
fi
"${CONTAINER_CMD[@]}" compose --env-file "$DEPLOY_DIR/opik.env" \
    -f "$DEPLOY_DIR/upstream/docker-compose.yaml" \
    -f "$DEPLOY_DIR/compose.local.yaml" \
    --profile opik down --volumes
echo "Opik containers and dedicated odw-opik-2132 volumes were deleted."
