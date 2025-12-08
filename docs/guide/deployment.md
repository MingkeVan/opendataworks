# 部署指南

本指南详细介绍了 OpenDataWorks 的部署流程，包括开发环境快速启动、生产环境部署以及离线部署方案。

## 目录结构说明

```
deploy/
├── .env.example              # 环境变量配置示例
├── docker-compose.dev.yml    # 开发环境编排 (Backend + Frontend + MySQL)
├── docker-compose.prod.yml   # 生产环境编排 (包含 DolphinScheduler)
├── docker-images/            # 离线镜像存储目录 (自动生成/使用)
└── README.md                 # 目录说明
```

## 1. 环境以及依赖

在开始之前，请确保您的环境满足以下要求：

- **Docker**: 20.10+
- **Docker Compose**: 2.0+
- **硬件资源**: 建议 4核 8G 以上 (如果开启 DolphinScheduler)

## 2. 快速开始 (开发/体验)

如果您只是想快速体验 OpenDataWorks，可以使用开发环境配置。该模式仅启动核心服务 (Backend, Frontend, MySQL)，不包含 DolphinScheduler。

```bash
# 1. 准备配置文件
# 在项目根目录下执行
cp deploy/.env.example deploy/.env

# 2. 启动服务
docker compose -f deploy/docker-compose.dev.yml up -d

# 3. 访问应用
# 前端地址: http://localhost:5173
```

## 3. 生产环境部署

生产环境部署包含完整的组件栈 (Backend, Frontend, MySQL, DolphinScheduler)。

### 步骤

1.  **配置环境**:
    复制示例配置并根据实际情况修改（如数据库密码、端口等）。
    ```bash
    cp deploy/.env.example deploy/.env
    vim deploy/.env
    ```

2.  **启动服务**:
    使用 `scripts/` 目录下的辅助脚本进行启动，通过 `../deploy` 目录加载配置。
    ```bash
    bash scripts/start.sh
    ```

3.  **验证**:
    - 前端: `http://<服务器IP>:5173` (或配置的端口)
    - DolphinScheduler: `http://<服务器IP>:12345/dolphinscheduler/ui`

## 4. 离线部署

针对无法访问外网的内网环境，我们提供了离线包制作和部署方案。

### 制作离线包 (在外网机器)

使用 `scripts/create-offline-package.sh` 脚本打包所有镜像、脚本和配置。

```bash
# 制作 Linux AMD64 平台的离线包
bash scripts/create-offline-package.sh --platform linux/amd64
```

执行完成后，会在当前目录下生成 `opendataworks-deployment-<timestamp>.tar.gz`。

### 安装离线包 (在内网机器)

1.  上传 tar.gz 包到目标服务器。
2.  解压并进入目录：
    ```bash
    tar -xzvf opendataworks-deployment-xxxx.tar.gz
    cd opendataworks-deployment
    ```
3.  执行启动脚本：
    ```bash
    # 加载镜像并启动服务
    bash scripts/load-package-and-start.sh
    ```

## 5. 常用操作

所有操作建议在项目根目录下使用 `scripts/` 中的脚本执行。

- **停止服务**:
  ```bash
  bash scripts/stop.sh
  ```

- **重启服务**:
  ```bash
  bash scripts/restart.sh
  ```

- **查看日志**:
  ```bash
  docker compose -f deploy/docker-compose.prod.yml logs -f
  ```

## 常见问题

### 端口冲突
如果 `5173` 或 `8080` 端口被占用，请在 `deploy/.env` 中修改 `FRONTEND_PORT` 或 `BACKEND_PORT`。

### 数据库连接失败
请检查 `.env` 中的 `MYSQL_PASSWORD` 是否与数据库实际密码一致，或者检查防火墙设置。
