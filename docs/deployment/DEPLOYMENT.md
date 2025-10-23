# OneData Works - 部署文档

## 📋 目录

- [快速开始](#快速开始)
- [环境要求](#环境要求)
- [详细部署步骤](#详细部署步骤)
- [配置说明](#配置说明)
- [验证部署](#验证部署)
- [故障排查](#故障排查)
- [生产环境部署](#生产环境部署)

---

## 🚀 快速开始

### 使用一键部署脚本（推荐）

```bash
# 1. 克隆仓库
git clone git@github.com:MingkeVan/opendataworks.git
cd opendataworks

# 2. 确保 DolphinScheduler 正在运行
docker-compose up -d

# 3. 运行部署脚本
./quick-deploy.sh
```

**脚本会自动完成**:
- ✅ 检查环境依赖
- ✅ 启动 Python DolphinScheduler 服务
- ✅ 编译并启动后端服务
- ✅ 启动前端开发服务器
- ✅ 运行验证测试

**部署选项**:
```bash
# 跳过前端部署（仅部署后端服务）
./quick-deploy.sh --skip-frontend
```

---

## 📦 环境要求

### 必需软件

| 软件 | 版本要求 | 说明 |
|------|----------|------|
| Java | 8+ | 后端运行环境 |
| Python | 3.8+ | DolphinScheduler 服务 |
| Node.js | 14+ | 前端开发 |
| npm | 6+ | 前端包管理 |
| Docker | 20+ | DolphinScheduler 容器 |
| Docker Compose | 1.29+ | 服务编排 |
| MySQL | 5.7+ | 数据库 |

### 可选工具

| 工具 | 用途 |
|------|------|
| jq | JSON 处理（测试脚本） |
| curl | API 测试 |
| git | 版本控制 |

### 环境变量

```bash
# Java 环境
export JAVA_HOME=/path/to/java
export PATH=$JAVA_HOME/bin:$PATH

# Python 环境
export PYTHONPATH=/path/to/project

# DolphinScheduler 配置
export DS_API_BASE_URL=http://localhost:12345/dolphinscheduler
export PYDS_USER_NAME=admin
export PYDS_USER_PASSWORD=dolphinscheduler123
```

---

## 📝 详细部署步骤

### 1. 准备工作

#### 1.1 克隆代码

```bash
git clone git@github.com:MingkeVan/opendataworks.git
cd opendataworks
```

#### 1.2 启动 DolphinScheduler

```bash
# 使用 Docker Compose 启动
docker-compose up -d

# 验证启动
docker ps | grep dolphinscheduler

# 访问 Web UI
# http://localhost:12345/dolphinscheduler
# 用户名: admin
# 密码: dolphinscheduler123
```

#### 1.3 配置数据库

```bash
# 创建数据库
mysql -u root -p

CREATE DATABASE onedata_portal DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'onedata'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON onedata_portal.* TO 'onedata'@'localhost';
FLUSH PRIVILEGES;
```

#### 1.4 配置应用

```bash
# 后端配置
cp backend/src/main/resources/application.yml.example \
   backend/src/main/resources/application.yml

# 编辑配置文件
vim backend/src/main/resources/application.yml
```

### 2. 部署 Python DolphinScheduler 服务

```bash
cd dolphinscheduler-service

# 创建虚拟环境
python3 -m venv venv
source venv/bin/activate

# 安装依赖
pip install -r requirements.txt

# 启动服务
python -m uvicorn dolphinscheduler_service.main:app \
    --host 0.0.0.0 --port 5001 &

# 验证
curl http://localhost:5001/health
```

**配置文件**: `dolphinscheduler-service/dolphinscheduler_service/config.py`

### 3. 部署后端服务

```bash
cd backend

# 编译项目
./gradlew build

# 启动服务
./gradlew bootRun

# 或使用后台模式
nohup ./gradlew bootRun > /tmp/backend.log 2>&1 &
```

**验证启动**:
```bash
# 检查健康状态
curl http://localhost:8080/actuator/health

# 测试 API
curl http://localhost:8080/api/tasks?pageNum=1&pageSize=10
```

### 4. 部署前端服务

```bash
cd frontend

# 安装依赖
npm install

# 开发模式
npm run dev

# 生产构建
npm run build

# 预览生产构建
npm run preview
```

**访问**: http://localhost:3000

---

## ⚙️ 配置说明

### 后端配置 (application.yml)

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/onedata_portal?useSSL=false&serverTimezone=UTC
    username: onedata
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver

dolphin:
  service-url: http://localhost:5001  # Python 服务地址
  webui-url: http://localhost:12345/dolphinscheduler
  project-name: data-portal
  workflow-name: unified-data-workflow
  tenant-code: default
  worker-group: default
  execution-type: PARALLEL
```

### Python 服务配置 (config.py)

```python
class Settings(BaseSettings):
    # DolphinScheduler API 配置
    api_base_url: str = "http://localhost:12345/dolphinscheduler"
    user_name: str = "admin"
    user_password: str = "dolphinscheduler123"

    # 工作流配置
    workflow_project: str = "data-portal"
    workflow_name: str = "unified-data-workflow"
    user_tenant: str = "default"
    workflow_worker_group: str = "default"
    workflow_execution_type: str = "PARALLEL"
    workflow_release_state: str = "ONLINE"
```

### 前端配置 (vite.config.js)

```javascript
export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
```

---

## ✅ 验证部署

### 自动化验证

使用提供的测试脚本：

```bash
# 完整的工作流生命周期测试
./test-workflow-lifecycle.sh

# 或使用手动测试指南
# 参考 docs/MANUAL_TEST_GUIDE.md
```

### 手动验证步骤

#### 1. 验证服务启动

```bash
# 检查所有服务进程
ps aux | grep -E "DataPortalApplication|uvicorn|vite"

# 检查端口监听
lsof -i :8080    # 后端
lsof -i :5001    # Python 服务
lsof -i :3000    # 前端
lsof -i :12345   # DolphinScheduler
```

#### 2. 验证 API 连接

```bash
# 后端健康检查
curl http://localhost:8080/actuator/health

# Python 服务健康检查
curl http://localhost:5001/health

# DolphinScheduler 登录
curl -X POST "http://localhost:12345/dolphinscheduler/login" \
  -d "userName=admin&userPassword=dolphinscheduler123"
```

#### 3. 验证功能

1. **访问前端**: http://localhost:3000
2. **创建任务**: 通过 UI 创建一个测试任务
3. **发布任务**: 将任务发布到 DolphinScheduler
4. **执行任务**: 测试单任务执行功能
5. **查看监控**: 检查执行监控页面

#### 4. 验证临时工作流清理

```bash
# 执行一个任务
curl -X POST "http://localhost:8080/api/tasks/1/execute"

# 查看 DolphinScheduler 中的工作流
# 应该看到一个名为 test-task-{code} 的临时工作流

# 等待 5 分钟后，临时工作流应该被自动删除
```

---

## 🔧 故障排查

### 常见问题

#### 1. 后端启动失败

**症状**:
```
Error creating bean with name 'dataSource'
```

**解决**:
```bash
# 检查数据库连接
mysql -u onedata -p -h localhost -D onedata_portal

# 检查配置文件
vim backend/src/main/resources/application.yml

# 查看详细错误
tail -f /tmp/backend.log
```

#### 2. Python 服务无法连接 DolphinScheduler

**症状**:
```
Failed to query project: Connection refused
```

**解决**:
```bash
# 检查 DolphinScheduler 是否运行
docker ps | grep dolphinscheduler

# 检查网络连接
curl http://localhost:12345/dolphinscheduler/ui

# 检查配置
vim dolphinscheduler-service/dolphinscheduler_service/config.py
```

#### 3. 前端无法连接后端

**症状**: 浏览器控制台显示 CORS 错误

**解决**:
```bash
# 检查后端是否运行
curl http://localhost:8080/api/tasks

# 检查前端代理配置
vim frontend/vite.config.js

# 重启前端服务
cd frontend
npm run dev
```

#### 4. 临时工作流未被删除

**症状**: DolphinScheduler 中临时工作流持续增加

**解决**:
```bash
# 检查后端日志中的清理日志
tail -f /tmp/backend.log | grep -i cleanup

# 手动触发清理（测试）
curl -X POST "http://localhost:5001/api/v1/workflows/{code}/delete" \
  -H "Content-Type: application/json" \
  -d '{"projectName": "data-portal"}'

# 查看 Python 服务日志
tail -f /tmp/dolphin-service.log | grep -i delete
```

### 日志位置

| 服务 | 日志文件 |
|------|---------|
| 后端 | `/tmp/backend.log` |
| Python 服务 | `/tmp/dolphin-service.log` |
| 前端 | `/tmp/frontend.log` |
| DolphinScheduler | `docker logs <container_id>` |

### 调试命令

```bash
# 查看所有服务状态
ps aux | grep -E "DataPortal|uvicorn|vite|dolphin"

# 查看端口占用
netstat -tulpn | grep -E "8080|5001|3000|12345"

# 重启所有服务
pkill -f "DataPortalApplication"
pkill -f "uvicorn.*dolphinscheduler"
pkill -f "vite"

# 然后重新运行
./quick-deploy.sh
```

---

## 🏭 生产环境部署

### 1. 使用生产模式启动

```bash
# 后端 - 使用 jar 包
cd backend
./gradlew bootJar
java -jar build/libs/data-portal-0.0.1-SNAPSHOT.jar

# Python 服务 - 使用 gunicorn
cd dolphinscheduler-service
gunicorn -w 4 -k uvicorn.workers.UvicornWorker \
  dolphinscheduler_service.main:app \
  --bind 0.0.0.0:5001

# 前端 - 构建静态文件
cd frontend
npm run build
# 使用 nginx 托管 dist 目录
```

### 2. 使用 systemd 服务

创建服务文件:

**后端服务** (`/etc/systemd/system/onedata-backend.service`):
```ini
[Unit]
Description=OneData Portal Backend
After=network.target mysql.service

[Service]
Type=simple
User=onedata
WorkingDirectory=/opt/onedata-works/backend
ExecStart=/usr/bin/java -jar build/libs/data-portal-0.0.1-SNAPSHOT.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

**Python 服务** (`/etc/systemd/system/onedata-dolphin.service`):
```ini
[Unit]
Description=OneData DolphinScheduler Service
After=network.target

[Service]
Type=simple
User=onedata
WorkingDirectory=/opt/onedata-works/dolphinscheduler-service
ExecStart=/opt/onedata-works/dolphinscheduler-service/venv/bin/gunicorn \
  -w 4 -k uvicorn.workers.UvicornWorker \
  dolphinscheduler_service.main:app --bind 0.0.0.0:5001
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启用服务:
```bash
sudo systemctl enable onedata-backend
sudo systemctl enable onedata-dolphin
sudo systemctl start onedata-backend
sudo systemctl start onedata-dolphin
```

### 3. Nginx 配置

```nginx
# /etc/nginx/sites-available/onedata
server {
    listen 80;
    server_name your-domain.com;

    # 前端静态文件
    location / {
        root /opt/onedata-works/frontend/dist;
        try_files $uri $uri/ /index.html;
    }

    # 后端 API 代理
    location /api/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # DolphinScheduler 代理
    location /dolphinscheduler/ {
        proxy_pass http://localhost:12345;
        proxy_set_header Host $host;
    }
}
```

### 4. 使用 Docker 部署（推荐）

创建 `docker-compose.yml`:

```yaml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root_password
      MYSQL_DATABASE: onedata_portal
      MYSQL_USER: onedata
      MYSQL_PASSWORD: onedata_password
    volumes:
      - mysql_data:/var/lib/mysql
    ports:
      - "3306:3306"

  dolphinscheduler:
    image: apache/dolphinscheduler-standalone-server:3.2.0
    environment:
      DATABASE: mysql
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/dolphinscheduler?useSSL=false
    ports:
      - "12345:12345"
    depends_on:
      - mysql

  dolphin-service:
    build: ./dolphinscheduler-service
    ports:
      - "5001:5001"
    environment:
      DS_API_BASE_URL: http://dolphinscheduler:12345/dolphinscheduler
    depends_on:
      - dolphinscheduler

  backend:
    build: ./backend
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/onedata_portal
      DOLPHIN_SERVICE_URL: http://dolphin-service:5001
    depends_on:
      - mysql
      - dolphin-service

  frontend:
    build: ./frontend
    ports:
      - "80:80"
    depends_on:
      - backend

volumes:
  mysql_data:
```

部署:
```bash
docker-compose up -d
```

---

## 📚 相关文档

- [快速开始指南](./README.md)
- [手动测试指南](./docs/MANUAL_TEST_GUIDE.md)
- [工作流生命周期](./docs/TASK_EXECUTION_WORKFLOW_LIFECYCLE.md)
- [浏览器测试结果](./docs/BROWSER_TEST_RESULTS.md)
- [修复文档](./docs/FIX_SUMMARY.md)

---

## 🆘 获取帮助

如果遇到问题:

1. 查看本文档的故障排查章节
2. 检查日志文件
3. 查看 [GitHub Issues](https://github.com/MingkeVan/opendataworks/issues)
4. 提交新的 Issue

---

**最后更新**: 2025-10-20
**维护者**: OneData Works Team
