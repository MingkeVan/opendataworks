# 离线部署包环境配置保护实施计划

## Goal

保证 OpenDataWorks 和 OpenDataAgent 新生成的离线部署包只包含
`deploy/.env.example`，不携带或覆盖目标服务器的 `deploy/.env`。

## Tasks

1. 修改两个 `create-offline-package.sh`，复制部署资产时排除 `.env`。
2. 删除包内生成、复制和重写 `.env` 的逻辑，仅重写 `.env.example`。
3. 在生成归档前增加 `.env` 制品断言，发现运行时配置立即失败。
4. 更新 OpenDataAgent 原设计中的包布局和环境配置说明。
5. 更新离线部署文档，明确升级解压不会提供或覆盖 `.env`。
6. 增加回归测试，覆盖主包和 OpenDataAgent 包的环境文件策略。

## Verification

- 对两个打包脚本运行 `bash -n`。
- 运行环境文件策略的 targeted pytest。
- 使用伪容器运行时生成两个最小离线归档，检查归档中存在
  `deploy/.env.example` 且不存在任何 `/.env`。
- 确认工作区已有 `deploy/.env` 内容和状态未被修改。

## Rollout and Backout

- 发布后重新生成离线包；已生成的旧离线包不会自动修复。
- 回退只需恢复两个打包脚本和文档。本变更不修改数据库、容器卷或运行中服务。
