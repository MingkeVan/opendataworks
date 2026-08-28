# DataAgent 控制面/Runtime 数据面与单激活 Adapter 实施计划

- Date: 2026-08-28
- Status: Proposed
- Design: [2026-08-28-dataagent-single-runtime-sdk-adapter-design.md](../design/2026-08-28-dataagent-single-runtime-sdk-adapter-design.md)
- Goal: 分阶段把 DataAgent 从 Claude 专属执行链迁移到控制面/Runtime 数据面架构和版本化中立协议，并交付部署期二选一、运行期固定的 Claude/OpenCode 两种 Runtime Plane flavor。
- Tech Stack: Python 3.11+、FastAPI、AnyIO、MySQL 8、Redis、Docker、Claude Agent SDK、OpenCode Server、HTTP/SSE、Vue 3/Vitest/Pytest

## 1. Architecture Summary

实施遵循以下顺序：

1. 先定义 Runtime Protocol、Agent Event Protocol、Conversation/Context contracts 和 conformance tests。
2. 把现有 Sandbox Runner 演进为 Runtime Plane Gateway，并建立统一 Runtime Server shell。
3. 把现有 Claude 行为原样迁入 Claude Runtime Plane flavor，证明服务边界不改变基线。
4. 再迁移事件持久化、前端 reducer、消息事务、历史读取、Context Snapshot、Session、权限和 interaction 双向协议。
5. 最后实现 OpenCode adapter 和第二套 Runtime Plane flavor。
6. 先完成 Claude Runtime Plane 发布，再在独立环境灰度 OpenCode flavor；不在一个生产环境同时运行两套 Runtime。

计划不允许通过在控制面 `task_executor.py`、API 或前端增加 Runtime 分支来“快速兼容”。任何厂商差异必须停留在 Runtime Plane adapter/transport 层；控制面不安装厂商 SDK，Runtime Plane 不直写业务 MySQL。

## 2. Task 1: 冻结运行时契约和 OpenCode 版本

### Files

- `docs/design/2026-08-28-dataagent-single-runtime-sdk-adapter-design.md`
- `dataagent/dataagent-backend/tests/runtime_contract/fixtures/`
- `dataagent/dataagent-backend/tests/runtime_contract/test_opencode_server_contract.py`
- `dataagent/dataagent-backend/tests/runtime_contract/test_claude_sdk_contract.py`

### Steps

1. 选择一个明确的 OpenCode release，记录版本、checksum、license 和安装来源。
2. 启动真实 `opencode serve`，保存经过清理的 OpenAPI schema、关键 SSE fixture 和版本信息。
3. 验证 health、Session create/get、async prompt、abort、event SSE、remote MCP、permission reply、question reply/reject。
4. 完整验证 `plan_exit -> question -> approve -> build agent`；记录事件顺序和状态变化。
5. 核对 Claude `claude-agent-sdk==0.2.115` 的 query、resume、partial messages、can_use_tool、AskUserQuestion 和 ExitPlanMode 基线。
6. 把两者都能稳定提供的执行语义冻结为 `DataAgent Runtime Protocol v1`，把前端需要的稳定投影语义冻结为 `DataAgent Agent Event Protocol v1`。
7. 如果 OpenCode 缺少稳定 Question/Plan reply 接口，停止生产实现并更新设计：OpenCode flavor 标为 experimental，不能通过后续发布门禁。

### Expected Result

- OpenCode 不是按 `latest` 接入，而是由可重复 fixture 和真实 contract test 支撑。
- 已知能力差异在写业务代码前暴露。

## 3. Task 2: 建立控制面 Runtime Client 和数据面 Runtime Server

### Files

- `dataagent/dataagent-backend/core/agent_runtime.py`
- `dataagent/dataagent-backend/core/agent_runtime/__init__.py`
- `dataagent/dataagent-backend/core/agent_runtime/contracts.py`
- `dataagent/dataagent-backend/core/agent_runtime/capabilities.py`
- `dataagent/dataagent-backend/core/agent_runtime/bootstrap.py`
- `dataagent/dataagent-backend/core/agent_runtime/client.py`
- `dataagent/dataagent-backend/runtime_plane/app.py`
- `dataagent/dataagent-backend/runtime_plane/api.py`
- `dataagent/dataagent-backend/runtime_plane/manifest.py`
- `dataagent/dataagent-backend/runtime_plane/supervisor.py`
- `dataagent/dataagent-backend/runtime_plane/event_journal.py`
- `dataagent/dataagent-backend/runtime_plane/workspace.py`
- `dataagent/dataagent-backend/runtime_plane_main.py`
- `dataagent/dataagent-backend/sandbox_runner_main.py`
- `dataagent/dataagent-backend/tests/test_agent_runtime_contracts.py`
- `dataagent/dataagent-backend/tests/test_runtime_plane_client.py`
- `dataagent/dataagent-backend/tests/test_runtime_plane_api.py`

### Steps

1. 用 package 替换现有单文件 `core/agent_runtime.py`，把共享 request/result/manifest types 与厂商辅助逻辑分开。
2. 定义 `AgentRunRequest`、`AgentRunResult`、`ContextBundle`、`RuntimeSessionRef`、`ModelTarget`、`McpServerSpec`、`RuntimeCapabilities` 和 Runtime Protocol 版本；执行请求不再包含自由 `prompt + history` 组合。
3. 实现控制面 `RuntimePlaneClient`：manifest、start run、status、events、interaction resolve、cancel。
4. 把 `sandbox_runner_main.py` 演进为 Runtime Plane Gateway，管理 Topic affinity、warm Runtime Cell 和转发协议。
5. 建立统一 Runtime Server shell、短期 append-only event journal 和 fake adapter。
6. `POST /v1/runs` 使用 `run_id + idempotency_key` 防止重复工具执行。
7. 明确 request/API 中不存在 Runtime 选择字段。
8. 增加服务身份认证、短生命周期 execution secret envelope 和中立错误类型。
9. 用 fake adapter 验证成功、失败、取消、断线重放、interaction timeout、幂等和 cleanup。

### Expected Result

- 控制面只认识 Runtime Protocol，不包含任何厂商 SDK。
- 数据面 server shell 只在启动阶段选择当前镜像内唯一 Adapter。
- Runtime Plane 不需要 DataAgent 业务 MySQL 凭据。

## 4. Task 3: 提取 Claude Agent SDK Adapter

### Files

- `dataagent/dataagent-backend/core/task_executor.py`
- `dataagent/dataagent-backend/runtime_plane/adapters/claude_agent_sdk.py`
- `dataagent/dataagent-backend/runtime_plane/event_journal.py`
- `dataagent/dataagent-backend/core/agent_runtime/client.py`
- `dataagent/dataagent-backend/tests/test_task_executor.py`
- `dataagent/dataagent-backend/tests/runtime_contract/test_claude_adapter.py`

### Steps

1. 把 `claude_agent_sdk` import、`ClaudeAgentOptions` 构造、query 消费和 Session resume 移入 Runtime Plane adapter。
2. 先通过 Runtime Protocol compatibility projection 保持现有 Claude `/sdk-events` 行为，避免服务拆分和前端迁移同时发生。
3. 把 MCP config、partial message fallback、result/usage/error 映射移入 adapter。
4. 把 `can_use_tool` 和 workspace hook 的协议翻译移入 adapter，事件写入 Runtime journal。
5. 控制面 `task_executor.py` 只保留 dispatch、timeout、cancel、recovery 和 result persistence。
6. 移除 Runtime Cell 对 `da_agent_sdk_record` 的直接写入；兼容阶段由控制面 projector 写旧记录。
7. 运行现有 Claude 相关 pytest，补充 Runtime Server/adapter conformance tests。

### Expected Result

- 在仍使用旧事件/API 的情况下，Claude 用户行为和任务状态不变。
- 控制面不再 import Claude SDK，也不再在本进程执行 Claude Agent Loop。

## 5. Task 4: 收口辅助 Agent 调用

### Files

- `dataagent/dataagent-backend/core/followup_suggestions.py`
- `dataagent/dataagent-backend/core/skill_admin_service.py`
- `dataagent/dataagent-backend/core/agent_runtime/client.py`
- `dataagent/dataagent-backend/tests/test_followup_suggestions.py`
- `dataagent/dataagent-backend/tests/test_skill_admin_service.py`

### Steps

1. Follow-up Suggestions 通过 `RuntimePlaneClient` 提交 `purpose=followup` 的 ephemeral run。
2. Provider/model probe 通过 Runtime Protocol 提交 `purpose=model_probe`，tools 为空且严格限制 turns/timeout。
3. Skill compare/evaluation 使用 `purpose=skill_compare`，保留其业务输入但不直接依赖 SDK。
4. 全仓 `rg "claude_agent_sdk"`，确保只有 Runtime Plane Claude adapter、版本检查和测试 fixture 仍有 import。

### Expected Result

- 不存在绕过 Runtime 抽象的隐藏 Claude 调用。

## 6. Task 5: 中立工具身份和权限策略

### Files

- `dataagent/dataagent-backend/core/permission_gate.py`
- `dataagent/dataagent-backend/core/agent_runtime/tool_identity.py`
- `dataagent/dataagent-backend/core/agent_runtime/tool_policy.py`
- `dataagent/dataagent-backend/runtime_plane/policy_enforcer.py`
- `dataagent/dataagent-backend/core/agent_profile_service.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_canonical_agent_tools.py`
- `dataagent/dataagent-backend/tests/test_permission_gate.py`
- `dataagent/dataagent-backend/tests/test_agent_profile_service.py`

### Steps

1. 定义 Canonical Tool IDs 和 Claude/OpenCode tool mapping。
2. 把 Agent Profile 的 `allowed_tools` 从 Claude 名称迁移到中立 ID，提供一次性数据库映射。
3. 控制面把 Agent Profile、data scope、permission mode 和高风险规则编译为不可变、带版本的 `ExecutionPolicySnapshot`。
4. Runtime Plane `PolicyEnforcer` 验证 snapshot 并在每次工具调用前执行；workspace escape 规则在数据面强制。
5. 适配器只传入标准 `ToolCallContext` 并翻译 allow/deny/ask 结果。
6. 移除把 `title`、`summary` 混入 Portal MCP input 再剥离的路径。
7. 权限卡片从 tool catalog、参数摘要和 diff 生成展示数据。
8. 加入 snapshot 篡改/过期、未知工具默认拒绝、路径逃逸、shell 高风险和 MCP 写工具测试。

### Expected Result

- 权限规则只实现一次。
- Portal MCP 获取原始合法参数，不依赖某个 Runtime 的 input rewrite。

## 7. Task 6: 控制面/数据面 Interaction 协议

### Files

- `dataagent/dataagent-backend/core/agent_runtime/interaction_service.py`
- `dataagent/dataagent-backend/runtime_plane/interaction_waiter.py`
- `dataagent/dataagent-backend/runtime_plane/api.py`
- `dataagent/dataagent-backend/core/task_executor.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/tests/test_task_permission_routes.py`
- `dataagent/dataagent-backend/tests/test_task_question_routes.py`

### Steps

1. 定义 permission、question、plan_approval 三种 `InteractionRequest` 和对应 `InteractionDecision` discriminated unions。
2. Runtime Plane 把原生交互写成 `interaction.requested`，`InteractionWaiter` 挂起当前 Adapter。
3. 控制面消费事件、复用现有 task waiting 状态和 decision/answer endpoint，不改变 UI 语义。
4. 控制面通过 `POST /v1/runs/{run_id}/interactions/{interaction_id}/resolve` 返回决定。
5. Runtime Plane 幂等应用决定、写入 `interaction.resolved`，再恢复 Claude/OpenCode 执行。
6. 实现 interaction timeout、cancel、控制面断线和 coordinator lease 恢复规则。
7. 验证 Runtime Plane 不访问业务库，且协议不向 vendor tool input 注入 UI-only 字段。

### Expected Result

- 交互暂停/恢复属于 DataAgent Runtime Protocol，控制面拥有用户决定，数据面只负责挂起和协议翻译。

## 8. Task 7: 新建 Neutral Event 存储和 API

### Files

- `dataagent/contracts/agent-events/v1/agent-event.schema.json`
- `dataagent/contracts/agent-events/v1/agent-event-record.schema.json`
- `dataagent/contracts/agent-events/v1/README.md`
- `dataagent/contracts/agent-events/v1/examples/`
- `dataagent/dataagent-backend/core/sdk_block_writer.py`
- `dataagent/dataagent-backend/core/agent_events/models.py`
- `dataagent/dataagent-backend/core/agent_events/validator.py`
- `dataagent/dataagent-backend/core/agent_events/projector.py`
- `dataagent/dataagent-backend/core/agent_runtime/event_ingestor.py`
- `dataagent/dataagent-backend/runtime_plane/event_journal.py`
- `dataagent/dataagent-backend/runtime_plane/adapters/claude_event_normalizer.py`
- `dataagent/dataagent-backend/runtime_plane/adapters/opencode_event_normalizer.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_agent_run_events.py`
- `dataagent/dataagent-backend/tests/test_agent_event_ingestor.py`
- `dataagent/dataagent-backend/tests/test_runtime_event_journal.py`
- `dataagent/dataagent-backend/tests/test_routes_contract.py`

### Steps

1. 编写 `DataAgent Agent Event Protocol v1` JSON Schema，使用 `type` 作为 discriminator，不允许自由格式 `payload`。
2. 显式定义 run、turn、content、tool、interaction、usage 和 terminal event 的字段、枚举、nullable 语义及大小限制。
3. 定义 `AgentEvent`、`AgentEventRecord` 和 `AgentError` Pydantic discriminated unions；wire model 不包含数据库 cursor。
4. 建立版本协商：Runtime manifest 声明协议版本，控制面拒绝不支持的版本。
5. 实现 sequence/state validator，检查 run.started、content/tool/interaction 配对、唯一 terminal event 和终止后禁止追加。
6. 创建 `da_agent_run_event`，使用 `(run_id, sequence)` 去重和 `(task_id, id)` SSE 游标索引。
7. Runtime Adapter 在数据面完成原生事件 -> AgentEvent 的 ID 归一化、schema 校验、大小限制和脱敏，再追加本地 append-only journal。
8. Runtime journal 支持 `after_sequence` 重放，并至少保留到控制面确认 terminal event；数据面不直写 MySQL。
9. 控制面 `AgentEventIngestor` 按 `(run_id, sequence)` 拉取、复验、幂等落库，成功后推进确认位置。
10. 新增 `/events`、`/events/stream`，返回 `AgentEventRecord {cursor,event}`，沿用 after_id 和 terminal status 语义。
11. heartbeat 改为非持久化 SSE comment，不能成为领域事件。
12. 新任务只写新表，不双写 `da_agent_sdk_record`。
13. 保留旧 `/sdk-events` 只读；实现旧 task 的历史兼容判定。
14. 为完整事件联合类型、非法顺序、重复 sequence、sequence gap、控制面断线重放、脱敏和 terminal invariant 增加 fixture contract tests。
15. 锁定 AgentEvent -> Task status 映射，验证 Adapter 无业务表写权限。
16. 使用同一组协议 fixture 验证 Python 历史 projector。
17. 更新 live smoke script 使用新 endpoint，同时保留旧历史检查。

### Expected Result

- 后端持久化/API 不再以 Anthropic payload 为公共协议。
- 每一个事件都有明确、版本化且可机器校验的结构，不存在 `payload: Any`。
- Runtime Plane 与 Control Plane 使用 sequence 重放，Control Plane 与前端使用数据库 cursor 重放，两段语义明确分离。
- SSE 重连和 terminal status 行为保持现状。

## 9. Task 8: 前端改用 Neutral Event Reducer

### Files

- `dataagent/dataagent-frontend/src/contracts/agentEvents.v1.ts`
- `dataagent/dataagent-frontend/src/contracts/parseAgentEvent.js`
- `dataagent/dataagent-frontend/src/views/intelligence/agentEventReducer.js`
- `dataagent/dataagent-frontend/src/views/intelligence/useNl2SqlChat.js`
- `dataagent/dataagent-frontend/src/views/intelligence/chatMessage.js`
- `dataagent/dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`
- `dataagent/dataagent-frontend/src/widget/WidgetChat.vue`
- `dataagent/dataagent-frontend/src/api/nl2sql.js`
- `dataagent/dataagent-frontend/src/views/intelligence/v2StreamParser.js`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/agentEventProjection.contract.spec.js`

### Steps

1. 根据 v1 JSON Schema 维护 TypeScript discriminated union，并用 contract test 防止与 canonical schema 漂移。
2. 定义独立 `AgentViewState`、`ContentViewBlock`、`ToolViewBlock` 和 `InteractionViewBlock`；视图模型不反向进入线协议。
3. 实现纯函数 `reduceAgentEvent(previousState, event)`，按 ID 关联 content/tool/interaction，而不是依赖厂商 block index。
4. transport 层处理 cursor、重复事件和 sequence gap；Reducer 只接受已经通过协议解析的 `AgentEvent`。
5. Chat 与 Widget 切换到 `/events/stream` 和同一个 reducer。
6. 用后端相同 fixtures 验证 Python history、Vue Chat 和 Widget 投影一致。
7. 保留 `v2StreamParser.js` 仅用于旧 task/history compatibility，不在新任务执行路径调用。
8. 测试断线重连、重复事件、sequence gap、permission/question/plan card、tool progress、thinking 和 terminal event。
9. 按仓库要求先 `nvm use`，再运行最小 Vitest 和 frontend build。

### Expected Result

- Vue 不知道当前部署使用 Claude 还是 OpenCode。
- Chat 与 Widget 对同一事件产生一致渲染。
- UI block、SSE record 和 Agent domain event 三层模型保持解耦。

## 10. Task 9: 拆分消息交互、存储、历史与 Context 子系统

### Files

- `dataagent/contracts/conversation/v1/send-message-command.schema.json`
- `dataagent/contracts/conversation/v1/conversation-message.schema.json`
- `dataagent/contracts/conversation/v1/context-bundle.schema.json`
- `dataagent/contracts/conversation/v1/README.md`
- `dataagent/dataagent-backend/core/conversation/__init__.py`
- `dataagent/dataagent-backend/core/conversation/contracts.py`
- `dataagent/dataagent-backend/core/conversation/command_service.py`
- `dataagent/dataagent-backend/core/conversation/message_repository.py`
- `dataagent/dataagent-backend/core/conversation/message_finalizer.py`
- `dataagent/dataagent-backend/core/conversation/history_service.py`
- `dataagent/dataagent-backend/core/conversation/interaction_repository.py`
- `dataagent/dataagent-backend/core/conversation/dispatch_outbox.py`
- `dataagent/dataagent-backend/core/conversation/context_reader.py`
- `dataagent/dataagent-backend/core/conversation/context_policy.py`
- `dataagent/dataagent-backend/core/conversation/context_assembler.py`
- `dataagent/dataagent-backend/core/conversation/context_snapshot_store.py`
- `dataagent/dataagent-backend/core/conversation/session_resume_policy.py`
- `dataagent/dataagent-backend/core/task_submission_service.py`
- `dataagent/dataagent-backend/core/task_coordinator.py`
- `dataagent/dataagent-backend/core/agent_runtime/contracts.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_conversation_context_model.py`
- `dataagent/dataagent-frontend/src/contracts/conversation.v1.ts`
- `dataagent/dataagent-frontend/src/api/nl2sql.js`
- `dataagent/dataagent-frontend/src/views/intelligence/useNl2SqlChat.js`
- `dataagent/dataagent-backend/tests/test_conversation_command_service.py`
- `dataagent/dataagent-backend/tests/test_conversation_history_service.py`
- `dataagent/dataagent-backend/tests/test_context_assembler.py`
- `dataagent/dataagent-backend/tests/test_session_resume_policy.py`
- `dataagent/dataagent-backend/tests/test_message_finalizer.py`

### Steps

1. 冻结 `SendMessageCommand`、`ConversationMessage`、`ConversationPart`、`ContextFragment` 和 `ContextBundle` JSON Schema；Python/TypeScript 只能使用显式 discriminated union，不保留自由 `Any` payload。
2. 创建 `core/conversation/` package，先从 `TopicTaskStore` 提取 Repository 接口；API、Coordinator 和 Runtime Client 不能继续直接组合消息 SQL。
3. 以 additive migration 扩展 `da_agent_message`：`role`、`kind`、`content_json`、`plain_text`、`visibility`、`context_eligible`、`client_message_id`、`parent_message_id`、`finalized_at`；`da_agent_task` 增加 `input_message_id/output_message_id/context_snapshot_id/run_id`，现有 `prompt` 进入兼容期而不再作为输入权威源。
4. 新增 `da_agent_task_dispatch_outbox`、`da_agent_interaction`、`da_agent_context_snapshot`、`da_agent_conversation_summary`、`da_agent_artifact` 和 `da_agent_message_artifact`；为 command/event/interaction/finalization 建立唯一约束。
5. 实现 `ConversationCommandService.accept()`：一个 MySQL 事务创建 Task、user message、assistant placeholder、Task 的 input/output message 引用和 outbox；`(topic_id, client_message_id)` 重试返回同一资源，queue/schedule 使用来源 ID 派生确定性幂等键。
6. 实现 Outbox Dispatcher，把已提交 task 发布到现有 Redis coordinator；覆盖 Redis 不可用、重复 publish、进程在 commit 后崩溃的恢复测试。
7. 将 `/tasks/deliver-message`、`POST /tasks`、Widget、queue 和 schedule 路径收口到 Command Service；新增规范 `/topics/{topic_id}/messages` 入口，兼容 API 不再各自写 Store。
8. 实现 `MessageFinalizer`，从中立事件幂等完成 assistant message；Task terminal、assistant terminal 和 Runtime Session watermark 在控制面事务中一致提交。
9. 把 permission/question/plan 当前状态迁入 `da_agent_interaction`；Interaction Event 保留审计，但新任务不再扫描 `da_agent_sdk_record` 判断 pending/resolved。
10. 实现 cursor-based `ConversationHistoryService`，只查 semantic message/artifact relation；旧 page/offset API 转调新服务，event blocks 仅对旧 task 走 legacy projector。
11. 实现 `ContextReader`：按 `history_through_seq` 读取 `context_eligible` semantic messages、summary、pinned facts 和显式 Artifact；当前 user message单独读取，不能混入历史集合。
12. 实现确定性的 `ContextPolicy v1`：system/agent/skill、summary、recent complete turns、artifact、current input 的优先级与 token budget；记录每个 source 的选择/排除理由。
13. 实现 `ContextAssembler` 和 `ContextSnapshotStore`，在 dispatch Runtime 前固化 watermark、provenance、token estimate 和 Agent/Skill/Tool/Workspace/Model 指纹；Snapshot 不含 Provider/MCP credential。
14. 实现 `SessionResumePolicy`：session watermark 和 snapshot lineage 完全匹配时只发送 current input，否则用完整 `ContextBundle` rebuild；删除 Control Plane 中通用的 `_build_history()` 与 `[用户]/[助手]` `_build_prompt()`。
15. 在 Claude/OpenCode Adapter 内分别实现 `ContextRenderer`；底层若只能接受文本 replay，使用版本化 renderer，并以同一组 golden fixture 校验两边包含相同语义片段、角色和 trust 边界。
16. 更新前端发送路径使用 `client_message_id` 和服务端 message IDs；历史路径按 `seq_id` cursor；terminal 后用持久化 assistant message 对账，执行明细按 task events 懒加载。
17. 增加长会话 summary、当前输入不重复、失败消息排除、question answer 纳入上下文、permission decision 排除、Session 丢失重建和指纹变化重建测试。
18. 对迁移前 Topic 做 backfill：现有 `sender_type/content/show_in_ui` 映射到新语义字段；不把 `steps_json/tool_json` 回填进 ConversationPart。

### Expected Result

- 消息接受是可重试、原子且可恢复的业务动作，不再可能只创建 Task 或只创建一半消息。
- UI history、Run event、Interaction、Context Snapshot 和原生 Session 各有单一事实来源。
- 同一 Conversation/Context contract 同时服务 Claude/OpenCode，Control Plane 不再通过拼接文本模拟历史。
- 页面刷新、Session 丢失和跨 Runtime 离线迁移都可以从 semantic messages + snapshots 恢复。

## 11. Task 10: Runtime Session 和部署锁

### Files

- `dataagent/dataagent-backend/core/agent_runtime/session_store.py`
- `dataagent/dataagent-backend/runtime_plane/session_manager.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/core/task_coordinator.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_runtime_session_and_lock.py`
- `dataagent/dataagent-backend/tests/test_runtime_session_store.py`
- `dataagent/dataagent-backend/tests/test_runtime_bootstrap.py`

### Steps

1. 创建 `da_agent_runtime_session` 和 `da_agent_runtime_deployment`。
2. 把有效 `chat_conversation_id` 回填为 Claude Runtime Session；占位 ID 忽略。
3. 任务表增加服务端只读 `runtime_kind` 审计字段，新任务从已验证的 Runtime manifest 注入。
4. 控制面保存外部 Session 索引；Runtime Plane 在 Topic `/mnt/home` 保存厂商 transcript/database 和实际 Session 状态。
5. Coordinator 通过 `SessionResumePolicy` 校验 Runtime、workspace/config fingerprint、`committed_message_seq` 和 `last_context_snapshot_id`；Runtime Plane 再验证外部 Session 可读。
6. 首次启动原子声明 Runtime lock；不一致时阻止 readiness 和 coordinator 启动。
7. 标记 `chat_conversation_id` deprecated，新路径停止写入；暂不在同一迁移删除列。
8. 测试并发首次启动、相同 Runtime 重启、错误 Runtime、Runtime Cell 重建、invalid Session 和旧数据 backfill。

### Expected Result

- 不会把 Claude Session 传给 OpenCode。
- 修改一个环境变量不能绕过部署后 Runtime 固定约束。

## 12. Task 11: Runtime flavor 和部署校验

### Files

- `dataagent/dataagent-backend/requirements-control-plane.txt`
- `dataagent/dataagent-backend/requirements-runtime-common.txt`
- `dataagent/dataagent-backend/requirements-runtime-claude.txt`
- `dataagent/dataagent-backend/requirements-runtime-opencode.txt`
- `dataagent/dataagent-backend/Dockerfile`
- `dataagent/dataagent-backend/Dockerfile.runner`
- `dataagent/dataagent-backend/runtime-manifest.json`
- `dataagent/dataagent-backend/sandbox_runner_main.py`
- `deploy/docker-compose.dev.yml`
- `deploy/docker-compose.prod.yml`
- `deploy/.env.example`
- `dataagent/dataagent-backend/tests/test_dataagent_requirements.py`

### Steps

1. 拆分 control-plane、runtime-common、runtime-Claude、runtime-OpenCode requirements，保留精确 Runtime 版本。
2. 构建一个不含厂商 SDK 的 Control Plane 镜像，以及 `claude`、`opencode`、可选 `dev` Runtime Plane targets。
3. OpenCode target 下载固定版本 binary、校验 checksum 和 `opencode --version`。
4. Runtime Plane 镜像写入 `runtime-manifest.json`，包含 flavor、runtime version、Runtime Protocol、Agent Event Protocol 和 build revision。
5. Control Plane 和 Runtime Gateway health/admin endpoint 返回清理后的 manifest/handshake 状态。
6. compose 增加 `DATAAGENT_AGENT_RUNTIME` 和 `OPENDATAWORKS_DATAAGENT_RUNTIME_IMAGE`，控制面镜像保持通用。
7. Control Plane 启动时调用 Gateway/Cell manifest 并 fail closed。
8. Runtime Plane services 移除业务 MySQL 凭据，只保留必要的服务认证和执行密钥通道。
9. 生成 SBOM/依赖扫描，确认 production Runtime flavor 不包含另一 Runtime，Control Plane 不包含任何厂商 SDK。

### Expected Result

- 部署人员在上线配置中明确二选一。
- 配错镜像或 env 时系统不能带病启动。

## 13. Task 12: 实现 OpenCode Process Manager 和 Adapter

### Files

- `dataagent/dataagent-backend/runtime_plane/transports/opencode_server.py`
- `dataagent/dataagent-backend/runtime_plane/adapters/opencode.py`
- `dataagent/dataagent-backend/core/sandbox_runner.py`
- `dataagent/dataagent-backend/core/task_executor.py`
- `dataagent/dataagent-backend/tests/runtime_contract/test_opencode_adapter.py`
- `dataagent/dataagent-backend/tests/runtime_contract/test_opencode_process_manager.py`

### Steps

1. 在 child 内启动 loopback OpenCode server，使用随机端口和随机 Basic Auth password。
2. 实现 startup timeout、health/version check、stderr 日志、graceful close 和强制 kill。
3. 实现 Session create/resume、async prompt、SSE consume、abort。
4. 实现 OpenCode event -> Neutral Event 的确定性映射和 `(run_id, sequence)` 幂等。
5. 实现 Portal remote MCP、Provider 临时配置、`.claude/skills` 发现。
6. 实现 permission/question/plan event 与 Runtime `InteractionWaiter`/Control `InteractionService` 的双向翻译。
7. warm child reuse 时复用同一 topic server；child reaper 时关闭 server。
8. 运行 Task 1 冻结的真实 Server contract tests，不使用 mock 代替关键交互。

### Expected Result

- OpenCode 可完成与 Claude 相同的 DataAgent 业务契约。
- OpenCode 原生类型和 endpoint 不泄漏出 adapter/transport。

## 14. Task 13: 运行时健康、指标和运维

### Files

- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/core/runtime_metrics.py`
- `dataagent/dataagent-backend/core/logging_config.py`
- `docs/handbook/` 中对应部署/运维文档

### Steps

1. Control Plane health/admin endpoint 暴露 runtime kind/version/capabilities/protocol/Gateway/Cell handshake；Runtime Plane 独立暴露清理后的 manifest 和 readiness。
2. 指标增加 run count、latency、idle timeout、permission wait、session resume failure、Runtime journal backlog、event ingest lag、sequence gap、SSE reconnect、OpenCode process count/start failure/RSS。
3. 日志统一带 task/topic/run/runtime，但脱敏 provider credential、MCP token 和 Basic Auth password。
4. 增加 runtime lock mismatch、Gateway/Cell mismatch、event ingest stall/sequence gap、session incompatible 的排障手册。
5. 明确升级同 Runtime 与跨 Runtime 离线迁移的不同 runbook。

### Expected Result

- 运维能确认实际启用的 Runtime 和失败层级，不需要从模型错误反推镜像配置。

## 15. Task 14: Claude flavor 回归和首次发布

### Files

- `dataagent/dataagent-backend/tests/`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/`
- `dataagent/dataagent-backend/scripts/validate_live_nl2sql_scenarios.py`
- `docs/reports/<date>-dataagent-runtime-adapter-claude-validation.md`

### Steps

1. 先发布通用 Control Plane 和 `runtime-plane:claude` flavor，保持 `DATAAGENT_AGENT_RUNTIME=claude_agent_sdk`。
2. 运行全部目标 pytest、Vitest、frontend build 和 Alembic upgrade。
3. 使用真实 Claude Provider 完成标准 E2E、resume、cancel、permission、question、plan、Portal MCP、Skill。
4. 对比改造前后的 task status、event projection、最终消息、usage 和日志。
5. 验证旧 Topic/旧 SDK events 仍可展示。
6. 观察至少一个发布周期，确认 neutral contract 稳定后再开放 OpenCode canary。

### Expected Result

- 抽象层首先在现有 Claude 生产路径上被证明，而不是和 OpenCode 一次性大爆炸上线。

## 16. Task 15: OpenCode flavor 灰度和发布

### Files

- `deploy/.env.example`
- `docs/handbook/` 中 OpenCode 部署 runbook
- `docs/reports/<date>-dataagent-runtime-adapter-opencode-validation.md`

### Steps

1. 使用独立测试环境和独立 Session schema 初始化 `opencode` runtime lock。
2. 先跑只读、最小工具和低并发流量，再跑完整 NL2SQL 与权限交互。
3. 运行标准 smoke prompt 和真实 NL2SQL prompt。
4. 验证 `waiting -> running -> success|failed|suspended`、events、最终消息、resume、cancel。
5. 验证 permission/question/plan、Portal MCP、Skills、workspace escape 和 warm reuse。
6. 做并发和容量测试，确定每个 warm topic OpenCode process 的内存/FD 基线和最大容器数。
7. 仅当所有 mandatory capabilities 和 E2E 通过后，把 OpenCode flavor 标为 production-ready。

### Expected Result

- OpenCode 作为一个独立部署选项上线，而不是在 Claude 环境内动态灰度。

## 17. Verification Matrix

| 场景 | Unit/Contract | Claude real E2E | OpenCode real E2E |
| --- | --- | --- | --- |
| Runtime/artifact/Gateway/Cell match | 必须 | 必须 | 必须 |
| Runtime Protocol / Agent Event Protocol handshake | 必须 | 必须 | 必须 |
| Runtime Plane 无业务 MySQL 凭据 | 必须 | 必须 | 必须 |
| Journal -> Control ingest 断线重放 | 必须 | 必须 | 必须 |
| Command 原子提交/幂等/outbox 恢复 | 必须 | 必须 | 必须 |
| Semantic history cursor/刷新恢复 | 必须 | 必须 | 必须 |
| Context budget/summary/watermark | 必须 | 必须 | 必须 |
| Context Snapshot 无 secret 且 provenance 完整 | 必须 | 必须 | 必须 |
| Session resume 只注入 current input | 必须 | 必须 | 必须 |
| Session 丢失后 ContextBundle rebuild | 必须 | 必须 | 必须 |
| 简单文本流 | 必须 | 必须 | 必须 |
| Thinking | 必须 | 必须 | 必须 |
| Portal MCP 只读 | 必须 | 必须 | 必须 |
| 高风险工具 allow/deny | 必须 | 必须 | 必须 |
| Question | 必须 | 必须 | 必须 |
| Plan approval -> build | 必须 | 必须 | 必须 |
| Session resume | 必须 | 必须 | 必须 |
| Cancel | 必须 | 必须 | 必须 |
| 总 timeout/idle timeout | 必须 | 必须 | 必须 |
| SSE reconnect/idempotence | 必须 | 必须 | 必须 |
| Skill 脚本 canonical invocation | 必须 | 必须 | 必须 |
| Workspace escape | 必须 | 必须 | 必须 |
| Warm child reuse/reaper | 必须 | 必须 | 必须 |
| 历史 Claude task 展示 | 必须 | 必须 | 不适用 |

本地 E2E 按仓库默认环境执行：MySQL `127.0.0.1:3316`、Redis `127.0.0.1:6379`、Session schema `dataagent`、`.venv-py313`，前端命令前先 `nvm use`。

## 18. Rollout

1. 合入 Runtime/Event/Conversation/Context contracts、Runtime Server shell 和 Claude adapter，兼容投影仍生成旧事件读取结果。
2. Control Plane 改为通过 RuntimePlaneClient 执行 Claude，Runtime Plane 停止直接写业务 MySQL。
3. 引入 Command Service/outbox 和 additive message schema；兼容 API 转调新服务，先保持旧 UI history 返回形状。
4. 切换 Agent Event Protocol 写入/API/前端，并启用 Interaction Store、MessageFinalizer、cursor history 和 Context Snapshot；保留旧事件只读。
5. 发布通用 Control Plane + Claude Runtime Plane，Runtime lock 初始化为 `claude_agent_sdk`。
6. 完成一个观察周期，重点验证历史、长上下文裁剪、Session resume/rebuild 和旧数据兼容。
7. 在独立环境发布相同 Control Plane + OpenCode Runtime Plane，Runtime lock 初始化为 `opencode`。
8. 完整通过 OpenCode mandatory capability 和 E2E 后才进入正式交付清单。
9. 旧 `da_agent_sdk_record`、旧 message 冗余列和 `/sdk-events` 的删除另开设计/计划，不在本次发布直接清理。

## 19. Backout

### 19.1 Claude 抽象阶段回退

- 回退应用镜像到改造前 Claude 版本。
- 新表为 additive，旧表和旧 endpoint 保留，因此不需要破坏性数据库回滚。
- 在 backout 窗口内不删除 `chat_conversation_id` 和 `da_agent_sdk_record`。

### 19.2 OpenCode 环境回退

- OpenCode 是独立部署选择，不允许把同一运行中数据库直接改 env 切回 Claude。
- 停止 OpenCode 环境，保留其 event/message/session 数据。
- 若是尚未承载生产数据的 canary，重建独立 Claude 测试环境即可。
- 若已承载生产 Topic，必须按设计中的离线 Runtime 迁移流程处理；不能复用 OpenCode Session ID。

### 19.3 触发回退的条件

- mandatory capability contract 不通过。
- Runtime/artifact/Gateway/Cell manifest 或协议版本不一致。
- Session resume 或 SSE 丢事件出现不可接受比例。
- permission/question/plan 无法可靠暂停和恢复。
- Sandbox escape guard 与基线不等价。
- OpenCode process 资源占用超过容量预算且无法通过 warm pool 参数控制。
