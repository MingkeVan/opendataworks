#!/bin/bash

# OpenDataWorks 重启脚本
# 功能：重启所有服务

set -e

echo "========================================="
echo "  OpenDataWorks 重启脚本"
echo "========================================="
echo ""

echo "🔄 重启 OpenDataWorks 服务..."
docker-compose -f docker-compose.prod.yml restart

echo ""
echo "⏳ 等待服务重启..."
sleep 5

echo ""
echo "========================================="
echo "  服务状态"
echo "========================================="
echo ""

# 显示服务状态
docker-compose -f docker-compose.prod.yml ps

echo ""
echo "✅ 服务重启完成"
echo ""
