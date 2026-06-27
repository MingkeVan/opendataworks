# Widget/Chat Plan Mode Approval Plan

配套设计:`docs/design/2026-06-26-widget-plan-mode-approval-design.md`

## 受影响栈

- 后端 DataAgent:`dataagent/dataagent-backend`(权限链路、agent 运行时、SDK 回调)
- 前端:`dataagent/dataagent-frontend`(计划卡渲染、模式说明)
- 部署:无新增;沿用 runner uid 兜底

## 任务

### 后端

1. `core/permission_gate.py`
   - 新增 `EXIT_PLAN_MODE_TOOL_NAME`、`is_exit_plan_mode(tool_name)`。
   - 新增 `POST_PLAN_MODE = "acceptEdits"` 与 `post_plan_mode()`。

2. `core/agent_runtime.py`
   - `_resolve_sdk_permission_mode` 改恒等映射;仅 `bypassPermissions` + root → `default`。
   - `_build_allowed_tools` 注释改为"路由到回调"语义(逻辑不变)。

3. `core/task_executor.py`
   - `_permission_result_types`/`_allow_result` 支持 `updated_permissions`;新增
     `_set_mode_permission_update(mode)`(带 SDK 缺失降级)。
   - 删除 `needs_gating` 把模式塌缩为 `default`/`bypass` 的分支:
     `permission_mode = _resolve_sdk_permission_mode(logical_permission_mode)`。
   - `can_use_tool` 安装条件含 `plan`。
   - `_build_can_use_tool_callback`:可变 `effective_mode`;抽出 `_wait_decision` 公共子流程;
     新增 `ExitPlanMode` 分支(记录 plan_request + waiting_permission + 批准 setMode→acceptEdits
     + 翻转 effective_mode;拒绝 deny)。

4. 记录/投影:确认 `risk_level="plan"` 透传(`sdk_block_writer`、`topic_task_store`),
   决策端点(`api/routes.py`)幂等复用——**无需改动**。

### 前端

5. `src/views/intelligence/chatMessage.js`:`permission_request` 投影区分 `risk_level==='plan'`。
6. `NL2SqlChatV2.vue` 与 `WidgetChat.vue`:渲染"计划待批准"卡(批准/拒绝复用现有决策链路);
   模式选择器补 tooltip/说明对齐语义。

### 文档与测试

7. 本 design/plan(本次);`docs/handbook` 权限处补一句模式语义。
8. 单测:
   - `tests/test_permission_gate.py`:`is_exit_plan_mode`、`post_plan_mode`、四模式映射。
   - `tests/test_task_executor.py`:`_resolve_sdk_permission_mode` 恒等映射(含 root 特例);
     plan 装 can_use_tool;ExitPlanMode 记录 plan_request + waiting_permission;批准返回带
     setMode 的 allow 且后续写按 acceptEdits 放行。
   - `tests/test_permission_waits.py`:plan_request 走同一持久化等待。

## 验证

### 单测/契约
- `pytest tests/test_permission_gate.py tests/test_task_executor.py tests/test_permission_waits.py`
- 前端:`nvm use` 后 `npm --prefix dataagent/dataagent-frontend run test`(或最小相关用例)。

### P0 SDK 复核(已完成)
- claude-agent-sdk 0.2.96 源码核实:`PermissionUpdate(type="setMode", mode, destination)`、
  `PermissionResultAllow.updated_permissions`、`_internal/query.py` 下发 `updatedPermissions` 均存在。

### 端到端 smoke(本地 MySQL 127.0.0.1:3316 + Redis 127.0.0.1:6379 + backend + 前端)
四模式各一次,经真实 HTTP/SSE:
- default:写操作 → `waiting_permission`,批准后继续。
- acceptEdits:草稿写自动、发布弹确认。
- plan:`POST /topics` → 提交需写入需求 → 出计划 → task `waiting_permission` + 前端计划卡
  → 决策批准 → 同 run 内继续执行写入(高危仍确认)→ 终态消息持久化。
- bypassPermissions:写操作自动执行。
- 取消:plan 等待中 `POST .../cancel` → suspended/task_cancelled。
- 记录验证环境(MySQL/Redis/Python venv、是否真实 provider、各场景通过/跳过)。

## 回滚

改动集中在权限链路;回滚即恢复 `_resolve_sdk_permission_mode` 旧实现与 `task_executor`
gating 分支。`plan` 仅是 `permission_request` 的新 `risk_level` 取值,前端旧版忽略未知 risk
即降级为普通确认卡,无破坏性 schema 变更。

## 已知限制

- plan 等待期间 runner 丢失 → suspended/run_lost,不自动续跑(沿用 06-26)。
- 仅对挂载 portal MCP 写工具的会话有写入 gating;纯只读会话四模式均只读。
