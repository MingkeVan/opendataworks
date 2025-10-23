# OpenDataWorks 完整部署包说明

## 📦 部署包清单

本目录包含两个部署包，用于不同的场景：

### 1. 源代码包（开发/构建用）
```
opendataworks-source-20251021-095100.zip  (640 KB)
```
包含完整源代码、配置文件和 Dockerfile，用于：
- 在有网络的环境中构建 Docker 镜像
- 查看和修改源代码
- 开发和测试

### 2. 内网部署包（生产部署用）
```
opendataworks-deployment-20251021-135319.tar.gz  (635 MB)
```
包含所有预构建的 Docker 镜像和部署脚本，用于：
- **内网环境直接部署**（推荐）
- 无需网络连接
- 开箱即用

## 🚀 内网部署步骤

### 前置准备

1. **将部署包传输到内网服务器**
```bash
# 只需传输这一个文件
opendataworks-deployment-20251021-135319.tar.gz
```

2. **解压部署包**
```bash
tar -xzf opendataworks-deployment-20251021-135319.tar.gz
cd deployment-package
```

### 快速部署（3 步）

#### 步骤 1: 加载 Docker 镜像
```bash
chmod +x *.sh
scripts/deploy/load-images.sh
```
这会加载以下镜像：
- opendataworks-frontend:latest
- opendataworks-backend:latest
- opendataworks-dolphin-service:latest
- mysql:8.0

#### 步骤 2: 配置环境变量
```bash
cp .env.example .env
vi .env
```

**必须修改的配置**（DolphinScheduler 连接信息）：
```bash
DOLPHIN_HOST=your-dolphinscheduler-host    # 改为实际地址
DOLPHIN_PORT=12345
DOLPHIN_USER=admin
DOLPHIN_PASSWORD=dolphinscheduler123
DOLPHIN_TENANT=default
```

#### 步骤 3: 启动服务
```bash
scripts/deploy/start.sh
```

等待 1-2 分钟，服务启动完成后访问：
- **前端**: http://服务器IP:80
- **后端**: http://服务器IP:8080
- **MySQL**: localhost:3306

## 📋 系统要求

### 硬件要求
- CPU: 4核及以上
- 内存: 8GB 及以上
- 磁盘: 50GB 可用空间

### 软件要求
- 操作系统: Linux (CentOS 7+, Ubuntu 18.04+) 或 macOS
- Docker: 20.10+ 或 Podman
- Docker Compose: 1.29+

### 端口要求
确保以下端口未被占用：
- 80 - 前端 Web 服务
- 8080 - 后端 API 服务
- 8000 - DolphinScheduler 服务
- 3306 - MySQL 数据库

## 🔐 默认账号信息

### MySQL 数据库
- **Root 账号**:
  - 用户名: `root`
  - 密码: `root123`

- **应用账号**:
  - 用户名: `onedata`
  - 密码: `onedata123`
  - 数据库: `onedata_portal`

⚠️ **重要**: 生产环境请及时修改默认密码！

## 🔧 常用操作

### 服务管理
```bash
scripts/deploy/start.sh      # 启动所有服务
scripts/deploy/stop.sh       # 停止所有服务
scripts/deploy/restart.sh    # 重启所有服务
```

### 查看服务状态
```bash
docker-compose -f deploy/docker-compose.prod.yml ps
```

### 查看日志
```bash
# 查看所有服务日志
docker-compose -f deploy/docker-compose.prod.yml logs -f

# 查看特定服务日志
docker-compose -f deploy/docker-compose.prod.yml logs -f backend
docker-compose -f deploy/docker-compose.prod.yml logs -f frontend
docker-compose -f deploy/docker-compose.prod.yml logs -f mysql
```

### 进入容器
```bash
# 进入后端容器
docker exec -it opendataworks-backend /bin/sh

# 进入 MySQL 容器
docker exec -it opendataworks-mysql mysql -uroot -proot123
```

## 📚 详细文档

部署包内包含以下文档：

1. **README_DEPLOYMENT.md** - 部署包说明（本文件）
2. **DOCKER_QUICK_START.md** - 快速开始指南
3. **DOCKER_DEPLOYMENT.md** - 完整部署文档
4. **README.md** - 项目说明文档

## 🚨 故障排查

### 1. 端口被占用
```bash
# 检查端口占用
netstat -tunlp | grep -E "80|8080|8000|3306"

# 解决方法：修改 deploy/docker-compose.prod.yml 中的端口映射
```

### 2. 服务启动失败
```bash
# 查看具体错误
docker-compose -f deploy/docker-compose.prod.yml logs backend

# 检查配置
cat .env
```

### 3. MySQL 初始化失败
```bash
# 完全重新初始化（会删除所有数据）
docker-compose -f deploy/docker-compose.prod.yml down -v
scripts/deploy/start.sh
```

### 4. 无法连接 DolphinScheduler
检查 `.env` 文件中的配置：
- DOLPHIN_HOST 是否可达
- DOLPHIN_PORT 是否正确
- 用户名密码是否正确

## 📊 服务架构

```
┌─────────────────────────────────────────────────┐
│              Nginx (Port 80)                    │
│         opendataworks-frontend                  │
│  ┌──────────────────────────────────────────┐  │
│  │  Static Files + API Proxy to Backend    │  │
│  └──────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│        Spring Boot (Port 8080)                  │
│         opendataworks-backend                   │
│  ┌──────────────────────────────────────────┐  │
│  │  REST API + Business Logic               │  │
│  └──────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
         │                           │
         ▼                           ▼
┌──────────────────┐      ┌──────────────────────┐
│   MySQL 8.0      │      │  DolphinScheduler    │
│   (Port 3306)    │      │  Service (Port 8000) │
│                  │      │                      │
│  onedata_portal  │      │  Python FastAPI      │
└──────────────────┘      └──────────────────────┘
```

## 🔄 数据持久化

系统使用 Docker 卷持久化数据：
- **mysql-data**: MySQL 数据库数据
- **backend-logs**: 后端服务日志

数据存储位置: `/var/lib/docker/volumes/`

备份数据卷:
```bash
docker run --rm -v opendataworks_mysql-data:/data \
  -v $(pwd):/backup alpine \
  tar czf /backup/mysql-backup-$(date +%Y%m%d).tar.gz -C /data .
```

## 📞 技术支持

遇到问题时，请提供以下信息：

1. **服务状态**
```bash
docker-compose -f deploy/docker-compose.prod.yml ps
```

2. **服务日志**
```bash
docker-compose -f deploy/docker-compose.prod.yml logs
```

3. **系统信息**
```bash
uname -a
docker version
docker-compose version
```

## 📝 版本信息

- **构建日期**: 2025-10-21
- **镜像版本**: latest
- **部署包大小**: 635 MB
- **包含镜像**:
  - opendataworks-frontend:latest (55 MB)
  - opendataworks-backend:latest (439 MB)
  - opendataworks-dolphin-service:latest (422 MB)
  - mysql:8.0 (757 MB)

---

**祝你部署顺利！如有问题，请参考详细文档或联系技术支持。** 🎉
