# 助手可见范围实施计划

对应设计:`docs/design/2026-07-20-agent-visibility-scope-design.md`

## 任务

- [ ] 新增 `core/agent_visibility.py`:`normalize_agent_visibility(strict)`、`agent_visible_to`、`filter_visible_agent_profiles`,含 fail-open 与管理员豁免。
- [ ] 新增 Alembic 迁移 `20260720_000020_add_agent_visibility.py`:`da_agent_profile.visibility_json TEXT` + 存量回填 mode=all,幂等守卫与 downgrade。
- [ ] `core/agent_profile_service.py` 接线:SELECT 列表、`_normalize_row`、`save_profile`、`normalize_agent_profile_payload`;快照不含 visibility。
- [ ] `models/schemas.py`:`AgentProfileBase.visibility`、`AgentProfile.visibility`、`AgentReadableProfile.visibility_mode`、`AdminAuthUser`/`AdminAuthUserList`。
- [ ] `api/admin_routes.py`:公开目录/详情/slash-commands 按身份过滤与 404;用户层 profiles 过滤;新增 `GET /api/v1/nl2sql-admin/auth-users`。
- [ ] `core/topic_task_store.py`:新增 `admin_list_auth_users`(按 `auth_user_id` 聚合,keyword 搜索)。
- [ ] `api/routes.py`:`_require_agent_profile` 增加可见性校验并从请求上下文构造身份;话题创建传入上下文。
- [ ] 前端 `dataagent/dataagent-frontend`:`dataagent.js` 增加 `listAuthUsers`;`AgentDetailView` 新增「可见范围」标签页;`AgentStudio` 徽标;`NL2SqlChatV2.loadAgents` 空态区分。
- [ ] 后端测试:新增 `test_agent_visibility.py`;扩展 `test_agent_profile_service.py`、`test_admin_routes.py`(响应分层 + 过滤矩阵 + auth-users)、话题创建拦截用例;`test_topic_task_store.py` 增加 auth-users 聚合用例。
- [ ] 前端测试与构建:按仓库 Node 基线运行受影响 vitest 套件与构建。

## 涉及文件

- `dataagent/dataagent-backend/core/agent_visibility.py`(新增)
- `dataagent/dataagent-backend/alembic/versions/20260720_000020_add_agent_visibility.py`(新增)
- `dataagent/dataagent-backend/core/agent_profile_service.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/api/admin_routes.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/tests/test_agent_visibility.py`(新增)
- `dataagent/dataagent-backend/tests/test_agent_profile_service.py`
- `dataagent/dataagent-backend/tests/test_admin_routes.py`
- `dataagent/dataagent-backend/tests/test_routes_contract.py`
- `dataagent/dataagent-backend/tests/test_topic_task_store.py`
- `dataagent/dataagent-frontend/src/api/dataagent.js`
- `dataagent/dataagent-frontend/src/views/intelligence/AgentDetailView.vue`
- `dataagent/dataagent-frontend/src/views/intelligence/AgentStudio.vue`
- `dataagent/dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`
- `docs/design/2026-07-20-agent-visibility-scope-design.md`、本文件

## 验证

- 单元:normalizer 默认/容错/strict 拒绝/去重/上限;判定矩阵(auth 关闭、admin、匿名×三档、登录用户×三档、selected 命中与未命中)。
- 契约:匿名/`dataagent` 标记 + user JWT/selected 成员/admin 对 `GET /agents` 的过滤差异;不可见详情 404;`AgentCatalogProfile` key 集不变;readable 含 `visibility_mode`、configuration 含完整 `visibility`;`auth-users` 契约与 401/403;不可见助手创建话题 400。
- 服务:payload 归一含 visibility、部分更新保留现值、快照不含 visibility、内置助手 bootstrap 不覆盖已配置可见性。
- 前端:受影响 vitest 套件通过;`nvm use` 后构建通过。
- 端到端冒烟(按 AGENTS.md 智能问数验证规则,环境可用时):`alembic upgrade head` 后起后端,验证匿名列表过滤、带会话过滤差异、不可见助手建话题被拒、管理端保存 visibility 持久化。若本地环境无法满足(如容器内无 Docker/MySQL),在交付说明中明确报告未执行的层。

## 发布与回退

- 发布顺序:先迁移后部署代码(迁移幂等,旧代码忽略新列,可安全先行)。
- 行为回退:代码回滚即恢复全量可见,`visibility_json` 数据保留无害;需要时 `alembic downgrade` 一步删除列。
- 升级后默认全量可见,无需运营动作;限制内置 `agent_opendataworks` 前需确认主门户嵌入影响。
