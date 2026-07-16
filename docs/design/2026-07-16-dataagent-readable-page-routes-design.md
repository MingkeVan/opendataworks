# DataAgent 可读页面路由设计

- 日期: 2026-07-16
- 状态: 已实施
- 范围: `dataagent/dataagent-frontend/`、相关认证回跳与文档
- 变更规模: 中等（公开页面 URL 迁移）
- 关联计划: `docs/plans/2026-07-16-dataagent-readable-page-routes-plan.md`

## 1. 背景与问题

独立 DataAgent SPA 当前把所有页面放在 `/intelligent-query/*` 下，并保留 `/nl2sql` 页面入口。独立站点本身已经表达 DataAgent 上下文，这两个前缀既重复又暴露实现术语；其中 `nl2sql` 尤其不适合作为面向用户的页面路径。

变更前的正式页面包括:

- `/intelligent-query/chat`
- `/intelligent-query/skills`
- `/intelligent-query/agents`
- `/intelligent-query/models`
- `/intelligent-query/widget`

## 2. 目标与非目标

### 目标

- 独立 DataAgent 的正式页面 URL 使用可直接理解的资源名。
- 地址栏中不再出现 `intelligent-query` 或 `nl2sql`。
- 旧书签、历史登录回跳和深链仍可到达对应页面，并立即替换为新 URL。
- query、hash、Skill folder 和 Agent ID 等上下文在跳转时不丢失。
- 客户端页面路由继续遵循现有 `DATAAGENT_BASE_PATH`，不把部署前缀写进业务路径。

### 非目标

- 不修改主门户 `frontend/` 的 `/intelligent-query` 页面。该路径属于 OpenDataWorks 门户导航，不是独立 DataAgent SPA 的冗余前缀。
- 不修改 `/api/v1/nl2sql/*` 或 `/api/v1/nl2sql-admin/*`。它们是机器接口契约，不是用户页面 URL；改名会涉及后端、代理、Widget、评测工具和外部客户端迁移。
- 不重命名内部组件、CSS class 或 NL2SQL 领域代码。

## 3. 正式路由

| 页面 | 新正式路径 | 旧路径 |
|---|---|---|
| Chat | `/chat` | `/intelligent-query/chat`、`/nl2sql` |
| Skills | `/skills`、`/skills/:folder` | `/intelligent-query/skills`、`/intelligent-query/skills/:folder` |
| Agents | `/agents`、`/agents/:agentId` | `/intelligent-query/agents`、`/intelligent-query/agents/:agentId` |
| 模型管理 | `/models` | `/intelligent-query/models` |
| Widget 接入 | `/widget-access` | `/intelligent-query/widget` |

`/` 仍自动进入 `/chat`，确保直接访问 DataAgent 主机时得到明确、可收藏的页面地址。

## 4. 兼容策略

- `/intelligent-query` 与 `/nl2sql` 作为只读兼容入口保留一个迁移周期，不再作为页面正式路径。
- 旧的 `?tab=skills|agents|models|widget|chat` 链接映射到相应新路径，并移除 `tab`；其他 query 与 hash 原样保留。
- `/intelligent-query/:pathMatch(.*)*` 只映射已知页面结构。未知路径回落 `/chat`，避免把任意旧后缀传播成无匹配的新 URL。
- 认证守卫、登录默认回跳和 OAuth authorize 的前端 redirect 均使用 `/chat` 或当前新路径。
- Vue Router 继续使用 `createWebHistory(import.meta.env.BASE_URL)`，页面路由不硬编码部署 base。本次不扩大处理既有的自定义 base + OAuth 服务端 302 配置边界。
- Widget 接入页不用 `/widget`：该路径会与生产构建已有的 `/widget/` 静态 bundle 目录冲突；`/widget-access` 同时表达页面用途并保证 Nginx 深链刷新可靠。

## 5. 影响与取舍

- 新 URL 更短，且页面层级直接对应用户可见的信息架构。
- 兼容跳转会保留少量旧术语代码，但它只存在于迁移边界，地址栏最终不会保留旧路径。
- 后端 OAuth 配置默认回跳 `/` 与新路由兼容；前端回跳默认值及页面路径示例统一使用 `/chat`。

## 6. 验证

- 路由单测覆盖正式路径、根路径、两类旧入口、动态深链、query/hash 保留和未知旧路径回落。
- 认证守卫与 redirect 消毒单测覆盖 `/chat` 和 `/models`。
- 更新受路径影响的视图与 API 客户端单测。
- 按仓库 Node 基线执行 DataAgent 前端定向 Vitest、全量 Vitest 与生产构建。

## 7. 发布与回退

- 发布不需要数据库、后端或 Nginx 变更，随 DataAgent 前端镜像正常发布。
- 回退只需恢复上一版前端镜像；旧链接在新旧版本中都可用。
