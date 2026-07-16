# Nginx 基础镜像固定设计

## Summary

将 OpenDataWorks 两个前端运行时基础镜像从浮动的 `nginx:alpine` 固定为 `nginx:1.30.3-alpine`，在保持 Alpine 小体积的同时，避免普通业务提交重建镜像时隐式更换 Nginx 主版本。

## Current State

- `frontend/Dockerfile` 使用 `nginx:alpine`。
- `dataagent/dataagent-frontend/Dockerfile` 使用 `nginx:alpine`。
- GitHub Actions 在 `main` 提交后重建并覆盖两个镜像的 `latest` tag。
- `nginx:alpine` 是浮动 tag，业务代码未改动 Dockerfile 时，重建仍可能引入新的 Nginx 或 Alpine 版本。

## Problem

浮动基础镜像使不同时间的同一业务提交无法保证生成相同的运行时基线，增加了问题定位和回滚成本。

## Goals

- 统一主前端和 DataAgent 前端的 Nginx 基线。
- 固定 Nginx 补丁版本为 `1.30.3`。
- 保留 Alpine 变体，不显著增加镜像体积。
- 不改变现有 Nginx 路由、端口、健康检查和发布 tag 规则。

## Non-Goals

- 本轮不调整 `opendataagent/web` 的独立镜像基线。
- 本轮不修改 PID 目录或容器安全策略。
- 基础镜像固定不解决特定宿主机上的 `pwrite EPERM`，该问题仍需在容器运行时层处理。

## Solution

修改两个运行时 stage：

```dockerfile
FROM nginx:1.30.3-alpine
```

使用带补丁版本的 tag，而不使用仍会跟随稳定分支漂移的 `stable-alpine`。

## Tradeoffs

- 收益：构建基线更稳定，回归和回滚更可预测。
- 代价：后续 Nginx 安全补丁需通过显式 PR 升级，不再由浮动 tag 自动带入。
- 镜像仍使用 Alpine 变体，小体积目标不变。

## Affected Areas

- `frontend/Dockerfile`
- `dataagent/dataagent-frontend/Dockerfile`
- GitHub Actions 生成的两个前端镜像
