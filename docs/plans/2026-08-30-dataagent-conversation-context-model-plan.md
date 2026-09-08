# DataAgent Conversation and Context Model Implementation Plan

**Date:** 2026-08-30
**Design:** [2026-08-30-dataagent-conversation-context-model-design.md](../design/2026-08-30-dataagent-conversation-context-model-design.md)
**Goal:** 分阶段修复消息提交、历史读取、上下文组装和交互状态边界，每个阶段可独立合入、验证和回退，并保持当前 Claude Agent SDK 执行拓扑。
**Tech Stack:** Python、FastAPI、Pydantic、PyMySQL、Alembic、Redis、AnyIO、Vue 3、Vite、MySQL 8、Claude Agent SDK

## Execution Order

1. 先冻结 Conversation/Context contracts 和 golden fixtures。
2. 独立交付原子消息接受与 Outbox，不同时改历史或 prompt。
3. 独立交付 semantic message/history cursor。
4. 再交付 ContextReader/Policy/Snapshot 和 Claude ContextRenderer。
5. 最后迁移 Interaction Store 与前端边界。
6. 旧字段/接口清理另开 design，不进入本计划。

本计划不创建 Runtime Plane，不接入 OpenCode。每个 Task 对应一个建议实施 PR；后续 Task 不能以“大重构”为由跳过前置验证。

## Task 1: Freeze Conversation and Context Contracts

### Files

- `dataagent/contracts/conversation/v1/send-message-command.schema.json`
- `dataagent/contracts/conversation/v1/conversation-message.schema.json`
- `dataagent/contracts/conversation/v1/context-bundle.schema.json`
- `dataagent/contracts/conversation/v1/cases.json`
- `dataagent/contracts/conversation/v1/README.md`
- `dataagent/dataagent-backend/core/conversation/contracts.py`
- `dataagent/dataagent-backend/tests/test_conversation_contract.py`
- `dataagent/dataagent-frontend/src/contracts/conversation.v1.ts`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/conversationContract.spec.js`

### Steps

1. 定义显式 discriminated unions；禁止 wire payload 使用自由 `Any`。
2. 冻结 `SendMessageCommand`、`ConversationMessage`、`ConversationPart`、`ContextFragment`、`NeutralMessage` 和 `ContextBundle`。
3. 定义 spec version、字段大小、nullable 语义、trust/channel/role 枚举和 credential 禁止字段。
4. 复用 `sdk-block-projection/cases.json` 的共享 golden fixture 模式，同时验证 Python 和 TypeScript。
5. 加入 current-input-once、turn pairing、watermark 和 secret rejection fixtures。

### Verification

- Targeted pytest contract test。
- Targeted Vitest contract test（先 `nvm use`）。
- JSON Schema examples 全部校验通过，非法 fixture 全部拒绝。

### Backout

- 仅新增未接线 contract 文件，可直接 revert；不影响运行路径。

## Task 2: Atomic Message Accept and Dispatch Outbox

### Files

- `dataagent/dataagent-backend/core/conversation/command_service.py`
- `dataagent/dataagent-backend/core/conversation/dispatch_outbox.py`
- `dataagent/dataagent-backend/core/conversation/message_repository.py`
- `dataagent/dataagent-backend/core/task_submission_service.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_message_accept_outbox.py`
- `dataagent/dataagent-backend/tests/test_conversation_command_service.py`
- `dataagent/dataagent-backend/tests/test_dispatch_outbox.py`

### Steps

1. Additive 扩展 Task/Message 的 ID、role、kind、visibility 和 context eligibility 字段。
2. 创建 `da_agent_task_dispatch_outbox` 和唯一约束。
3. 在一个连接/事务中创建 Task、user message、assistant placeholder、Task message refs 和 outbox。
4. `(topic_id, client_message_id)` 幂等返回第一次结果。
5. queue/schedule 使用来源 ID 生成确定性 key。
6. 实现 outbox claim/retry/backoff/published 状态和 lag 指标。
7. 新 endpoint 与现有 `/tasks/deliver-message`、`POST /tasks` 转调同一 CommandService。
8. 保持现有 Coordinator claim/lease；重复 Redis publish 不重复执行。

### Verification

- 每个事务步骤注入失败并验证无半状态。
- 并发相同 idempotency key 只创建一组资源。
- Redis unavailable -> HTTP accept succeeds -> Redis restore -> Task executes。
- Queue/schedule replay 不创建重复 Task。
- Alembic upgrade 和 additive downgrade 结构测试。

### Backout

- 停止 Outbox Dispatcher，再把 endpoints 切回旧 submission service。
- 新表和新列保留但不读；未发布 outbox 转为明确 failed/cancelled，不能静默丢弃。

## Task 3: Semantic Message Repository and Cursor History

### Files

- `dataagent/dataagent-backend/core/conversation/message_repository.py`
- `dataagent/dataagent-backend/core/conversation/history_service.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-frontend/src/api/nl2sql.js`
- `dataagent/dataagent-frontend/src/views/intelligence/useNl2SqlChat.js`
- `dataagent/dataagent-backend/tests/test_conversation_history_service.py`

### Steps

1. Backfill sender/content/show_in_ui 到 role/parts/plain_text/visibility/context_eligible。
2. 新任务停止写 `event/steps_json/tool_json`。
3. 实现 `before_seq/after_seq` cursor query 和 actor authorization。
4. 新 history query 不读取 SDK records；返回 task execution-detail link。
5. 旧 page/offset API 保持 envelope，并仅在 legacy include-blocks 路径使用旧 projector。
6. 前端历史列表切 cursor；执行详情和 active stream 继续现有 SDK-event transport。

### Verification

- 并发 append 时分页不重不漏。
- Topic/widget authorization 与旧 API 一致。
- 历史列表 SQL 不触发 SDK record query。
- 旧 task 仍可通过兼容入口展示 blocks。
- Targeted frontend tests 和 build（先 `nvm use`）。

### Backout

- 前端切回旧 history API；兼容 API 从旧 SQL 返回。
- 新语义字段保留，不回滚 backfill 数据。

## Task 4: Context Reader, Policy and Snapshot

### Files

- `dataagent/dataagent-backend/core/conversation/context_reader.py`
- `dataagent/dataagent-backend/core/conversation/context_policy.py`
- `dataagent/dataagent-backend/core/conversation/context_assembler.py`
- `dataagent/dataagent-backend/core/conversation/context_snapshot_store.py`
- `dataagent/dataagent-backend/core/conversation/summary_store.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_context_snapshot_summary.py`
- `dataagent/dataagent-backend/tests/test_context_reader.py`
- `dataagent/dataagent-backend/tests/test_context_policy.py`
- `dataagent/dataagent-backend/tests/test_context_snapshot_store.py`

### Steps

1. 按 `current_message_id` 冻结 `history_through_seq`。
2. 只读取 watermark 内 context-eligible terminal semantic messages。
3. 读取 platform/agent/skill/data-scope、summary、recent turns 和 attachment candidates，并标 trust/provenance。
4. 实现确定性 token budget 与完整 turn selection。
5. 持久化 selected/excluded sources、理由、token estimate 和所有指纹。
6. Snapshot JSON 明确拒绝 Provider/MCP/DB credential 字段。
7. Summary 带 coverage 和 source snapshot，不覆盖未包含的消息区间。

### Verification

- 相同输入产生稳定 ContextSelection。
- current input 恰好一次。
- summary/recent turns 无重叠和断层。
- failed/cancelled/permission/tool records 默认不进入 Context。
- secret fixtures 被 schema/validator 拒绝。

### Backout

- 关闭 ContextSnapshot feature flag，当前执行仍走 legacy prompt。
- Snapshot/summary 表保留只读用于诊断。

## Task 5: Claude Context Renderer and Session Checkpoint

### Files

- `dataagent/dataagent-backend/core/conversation/claude_context_renderer.py`
- `dataagent/dataagent-backend/core/conversation/session_checkpoint_store.py`
- `dataagent/dataagent-backend/core/task_coordinator.py`
- `dataagent/dataagent-backend/core/task_executor.py`
- `dataagent/dataagent-backend/core/agent_runtime.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_native_session_checkpoint.py`
- `dataagent/dataagent-backend/tests/test_claude_context_renderer.py`
- `dataagent/dataagent-backend/tests/test_session_checkpoint_store.py`
- `dataagent/dataagent-backend/tests/test_task_executor.py`

### Steps

1. 实现版本化 Claude ContextBundle renderer。
2. 新建 Session 时 renderer 生成角色/信任边界明确的输入，不在通用层拼 `[用户]/[助手]`。
3. ResumePolicy 校验 message watermark、snapshot lineage、config fingerprints 和真实 Session 可读性。
4. Resume 只发送 current input；Rebuild 使用完整 bundle。
5. Terminal message 成功后原子推进 checkpoint；失败/取消不推进。
6. Canary 阶段同时记录 legacy/new prompt fingerprint，不记录明文敏感内容。
7. 指标稳定后删除通用 `_build_history/_build_prompt` 调用路径，保留短期 feature flag 回退。

### Verification

- Golden fixtures 锁定 role/order/trust/current-input-once。
- Session missing、ahead/behind watermark、fingerprint mismatch 均 rebuild。
- Resume 与 rebuild 对相同 semantic sources 保持等价输入意图。
- 真实 Claude Provider smoke 覆盖普通对话、NL2SQL、resume 和 Session 删除恢复。

### Backout

- Feature flag 切回 legacy prompt；停止推进新 checkpoint。
- 保留 snapshots/checkpoints 供问题分析，不改写旧 `chat_conversation_id`。

## Task 6: Interaction Store

### Files

- `dataagent/dataagent-backend/core/conversation/interaction_repository.py`
- `dataagent/dataagent-backend/core/sdk_block_writer.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_interaction_store.py`
- `dataagent/dataagent-backend/tests/test_interaction_repository.py`
- `dataagent/dataagent-backend/tests/test_task_permission_routes.py`
- `dataagent/dataagent-backend/tests/test_task_question_routes.py`

### Steps

1. 创建 Interaction 表、status/kind/version/expiry 枚举和唯一约束。
2. SDK callback 创建 pending Interaction，同时保留兼容 SDK record。
3. Decision endpoint 使用 version CAS 和幂等结果。
4. Coordinator recovery 从 Interaction Store 读取 waiting 状态。
5. Question answer 可事务性生成 embedded semantic message；permission/plan decision 永不 context eligible。
6. 增加 dual-record reconciliation metric 和 repair command。

### Verification

- 重复/并发 decision 只应用一次。
- stale version、expired、cancelled、wrong task/request 均 fail closed。
- 断线/重启后 pending interaction 可恢复。
- Interaction 与 SDK compatibility record 投影一致。

### Backout

- 读取路径切回 SDK record；停止 Interaction dual-write。
- Interaction rows 保留，避免丢失已接受决定；回退脚本先核对 drift。

## Task 7: Frontend Command and Recovery Boundaries

### Files

- `dataagent/dataagent-frontend/src/contracts/conversation.v1.ts`
- `dataagent/dataagent-frontend/src/api/nl2sql.js`
- `dataagent/dataagent-frontend/src/views/intelligence/useNl2SqlChat.js`
- `dataagent/dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`
- `dataagent/dataagent-frontend/src/widget/WidgetChat.vue`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/`

### Steps

1. Client 生成稳定 `client_message_id`，网络重试复用。
2. Optimistic state 使用服务端返回 user/assistant message IDs。
3. History 使用 cursor，active execution 继续现有 SDK event stream。
4. Terminal 后重新读取 assistant semantic message 对账。
5. 页面刷新先恢复 semantic history，再恢复 active interaction/event blocks。
6. Chat 与 Widget 共用 command/history transport。

### Verification

- 网络 retry/offline/refresh 不产生重复气泡或 Task。
- Chat/Widget 对同一 semantic history fixture 一致。
- Permission/Question 刷新后可继续。
- `nvm use` 后运行 targeted Vitest 和 frontend build。

### Backout

- 前端回切兼容 deliver/history API；后端 additive schema 保留。

## Task 8: End-to-End Rollout and Cleanup Decision

### Files

- `dataagent/dataagent-backend/scripts/validate_live_nl2sql_scenarios.py`
- `docs/reports/<date>-dataagent-conversation-context-validation.md`
- `docs/handbook/` 对应消息/上下文运维文档

### Steps

1. 在本地 MySQL `127.0.0.1:3316`、Redis `127.0.0.1:6379`、`.venv-py313` 完成真实 HTTP smoke。
2. 覆盖 accept/outbox、history refresh、long context、Session resume/rebuild、permission/question/cancel。
3. 记录真实 Provider 是否使用、执行时长、Context token budget、outbox lag 和 reconciliation drift。
4. 每个阶段至少观察一个发布窗口后才启用下一阶段。
5. 旧表列/API/SDK compatibility 删除另开 cleanup design；本计划只给出是否具备清理条件的报告。

### Verification Gates

- 发布阻断：原子接受、幂等、Task terminal、history refresh、Context current-input-once、Session rebuild、permission/question recovery。
- 周期回归：长会话多种 budget、summary quality、极端附件、并发 Topic 和性能容量。

### Backout

- 按设计中的 Phase 边界回退，禁止用一次镜像回滚覆盖所有阶段。
- 回退报告必须说明 pending outbox、active interaction、context checkpoint 的处理结果。

## Verification Matrix

| Scenario | Unit/Contract | Local E2E | Release Blocking |
| --- | --- | --- | --- |
| Contract/schema drift | Required | N/A | Yes |
| Atomic accept rollback | Required | Required | Yes |
| Idempotent HTTP retry | Required | Required | Yes |
| Redis outage/outbox recovery | Required | Required | Yes |
| History cursor under append | Required | Required | Yes |
| Context watermark/current-input-once | Required | Required | Yes |
| Summary + recent complete turns | Required | Periodic | No |
| Claude Session resume | Required | Required | Yes |
| Claude Session lost -> rebuild | Required | Required | Yes |
| Permission/question recovery | Required | Required | Yes |
| Large attachment selection | Required | Periodic | No |
| Concurrent Topic capacity | Required | Periodic | No |

## Completion Criteria

- Conversation/Context canonical contracts are versioned and shared by Python/TypeScript tests.
- Message accept is atomic and idempotent with recoverable dispatch.
- UI history and model context no longer share `show_in_ui` query semantics.
- Claude execution uses ContextBundle/ContextRenderer with auditable snapshots.
- Interaction current state no longer depends on reverse-scanning SDK records.
- Each phase has recorded targeted tests and at least one real local E2E before being called fully verified.
