#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_PYTHON="$REPO_ROOT/dataagent/dataagent-backend/.venv-py313/bin/python"
if [[ ! -x "$DEFAULT_PYTHON" ]]; then
    DEFAULT_PYTHON=python3
fi
EVAL_PYTHON="${DATAAGENT_EVAL_PYTHON_BIN:-$DEFAULT_PYTHON}"
IMAGE="${OPENDATAWORKS_DATAAGENT_EVALS_OPIK_IMAGE:-opendataworks-dataagent-evals-opik:2.1.32}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    "$EVAL_PYTHON" "$REPO_ROOT/tools/dataagent-evals/opik/run.py" --help
    exit 0
fi

if [[ "${DATAAGENT_OPIK_RUN_LOCAL:-}" == "1" ]]; then
    exec "$EVAL_PYTHON" "$REPO_ROOT/tools/dataagent-evals/opik/run.py" "$@"
fi

if command -v docker >/dev/null 2>&1; then
    CONTAINER_CMD=docker
elif command -v podman >/dev/null 2>&1; then
    CONTAINER_CMD=podman
elif [[ -x /opt/podman/bin/podman ]]; then
    CONTAINER_CMD=/opt/podman/bin/podman
else
    echo "docker or podman is required; set DATAAGENT_OPIK_RUN_LOCAL=1 for local Python" >&2
    exit 2
fi

DATASET_PATH="${DATAAGENT_EVAL_DATASET:-}"
OUTPUT_PATH=""
SNAPSHOT_PATH="${DATAAGENT_EVAL_AGENT_SNAPSHOT_PATH:-}"
ARGS=("$@")
for ((i = 0; i < ${#ARGS[@]}; i++)); do
    if [[ "${ARGS[$i]}" == "--dataset" && $((i + 1)) -lt ${#ARGS[@]} ]]; then
        DATASET_PATH="${ARGS[$((i + 1))]}"
    elif [[ "${ARGS[$i]}" == --dataset=* ]]; then
        DATASET_PATH="${ARGS[$i]#--dataset=}"
    elif [[ "${ARGS[$i]}" == "--output-dir" && $((i + 1)) -lt ${#ARGS[@]} ]]; then
        OUTPUT_PATH="${ARGS[$((i + 1))]}"
    elif [[ "${ARGS[$i]}" == --output-dir=* ]]; then
        OUTPUT_PATH="${ARGS[$i]#--output-dir=}"
    elif [[ "${ARGS[$i]}" == "--agent-snapshot-path" && $((i + 1)) -lt ${#ARGS[@]} ]]; then
        SNAPSHOT_PATH="${ARGS[$((i + 1))]}"
    elif [[ "${ARGS[$i]}" == --agent-snapshot-path=* ]]; then
        SNAPSHOT_PATH="${ARGS[$i]#--agent-snapshot-path=}"
    fi
done

VOLUMES=(-v "$REPO_ROOT:/workspace")
if [[ -n "$DATASET_PATH" && "$DATASET_PATH" = /* ]]; then
    DATASET_DIR="$(cd "$(dirname "$DATASET_PATH")" && pwd)"
    VOLUMES+=(-v "$DATASET_DIR:$DATASET_DIR:ro")
fi
if [[ -n "$OUTPUT_PATH" && "$OUTPUT_PATH" = /* ]]; then
    mkdir -p "$OUTPUT_PATH"
    VOLUMES+=(-v "$OUTPUT_PATH:$OUTPUT_PATH")
fi
if [[ -n "$SNAPSHOT_PATH" && "$SNAPSHOT_PATH" = /* ]]; then
    SNAPSHOT_DIR="$(cd "$(dirname "$SNAPSHOT_PATH")" && pwd)"
    VOLUMES+=(-v "$SNAPSHOT_DIR:$SNAPSHOT_DIR:ro")
fi

exec "$CONTAINER_CMD" run --rm \
    --network host \
    -e NO_PROXY="host.containers.internal,127.0.0.1,localhost,${NO_PROXY:-}" \
    -e no_proxy="host.containers.internal,127.0.0.1,localhost,${no_proxy:-}" \
    -e DATAAGENT_EVAL_AUTH_TOKEN \
    -e DATAAGENT_EVAL_JUDGE_BASE_URL="${DATAAGENT_EVAL_JUDGE_BASE_URL:-}" \
    -e DATAAGENT_EVAL_JUDGE_TOKEN \
    -e DATAAGENT_EVAL_JUDGE_MODEL="${DATAAGENT_EVAL_JUDGE_MODEL:-}" \
    -e DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS="${DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS:-300}" \
    -e DATAAGENT_EVAL_JUDGE_MAX_TOKENS="${DATAAGENT_EVAL_JUDGE_MAX_TOKENS:-4096}" \
    -e DATAAGENT_EVAL_DATASET="${DATAAGENT_EVAL_DATASET:-}" \
    -e DATAAGENT_EVAL_AGENT_ID="${DATAAGENT_EVAL_AGENT_ID:-}" \
    -e DATAAGENT_EVAL_ENVIRONMENT_LABEL="${DATAAGENT_EVAL_ENVIRONMENT_LABEL:-local}" \
    -e DATAAGENT_EVAL_RUN_LABEL="${DATAAGENT_EVAL_RUN_LABEL:-}" \
    -e DATAAGENT_EVAL_HISTORY_ROOT="${DATAAGENT_EVAL_HISTORY_ROOT:-}" \
    -e DATAAGENT_EVAL_AGENT_SNAPSHOT_PATH="${DATAAGENT_EVAL_AGENT_SNAPSHOT_PATH:-}" \
    -e OPIK_BASE_URL="${OPIK_BASE_URL:-http://127.0.0.1:5173/api}" \
    -e OPIK_PROJECT_NAME="${OPIK_PROJECT_NAME:-dataagent-evals}" \
    "${VOLUMES[@]}" \
    -w /workspace \
    "$IMAGE" "$@"
