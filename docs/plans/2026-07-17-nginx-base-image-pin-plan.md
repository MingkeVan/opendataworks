# Nginx 基础镜像固定实施计划

## Goal

将 OpenDataWorks 主前端和 DataAgent 前端的 Nginx 运行时基线固定为 `nginx:1.30.3-alpine`。

## Scope

- 修改 `frontend/Dockerfile`。
- 修改 `dataagent/dataagent-frontend/Dockerfile`。
- 保持现有 Nginx 配置、构建产物路径、端口和 CMD 不变。
- 不修改 `opendataagent/web/Dockerfile`。

## Execution Steps

1. 在 Docker Hub 官方 Nginx 镜像中确认 `1.30.3-alpine` tag 和多架构支持。
2. 将两个 Dockerfile 的 `FROM nginx:alpine` 替换为 `FROM nginx:1.30.3-alpine`。
3. 检查差异范围和 Dockerfile stage 结构。
4. 在本地容器运行时可用时构建两个镜像；否则交由 GitHub Actions 完成多架构构建验证。

## Verification

- `rg '^FROM nginx:' frontend/Dockerfile dataagent/dataagent-frontend/Dockerfile`
- `git diff --check`
- `docker build -t opendataworks-frontend:nginx-1.30.3 frontend`
- `docker build -t opendataworks-dataagent-frontend:nginx-1.30.3 -f dataagent/dataagent-frontend/Dockerfile .`

## Verification Results

- Docker Hub 官方镜像中已确认 `nginx:1.30.3-alpine` tag 和多架构支持。
- 使用 Podman 5.5.1（ARM64 Linux）完整构建两个镜像，前端构建均成功。
- 两个镜像均已启动验证，`nginx -v` 返回 `nginx/1.30.3`，工作进程正常启动。
- 本地未压缩镜像大小约为：主前端 63.1 MiB，DataAgent 前端 64.7 MiB。
- 构建期间 npm audit 报告 20 个既有依赖漏洞；本次仅固定运行时基础镜像，未调整前端依赖。
- Docker 26 目标机器上的 `pwrite /run/nginx.pid` 场景仍需实机验证；固定版本用于消除浮动基础镜像漂移，不代表宿主机运行时权限问题已修复。

## Rollout

- 合并到 `main` 后，现有 `docker-build.yml` 重建并推送两个前端镜像。
- 在目标 Docker 26 机器上验证镜像启动、健康检查和反向代理路由。

## Backout

- 回退本次 Dockerfile 变更并重建镜像。
- 临时回滚时使用已验证的旧业务镜像 tag 或 digest，不直接切回浮动基础镜像。
