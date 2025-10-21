#!/bin/bash

# OpenDataWorks 启动脚本
# 功能：启动所有服务

set -e

echo "========================================="
echo "  OpenDataWorks 启动脚本"
echo "========================================="
echo ""

# 检查 .env 文件
if [ ! -f ".env" ]; then
    echo "⚠️  警告: 未找到 .env 文件"
    echo "正在从 .env.example 复制..."
    cp .env.example .env
    echo "✅ 已创建 .env 文件"
    echo ""
    echo "⚠️  请编辑 .env 文件，配置 DolphinScheduler 连接信息："
    echo "   - DOLPHIN_HOST"
    echo "   - DOLPHIN_PORT"
    echo "   - DOLPHIN_USER"
    echo "   - DOLPHIN_PASSWORD"
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

# 检查 docker-compose 是否安装
if ! command -v docker-compose &> /dev/null; then
    echo "❌ 错误: 未找到 docker-compose 命令"
    echo "请安装 Docker Compose"
    exit 1
fi

echo "🚀 启动 OpenDataWorks 服务..."
echo ""

# 启动服务
docker-compose -f docker-compose.prod.yml up -d

echo ""
echo "⏳ 等待服务启动..."
sleep 5

echo ""
echo "========================================="
echo "  服务状态检查"
echo "========================================="
echo ""

# 显示服务状态
docker-compose -f docker-compose.prod.yml ps

echo ""
echo "========================================="
echo "  OpenDataWorks 启动完成！"
echo "========================================="
echo ""
echo "📝 服务访问地址："
echo "  前端: http://localhost"
echo "  后端: http://localhost:8080"
echo "  DolphinScheduler 服务: http://localhost:8000"
echo "  MySQL: localhost:3306"
echo ""
echo "📋 常用命令："
echo "  查看日志: docker-compose -f docker-compose.prod.yml logs -f [service_name]"
echo "  停止服务: ./stop.sh"
echo "  重启服务: ./restart.sh"
echo "  查看状态: docker-compose -f docker-compose.prod.yml ps"
echo ""
echo "🔐 默认账号信息："
echo "  MySQL root: root / root123"
echo "  MySQL 应用: onedata / onedata123"
echo ""
