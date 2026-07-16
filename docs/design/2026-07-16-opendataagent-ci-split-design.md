# Opendataagent CI 拆分设计

## Summary

将 `opendataagent-server` 与 `opendataagent-web` 镜像构建从主 `Build and Release` workflow 中拆出，放入独立的 GitHub Actions workflow。
主 workflow 继续负责 OpenDataWorks 主体镜像、主离线包和 GitHub Release；`opendataagent` workflow 单独负责 `opendataagent` 两个在线镜像。

## Current State

- `.github/workflows/docker-build.yml` 同时构建 OpenDataWorks 主镜像、DataAgent 镜像、评测镜像和 `opendataagent` 镜像。
- `opendataagent-server` 构建前需要执行 `opendataagent/scripts/sync-root-skills.sh`，把根 `skills/` 同步到 server 构建上下文。
- 主 release job 仍会调用 `opendataagent/scripts/create-offline-package.sh` 生成 `opendataagent` 离线包。

## Problem

`opendataagent` 镜像构建与主仓库镜像构建混在同一个矩阵中：

- 主 workflow 的职责过宽，Actions run 中难以单独观察 `opendataagent` 构建结果。
- 普通 main push 会在主 workflow 中一起跑 `opendataagent` 镜像构建，即使本次变更不涉及 `opendataagent`。
- 后续要优化 `opendataagent` 构建缓存或发布节奏时，会牵连主 release workflow。

## Goals

- 新增独立的 `Build Opendataagent Images` workflow。
- 从主 `docker-build.yml` 镜像矩阵中移除 `opendataagent-server` 和 `opendataagent-web`。
- 保留 `opendataagent-server` 构建前的 root skills 同步步骤。
- `main` push 仅在 `opendataagent/**`、`skills/**` 或 workflow 自身变化时触发 `opendataagent` 镜像构建。
- `v*` tag 和手动触发仍可构建并推送 `opendataagent` 镜像。
- 不降低离线包压缩等级，不牺牲离线包体积。

## Non-Goals

- 本轮不改变 `opendataagent` 离线包内容和压缩格式。
- 本轮不把 `opendataagent` 离线包发布拆到另一个 release。
- 本轮不改变 Docker Hub 镜像命名、tag 规则或平台矩阵。

## Solution

新增 `.github/workflows/opendataagent-build.yml`：

- 触发条件：
  - `push` 到 `main`，并且改动命中 `opendataagent/**`、`skills/**` 或 workflow 文件
  - `pull_request` 到 `main`，并且改动命中相同路径
  - `push` tag `v*`
  - `workflow_dispatch`
- 构建矩阵：
  - `opendataagent-server`
  - `opendataagent-web`
- 镜像 tag 规则与主 workflow 保持一致：
  - branch / PR ref tag
  - semver tag
  - main 分支 `latest`
  - 手动输入版本 tag

主 `.github/workflows/docker-build.yml`：

- 移除 `opendataagent-server/web` 矩阵项。
- 移除矩阵内专用于 `opendataagent-server` 的 root skills 同步步骤。
- 主 summary 只列出该 workflow 实际构建的 OpenDataWorks / DataAgent 镜像。

## Tradeoffs

- `opendataagent` 镜像构建从主 release run 中解耦，主 run 更短、职责更清晰。
- tag release 时主 workflow 和 `opendataagent` workflow 会并行运行，`opendataagent` 在线镜像发布状态需要查看独立 Actions run。
- 主 release 仍负责 `opendataagent` 离线包附件，因此不会因为在线镜像 workflow 拆分而改变离线包体积。

## Affected Areas

- `.github/workflows/docker-build.yml`
- `.github/workflows/opendataagent-build.yml`
- `docs/design/`
- `docs/plans/`
