#!/usr/bin/env bash

set -euo pipefail

TARGET_VERSION="${1:-}"
if [[ -z "$TARGET_VERSION" || "$TARGET_VERSION" == "latest" ]]; then
    echo "Usage: deploy/opik/check-upgrade.sh <explicit-version>" >&2
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
for component in opik-backend opik-python-backend opik-frontend; do
    "${CONTAINER_CMD[@]}" manifest inspect "ghcr.io/comet-ml/opik/${component}:${TARGET_VERSION}" >/dev/null
done

echo "All required Opik images exist for ${TARGET_VERSION}."
echo "This check made no deployment or volume changes; run compatibility tests before changing VERSION/opik.env."
