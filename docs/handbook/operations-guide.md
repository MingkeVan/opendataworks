# 运维与部署指南

整合 `docs/deployment/*.md`、`DOCKER_BUILD.md`、`RESTART_GUIDE.md` 等文件，给出统一的部署与回滚流程。

## 部署方式对比

| 方式 | 适用场景 | 入口 |
| --- | --- | --- |
| Docker Compose | PoC、本地/测试环境一键启动 | `deploy/docker-compose.prod.yml` |
| 离线包 | 无外网、需要提前拉取镜像 | `scripts/offline/create-offline-package-from-dockerhub.sh` |
| 裸机/systemd | 生产环境分层部署、需要自定义安全策略 | `docs/handbook/operations-guide.md` 本文 + `scripts/deploy/*.sh` |

## Docker Compose

```bash
cd deploy
cp docker-compose.prod.yml docker-compose.yml
# 如果已推送镜像，可直接 docker compose up
# 需本地构建时：
cd ..
scripts/build/build-multiarch.sh --namespace your-registry
```

- MySQL 卷：`mysql-data`
- 后端日志卷：`backend-logs`
- 环境变量重点：
  - `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE=opendataworks`, `MYSQL_USER=opendataworks`
  - `SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/opendataworks`
  - `DOLPHIN_*` 放在 `.env` 或 Compose env 中
- 需要扩展端口（如前端 80 → 8081）时，直接修改 `ports`。

## 离线部署

1. 执行 `scripts/offline/create-offline-package-from-dockerhub.sh`，生成 `opendataworks-deployment-*.tar.gz`。
2. 目标机器解压后包含：Docker Compose 文件、镜像 tar、配置样例、`database/mysql` 脚本。
3. 使用 `scripts/deploy/load-package-and-start.sh --package <tar>` 自动加载镜像并启动。

## 裸机部署 (systemd)

### 后端

1. 将 `backend/build/libs/opendataworks-backend-*.jar` 拷贝至 `/opt/opendataworks/backend/`。
2. 创建 systemd 服务 `/etc/systemd/system/opendataworks-backend.service`：

```ini
[Unit]
Description=OpenDataWorks Backend
After=network.target

[Service]
User=opendataworks
WorkingDirectory=/opt/opendataworks/backend
ExecStart=/usr/bin/java -jar opendataworks-backend.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

3. `sudo systemctl daemon-reload && sudo systemctl enable --now opendataworks-backend`。

### Python Service

类似，创建 `opendataworks-dolphin.service`，`WorkingDirectory=/opt/opendataworks/dolphinscheduler-service`，`ExecStart=/opt/.../venv/bin/gunicorn -c gunicorn_conf.py dolphinscheduler_service.main:app`。

### 前端

1. `npm run build` 产物复制到 `/opt/opendataworks/frontend/dist`。
2. 使用 Nginx 反向代理：

```nginx
server {
    listen 80;
    server_name opendataworks.example.com;

    location / {
        root /opt/opendataworks/frontend/dist;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
    }
}
```

## 配置清单

| 组件 | 文件 | 说明 |
| --- | --- | --- |
| Backend | `application.yml` | DB、Dolphin/Dinky、日志、CORS |
| Python Service | `.env` | Dolphin host、port、user、token、project、tenant |
| Frontend | `.env.production` | `VITE_API_BASE`, `VITE_DOLPHIN_URL` |
| Compose | `deploy/docker-compose.prod.yml` | 镜像/tag/端口/卷 |

## 滚动/重启

- Docker：`docker compose restart backend` / `logs -f backend`。
- systemd：`sudo systemctl restart opendataworks-backend`。
- 数据库迁移后，可运行 `scripts/dev/init-database.sh`（仅开发环境）或在生产环境执行 Flyway 脚本。

## 镜像构建与大小控制

- 构建脚本：`scripts/build/build-multiarch.sh`，支持多架构 `linux/amd64,linux/arm64`。
- 产物：`opendataworks-backend`, `opendataworks-dolphin-service`, `opendataworks-frontend`。
- 构建前确保 `frontend/dist`、`backend/target` 已存在，否则脚本会自动触发构建。

## 运维 checklist

1. **启动前**：确认 `.env`、`application.yml`、数据库账号、Dolphin API 可连通。
2. **启动中**：观察 Compose/systemd 日志；若 Backend 启动 >60s，优先检查 MySQL 连接。
3. **启动后**：
   - `curl http://<host>:8080/api/actuator/health`
   - `curl http://<host>:5001/health`
   - `mysql -u opendataworks -popendataworks123 -h <db> opendataworks -e "SHOW TABLES"`
   - 前端页面是否可打开/登录
4. **巡检**：定期查看 `inspection_issue`、`task_execution_log`，配合 [testing-guide.md](testing-guide.md) 的脚本回归关键流程。

