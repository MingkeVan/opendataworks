#!/bin/bash

# OpenDataWorks 多架构镜像构建和推送脚本
# 支持: AMD64 (x86_64) 和 ARM64 (aarch64)
# 推送目标: Docker Hub

set -e

echo "========================================="
echo "  OpenDataWorks 多架构镜像构建脚本"
echo "========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 默认配置
DEFAULT_REGISTRY="docker.io"
DEFAULT_NAMESPACE="opendataworks"
VERSION="${VERSION:-latest}"
PLATFORMS="linux/amd64,linux/arm64"

# 解析命令行参数
PUSH=false
BUILD_FRONTEND=true
BUILD_BACKEND=true
BUILD_DOLPHIN=true

usage() {
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -u, --username USER     Docker Hub 用户名 (必需)"
    echo "  -p, --password PASS     Docker Hub 密码/Token (必需)"
    echo "  -n, --namespace NS      Docker Hub 命名空间 (默认: opendataworks)"
    echo "  -v, --version VER       镜像版本标签 (默认: latest)"
    echo "  --push                  构建后推送到 Docker Hub"
    echo "  --no-frontend           跳过前端镜像构建"
    echo "  --no-backend            跳过后端镜像构建"
    echo "  --no-dolphin            跳过 DolphinScheduler 服务镜像构建"
    echo "  --platform PLATFORMS    目标平台 (默认: linux/amd64,linux/arm64)"
    echo "  -h, --help              显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0 -u myuser -p mytoken --push"
    echo "  $0 -u myuser -p mytoken -v v1.0.0 --push"
    echo "  $0 -u myuser -p mytoken --no-dolphin --push"
    echo ""
    exit 1
}

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        -u|--username)
            DOCKER_USERNAME="$2"
            shift 2
            ;;
        -p|--password)
            DOCKER_PASSWORD="$2"
            shift 2
            ;;
        -n|--namespace)
            DEFAULT_NAMESPACE="$2"
            shift 2
            ;;
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        --push)
            PUSH=true
            shift
            ;;
        --no-frontend)
            BUILD_FRONTEND=false
            shift
            ;;
        --no-backend)
            BUILD_BACKEND=false
            shift
            ;;
        --no-dolphin)
            BUILD_DOLPHIN=false
            shift
            ;;
        --platform)
            PLATFORMS="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo -e "${RED}❌ 未知参数: $1${NC}"
            usage
            ;;
    esac
done

# 检查必需参数
if [ "$PUSH" = true ]; then
    if [ -z "$DOCKER_USERNAME" ] || [ -z "$DOCKER_PASSWORD" ]; then
        echo -e "${RED}❌ 错误: 推送到 Docker Hub 需要提供用户名和密码${NC}"
        echo ""
        usage
    fi
fi

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ 错误: 未找到 Docker${NC}"
    echo "请先安装 Docker: https://docs.docker.com/get-docker/"
    exit 1
fi

echo -e "${GREEN}✓ 检测到 Docker${NC}"
docker --version
echo ""

# 检查 Docker Buildx
if ! docker buildx version &> /dev/null; then
    echo -e "${RED}❌ 错误: Docker Buildx 未安装或未启用${NC}"
    echo "请参考: https://docs.docker.com/buildx/working-with-buildx/"
    exit 1
fi

echo -e "${GREEN}✓ 检测到 Docker Buildx${NC}"
docker buildx version
echo ""

# 创建或使用 buildx builder
BUILDER_NAME="opendataworks-builder"
if ! docker buildx inspect $BUILDER_NAME &> /dev/null; then
    echo -e "${YELLOW}📦 创建新的 builder: $BUILDER_NAME${NC}"
    docker buildx create --name $BUILDER_NAME --use --bootstrap
else
    echo -e "${GREEN}✓ 使用现有 builder: $BUILDER_NAME${NC}"
    docker buildx use $BUILDER_NAME
fi
echo ""

# 登录 Docker Hub
if [ "$PUSH" = true ]; then
    echo -e "${YELLOW}🔑 登录 Docker Hub...${NC}"
    echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Docker Hub 登录成功${NC}"
    else
        echo -e "${RED}❌ Docker Hub 登录失败${NC}"
        exit 1
    fi
    echo ""
fi

# 定义镜像名称
FRONTEND_IMAGE="$DEFAULT_NAMESPACE/opendataworks-frontend"
BACKEND_IMAGE="$DEFAULT_NAMESPACE/opendataworks-backend"
DOLPHIN_SERVICE_IMAGE="$DEFAULT_NAMESPACE/opendataworks-dolphin-service"

# 构建参数
BUILD_ARGS="--platform=$PLATFORMS"
if [ "$PUSH" = true ]; then
    BUILD_ARGS="$BUILD_ARGS --push"
else
    BUILD_ARGS="$BUILD_ARGS --load"
    # 注意: --load 只支持单一平台，如果是多平台需要使用 --push
    if [[ "$PLATFORMS" == *","* ]]; then
        echo -e "${YELLOW}⚠️  警告: 多平台构建必须使用 --push 选项${NC}"
        echo -e "${YELLOW}⚠️  将改为仅本地构建模式 (不推送)${NC}"
        BUILD_ARGS="--platform=$PLATFORMS"
    fi
fi

echo "========================================="
echo "  构建配置"
echo "========================================="
echo "版本标签:   $VERSION"
echo "目标平台:   $PLATFORMS"
echo "命名空间:   $DEFAULT_NAMESPACE"
echo "推送镜像:   $PUSH"
echo "构建前端:   $BUILD_FRONTEND"
echo "构建后端:   $BUILD_BACKEND"
echo "构建Dolphin: $BUILD_DOLPHIN"
echo "========================================="
echo ""

# 构建计数
TOTAL_BUILDS=0
SUCCESSFUL_BUILDS=0

# 构建前端镜像
if [ "$BUILD_FRONTEND" = true ]; then
    echo -e "${YELLOW}📦 [1/3] 构建前端镜像...${NC}"
    echo "镜像: $FRONTEND_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    cd frontend
    if docker buildx build $BUILD_ARGS \
        -t $FRONTEND_IMAGE:$VERSION \
        -t $FRONTEND_IMAGE:latest \
        --file Dockerfile \
        . ; then
        echo -e "${GREEN}✅ 前端镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ 前端镜像构建失败${NC}"
    fi
    cd ..
    ((TOTAL_BUILDS++))
    echo ""
fi

# 构建后端镜像
if [ "$BUILD_BACKEND" = true ]; then
    echo -e "${YELLOW}📦 [2/3] 构建后端镜像...${NC}"
    echo "镜像: $BACKEND_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    cd backend
    if docker buildx build $BUILD_ARGS \
        -t $BACKEND_IMAGE:$VERSION \
        -t $BACKEND_IMAGE:latest \
        --file Dockerfile \
        . ; then
        echo -e "${GREEN}✅ 后端镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ 后端镜像构建失败${NC}"
    fi
    cd ..
    ((TOTAL_BUILDS++))
    echo ""
fi

# 构建 DolphinScheduler 服务镜像
if [ "$BUILD_DOLPHIN" = true ]; then
    echo -e "${YELLOW}📦 [3/3] 构建 DolphinScheduler 服务镜像...${NC}"
    echo "镜像: $DOLPHIN_SERVICE_IMAGE:$VERSION"
    echo "平台: $PLATFORMS"

    cd dolphinscheduler-service
    if docker buildx build $BUILD_ARGS \
        -t $DOLPHIN_SERVICE_IMAGE:$VERSION \
        -t $DOLPHIN_SERVICE_IMAGE:latest \
        --file Dockerfile \
        . ; then
        echo -e "${GREEN}✅ DolphinScheduler 服务镜像构建成功${NC}"
        ((SUCCESSFUL_BUILDS++))
    else
        echo -e "${RED}❌ DolphinScheduler 服务镜像构建失败${NC}"
    fi
    cd ..
    ((TOTAL_BUILDS++))
    echo ""
fi

# 总结
echo "========================================="
echo "  构建完成"
echo "========================================="
echo -e "成功: ${GREEN}$SUCCESSFUL_BUILDS${NC}/$TOTAL_BUILDS"
echo ""

if [ $SUCCESSFUL_BUILDS -eq $TOTAL_BUILDS ]; then
    echo -e "${GREEN}🎉 所有镜像构建成功！${NC}"
    echo ""

    if [ "$PUSH" = true ]; then
        echo "✅ 镜像已推送到 Docker Hub:"
        [ "$BUILD_FRONTEND" = true ] && echo "  - $FRONTEND_IMAGE:$VERSION"
        [ "$BUILD_BACKEND" = true ] && echo "  - $BACKEND_IMAGE:$VERSION"
        [ "$BUILD_DOLPHIN" = true ] && echo "  - $DOLPHIN_SERVICE_IMAGE:$VERSION"
        echo ""
        echo "📝 拉取镜像命令:"
        [ "$BUILD_FRONTEND" = true ] && echo "  docker pull $FRONTEND_IMAGE:$VERSION"
        [ "$BUILD_BACKEND" = true ] && echo "  docker pull $BACKEND_IMAGE:$VERSION"
        [ "$BUILD_DOLPHIN" = true ] && echo "  docker pull $DOLPHIN_SERVICE_IMAGE:$VERSION"
    else
        echo "ℹ️  镜像已构建到本地 Docker 镜像仓库"
        echo ""
        echo "查看本地镜像:"
        echo "  docker images | grep opendataworks"
    fi
    echo ""
    echo "📝 下一步:"
    if [ "$PUSH" = false ]; then
        echo "  1. 运行 '$0 --push' 推送镜像到 Docker Hub"
        echo "  2. 或使用 docker-compose.prod.yml 启动服务"
    else
        echo "  1. 在目标服务器上拉取镜像"
        echo "  2. 使用 docker-compose 启动服务"
    fi
    echo ""

    exit 0
else
    echo -e "${RED}❌ 部分镜像构建失败${NC}"
    echo "请检查错误日志并重试"
    exit 1
fi
