# 离线包容器日志落盘设计

- 日期: 2026-06-15
- 主题 slug: offline-container-log-persistence
- 影响范围: 部署（`deploy/docker-compose.prod.yml`、`scripts/`、`deploy/README.md`）
- 不涉及: 后端/前端业务代码、DataAgent runtime 代码、schema

## 背景与现状

离线包使用 `deploy/docker-compose.prod.yml` 部署以下服务：
`mysql`、`redis`、`backend`、`frontend`、`dataagent-frontend`、`dataagent-home-init`（一次性）、`dataagent-backend`、`dataagent-sandbox-runner`、`portal-mcp`。

当前日志现状：

- 仅 `backend` 通过 `backend-logs:/app/logs` 卷持久化应用日志。
- 其余服务只把日志写到容器 stdout/stderr，交给 Docker 默认 `json-file` 驱动。
  - 这些日志在宿主机 `/var/lib/docker/containers/<id>/<id>-json.log`，容器重启不丢，但
    `docker rm` / `docker compose down` 删容器时一并删除，且**默认无轮转、会无限增长**。
  - 离线/隔离环境排障时拿不到一份“宿主机包目录里的日志文件”，不便于打包带走。
- DataAgent 的 task child 容器由 `dataagent-sandbox-runner` 以 `--rm` 启动。
  - **child 日志已落盘**：runner（长驻服务，挂载 `${DATAAGENT_HOST_ROOT}:/dataagent_runtime`）
    会把 child 的 stdout/stderr 实时写入
    `/dataagent_runtime/<topic_id>/logs/<task_id>.log`
    （见 `sandbox_runner_main.py` 的 `_sandbox_task_log_path` / `_append_task_log`）。
  - 因为日志是 runner 写在挂载卷上的文件，不在 child 容器内，`--rm` 删除 child 不影响日志留存。

## 问题

1. 7 个长驻服务（除 backend 外）的容器日志没有落到宿主机包目录的可见文件，排障需要逐个 `docker logs`。
2. 默认 `json-file` 无轮转，长期运行可能撑大磁盘。
3. task child 日志虽已落盘，但散落在 `<HOST_ROOT>/<topic>/logs/` 下，和服务日志不在一处，排障时不直观。

## 目标

- 让全部服务容器日志稳定落在宿主机、容器重启不丢、且有大小上限不撑爆磁盘。
- 提供一条命令，把全部服务日志 + 既有 task child 日志汇总成 `deploy/logs/` 下的普通文件，便于查看/打包带走。
- 不改动 DataAgent runtime 代码（child 日志已由 runner 落盘，无需重复实现）。

## 方案（json-file 轮转 + 收集 sidecar + 一键导出）

经与需求方确认，落盘目标为“打开宿主机目录即见实时日志文件、覆盖除两个 nginx 前端外的全部服务、且不重建业务镜像”。
由于 `dataagent-backend` / `portal-mcp` / `sandbox-runner` 等 python 服务仅 `logging.basicConfig(stream=sys.stdout)`
（见 `dataagent-backend/main.py`），照搬 backend 的“应用写文件 + 挂卷”模式需要改镜像；nginx 前端则把日志软链到
stdout。Docker 也无法把容器 stdout 原生重定向到 bind mount。因此采用**收集 sidecar** 统一镜像外采集，既能实时落盘、又
不改任何业务镜像、也无各服务挂卷的宿主机权限问题。

### 1. 统一 json-file 轮转

在 `docker-compose.prod.yml` 顶部定义扩展锚点并应用到所有服务：

```yaml
x-default-logging: &default-logging
  driver: json-file
  options:
    max-size: "20m"
    max-file: "5"
```

每个服务加 `logging: *default-logging`。效果：单服务日志最多 `20m * 5 = 100MB` 滚动保留，
容器重启/崩溃日志仍在宿主机 docker 数据目录，`docker compose logs` 始终可用。

### 2. 日志收集 sidecar `log-collector`

- 复用 runner 镜像（`opendataworks-dataagent-runner`，内含静态 docker CLI，见 `Dockerfile.runner`），离线包**不新增镜像**。
- 只读挂 docker socket（`${DATAAGENT_DOCKER_SOCKET}:/var/run/docker.sock:ro`）+ bind mount `./logs:/logs`。
- 对 `LOG_COLLECTOR_CONTAINERS` 列出的每个容器跑 `docker logs --follow --timestamps --tail 0`，实时写
  `deploy/logs/<container>.log`；单容器一个后台循环，容器未就绪/重启自动重连。
- 默认目标（仅应用服务）：`backend`、`dataagent-backend`、`dataagent-sandbox-runner`、`portal-mcp`；
  不含 mysql/redis 基础设施与两个 nginx 前端；可在 `.env` 用 `LOG_COLLECTOR_CONTAINERS` 覆盖增减。
- `--tail 0`：只采挂接点之后的新日志，避免重连重复回灌；完整历史由 json-file 轮转与 `dump-logs.sh` 兜底。

打开 `deploy/logs/` 即见各服务实时日志文件，无需敲命令。

### 3. 一键导出脚本 `scripts/dump-logs.sh`

- 复用 `scripts/lib/container-runtime.sh` 检测 compose 命令（docker/podman 兼容）。
- 把每个服务的日志导出到 `deploy/logs/services/<service>.log`。
- 解析 `DATAAGENT_HOST_ROOT`（同 `start.sh` 规则：默认 `/dataagent_runtime`，相对路径按 `deploy/` 展开），
  把既有 task child 日志 `<HOST_ROOT>/<topic>/logs/*.log` 复制到 `deploy/logs/task-child/<topic>/`。
- 输出目录、文件清单、读取失败（权限）以明确提示打印。

排障流程：`scripts/dump-logs.sh` → 打开 `deploy/logs/` 即可看到全部服务 + 所有 task child 日志。

### 4. 文档

更新 `deploy/README.md` 的日志/排障章节：说明 json-file 轮转、`log-collector` 实时落盘、`dump-logs.sh` 用法、child 日志位置。

## 取舍

- 选收集 sidecar 而非“逐应用写文件 + 挂卷”：python 服务与 nginx 默认只 stdout，逐个改要动镜像/配置且不统一；
  Docker 不能原生把 stdout 重定向到挂载文件。sidecar 镜像外统一采集，实时、不改业务镜像、无各服务挂卷权限坑。
- 收集器复用 runner 镜像（已含 docker CLI 且已在离线包内），**不新增镜像**；socket 以**只读**挂载。
- 仅收集应用服务；不收集 mysql/redis 基础设施与两个 nginx 前端（按需求）；如需可通过 `LOG_COLLECTOR_CONTAINERS` 加入。
- child 日志不重复造轮子：runner 已落盘到 `<HOST_ROOT>/<topic>/logs/`，`dump-logs.sh` 负责汇总进 `deploy/logs/`。
- json-file 轮转作为通用兜底：容器重启不丢、有大小上限；收集器/导出脚本不可用时仍可 `docker compose logs`。
- `deploy/logs/` 已被根 `.gitignore` 的 `logs/` 规则忽略，运行期日志不入库。
