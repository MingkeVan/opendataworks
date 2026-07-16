# DataAgent 可读页面路由实施计划

- 日期: 2026-07-16
- 关联设计: `docs/design/2026-07-16-dataagent-readable-page-routes-design.md`
- 影响栈: `dataagent/dataagent-frontend/`（Vue 3 / Vue Router）、`scripts/start.sh` 启动输出、文档

## 任务清单

- [x] 将独立 DataAgent 的正式路由改为 `/chat`、`/skills`、`/agents`、`/models`、`/widget-access` 及对应详情路径。
- [x] 让 `/` 跳转 `/chat`，为 `/intelligent-query`、`/intelligent-query/*` 与 `/nl2sql` 添加保留 query/hash/动态参数的兼容跳转。
- [x] 更新侧栏、智能体跳转、聊天来源标记、认证默认回跳和 OAuth redirect 示例。
- [x] 更新路由、认证、视图和 API 客户端回归测试。
- [x] 更新 DataAgent README、架构手册、启动输出和认证设计中的独立 SPA 页面路径说明。
- [x] 执行定向 Vitest、DataAgent 前端全量 Vitest、生产构建和构建产物 HTTP 冒烟。

## 验证标准

1. 访问 `/` 后地址栏为 `/chat`。
2. 所有 DataAgent 菜单及详情页面的新路径可直接刷新。
3. `/intelligent-query/*` 和 `/nl2sql` 旧链接跳转后地址栏不含旧术语，且上下文不丢失。
4. 登录、OAuth 和非管理员拦截均回到新路径。
5. 前端测试与构建通过；本次不触及真实 NL2SQL 执行链，因此无需启动 MySQL、Redis 或模型运行时。

## 验证结果

- Node: `.nvmrc` 对应的 `v20.19.0`。
- DataAgent 前端全量 Vitest: 38 个测试文件、363 项测试全部通过。
- `npm run build`: 通过。
- `npm run build:widget`: 通过，应用 `dist/index.html` 与 `/widget/` bundle 产物同时存在。
- 构建产物本地 HTTP 冒烟: `/`、全部正式页面深链、两类旧入口、`/widget/opendataworks-widget.bundle.js` 与 `/widget/style.css` 均返回 200，页面与静态资源 Content-Type 正确。
- 本机没有 Docker/Nginx CLI，未单独启动生产 Nginx 容器；现有 Nginx SPA fallback 未修改。

## Rollout / Backout

- Rollout: 发布新的 `dataagent-frontend` 构建产物或镜像。
- Backout: 恢复上一版 `dataagent-frontend`；无数据迁移或服务端状态需要回滚。
