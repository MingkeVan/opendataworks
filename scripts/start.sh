#!/bin/bash

# OpenDataWorks 启动脚本
# 功能：启动所有服务

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"
COMPOSE_FILE_NAME="docker-compose.prod.yml"
COMPOSE_FILE="$DEPLOY_DIR/$COMPOSE_FILE_NAME"
ENV_FILE="$DEPLOY_DIR/.env"
ENV_EXAMPLE="$DEPLOY_DIR/.env.example"

if [ ! -f "$COMPOSE_FILE" ]; then
    echo "❌ 错误: 未找到 $COMPOSE_FILE"
    exit 1
fi

echo "========================================="
echo "  OpenDataWorks 启动脚本"
echo "========================================="
echo ""

# 检查 .env 文件
if [ ! -f "$ENV_FILE" ]; then
    if [ ! -f "$ENV_EXAMPLE" ]; then
        echo "❌ 错误: 未找到 $ENV_EXAMPLE"
        exit 1
    fi

    echo "⚠️  警告: 未找到部署配置文件 $ENV_FILE"
    echo "正在从模板复制..."
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    echo "✅ 已创建 $ENV_FILE"
    echo ""
    echo "⚠️  请编辑 $ENV_FILE，确认关键配置："
    echo "   - SPRING_DATASOURCE_URL / USERNAME / PASSWORD 指向 mysql 服务"
    echo "   - DOLPHIN_URL 与 DOLPHIN_TOKEN（外部 DolphinScheduler）"
    echo ""
    read -p "是否继续启动？(y/n) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "已取消启动"
        exit 0
    fi
fi

# 检查 Docker 是否运行
if ! docker info > /dev/null 2>&1; then
    echo "❌ 错误: Docker 未运行，请先启动 Docker"
    exit 1
fi

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

echo "🚀 启动 OpenDataWorks 服务..."
echo ""

# 启动服务
pushd "$DEPLOY_DIR" >/dev/null
if [ "$USE_ENV_FLAG" = true ]; then
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" --env-file "$ENV_FILE" up -d
else
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" up -d
fi

echo ""
echo "⏳ 等待服务启动..."
sleep 5

echo ""
echo "========================================="
echo "  服务状态检查"
echo "========================================="
echo ""

# 显示服务状态
if [ "$USE_ENV_FLAG" = true ]; then
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" --env-file "$ENV_FILE" ps
else
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME" ps
fi
popd >/dev/null

ENV_FLAG_TEXT=""
if [ "$USE_ENV_FLAG" = true ]; then
    ENV_FLAG_TEXT=" --env-file $ENV_FILE"
fi

echo ""
echo "========================================="
echo "  OpenDataWorks 启动完成！"
echo "========================================="
echo ""
echo "📝 服务访问地址："
echo "  前端: http://localhost"
echo "  后端: http://localhost:8080"
echo "  MySQL: localhost:3306"
echo "  DolphinScheduler: 参考 $ENV_FILE 中的 DOLPHIN_URL（Compose 未内置）"
echo ""
echo "📋 常用命令："
echo "  查看日志: ${COMPOSE_CMD[*]} -f $COMPOSE_FILE$ENV_FLAG_TEXT logs -f [service_name]"
echo "  停止服务: scripts/stop.sh"
echo "  重启服务: scripts/restart.sh"
echo "  查看状态: ${COMPOSE_CMD[*]} -f $COMPOSE_FILE$ENV_FLAG_TEXT ps"
echo ""
echo "🔐 默认账号信息："
echo "  MySQL root: root / root123"
echo "  MySQL 应用: opendataworks / opendataworks123"
echo ""
