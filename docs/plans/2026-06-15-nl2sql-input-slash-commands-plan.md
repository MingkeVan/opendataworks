# NL2SQL 输入区斜杠命令 执行计划

- 配套设计：`docs/design/2026-06-15-nl2sql-input-slash-commands-design.md`
- 范围：`dataagent-backend`（捕获 + 接口）+ `dataagent-frontend`（拉取 + 渲染）

## 后端任务

1. 新增 `core/slash_command_cache.py`：`record_agent_slash_commands` / `get_agent_slash_commands`（进程内 dict）。
2. `core/task_executor.py`：`ingest()` 增加 `SystemMessage`/`init` 分支，捕获 `slash_commands` 并按 `agent_snapshot.agent_id` 写缓存。
3. `models/schemas.py`：新增 `AgentSlashCommandsResponse{slash_commands, source}`。
4. `api/admin_routes.py`：新增 `GET /agents/{agent_id}/slash-commands`（命中缓存→sdk；未命中→`skill_folders` 回退；不存在→404）。

## 前端任务

5. `api/nl2sql.js`：`agentApi.getAgentSlashCommands(agentId)`。
6. `views/intelligence/useSlashCommands.js`：纯函数 `parseSlashQuery/filterCommands/buildCommand/buildCommands` + 组合式 `useSlashCommands`；`select` 统一补全 `/<name> `。
7. `SlashCommandMenu.vue`：展示弹层（已存在，沿用）。
8. 接入 `NL2SqlChatV2.vue` 与 `widget/WidgetChat.vue`：拉取命令（载入/切换 + 运行结束）、渲染菜单、统一 keydown/input、发送时 `close()`；widget 样式同步 `widget/styles.js`。

## 验证

- 后端：`pytest tests/test_slash_command_cache.py tests/test_admin_routes.py tests/test_task_executor.py`，并跑全量回归。
- 前端：`nvm use` 后 `vitest run` 覆盖 `useSlashCommands` 与两端组件；`vite build` + `vite build --config vite.widget.config.js`。
- 报告中说明真实 SDK `system/init` 端到端未用真实模型本地验证，捕获以构造消息单测覆盖。

## 回滚

- 后端：删除 `slash_command_cache.py`、`ingest` 分支、接口与 schema；无数据迁移。
- 前端：删除新增模块与两端接入片段；纯增量，回滚即还原。
