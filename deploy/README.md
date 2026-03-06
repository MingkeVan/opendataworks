# OpenDataWorks Deployment Guide

This guide covers both Online (source code) and Offline (deployment package) deployment methods.

## Directory Contents

- `../scripts/start.sh`: Starts the application. Checks for `.env` and creates it if missing.
- `../scripts/stop.sh`: Stops all services.
- `../scripts/restart.sh`: Restarts all services.
- `../scripts/load-images.sh`: Loads Docker images from `docker-images/` (Offline mode).
- `../scripts/create-offline-package.sh`: Utility to generate an offline deployment package.
- `docker-compose.prod.yml`: Production configuration.
- `.env.example`: Template for environment variables.

---

## 1. Online Deployment (From Source)

Use this method if you have internet access and are deploying directly from the source code repository.

### Prerequisites
- Docker and Docker Compose installed.
- Internet access to pull images from Docker Hub.

### Steps
1. **Navigate to deploy directory**:
   ```bash
   cd deploy
   ```

2. **Configure Environment**:
   ```bash
   cp .env.example .env
   # Edit .env for database credentials; DolphinScheduler config is set in System Settings after startup
   vim .env
   ```

3. **Start Services**:
   ```bash
   ../scripts/start.sh
   ```

   DataAgent 默认地址：
   - DataAgent Frontend: `http://localhost:3100/nl2sql`
   - DataAgent Backend: `http://localhost:8900`
   - 可直接编辑挂载文件后生效：
     - `dataagent/.claude/settings.json`（大模型配置 JSON）
     - `dataagent/.claude/skills/`（Skills 目录）
   - DataAgent 前端默认通过同源 `/api` 代理访问后端，无需额外改前端地址

   > **💡 数据库自动初始化**: MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建数据库和用户。无需手动创建数据库。表结构由后端服务的 Flyway 自动创建。

---

## 2. Offline Deployment (Using Package)

Use this method for isolated environments without internet access. You will use the `opendataworks-deployment-*.tar.gz` package.

### Prerequisites
- Docker or Podman installed on the target machine.
- The offline deployment package (`opendataworks-deployment-*.tar.gz`).

### Steps
1. **Extract Package**:
   ```bash
   tar -xzf opendataworks-deployment-*.tar.gz
   cd opendataworks-deployment
   ```

2. **Load Images**:
   This loads all required Docker images from the local archive.
   ```bash
   scripts/load-images.sh
   ```

3. **Configure Environment**:
   ```bash
   cp deploy/.env.example deploy/.env
   # Edit .env and configure settings
   vim deploy/.env
   ```

4. **Start Services**:
   ```bash
   scripts/start.sh
   ```

   DataAgent 默认地址：
   - DataAgent Frontend: `http://localhost:3100/nl2sql`
   - DataAgent Backend: `http://localhost:8900`
   - 离线包内同样保留 `dataagent/.claude/settings.json` 与 `dataagent/.claude/skills/`，可直接编辑

   > **💡 数据库自动初始化**: MySQL 容器首次启动时，会自动执行 `deploy/database/mysql/` 目录下的初始化脚本，创建数据库和用户。无需手动创建数据库。表结构由后端服务的 Flyway 自动创建。

---

## Common Operations

### Stop Services
```bash
# Online (from root)
scripts/stop.sh
# Offline (from package root)
scripts/stop.sh
```

### Restart Services
```bash
# Online (from root)
scripts/restart.sh
# Offline (from package root)
scripts/restart.sh
```

### Check Logs
```bash
# View logs for a specific service (e.g., backend)
docker-compose -f docker-compose.prod.yml logs -f backend
```
