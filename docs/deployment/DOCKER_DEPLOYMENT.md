# OpenDataWorks Docker 部署指南

本文档说明如何在内网环境部署 OpenDataWorks 系统。

## 📋 目录结构

部署包应包含以下文件和目录：

```
opendataworks/
├── docker-images/              # Docker 镜像 tar 包目录
│   ├── opendataworks-frontend.tar
│   ├── opendataworks-backend.tar
│   ├── opendataworks-dolphin-service.tar
│   └── mysql-8.0.tar
├── mysql-init/                 # MySQL 初始化脚本
│   ├── 00-init.sql
│   ├── 01-schema.sql
│   ├── 02-inspection_schema.sql
│   └── 03-sample_data.sql
├── deploy/docker-compose.prod.yml     # Docker Compose 配置文件
├── .env.example                # 环境变量配置示例
├── load-images.sh              # 镜像加载脚本
├── start.sh                    # 服务启动脚本
├── stop.sh                     # 服务停止脚本
└── restart.sh                  # 服务重启脚本
```

## 🔧 系统要求

### 硬件要求
- CPU: 4核及以上
- 内存: 8GB 及以上
- 磁盘: 50GB 可用空间

### 软件要求
- 操作系统: Linux (CentOS 7+, Ubuntu 18.04+) 或 macOS
- Docker: 20.10+
- Docker Compose: 1.29+

## 📦 部署步骤

### 步骤 1: 安装 Docker 和 Docker Compose

如果内网服务器尚未安装 Docker，请参考以下步骤：

#### CentOS/RHEL
```bash
# 安装 Docker
sudo yum install -y yum-utils
sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo yum install -y docker-ce docker-ce-cli containerd.io

# 启动 Docker
sudo systemctl start docker
sudo systemctl enable docker

# 安装 Docker Compose
sudo curl -L "https://github.com/docker/compose/releases/download/v2.20.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
```

#### Ubuntu/Debian
```bash
# 安装 Docker
sudo apt-get update
sudo apt-get install -y docker.io

# 启动 Docker
sudo systemctl start docker
sudo systemctl enable docker

# 安装 Docker Compose
sudo curl -L "https://github.com/docker/compose/releases/download/v2.20.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
```

### 步骤 2: 传输部署文件

将整个部署目录传输到内网服务器：

```bash
# 示例：使用 scp
scp -r opendataworks/ user@internal-server:/opt/
```

### 步骤 3: 加载 Docker 镜像

进入部署目录并执行镜像加载脚本：

```bash
cd /opt/opendataworks
scripts/deploy/load-images.sh
```

脚本会自动加载以下镜像：
- opendataworks-frontend:latest
- opendataworks-backend:latest
- opendataworks-dolphin-service:latest
- mysql:8.0

### 步骤 4: 配置环境变量

复制环境变量配置文件并编辑：

```bash
cp .env.example .env
vi .env
```

**重要配置项**（必须修改）：

```bash
# DolphinScheduler 配置
DOLPHIN_HOST=your-dolphinscheduler-host    # DolphinScheduler 服务器地址
DOLPHIN_PORT=12345                          # DolphinScheduler 端口
DOLPHIN_USER=admin                          # DolphinScheduler 用户名
DOLPHIN_PASSWORD=dolphinscheduler123        # DolphinScheduler 密码
DOLPHIN_TENANT=default                      # DolphinScheduler 租户
```

可选配置项：

```bash
# MySQL 配置（如需修改默认密码）
MYSQL_ROOT_PASSWORD=root123
MYSQL_PASSWORD=onedata123
```

### 步骤 5: 启动服务

执行启动脚本：

```bash
scripts/deploy/start.sh
```

启动过程需要 1-2 分钟，脚本会：
1. 检查 .env 配置文件
2. 启动所有服务容器
3. 等待服务就绪
4. 显示服务状态

### 步骤 6: 验证部署

访问以下地址验证服务是否正常：

- **前端界面**: http://服务器IP:80
- **后端API**: http://服务器IP:8080/actuator/health
- **DolphinScheduler 服务**: http://服务器IP:8000/health

## 🎯 服务说明

### 服务列表

| 服务名称 | 容器名称 | 端口 | 说明 |
|---------|---------|------|------|
| frontend | opendataworks-frontend | 80 | 前端界面 |
| backend | opendataworks-backend | 8080 | 后端 API |
| dolphin-service | opendataworks-dolphin-service | 8000 | DolphinScheduler 集成服务 |
| mysql | opendataworks-mysql | 3306 | MySQL 数据库 |

### 数据持久化

系统使用 Docker 卷持久化数据：

- **mysql-data**: MySQL 数据库数据
- **backend-logs**: 后端服务日志

数据存储位置：`/var/lib/docker/volumes/`

## 🔨 常用操作

### 查看服务状态

```bash
docker-compose -f deploy/docker-compose.prod.yml ps
```

### 查看服务日志

```bash
# 查看所有服务日志
docker-compose -f deploy/docker-compose.prod.yml logs -f

# 查看特定服务日志
docker-compose -f deploy/docker-compose.prod.yml logs -f frontend
docker-compose -f deploy/docker-compose.prod.yml logs -f backend
docker-compose -f deploy/docker-compose.prod.yml logs -f dolphin-service
docker-compose -f deploy/docker-compose.prod.yml logs -f mysql
```

### 停止服务

```bash
scripts/deploy/stop.sh
```

### 重启服务

```bash
# 重启所有服务
scripts/deploy/restart.sh

# 重启特定服务
docker-compose -f deploy/docker-compose.prod.yml restart backend
```

### 进入容器

```bash
# 进入后端容器
docker exec -it opendataworks-backend /bin/sh

# 进入 MySQL 容器
docker exec -it opendataworks-mysql mysql -uroot -proot123
```

## 🔐 默认账号信息

### MySQL 数据库

- **Root 账号**:
  - 用户名: `root`
  - 密码: `root123`

- **应用账号**:
  - 用户名: `onedata`
  - 密码: `onedata123`
  - 数据库: `onedata_portal`

### 连接 MySQL

```bash
# 从宿主机连接
mysql -h 127.0.0.1 -P 3306 -uonedata -ponedata123 onedata_portal

# 从容器内连接
docker exec -it opendataworks-mysql mysql -uonedata -ponedata123 onedata_portal
```

## 🚨 故障排查

### 服务无法启动

1. 检查端口占用：
```bash
netstat -tunlp | grep -E "80|8080|8000|3306"
```

2. 查看容器日志：
```bash
docker-compose -f deploy/docker-compose.prod.yml logs backend
```

3. 检查 Docker 资源：
```bash
docker stats
```

### MySQL 初始化失败

如果数据库初始化失败，可以重新初始化：

```bash
# 停止并删除所有容器和数据卷
docker-compose -f deploy/docker-compose.prod.yml down -v

# 重新启动
scripts/deploy/start.sh
```

### 后端无法连接数据库

检查以下几点：
1. MySQL 容器是否正常运行
2. .env 中数据库配置是否正确
3. 后端容器能否访问 MySQL 容器

```bash
# 测试网络连通性
docker exec opendataworks-backend ping -c 3 mysql
```

### DolphinScheduler 连接失败

检查 .env 文件中的 DolphinScheduler 配置：
- DOLPHIN_HOST 是否可达
- DOLPHIN_PORT 是否正确
- 用户名密码是否正确

## 📊 性能优化

### JVM 参数调优

编辑 backend 服务的环境变量：

```yaml
# deploy/docker-compose.prod.yml
services:
  backend:
    environment:
      JAVA_OPTS: "-Xms1g -Xmx2g -XX:+UseG1GC"
```

### MySQL 参数调优

对于生产环境，可以调整 MySQL 配置：

```yaml
# deploy/docker-compose.prod.yml
services:
  mysql:
    command:
      - --max_connections=500
      - --innodb_buffer_pool_size=2G
```

## 🔄 升级部署

### 镜像升级步骤

1. 停止服务：
```bash
scripts/deploy/stop.sh
```

2. 加载新镜像：
```bash
scripts/deploy/load-images.sh
```

3. 重新启动：
```bash
scripts/deploy/start.sh
```

### 数据备份

在升级前建议备份数据：

```bash
# 备份 MySQL 数据
docker exec opendataworks-mysql mysqldump -uroot -proot123 onedata_portal > backup_$(date +%Y%m%d).sql

# 备份数据卷
docker run --rm -v opendataworks_mysql-data:/data -v $(pwd):/backup alpine tar czf /backup/mysql-data-backup.tar.gz -C /data .
```

## 📞 技术支持

如遇到问题，请提供以下信息：

1. 服务状态：`docker-compose -f deploy/docker-compose.prod.yml ps`
2. 服务日志：`docker-compose -f deploy/docker-compose.prod.yml logs`
3. 系统信息：`uname -a`, `docker version`, `docker-compose version`

---

**部署完成后，请及时修改默认密码以确保系统安全！**
