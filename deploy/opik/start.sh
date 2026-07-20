#!/usr/bin/env bash

set -euo pipefail

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
COMPOSE=("${CONTAINER_CMD[@]}" compose --env-file "$DEPLOY_DIR/opik.env" -f "$DEPLOY_DIR/upstream/docker-compose.yaml" -f "$DEPLOY_DIR/compose.local.yaml" --profile opik)

"${CONTAINER_CMD[@]}" compose version >/dev/null
if grep -En 'image:.*latest|OPIK_VERSION:-latest' "$DEPLOY_DIR/upstream/docker-compose.yaml" "$DEPLOY_DIR/compose.local.yaml"; then
    echo "Refusing to start: an unpinned image reference was found" >&2
    exit 2
fi

"${COMPOSE[@]}" config >/dev/null
"${COMPOSE[@]}" pull
"${COMPOSE[@]}" up -d --no-build
"$DEPLOY_DIR/health.sh"
