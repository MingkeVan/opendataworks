#!/bin/bash

# OpenDataWorks 停止脚本
# 功能：停止所有服务

set -e

echo "========================================="
echo "  OpenDataWorks 停止脚本"
echo "========================================="
echo ""

echo "🛑 停止 OpenDataWorks 服务..."
docker-compose -f docker-compose.prod.yml down

echo ""
echo "✅ 所有服务已停止"
echo ""
echo "📝 提示："
echo "  - 数据卷已保留，重新启动后数据不会丢失"
echo "  - 如需完全清理（包括数据），运行: docker-compose -f docker-compose.prod.yml down -v"
echo ""
