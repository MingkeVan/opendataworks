#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PACKAGE_NAME="opendataworks-deployment"

usage() {
    cat <<'EOF'
Usage: scripts/offline/create-offline-package-from-dockerhub.sh [options]

Options:
  --registry <registry>     Remote registry host (default: docker.io)
  --namespace <namespace>   Docker Hub namespace for opendataworks images (default: mingkevan)
  --tag <tag>               Image tag to pull (default: latest)
  --output <path>           Output tar.gz path (default: ./opendataworks-deployment-<timestamp>.tar.gz)
  --platform <platform>     Optional pull platform (e.g. linux/amd64)
  --keep-workdir            Do not delete temporary build directory (for debugging)
  -h, --help                Show this help

Environment overrides:
  OPENDATAWORKS_REGISTRY, OPENDATAWORKS_NAMESPACE, OPENDATAWORKS_TAG,
  OPENDATAWORKS_PLATFORM

The script packages current deploy/ assets and scripts/deploy/, pulls required images
from Docker Hub, retags them for local use, saves them into deploy/docker-images/*.tar,
and produces a compressed archive that can be copied into an isolated environment.
EOF
}

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

die() {
    log "ERROR: $*"
    exit 1
}

detect_container_cmd() {
    if command -v docker >/dev/null 2>&1; then
        echo docker
    elif command -v podman >/dev/null 2>&1; then
        echo podman
    else
        die "docker or podman is required"
    fi
}

PARSER_REGISTRY="${OPENDATAWORKS_REGISTRY:-docker.io}"
PARSER_NAMESPACE="${OPENDATAWORKS_NAMESPACE:-mikefan2019}"
PARSER_TAG="${OPENDATAWORKS_TAG:-latest}"
PARSER_PLATFORM="${OPENDATAWORKS_PLATFORM:-}"
OUTPUT_PATH=""
KEEP_WORKDIR=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --registry)
            PARSER_REGISTRY="$2"
            shift 2
            ;;
        --namespace)
            PARSER_NAMESPACE="$2"
            shift 2
            ;;
        --tag)
            PARSER_TAG="$2"
            shift 2
            ;;
        --output)
            OUTPUT_PATH="$2"
            shift 2
            ;;
        --platform)
            PARSER_PLATFORM="$2"
            shift 2
            ;;
        --keep-workdir)
            KEEP_WORKDIR=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

CONTAINER_CMD=$(detect_container_cmd)
log "Using container runtime: $CONTAINER_CMD"

if [[ -z "$OUTPUT_PATH" ]]; then
    OUTPUT_PATH="opendataworks-deployment-$(date '+%Y%m%d-%H%M%S').tar.gz"
fi

if [[ -e "$OUTPUT_PATH" ]]; then
    die "output path already exists: $OUTPUT_PATH"
fi

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/opendataworks-package.XXXXXXXX")
PACKAGE_ROOT="$WORKDIR/$PACKAGE_NAME"
trap '[[ "$KEEP_WORKDIR" = true ]] || rm -rf "$WORKDIR"' EXIT

log "Preparing deployment package workspace at $PACKAGE_ROOT"
mkdir -p "$PACKAGE_ROOT"

DEPLOY_PACKAGE_DIR="$PACKAGE_ROOT/deploy"
SCRIPTS_PACKAGE_DIR="$PACKAGE_ROOT/scripts"
DEPLOY_IMAGE_DIR="$DEPLOY_PACKAGE_DIR/docker-images"

mkdir -p "$DEPLOY_PACKAGE_DIR"
tar -C "$REPO_ROOT/deploy" --exclude='docker-images/*.tar' -cf - . | tar -C "$DEPLOY_PACKAGE_DIR" -xf -
mkdir -p "$DEPLOY_IMAGE_DIR"
rm -f "$DEPLOY_IMAGE_DIR/"*.tar 2>/dev/null || true

if [[ -d "$REPO_ROOT/database/mysql" ]]; then
    log "Copying database/mysql scripts"
    mkdir -p "$PACKAGE_ROOT/database/mysql"
    tar -C "$REPO_ROOT/database/mysql" -cf - . | tar -C "$PACKAGE_ROOT/database/mysql" -xf -
fi

mkdir -p "$SCRIPTS_PACKAGE_DIR"
tar -C "$REPO_ROOT/scripts" -cf - deploy | tar -C "$SCRIPTS_PACKAGE_DIR" -xf -

cp "$REPO_ROOT/deploy/offline/README_OFFLINE.md" "$PACKAGE_ROOT/README_OFFLINE.md"
if [[ -f "$REPO_ROOT/docs/handbook/operations-guide.md" ]]; then
    cp "$REPO_ROOT/docs/handbook/operations-guide.md" "$PACKAGE_ROOT/OPERATIONS_GUIDE.md"
fi

declare -a MANIFEST_RAW=()

pull_image() {
    local image="$1"
    if [[ -n "$PARSER_PLATFORM" ]]; then
        "$CONTAINER_CMD" pull --platform "$PARSER_PLATFORM" "$image"
    else
        "$CONTAINER_CMD" pull "$image"
    fi
}

save_image() {
    local image="$1"
    local archive="$2"
    "$CONTAINER_CMD" save -o "$DEPLOY_IMAGE_DIR/$archive" "$image"
}

retag_image() {
    local source="$1"
    local target="$2"
    if [[ "$source" != "$target" ]]; then
        "$CONTAINER_CMD" tag "$source" "$target"
    fi
}

get_digest() {
    local image="$1"
    "$CONTAINER_CMD" image inspect --format='{{index .RepoDigests 0}}' "$image" 2>/dev/null || true
}

compute_checksums() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$@"
    else
        die "sha256sum or shasum binary is required to compute checksums"
    fi
}

OP_TAG="$PARSER_TAG"

MAIN_IMAGES=(
    "opendataworks-frontend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-frontend:${OP_TAG}|opendataworks-frontend:${OP_TAG}"
    "opendataworks-backend.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-backend:${OP_TAG}|opendataworks-backend:${OP_TAG}"
    "opendataworks-dolphin-service.tar|${PARSER_REGISTRY}/${PARSER_NAMESPACE}/opendataworks-dolphin-service:${OP_TAG}|opendataworks-dolphin-service:${OP_TAG}"
)

EXTRA_IMAGES=(
    "mysql-8.0.tar|docker.io/library/mysql:8.0|mysql:8.0"
)

log "Pulling application images from ${PARSER_REGISTRY}/${PARSER_NAMESPACE} tag ${OP_TAG}"
for entry in "${MAIN_IMAGES[@]}"; do
    IFS='|' read -r archive source target <<<"$entry"
    log "Pulling $source"
    pull_image "$source"
    retag_image "$source" "$target"
    log "Saving $target to deploy/docker-images/$archive"
    save_image "$target" "$archive"
    digest=$(get_digest "$source")
    MANIFEST_RAW+=("$archive|$source|$target|$digest")
done

log "Pulling dependency images"
for entry in "${EXTRA_IMAGES[@]}"; do
    IFS='|' read -r archive source target <<<"$entry"
    log "Pulling $source"
    pull_image "$source"
    retag_image "$source" "$target"
    log "Saving $target to deploy/docker-images/$archive"
    save_image "$target" "$archive"
    digest=$(get_digest "$source")
    MANIFEST_RAW+=("$archive|$source|$target|$digest")
done

MANIFEST_FILE="$DEPLOY_IMAGE_DIR/manifest.json"
{
    printf '[\n'
    for i in "${!MANIFEST_RAW[@]}"; do
        IFS='|' read -r archive source target digest <<<"${MANIFEST_RAW[$i]}"
        printf '  {\n'
        printf '    "archive": "%s",\n' "$archive"
        printf '    "source": "%s",\n' "$source"
        printf '    "target": "%s"' "$target"
        if [[ -n "$digest" ]]; then
            printf ',\n    "digest": "%s"\n' "$digest"
        else
            printf '\n'
        fi
        if [[ "$i" -lt $((${#MANIFEST_RAW[@]} - 1)) ]]; then
            printf '  },\n'
        else
            printf '  }\n'
        fi
    done
    printf ']\n'
} > "$MANIFEST_FILE"

checksum_file="$DEPLOY_IMAGE_DIR/checksums.sha256"
log "Generating checksums"
(cd "$DEPLOY_IMAGE_DIR" && compute_checksums *.tar > checksums.tmp && mv checksums.tmp "$(basename "$checksum_file")")

log "Creating archive $OUTPUT_PATH"
tar -C "$WORKDIR" -czf "$OUTPUT_PATH" "$PACKAGE_NAME"

if [[ "$KEEP_WORKDIR" = true ]]; then
    log "Temporary workspace kept at: $WORKDIR"
else
    log "Cleaning up temporary workspace"
fi

log "Offline deployment package ready: $OUTPUT_PATH"
log "Included manifest: $PACKAGE_NAME/deploy/docker-images/manifest.json"
log "Image checksums: $PACKAGE_NAME/deploy/docker-images/checksums.sha256"
