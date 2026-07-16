# DataAgent 显示名称与普通用户导航实施计划

- 日期: 2026-07-16
- 关联设计: `docs/design/2026-07-16-dataagent-display-name-and-user-navigation-design.md`
- 影响栈: `dataagent/dataagent-backend/`（FastAPI / Pydantic / Topic Store）、`dataagent/dataagent-frontend/`（Vue 3 / Pinia / Vue Router）

## 任务清单

### 后端

- [x] B1 将 `AuthIdentity.username` 重命名为 `display_name`，OAuth 支持 `display_name -> preferred_username -> name -> email -> sub` 回退，JWT 继续用标准 `name` claim，`/auth/me` 返回 `display_name`。
- [x] B2 请求上下文和 Topic 对外契约改用 `auth_display_name`；`topic_task_store` 在持久化边界映射现有物理列 `auth_username`，不增加迁移。
- [x] B3 新增 auth 关闭时兼容放行、auth 启用时要求登录的 `require_user` 依赖；Skills 列表、详情和版本比较改为普通用户可读，所有写操作和导出继续要求 `require_admin`。
- [x] B4 Agent 响应拆为三层：匿名 `AgentCatalogProfile`、登录用户不含 `env_vars` 的 `AgentReadableProfile`、管理员完整 `AgentProfile`；保留 Agent 写操作的管理员门禁。
- [x] B5 更新认证、Topic、Widget 和管理路由测试，覆盖显示名称不参与授权、普通用户读/写矩阵、Agent 敏感字段不出现在公开响应。

### 前端

- [x] F1 认证 Store 与用户区域切换到 `display_name`，移除左下角“管理员/普通用户”描述项。
- [x] F2 Chat、Skills、智能体菜单对普通用户可见；Skills/智能体列表与详情路由移除 `adminOnly`，模型管理和 Widget 接入保持管理员限制。
- [x] F3 Skills 列表和详情按 `authStore.isAdmin` 呈现管理/只读模式：普通用户只保留浏览、内容阅读、版本和差异查看。
- [x] F4 智能体列表对普通用户隐藏新建/删除；详情页普通用户只读查看系统提示词、Skills、工具、MCP、数据范围等配置但不接收 `env_vars`，管理员使用完整配置端点继续编辑。
- [x] F5 更新菜单、路由、认证 Store、Skills 和 Agent 视图单测。

## 验证标准

1. 同一 `provider:sub` 的 `display_name` 变化不改变管理员提名、Topic 归属和查询谓词。
2. 普通用户可进入 Chat、Skills、智能体；模型管理和 Widget 接入仍不可见且不可直接访问。
3. 普通用户可读取 Skill 列表、详情和版本差异，所有 Skill 变更与导出接口返回 403。
4. Agent 匿名接口仅返回目录字段；普通用户只读详情包含系统提示词、Skills、工具、MCP 和数据范围但不含 `env_vars`；管理员仍能读取和修改完整配置。
5. 普通用户界面不渲染管理操作；管理员管理流程保持可用。
6. 后端定向 pytest、前端定向 Vitest、前端全量 Vitest和生产构建通过。
7. 本地 HTTP 烟测覆盖一个普通用户和一个管理员的真实鉴权与权限矩阵；本次不触发模型执行。

## 实施验证

- 后端全量测试：`493 passed`。
- 前端全量测试：`38` 个测试文件、`372 passed`。
- 前端生产构建：通过；仅保留既有的大 chunk 提示。
- 本地 HTTP 烟测：使用 `127.0.0.1:3306` 的 MySQL（业务库 `opendataworks`、会话库 `dataagent`）、Podman 启动的 `127.0.0.1:6379` Redis、`.venv-py313` Python 环境和真实 Uvicorn 服务。
- 烟测结果：普通用户 Skill/Agent 只读接口均为 `200`，Agent 完整配置、Agent 创建、Skill 更新和管理设置均为 `403`；管理员完整配置与管理设置均为 `200`；创建并清理 `1` 个 Topic，回读 `auth_display_name=Smoke Display Name`。
- 模型执行：未触发，未使用真实模型凭据。

## Rollout / Backout

- Rollout: DataAgent 前后端作为同一版本发布；JWT `name` claim 与 Topic 物理列保持兼容，无数据库迁移。
- Backout: 恢复上一版前后端即可；Topic 数据无需回滚。
