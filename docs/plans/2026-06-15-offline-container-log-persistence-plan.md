# 离线包容器日志落盘实施计划

- 日期: 2026-06-15
- 主题 slug: offline-container-log-persistence
- 配套设计: docs/design/2026-06-15-offline-container-log-persistence-design.md

## 任务

1. `deploy/docker-compose.prod.yml`
   - 顶部新增扩展锚点 `x-default-logging`（json-file，max-size 20m，max-file 5）。
   - 为以下服务各加 `logging: *default-logging`：
     `mysql`、`redis`、`backend`、`frontend`、`dataagent-frontend`、`dataagent-home-init`、
     `dataagent-backend`、`dataagent-sandbox-runner`、`portal-mcp`。

2. `deploy/docker-compose.prod.yml`：新增 `log-collector` sidecar
   - 复用 `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE`（含 docker CLI），不新增镜像。
   - 只读挂 `${DATAAGENT_DOCKER_SOCKET}:/var/run/docker.sock:ro` + bind mount `./logs:/logs`。
   - 对 `LOG_COLLECTOR_CONTAINERS` 每个容器 `docker logs --follow --timestamps --tail 0` 写 `deploy/logs/<container>.log`，自愈重连。
   - 默认目标为 4 个应用服务（backend / dataagent-backend / sandbox-runner / portal-mcp），可在 `.env` 覆盖。

3. `scripts/start.sh`：启动前 `mkdir -p deploy/logs`。

4. 新增 `scripts/dump-logs.sh`
   - 复用 `scripts/lib/container-runtime.sh` 的 `detect_compose_cmd`。
   - 解析 `DATAAGENT_HOST_ROOT`（默认 `/dataagent_runtime`，相对路径按 `deploy/` 展开）。
   - 导出每个服务日志到 `deploy/logs/services/<service>.log`（一次性快照/兜底）。
   - 复制 task child 日志 `<HOST_ROOT>/*/logs/*.log` 到 `deploy/logs/task-child/<topic>/`。
   - 打印输出目录与失败提示；可执行位 `chmod +x`。

5. `deploy/README.md`
   - 更新 “Check Logs” / 排障章节：json-file 轮转、`log-collector` 实时落盘、`dump-logs.sh` 用法、child 日志位置。

## 触及文件

- `deploy/docker-compose.prod.yml`（改：logging 锚点 + log-collector）
- `scripts/start.sh`（改：预建 logs 目录）
- `scripts/dump-logs.sh`（新增，离线包打包脚本已整目录复制 `scripts/`，自动纳入）
- `deploy/README.md`（改）
- `docs/design/2026-06-15-offline-container-log-persistence-design.md`（新增）
- `docs/plans/2026-06-15-offline-container-log-persistence-plan.md`（新增）

## 验证

- `docker compose -f deploy/docker-compose.prod.yml config`（或 `--env-file`）校验 logging 锚点与 log-collector 合法、各服务 logging 生效。
- `bash -n scripts/dump-logs.sh`、`bash -n scripts/start.sh` 语法检查。
- 若本地容器运行时可用：起栈后确认 `deploy/logs/<container>.log` 由 collector 实时写入；
  跑 `scripts/dump-logs.sh` 确认 `deploy/logs/services/*.log` 生成；
  若跑过智能问数，确认 `deploy/logs/task-child/` 有 child 日志。
- 文档检查：目录放置、命名、交叉链接。

## 回滚

- 改动均为部署配置/脚本/文档，回滚即还原 compose 的 logging 锚点、删除 `dump-logs.sh` 与文档。
- 不涉及数据迁移或运行时代码，无数据风险。
