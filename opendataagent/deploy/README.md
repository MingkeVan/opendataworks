# Opendataagent Deploy

## 在线部署

```bash
cd opendataagent/deploy
cp .env.example .env
bash ../scripts/start.sh
```

如果需要手工调用 Compose，请显式指定项目名，避免与根 OpenDataWorks 部署同为 `deploy` 项目：

```bash
docker compose --project-name opendataagent --env-file .env up -d --build
```

默认端口：

- Web: `18080`
- Server: `18900`
- MySQL: `13306`

迁移提示：如果同一机器上 OpenDataWorks 和 opendataagent 都曾经从名为 `deploy` 的目录直接执行过 `docker compose up`，请在维护窗口分别停掉旧容器后再用上述脚本重启。不要只对其中一套部署执行 `--remove-orphans`，因为旧容器可能共享同一个 Compose project label。

## 离线部署包

在可联网且容器运行时可用的机器上生成：

```bash
bash opendataagent/scripts/create-offline-package.sh
```

默认输出：

- `output/releases/opendataagent/opendataagent-deployment-<tag>.tar.gz`

离线包内容包括：

- `opendataagent-server` 镜像
- `opendataagent-web` 镜像
- `mysql:8.0` 镜像
- `shared-skills/` 快照
- `deploy/.env.example` 配置模板；不包含运行时 `deploy/.env`
- 离线镜像加载和启动脚本

因此升级解压不会覆盖服务器现有的 `deploy/.env`。首次安装时，
`load-package-and-start.sh` 只会在 `.env` 不存在时从模板初始化。

## 安装离线包

```bash
tar -xzf opendataagent-deployment-<tag>.tar.gz
cd opendataagent-deployment
bash scripts/load-package-and-start.sh --package .
```

或一步到位：

```bash
bash opendataagent/scripts/load-package-and-start.sh \
  --package opendataagent-deployment-<tag>.tar.gz
```
