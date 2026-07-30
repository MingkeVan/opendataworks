#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROJECT_ROOT/.." && pwd)"
LIB_DIR="$SCRIPT_DIR/lib"
VERSION_FILE="$PROJECT_ROOT/VERSION"
SYNC_SCRIPT="$PROJECT_ROOT/scripts/sync-root-skills.sh"

# shellcheck source=/dev/null
source "$LIB_DIR/container-runtime.sh"

usage() {
    cat <<'EOF'
Usage: opendataagent/scripts/create-offline-package.sh [options]

Options:
  --tag <tag>             Image tag, defaults to opendataagent/VERSION
  --output <path>         Output tar.gz path
  --platform <platform>   Optional pull/build platform, e.g. linux/amd64
  --skip-build            Do not rebuild app images before packaging
  --keep-workdir          Keep temporary workspace for debugging
  -h, --help              Show help

The package contains:
  - deploy/docker-compose.yml and deploy/.env.example (never deploy/.env)
  - docker images for opendataagent-server, opendataagent-web, mysql:8.0
  - shared-skills/ snapshot from repository root skills/
  - offline load/start scripts
EOF
}

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

die() {
    log "ERROR: $*"
    exit 1
}

compute_checksums() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$@"
    else
        die "sha256sum or shasum is required"
    fi
}

TAG="$(tr -d '[:space:]' < "$VERSION_FILE")"
OUTPUT_PATH=""
PLATFORM="${PLATFORM:-}"
SKIP_BUILD=false
KEEP_WORKDIR=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)
            TAG="$2"
            shift 2
            ;;
        --output)
            OUTPUT_PATH="$2"
            shift 2
            ;;
        --platform)
            PLATFORM="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
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

if [[ -z "$OUTPUT_PATH" ]]; then
    OUTPUT_PATH="$REPO_ROOT/output/releases/opendataagent/opendataagent-deployment-${TAG}.tar.gz"
fi

mkdir -p "$(dirname "$OUTPUT_PATH")"
if [[ -e "$OUTPUT_PATH" ]]; then
    die "output path already exists: $OUTPUT_PATH"
fi

if ! CONTAINER_CMD="$(detect_container_runtime)"; then
    die "docker or podman is required"
fi
if ! ensure_container_runtime_ready "$CONTAINER_CMD"; then
    die "$CONTAINER_CMD is not ready"
fi

log "Using container runtime: $CONTAINER_CMD"

SERVER_IMAGE="opendataagent-server:${TAG}"
WEB_IMAGE="opendataagent-web:${TAG}"
MYSQL_IMAGE="mysql:8.0"

build_image() {
    local tag="$1"
    local context="$2"
    if [[ -n "$PLATFORM" ]]; then
        "$CONTAINER_CMD" build --platform "$PLATFORM" -t "$tag" "$context"
    else
        "$CONTAINER_CMD" build -t "$tag" "$context"
    fi
}

ensure_local_image() {
    local image="$1"
    "$CONTAINER_CMD" image inspect "$image" >/dev/null 2>&1 || die "required image not found: $image"
}

pull_image() {
    local image="$1"
    if [[ -n "$PLATFORM" ]]; then
        "$CONTAINER_CMD" pull --platform "$PLATFORM" "$image"
    else
        "$CONTAINER_CMD" pull "$image"
    fi
}

if [[ "$SKIP_BUILD" = false ]]; then
    log "Syncing shared skills into server bundle"
    "$SYNC_SCRIPT"
    log "Building $SERVER_IMAGE"
    build_image "$SERVER_IMAGE" "$PROJECT_ROOT/server"
    log "Building $WEB_IMAGE"
    build_image "$WEB_IMAGE" "$PROJECT_ROOT/web"
fi

ensure_local_image "$SERVER_IMAGE"
ensure_local_image "$WEB_IMAGE"

log "Pulling dependency image $MYSQL_IMAGE"
pull_image "$MYSQL_IMAGE"
ensure_local_image "$MYSQL_IMAGE"

WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/opendataagent-package.XXXXXXXX")"
PACKAGE_NAME="opendataagent-deployment"
PACKAGE_ROOT="$WORKDIR/$PACKAGE_NAME"
PACKAGED_DEPLOY_DIR="$PACKAGE_ROOT/deploy"
PACKAGED_SCRIPTS_DIR="$PACKAGE_ROOT/scripts"
PACKAGED_IMAGE_DIR="$PACKAGED_DEPLOY_DIR/docker-images"
PACKAGED_SHARED_SKILLS_DIR="$PACKAGE_ROOT/shared-skills"
trap '[[ "$KEEP_WORKDIR" = true ]] || rm -rf "$WORKDIR"' EXIT

log "Preparing workspace at $PACKAGE_ROOT"
mkdir -p "$PACKAGED_DEPLOY_DIR" "$PACKAGED_SCRIPTS_DIR" "$PACKAGED_IMAGE_DIR" "$PACKAGED_SHARED_SKILLS_DIR"

log "Copying deploy/ assets"
tar -C "$PROJECT_ROOT/deploy" \
    --exclude='./.env' \
    --exclude='.env' \
    --exclude='docker-images/*.tar' \
    --exclude='docker-images/checksums.sha256' \
    --exclude='docker-images/manifest.json' \
    -cf - . | tar -C "$PACKAGED_DEPLOY_DIR" -xf -

log "Copying runtime scripts"
mkdir -p "$PACKAGED_SCRIPTS_DIR/lib"
cp "$PROJECT_ROOT/scripts/load-images.sh" "$PACKAGED_SCRIPTS_DIR/load-images.sh"
cp "$PROJECT_ROOT/scripts/load-package-and-start.sh" "$PACKAGED_SCRIPTS_DIR/load-package-and-start.sh"
cp "$PROJECT_ROOT/scripts/start.sh" "$PACKAGED_SCRIPTS_DIR/start.sh"
cp "$PROJECT_ROOT/scripts/stop.sh" "$PACKAGED_SCRIPTS_DIR/stop.sh"
cp "$PROJECT_ROOT/scripts/restart.sh" "$PACKAGED_SCRIPTS_DIR/restart.sh"
cp "$PROJECT_ROOT/scripts/lib/container-runtime.sh" "$PACKAGED_SCRIPTS_DIR/lib/container-runtime.sh"
chmod +x "$PACKAGED_SCRIPTS_DIR/"*.sh "$PACKAGED_SCRIPTS_DIR/lib/container-runtime.sh"

log "Copying shared skills snapshot"
tar -C "$REPO_ROOT/skills" -cf - . | tar -C "$PACKAGED_SHARED_SKILLS_DIR" -xf -
chmod -R a+rX "$PACKAGED_SHARED_SKILLS_DIR"
if [[ -f "$PACKAGED_SHARED_SKILLS_DIR/bin/odw-cli" ]]; then
    chmod +x "$PACKAGED_SHARED_SKILLS_DIR/bin/odw-cli"
fi

cp "$PROJECT_ROOT/VERSION" "$PACKAGE_ROOT/VERSION"
cp "$PROJECT_ROOT/deploy/README.md" "$PACKAGE_ROOT/README.md"

rewrite_env_file() {
    local env_file="$1"
    if [[ ! -f "$env_file" ]]; then
        return
    fi

    sed \
        -e "s|^OPENDATAAGENT_VERSION=.*|OPENDATAAGENT_VERSION=${TAG}|" \
        -e "s|^OPENDATAAGENT_SHARED_SKILLS_PATH=.*|OPENDATAAGENT_SHARED_SKILLS_PATH=../shared-skills|" \
        "$env_file" > "${env_file}.tmp" && mv "${env_file}.tmp" "$env_file"

    grep -q '^OPENDATAAGENT_VERSION=' "$env_file" 2>/dev/null || \
        echo "OPENDATAAGENT_VERSION=${TAG}" >> "$env_file"
    grep -q '^OPENDATAAGENT_SHARED_SKILLS_PATH=' "$env_file" 2>/dev/null || \
        echo "OPENDATAAGENT_SHARED_SKILLS_PATH=../shared-skills" >> "$env_file"
}

rm -f "$PACKAGED_DEPLOY_DIR/.env"
if [[ ! -f "$PACKAGED_DEPLOY_DIR/.env.example" ]]; then
    die "deploy/.env.example is required for the offline package"
fi
rewrite_env_file "$PACKAGED_DEPLOY_DIR/.env.example"

assert_package_has_no_runtime_env() {
    local unexpected_env
    unexpected_env="$(find "$PACKAGE_ROOT" -type f -name '.env' -print -quit)"
    if [[ -n "$unexpected_env" ]]; then
        die "runtime .env must not be included in the offline package: $unexpected_env"
    fi
}

save_image() {
    local image="$1"
    local archive="$2"
    log "Saving $image -> deploy/docker-images/$archive"
    "$CONTAINER_CMD" save -o "$PACKAGED_IMAGE_DIR/$archive" "$image"
}

manifest_file="$PACKAGED_IMAGE_DIR/manifest.json"
declare -a manifest_entries=(
    "opendataagent-server.tar|$SERVER_IMAGE|$SERVER_IMAGE"
    "opendataagent-web.tar|$WEB_IMAGE|$WEB_IMAGE"
    "mysql-8.0.tar|$MYSQL_IMAGE|$MYSQL_IMAGE"
)

save_image "$SERVER_IMAGE" "opendataagent-server.tar"
save_image "$WEB_IMAGE" "opendataagent-web.tar"
save_image "$MYSQL_IMAGE" "mysql-8.0.tar"

{
    printf '[\n'
    for i in "${!manifest_entries[@]}"; do
        IFS='|' read -r archive source target <<<"${manifest_entries[$i]}"
        printf '  {\n'
        printf '    "archive": "%s",\n' "$archive"
        printf '    "source": "%s",\n' "$source"
        printf '    "target": "%s"\n' "$target"
        if [[ "$i" -lt $((${#manifest_entries[@]} - 1)) ]]; then
            printf '  },\n'
        else
            printf '  }\n'
        fi
    done
    printf ']\n'
} > "$manifest_file"

log "Generating checksums"
(cd "$PACKAGED_IMAGE_DIR" && compute_checksums *.tar > checksums.sha256)

assert_package_has_no_runtime_env

log "Creating archive $OUTPUT_PATH"
tar -C "$WORKDIR" -czf "$OUTPUT_PATH" "$PACKAGE_NAME"

if [[ "$KEEP_WORKDIR" = true ]]; then
    log "Temporary workspace kept at $WORKDIR"
fi
log "Offline package ready: $OUTPUT_PATH"
