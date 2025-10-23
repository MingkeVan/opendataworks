# Docker 多架构镜像构建指南

本文档介绍如何构建和推送 OpenDataWorks 的多架构 Docker 镜像。

## 目录

- [前置要求](#前置要求)
- [快速开始](#快速开始)
- [详细说明](#详细说明)
- [配置选项](#配置选项)
- [常见问题](#常见问题)

## 前置要求

### 1. Docker 和 Buildx

确保安装了 Docker 和 Buildx 插件：

```bash
# 检查 Docker 版本
docker --version

# 检查 Buildx 版本
docker buildx version
```

如果未安装 Buildx，请参考：https://docs.docker.com/buildx/working-with-buildx/

### 2. Docker Hub 账号

- 注册 Docker Hub 账号: https://hub.docker.com/
- 创建访问令牌（推荐）或使用密码

创建访问令牌步骤：
1. 登录 Docker Hub
2. 进入 Account Settings > Security
3. 点击 "New Access Token"
4. 保存生成的令牌

### 3. 启用 QEMU（用于跨平台构建）

```bash
# 安装 QEMU 模拟器
docker run --privileged --rm tonistiigi/binfmt --install all

# 验证支持的平台
docker buildx inspect --bootstrap
```

## 快速开始

### 方法一：使用配置文件（推荐）

1. **创建配置文件：**

```bash
# 复制配置模板
cp docker-build.env.example docker-build.env

# 编辑配置文件
vim docker-build.env
```

2. **编辑配置文件内容：**

```bash
# Docker Hub 凭证
DOCKER_USERNAME=your-dockerhub-username
DOCKER_PASSWORD=your-dockerhub-token

# 镜像配置
DOCKER_NAMESPACE=opendataworks
VERSION=v1.0.0

# 目标平台
PLATFORMS=linux/amd64,linux/arm64

# 构建选项
PUSH_TO_REGISTRY=true
BUILD_FRONTEND=true
BUILD_BACKEND=true
BUILD_DOLPHIN=true
```

3. **运行构建：**

```bash
scripts/build/build-quick.sh
```

### 方法二：直接使用命令行参数

```bash
# 基本用法
scripts/build/build-multiarch.sh \
  -u your-username \
  -p your-token \
  --push

# 指定版本
scripts/build/build-multiarch.sh \
  -u your-username \
  -p your-token \
  -v v1.0.0 \
  --push

# 只构建特定服务
scripts/build/build-multiarch.sh \
  -u your-username \
  -p your-token \
  --no-dolphin \
  --push
```

## 详细说明

### 构建脚本参数

`build-multiarch.sh` 支持以下参数：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-u, --username` | Docker Hub 用户名 | - |
| `-p, --password` | Docker Hub 密码/令牌 | - |
| `-n, --namespace` | Docker Hub 命名空间 | opendataworks |
| `-v, --version` | 镜像版本标签 | latest |
| `--push` | 构建后推送到 Docker Hub | false |
| `--no-frontend` | 跳过前端镜像构建 | false |
| `--no-backend` | 跳过后端镜像构建 | false |
| `--no-dolphin` | 跳过 DolphinScheduler 服务构建 | false |
| `--platform` | 目标平台 | linux/amd64,linux/arm64 |
| `-h, --help` | 显示帮助信息 | - |

### 支持的平台

- `linux/amd64` - AMD64/x86_64 架构（Intel/AMD 处理器）
- `linux/arm64` - ARM64/aarch64 架构（Apple Silicon, AWS Graviton 等）
- `linux/arm/v7` - ARMv7 架构（树莓派等）

### 构建的镜像

脚本会构建以下镜像：

1. **前端镜像**: `{namespace}/opendataworks-frontend:{version}`
   - 基于 Node.js 18 Alpine
   - 使用 Nginx 提供静态文件

2. **后端镜像**: `{namespace}/opendataworks-backend:{version}`
   - 基于 OpenJDK 17
   - Spring Boot 应用

3. **DolphinScheduler 服务镜像**: `{namespace}/opendataworks-dolphin-service:{version}`
   - Python FastAPI 服务

## 配置选项

### 环境变量

构建过程支持以下环境变量：

```bash
# 设置版本
export VERSION=v1.0.0

# 设置命名空间
export DOCKER_NAMESPACE=mycompany

# 使用环境变量构建
scripts/build/build-multiarch.sh -u user -p pass --push
```

### 仅构建本地镜像（不推送）

```bash
# 仅支持单一平台
scripts/build/build-multiarch.sh --platform linux/amd64

# 多平台必须使用 --push
scripts/build/build-multiarch.sh -u user -p pass --push
```

## 使用构建的镜像

### 拉取镜像

```bash
# 拉取特定版本
docker pull opendataworks/opendataworks-frontend:v1.0.0
docker pull opendataworks/opendataworks-backend:v1.0.0
docker pull opendataworks/opendataworks-dolphin-service:v1.0.0

# 拉取最新版本
docker pull opendataworks/opendataworks-frontend:latest
docker pull opendataworks/opendataworks-backend:latest
docker pull opendataworks/opendataworks-dolphin-service:latest
```

### 更新 docker-compose.yml

如果使用自定义命名空间，需要更新 `docker-compose.prod.yml`：

```yaml
services:
  frontend:
    image: your-namespace/opendataworks-frontend:v1.0.0

  backend:
    image: your-namespace/opendataworks-backend:v1.0.0

  dolphin-service:
    image: your-namespace/opendataworks-dolphin-service:v1.0.0
```

### 启动服务

```bash
docker-compose -f docker-compose.prod.yml up -d
```

## 常见问题

### Q1: 构建时提示 "buildx" 命令不存在

**解决方案：**

```bash
# 更新 Docker 到最新版本
sudo apt update
sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin

# 或者手动安装 buildx
mkdir -p ~/.docker/cli-plugins
curl -SL https://github.com/docker/buildx/releases/download/v0.11.2/buildx-v0.11.2.linux-amd64 \
  -o ~/.docker/cli-plugins/docker-buildx
chmod +x ~/.docker/cli-plugins/docker-buildx
```

### Q2: 多平台构建时提示错误

**解决方案：**

```bash
# 安装 QEMU 支持
docker run --privileged --rm tonistiigi/binfmt --install all

# 创建新的 builder
docker buildx create --name multiarch --use --bootstrap

# 重试构建
scripts/build/build-multiarch.sh -u user -p pass --push
```

### Q3: 推送镜像时认证失败

**解决方案：**

```bash
# 手动登录测试
docker login

# 使用访问令牌而非密码
# 在 Docker Hub 中创建访问令牌，然后使用令牌作为密码
```

### Q4: ARM64 镜像构建时间很长

这是正常现象。在 x86_64 机器上构建 ARM64 镜像需要 QEMU 模拟，会比原生构建慢 5-10 倍。

**优化建议：**

1. 使用 GitHub Actions 或 GitLab CI/CD 的原生 ARM 运行器
2. 使用 AWS Graviton 或其他 ARM 云服务器
3. 考虑使用 Docker Hub 的自动构建功能

### Q5: 如何验证镜像支持的平台

```bash
# 查看镜像支持的平台
docker buildx imagetools inspect opendataworks/opendataworks-frontend:latest
```

### Q6: 构建失败，如何调试？

```bash
# 启用详细日志
docker buildx build --progress=plain ...

# 或者直接在各个服务目录下测试构建
cd frontend
docker buildx build --platform linux/amd64,linux/arm64 -t test:latest .
```

## GitHub Actions 自动构建

可以使用 GitHub Actions 自动化构建和推送：

创建 `.github/workflows/docker-build.yml`：

```yaml
name: Build Multi-Arch Docker Images

on:
  push:
    tags:
      - 'v*'
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up QEMU
        uses: docker/setup-qemu-action@v2

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v2

      - name: Login to Docker Hub
        uses: docker/login-action@v2
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_TOKEN }}

      - name: Build and Push
        run: |
          scripts/build/build-multiarch.sh \
            -u ${{ secrets.DOCKER_USERNAME }} \
            -p ${{ secrets.DOCKER_TOKEN }} \
            -v ${GITHUB_REF#refs/tags/} \
            --push
```

## 更多资源

- [Docker Buildx 文档](https://docs.docker.com/buildx/working-with-buildx/)
- [多平台镜像构建指南](https://docs.docker.com/build/building/multi-platform/)
- [Docker Hub 文档](https://docs.docker.com/docker-hub/)

## 技术支持

如有问题，请提交 Issue 或联系项目维护者。
