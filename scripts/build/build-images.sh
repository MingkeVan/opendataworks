#!/bin/bash

# OpenDataWorks 镜像构建脚本
# 功能：构建所有服务的 Docker 镜像并导出为 tar 包
# 支持 Docker 和 Podman

set -e

echo "========================================="
echo "  OpenDataWorks 镜像构建脚本"
echo "========================================="
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# 检测使用 Docker 还是 Podman
if command -v docker &> /dev/null; then
    CONTAINER_CMD="docker"
    echo "✓ 检测到 Docker"
elif command -v podman &> /dev/null; then
    CONTAINER_CMD="podman"
    echo "✓ 检测到 Podman"
else
    echo "❌ 错误: 未找到 Docker 或 Podman"
    echo "请先安装 Docker 或 Podman"
    exit 1
fi
echo ""

# 定义镜像名称和版本
FRONTEND_IMAGE="opendataworks-frontend:latest"
BACKEND_IMAGE="opendataworks-backend:latest"

# 创建输出目录
OUTPUT_DIR="$REPO_ROOT/deploy/docker-images"
mkdir -p "$OUTPUT_DIR"

echo "📦 步骤 1/2: 构建前端镜像 (AMD64 架构)..."
cd "$REPO_ROOT/frontend"
$CONTAINER_CMD build --platform linux/amd64 -t $FRONTEND_IMAGE .
cd "$REPO_ROOT"
echo "✅ 前端镜像构建完成"
echo ""

echo "📦 步骤 2/2: 构建后端镜像 (AMD64 架构)..."
cd "$REPO_ROOT/backend"
$CONTAINER_CMD build --platform linux/amd64 -t $BACKEND_IMAGE .
cd "$REPO_ROOT"
echo "✅ 后端镜像构建完成"
echo ""

echo "📦 导出镜像为 tar 包..."
echo "  - 导出前端镜像..."
$CONTAINER_CMD save -o "$OUTPUT_DIR/opendataworks-frontend.tar" $FRONTEND_IMAGE
echo "  - 导出后端镜像..."
$CONTAINER_CMD save -o "$OUTPUT_DIR/opendataworks-backend.tar" $BACKEND_IMAGE

echo "✅ 所有镜像导出完成"
echo ""

echo "========================================="
echo "  镜像构建和导出完成！"
echo "========================================="
echo ""
echo "📁 镜像文件保存在: $OUTPUT_DIR/"
ls -lh "$OUTPUT_DIR"/*.tar
echo ""
echo "镜像清单："
echo "  ✓ $FRONTEND_IMAGE"
echo "  ✓ $BACKEND_IMAGE"
echo ""
echo "📝 下一步："
echo "  1. 将 $OUTPUT_DIR/ 目录下的所有 tar 文件传输到内网服务器"
echo "  2. 在内网服务器上运行 scripts/deploy/load-images.sh 加载镜像"
echo "  3. 运行 scripts/deploy/start.sh 启动服务"
echo ""
