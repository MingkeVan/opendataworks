# OpenDataWorks Docker 部署快速指南

本指南帮助你快速完成 OpenDataWorks 的 Docker 容器化部署，适用于内网环境。

## 📦 部署包说明

完整的部署包包含以下两部分：

### 1. 源代码包
`opendataworks-source-YYYYMMDD-HHMMSS.zip` - 包含所有源代码和配置文件

### 2. Docker 镜像包
`docker-images/` 目录 - 包含以下镜像 tar 文件：
- `opendataworks-frontend.tar` - 前端镜像 (~50MB)
- `opendataworks-backend.tar` - 后端镜像 (~300MB)
- `opendataworks-dolphin-service.tar` - DolphinScheduler 服务镜像 (~200MB)
- `mysql-8.0.tar` - MySQL 数据库镜像 (~150MB)

## 🚀 快速部署（外网环境）

### 步骤 1: 构建镜像

在有网络的环境中执行：

```bash
# 解压源代码包
unzip opendataworks-source-*.zip
cd opendataworks

# 执行构建脚本（自动构建并导出所有镜像）
scripts/build-images.sh
```

构建完成后，会在 `docker-images/` 目录生成所有镜像 tar 包。

### 步骤 2: 传输到内网

将以下文件传输到内网服务器：

```bash
# 需要传输的文件
docker-images/              # 镜像 tar 包目录
mysql-init/                 # MySQL 初始化脚本
docker-compose.prod.yml     # Docker Compose 配置
.env.example                # 环境变量配置示例
load-images.sh              # 镜像加载脚本
start.sh                    # 启动脚本
stop.sh                     # 停止脚本
restart.sh                  # 重启脚本
DOCKER_DEPLOYMENT.md        # 详细部署文档
```

## 🔧 内网部署步骤

### 步骤 1: 加载镜像

```bash
# 在内网服务器上执行
scripts/deploy/load-images.sh
```

### 步骤 2: 配置环境变量

```bash
# 复制配置文件
cp .env.example .env

# 编辑配置（重要！）
vi .env
```

**必须修改的配置**：

```bash
# DolphinScheduler 配置
DOLPHIN_HOST=your-dolphinscheduler-host    # 改为实际的 DolphinScheduler 地址
DOLPHIN_PORT=12345                          # DolphinScheduler 端口
DOLPHIN_USER=admin                          # 用户名
DOLPHIN_PASSWORD=dolphinscheduler123        # 密码
```

### 步骤 3: 启动服务

```bash
scripts/deploy/start.sh
```

启动后访问：
- **前端**: http://服务器IP:80
- **后端**: http://服务器IP:8080
- **MySQL**: localhost:3306

## 📋 常用命令

```bash
# 启动服务
scripts/deploy/start.sh

# 停止服务
scripts/deploy/stop.sh

# 重启服务
scripts/deploy/restart.sh

# 查看服务状态
docker-compose -f deploy/docker-compose.prod.yml ps

# 查看日志
docker-compose -f deploy/docker-compose.prod.yml logs -f [service_name]
```

## 🔐 默认账号

### MySQL 数据库
- Root: `root` / `root123`
- 应用: `onedata` / `onedata123`
- 数据库: `onedata_portal`

## 📚 详细文档

更多详细信息请参考：[DOCKER_DEPLOYMENT.md](./DOCKER_DEPLOYMENT.md)

## 🚨 故障排查

### 端口被占用
```bash
# 检查端口占用
netstat -tunlp | grep -E "80|8080|8000|3306"

# 修改 deploy/docker-compose.prod.yml 中的端口映射
```

### 服务启动失败
```bash
# 查看日志
docker-compose -f deploy/docker-compose.prod.yml logs backend

# 检查配置
cat .env
```

### MySQL 初始化失败
```bash
# 重新初始化（会删除所有数据）
docker-compose -f deploy/docker-compose.prod.yml down -v
scripts/deploy/start.sh
```

## 💡 注意事项

1. **首次部署**：确保配置 .env 文件中的 DolphinScheduler 连接信息
2. **端口冲突**：如果默认端口被占用，需要修改 deploy/docker-compose.prod.yml
3. **数据备份**：升级前请备份 MySQL 数据
4. **安全性**：生产环境请及时修改默认密码

## 📞 技术支持

如遇问题，请提供：
1. 服务状态：`docker-compose -f deploy/docker-compose.prod.yml ps`
2. 服务日志：`docker-compose -f deploy/docker-compose.prod.yml logs`
3. 系统信息：`uname -a`, `docker version`

---

**快速部署到此完成！祝你部署顺利！** 🎉
