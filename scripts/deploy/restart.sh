#!/bin/bash

# OpenDataWorks 重启脚本
# 功能：重启所有服务

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"
COMPOSE_FILE_NAME="docker-compose.prod.yml"
COMPOSE_FILE="$DEPLOY_DIR/$COMPOSE_FILE_NAME"
ENV_FILE="$DEPLOY_DIR/.env"
USE_ENV_FLAG=false

if command -v docker-compose &> /dev/null; then
    COMPOSE_CMD=(docker-compose)
elif command -v docker &> /dev/null && docker compose version &> /dev/null; then
    COMPOSE_CMD=(docker compose)
    USE_ENV_FLAG=true
else
    echo "❌ 错误: 未找到 docker-compose 或 docker compose"
    exit 1
fi

echo "========================================="
echo "  OpenDataWorks 重启脚本"
echo "========================================="
echo ""

echo "🔄 重启 OpenDataWorks 服务..."
pushd "$DEPLOY_DIR" >/dev/null
ENV_FLAG_ARGS=()
if [ "$USE_ENV_FLAG" = true ] && [ -f "$ENV_FILE" ]; then
    ENV_FLAG_ARGS=(--env-file "$ENV_FILE")
fi

"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" "${ENV_FLAG_ARGS[@]}" restart

echo ""
echo "⏳ 等待服务重启..."
sleep 5

echo ""
echo "========================================="
echo "  服务状态"
echo "========================================="
echo ""

# 显示服务状态
"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" "${ENV_FLAG_ARGS[@]}" ps
popd >/dev/null

echo ""
echo "✅ 服务重启完成"
echo ""
