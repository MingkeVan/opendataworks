# DataAgent Conversation and Context Model

**Date:** 2026-08-30
**Goal:** 在不改变当前 Claude Agent SDK 执行拓扑的前提下，拆分消息命令、语义消息、执行交互、历史读取和模型上下文，消除消息提交非原子、历史与上下文耦合及 Session 丢失后 prompt 语义漂移。
**Tech Stack:** DataAgent backend（Python、FastAPI、Pydantic、PyMySQL、Alembic、Redis、AnyIO）、DataAgent frontend（Vue 3、Vite）、MySQL 8、当前 Claude Agent SDK 执行路径
**Plan:** [2026-08-30-dataagent-conversation-context-model-plan.md](../plans/2026-08-30-dataagent-conversation-context-model-plan.md)

## Scope

### In Scope

- 为消息提交定义一个带幂等键的业务命令，并以单个 MySQL 事务创建 Task、用户消息、助手占位消息和可靠派发 Outbox。
- 把长期语义消息、执行交互、UI 历史和模型上下文定义为不同对象和查询边界。
- 定义版本化 `ConversationMessage`、`ContextFragment` 和 `ContextBundle` 契约。
- 建立 `ConversationHistoryService`、`ContextReader`、`ContextPolicy`、`ContextAssembler` 和 `ContextSnapshotStore`。
- 在当前 `task_executor.py` 内先实现唯一的 Claude `ContextRenderer`，替换通用层的 `[用户]/[助手]` 文本拼接。
- 把 permission/question/plan 的当前业务状态从 `da_agent_sdk_record` 扫描迁入独立 Interaction Store。
- 保持现有 TaskCoordinator、Sandbox Runner、Claude Agent SDK、`da_agent_sdk_record` 和 Chat V2 实时流作为本设计实施期间的主执行路径。
- 给每个实施阶段定义兼容、验证和回退边界。

### Non-Goals

- 不接入 OpenCode，不引入 Runtime Plane Server，不拆 Control Plane/Runtime Plane 进程拓扑。
- 不定义 Claude/OpenCode 双 Adapter，不增加部署期 Runtime 选择。
- 不在本设计中迁移前端到新的 Neutral Agent Event 协议。
- 不删除 `da_agent_sdk_record`、`v2StreamParser.js`、`chat_conversation_id` 或旧消息列；它们进入兼容期但仍可读。
- 不把 Attachment/Artifact 存储全面规范化为新资产系统；首版复用当前 `attachments_json` 和 workspace 文件元数据。
- 不自研 Agent Loop，不改变 Portal MCP、Skills 或 Sandbox 安全边界。
- 不承诺一次实现全部阶段；设计允许按 Task 独立合入和回退。

## Motivation

这项改造与是否接入第二个 Runtime 无关。它修复当前生产路径已经存在的正确性和可审计性问题：

1. 同一条用户消息的 HTTP 重试可能创建重复 Task，继而重复执行工具。
2. Task、用户消息、助手消息和派发不是一个事务，失败时可能留下半完成状态。
3. `show_in_ui` 同时决定 UI 展示和模型上下文，展示策略可以意外改变模型输入。
4. 原生 Session 存在时只发送当前问题，Session 不可用时却把历史拼成一段文本；同一 Topic 的上下文语义不稳定。
5. 无法回答某次模型运行实际读取了哪些消息、摘要和附件，也无法可靠重放。
6. permission/question 的业务当前状态依赖扫描 SDK 原始记录，厂商记录格式进入了控制面业务判断。

即使 DataAgent 永久只使用 Claude Agent SDK，这些问题也值得独立修复。该设计也是未来任何 Runtime 抽象的前置条件，但其交付价值不依赖后续 Runtime Plane 方案获批。

## Current State

### Message Submission

`core/task_submission_service.py::submit_message_task()` 当前顺序为：

```text
create_task
  -> append_user_message
  -> ensure_assistant_message
  -> coordinator.submit_task
```

四个动作没有共同事务，也没有 `client_message_id`。Redis/Coordinator 瞬时失败发生在前三次 MySQL 提交之后时，API 无法安全重试。

### History and Context

`TopicTaskStore.list_topic_messages*()` 查询 `da_agent_message WHERE show_in_ui = 1`，同时承担：

- Topic 历史页面的数据源。
- `TaskCoordinator._build_history()` 的模型上下文来源。
- assistant 历史 blocks 的 SDK-record 补充查询。

`TaskCoordinator._build_history()` 只保留 user/assistant 非空文本；`agent_runtime._build_prompt()` 再将它们拼成：

```text
[用户]: ...

[助手]: ...

[用户]: current question
```

`task_executor.py` 在有 `resume_session_id` 时不走该路径，只发送当前问题。因此原生 Session 的可用性改变了模型接收的 prompt 结构。

### Interactions

Permission 和 Question 的 pending/resolved 判断通过扫描 `da_agent_sdk_record` 中的 `permission_request`、`permission_decision`、`question_request` 和 `question_answer` 完成。SDK record 同时承担原始执行记录、UI 投影输入和业务状态表三种职责。

### Existing Contract Fixture

仓库已经有语言无关的 golden fixture 模式：

- `dataagent/contracts/sdk-block-projection/cases.json`
- `tests/test_sdk_block_projection_contract.py`
- `__tests__/sdkBlockProjection.contract.spec.js`

它同时约束后端 `_project_sdk_records` 与前端 `v2StreamParser.processV2Record`。本设计沿用这一模式定义 Conversation/Context fixture，而不是重新发明独立测试机制。现有 Chat V2 协议来源见：

- [2026-05-31-chat-v2-design.md](2026-05-31-chat-v2-design.md)
- [2026-06-08-chat-v2-only-cleanup-design.md](2026-06-08-chat-v2-only-cleanup-design.md)

## Problem

### Atomicity and Idempotency

HTTP 接受成功必须代表一组不可分割的事实已经提交：Task、用户消息、助手占位消息和待派发记录。当前实现只能分别保证每张表的局部提交，不能保证整个业务命令。

### Semantic Message and Execution Trace Are Conflated

长期会话历史需要稳定、简洁、可分页；执行轨迹需要逐事件审计、工具详情和交互恢复。把二者嵌在同一个 message view 中会产生 N+1 查询、厂商格式泄漏和保留策略冲突。

### UI Visibility Is Not Context Eligibility

一条消息是否展示给用户，与是否允许进入模型上下文是两个维度：

- UI notice 可以可见但不应发给模型。
- 嵌入 Question 卡片的用户答案可以不占顶层气泡，但应进入后续上下文。
- 权限 approve/deny 可见且可审计，但不应成为模型历史。

### Context Has No Reproducible Input Record

当前没有 watermark、token budget、摘要覆盖范围、来源列表、排除理由和配置指纹。Session 丢失、Prompt 策略变化或长会话裁剪后，无法复现原运行输入。

## Design

### Principles

- 一个事实只有一个权威来源；其他结构都是引用或投影。
- UI History 与 Model Context 使用不同 Service 和 Query，不允许互相调用。
- 当前用户输入在每次执行中恰好出现一次。
- Context 先结构化，再由 Runtime-specific renderer 翻译；通用层不拼角色文本。
- 原生 Session 是优化，不是会话历史数据库。
- 网络链路使用至少一次传输与业务幂等，不宣称跨系统 exactly-once。
- 先做 additive migration；旧字段和旧 API 在兼容窗口内保留。

### Domain Objects and Sources of Truth

| Object | Purpose | Source of truth | Model input |
| --- | --- | --- | --- |
| `SendMessageCommand` | 请求一次对话执行 | 接受后由 Message/Task/Outbox 共同证明 | 否 |
| `ConversationMessage` | 用户输入、助手长期答案、可复用问答 | `da_agent_message` | 由 ContextPolicy 决定 |
| `Task` | 执行生命周期 | `da_agent_task` | 否 |
| `Interaction` | permission/question/plan 当前状态与决定 | `da_agent_interaction` | Question answer 可提升为语义消息 |
| `SdkRecord` | 当前 Claude 执行轨迹与 Chat V2 投影输入 | `da_agent_sdk_record` | 否 |
| `ContextBundle` | 一次执行实际选择的结构化输入 | `da_agent_context_snapshot` | 是 |
| `NativeSessionCheckpoint` | Claude Session resume guard | `da_agent_native_session_checkpoint` | 只决定 resume/rebuild |
| `AgentViewState` | 前端实时和历史 block | SDK record 投影 | 否 |

### Module Boundaries

```text
core/conversation/
  contracts.py
  command_service.py
  message_repository.py
  history_service.py
  interaction_repository.py
  dispatch_outbox.py
  context_reader.py
  context_policy.py
  context_assembler.py
  context_snapshot_store.py
  session_checkpoint_store.py
  claude_context_renderer.py
```

依赖方向：

```text
API -> CommandService / HistoryService
Coordinator -> ContextAssembler -> ClaudeContextRenderer -> existing task_executor
SDK callbacks/routes -> InteractionRepository
Repositories -> MySQL
Frontend -> Command API / History API / existing sdk-events API
```

规则：

- API route 只做 schema、认证、授权和错误映射。
- `ConversationCommandService` 独占消息接受事务。
- `HistoryService` 不能读取 `da_agent_sdk_record`；执行详情由现有 task events/SDK events API 单独加载。
- `ContextReader` 不能调用 History API，必须读取带 watermark 的语义候选。
- `ClaudeContextRenderer` 可以认识 Claude prompt/session 能力；其他 conversation 模块不能 import `claude_agent_sdk`。
- `TopicTaskStore` 在迁移期保留 facade，内部逐步委托到新 repositories。

### SendMessageCommand

规范入口：

```http
POST /api/v1/nl2sql/topics/{topic_id}/messages
Idempotency-Key: <client_message_id>
```

```json
{
  "client_message_id": "01J...",
  "content": {
    "parts": [
      {"type": "text", "text": "最近 30 天工作流发布次数趋势"}
    ]
  },
  "execution": {
    "provider_id": "openrouter",
    "model_name": "anthropic/claude-sonnet-4",
    "database_hint": "opendataworks",
    "debug": false,
    "execution_mode": "interactive"
  }
}
```

内部 queue/schedule 使用确定性幂等键：`queue:{queue_id}`、`schedule-log:{schedule_log_id}`。兼容 `/tasks/deliver-message` 和 `/tasks` 在迁移期转调同一 Service；新前端始终生成 `client_message_id`。

### Atomic Accept Transaction

`ConversationCommandService.accept()` 在一个 MySQL 事务内：

1. 校验 Topic 与 Actor 权限、Topic 绑定 Agent 和 execution options。
2. 以 `(topic_id, client_message_id)` 查询既有 user message；存在则返回首次生成的 IDs。
3. `SELECT ... FOR UPDATE` 锁定 Topic sequence。
4. 创建 Task。
5. 创建不可变 user message。
6. 创建 Task 唯一 assistant placeholder，状态 `waiting`。
7. 回写 Task 的 `input_message_id` 和 `output_message_id`。
8. 创建唯一 pending dispatch outbox。
9. 提交后返回 `task_id/user_message_id/assistant_message_id`。

Outbox Dispatcher 独立读取 committed outbox 并发布到现有 Redis Coordinator。Redis 不可用不回滚已经接受的用户消息；dispatcher 重试。Coordinator 仍以 `task_id` claim，重复 publish 不重复执行。

### ConversationMessage

```python
class ConversationMessage:
    message_id: str
    topic_id: str
    task_id: str | None
    seq_id: int
    role: Literal["user", "assistant"]
    kind: Literal["chat", "interaction_answer", "notice"]
    status: Literal["waiting", "streaming", "final", "failed", "cancelled"]
    parts: list[ConversationPart]
    plain_text: str
    visibility: Literal["visible", "embedded", "hidden"]
    context_eligible: bool
    client_message_id: str | None
    parent_message_id: str | None
```

`ConversationPart` v1 是显式联合类型：

- `TextPart {type=text, text}`
- `AttachmentRefPart {type=attachment_ref, attachment_id, title, mime_type}`
- `QuestionAnswerPart {type=question_answer, interaction_id, answers}`

不允许把 thinking、tool input/output、permission payload 或厂商 part 塞进 `ConversationPart`。

写入规则：

- user message 创建后不可修改。
- assistant placeholder 在 terminal 前可由现有 coordinator/result writer 幂等更新，terminal 后正文不可修改。
- 一个 Task 只有一个顶层 user message 和一个顶层 assistant message。
- `feedback` 继续作为独立用户评价字段，不改变消息正文。
- `event/steps_json/tool_json` 新路径停止写入，但不在本设计中删除。
- `task.prompt` 进入兼容期，user message 是用户输入的权威来源。

### Conversation History

新查询按 `seq_id` cursor，而不是 page/offset：

```http
GET /api/v1/nl2sql/topics/{topic_id}/messages?before_seq=120&limit=50
GET /api/v1/nl2sql/messages/{message_id}
```

约束：

- 只查询 semantic message 和当前 attachment metadata。
- 不加载 SDK records，不内嵌完整 execution blocks。
- 返回 `task_id` 和执行详情链接；展开详情时调用现有 task SDK-event API。
- 新增消息期间，`before_seq/after_seq` 分页不重不漏。
- 旧 page/offset API 在兼容期转调 HistoryService，并保持原响应 envelope；只有旧 Chat V2 history view 仍可请求 legacy blocks。

### Context Contracts

Canonical schema：

```text
dataagent/contracts/conversation/v1/
  send-message-command.schema.json
  conversation-message.schema.json
  context-bundle.schema.json
  cases.json
  README.md
```

`ContextBundle` 是本设计与未来 Runtime 抽象之间唯一需要冻结的接口：

```python
class ContextBundle:
    spec_version: Literal["conversation-context/v1"]
    snapshot_id: str
    topic_id: str
    task_id: str
    current_message_id: str
    history_through_seq: int
    policy_version: str
    system_fragments: list[ContextFragment]
    memory_fragments: list[ContextFragment]
    conversation: list[NeutralMessage]
    current_input: NeutralMessage
    attachments: list[ContextAttachment]
    provenance: list[ContextProvenance]
    budget: ContextBudgetResult
```

`ContextFragment` 显式保存：

```text
fragment_id
channel       system | memory | conversation | current_input | attachment
role          system | user | assistant
trust         trusted | derived | untrusted
source_type
source_id
content
content_hash
estimated_tokens
```

Provider/MCP credential、Claude Session ID、数据库连接密码不属于 ContextBundle。

### Context Read Watermark

每次 Task 使用持久化 user message 作为当前输入：

- `current_message_id` 固定为 Task 的 `input_message_id`。
- `history_through_seq = current_user.seq_id - 1`。
- `ContextReader` 只读取 `seq_id <= history_through_seq` 且 `context_eligible = true` 的 terminal semantic messages。
- 当前 user message 不进入 history 集合，只放入 `current_input`。
- failed/cancelled 且无有效正文的 assistant message、permission decision、tool delta、UI notice 默认排除。
- Question answer 可以写成 `interaction_answer + embedded + context_eligible`。

### ContextPolicy v1

首版使用可测试的确定性规则：

1. 预留最大输出 tokens、工具 schema 和系统指令预算。
2. 按固定顺序加入 platform、Agent snapshot、Skill 和 data-scope fragments。
3. 加入最新且覆盖连续区间的 conversation summary。
4. 从新到旧加入 summary watermark 之后的完整 user/assistant turn；不能只留下半轮。
5. 加入本轮显式引用的 attachment metadata/选定内容。
6. 最后加入 current input，且不得再次出现在 history。
7. 达到预算时停止；记录每个 source 的 selected/excluded 状态和原因。

同一 sources、policy version、model budget 和配置指纹必须产生相同选择结果。

### Context Snapshot

在调用 Claude 之前持久化不可变 snapshot：

```text
snapshot_id
topic_id / task_id / current_message_id
history_through_seq
policy_version
context_json
selected_sources_json
excluded_sources_json
estimated_input_tokens
summary_id / summary_covers_through_seq
agent_snapshot_fingerprint
skill_set_fingerprint
tool_catalog_fingerprint
workspace_fingerprint
model_target_fingerprint
parent_snapshot_id
created_at
```

Snapshot 使以下问题可审计：本次看了哪些消息、为什么排除某段、是否用了摘要、Session 丢失时重建了什么、策略升级前后的输入有何差异。

### Claude ContextRenderer and Native Session

首版只实现 `ClaudeContextRenderer`，并继续在当前 `task_executor.py` 内调用 Claude Agent SDK。

`ContextDeliveryPlan` 只有两种模式：

- `resume`：原生 Session checkpoint 完全匹配时，只把 `current_input` 发送给 Claude。
- `rebuild`：Session 不存在、不可读、watermark 落后/超前或指纹变化时，用完整 ContextBundle 创建新 Session。

Resume 条件全部满足才成立：

- checkpoint 的 `committed_message_seq == history_through_seq`。
- `last_context_snapshot_id` 与当前 snapshot 的 parent lineage 相连。
- Agent/Skill/Tool/Workspace/Model fingerprints 相容。
- Claude Session 实际存在、可读、没有未决旧 Task。

通用层删除 `_build_history()` 和 `[用户]/[助手]` `_build_prompt()`。如果 Claude SDK 新 Session 入口只能接受文本，版本化 transcript rendering 只能位于 `ClaudeContextRenderer`，并用 golden fixture 锁定角色、顺序、trust boundary 和 current-input-once 不变量。

Task terminal 与 assistant message terminal 成功提交后，原子推进 checkpoint 的 `committed_message_seq` 和 `last_context_snapshot_id`。失败/取消不会推进。

### Interaction Store

```text
da_agent_interaction
  interaction_id
  topic_id / task_id
  kind                 permission | question | plan_approval
  status               pending | resolved | expired | cancelled
  request_json
  decision_json
  version
  expires_at
  created_at / resolved_at
```

控制面规则：

- SDK callback/record writer 创建 pending Interaction，并保留现有 SDK record 供 Chat V2 投影。
- 用户 resolve 使用 `interaction_id + version` compare-and-set，重复提交返回首次决定。
- Interaction Store 是“当前还能否操作、用户决定是什么”的权威；SDK record 只保留执行审计和兼容投影。
- Permission/Plan decision 不进入后续 Context。
- Question answer 在需要跨 turn 保留时，事务性创建 embedded semantic message。

### Frontend Boundaries

前端拆分三条路径，但本设计不替换现有 SDK-event parser：

- `conversationCommandApi`：生成并重用 `client_message_id`，使用服务端返回的 message IDs。
- `conversationHistoryApi`：按 `seq_id` 读取 semantic history。
- `sdkEventTransport`：继续读取当前 Claude SDK events，渲染 live blocks 和 execution detail。

发送后 UI 以服务端返回的 IDs 建 optimistic view；terminal 后获取 assistant semantic message 对账。页面刷新先读 semantic history，再为 active task 恢复现有 SDK-event stream。

### Data Migration

采用 additive migration：

| Change | Purpose |
| --- | --- |
| extend `da_agent_message` | `role/kind/content_json/plain_text/visibility/context_eligible/client_message_id/parent_message_id/finalized_at` |
| extend `da_agent_task` | `input_message_id/output_message_id/context_snapshot_id`；`prompt` deprecated |
| `da_agent_task_dispatch_outbox` | 可靠派发 |
| `da_agent_interaction` | 交互当前状态 |
| `da_agent_context_snapshot` | 实际模型输入 |
| `da_agent_context_summary` | 可版本化摘要；在长会话阶段启用 |
| `da_agent_native_session_checkpoint` | Session watermark 与配置 lineage |

首版不新建 Artifact/Relation 表，避免把资产系统重构叠加进来。现有 `attachments_json` 通过 adapter 读入 ContextAttachment；未来如需规范化另开 design。

### Consistency Invariants

- `(topic_id, client_message_id)` 唯一。
- 一个 Task 只有一个顶层 user message、一个顶层 assistant message和一个 pending dispatch outbox。
- user message 创建后不可变。
- assistant message terminal 后正文不可变。
- Interaction resolve 使用版本 CAS。
- current input 在 ContextBundle 中恰好一次。
- ContextSnapshot 在执行开始后不可变。
- failed/cancelled Task 不推进 native-session checkpoint。

## Interfaces / Data Model

### Public APIs

```http
POST /api/v1/nl2sql/topics/{topic_id}/messages
GET  /api/v1/nl2sql/topics/{topic_id}/messages?before_seq=&after_seq=&limit=
GET  /api/v1/nl2sql/messages/{message_id}
POST /api/v1/nl2sql/tasks/{task_id}/permission-decision
POST /api/v1/nl2sql/tasks/{task_id}/question-answer
```

现有 deliver/task/history endpoints 在兼容期保留 URL 和 response envelope，但内部调用新服务。

### Internal Interfaces

```python
class ConversationCommandService(Protocol):
    async def accept(self, command: SendMessageCommand, actor: ActorContext) -> AcceptedMessageTask: ...

class ConversationHistoryService(Protocol):
    def list_messages(self, query: HistoryQuery, actor: ActorContext) -> MessagePage: ...

class ContextReader(Protocol):
    def read_sources(self, request: ContextReadRequest) -> ContextSources: ...

class ContextPolicy(Protocol):
    def select(self, sources: ContextSources, budget: ContextBudget) -> ContextSelection: ...

class ContextAssembler(Protocol):
    def assemble(self, request: ContextBuildRequest) -> ContextBundle: ...

class ClaudeContextRenderer(Protocol):
    def render(self, bundle: ContextBundle, delivery: ContextDeliveryPlan) -> ClaudeRenderedInput: ...
```

## Rollout and Backout

### Phase 1: Contracts and Atomic Accept

- Add schemas, message/task columns and outbox.
- Route new frontend and internal producers through CommandService.
- Keep old history/context/execution unchanged.
- Backout: route endpoints back to old submission service; additive rows remain inert. Outbox dispatcher must be stopped before rollback.

### Phase 2: Semantic History

- Enable new HistoryService/cursor API.
- Keep old page/offset API and SDK block hydration for legacy consumers.
- Backout: switch frontend to old history API; no destructive migration required.

### Phase 3: Context Snapshot and Claude Renderer

- Build ContextBundle and persist snapshot.
- Canary Claude renderer with snapshot audit; then remove generic prompt concatenation.
- Backout: restore legacy `_build_history/_build_prompt` feature flag while retaining snapshots for diagnosis. Do not delete checkpoints during rollback.

### Phase 4: Interaction Store

- Dual-record Interaction Store and compatible SDK record for one release.
- Switch business reads to Interaction Store after reconciliation metrics reach zero drift.
- Backout: read pending/resolved state from SDK records; retain new interaction rows.

### Phase 5: Cleanup

Deletion of old columns, old APIs or SDK compatibility paths requires a separate cleanup design after at least one stable release.

## Risks / Alternatives

### Risks

- Transactional outbox can accumulate during Redis outage; dispatcher lag and oldest-pending age need alerts.
- ContextPolicy changes can alter model output; every snapshot records policy version and selected sources.
- Claude Session checkpoint can diverge from semantic history; mismatch always rebuilds rather than guessing.
- Summary may introduce factual compression errors; summary is `derived`, retains coverage/provenance and never replaces current turns silently.
- Dual-write Interaction/SDK records can drift; compatibility phase needs reconciliation metrics and idempotent repair.

### Alternatives Rejected

#### Keep `show_in_ui` as Context Filter

It keeps implementation small but lets presentation decisions change model behavior and cannot represent hidden-but-contextual answers.

#### Store Full Tool Trace in ConversationMessage

It makes history self-contained but couples long-term messages to vendor event shapes and prevents separate retention.

#### Trust Native Session as History

It minimizes prompt work but cannot recover from Session loss, cannot audit actual inputs and blocks future Runtime portability.

#### Rebuild Runtime Plane in the Same Change

The current correctness fixes have independent value and lower risk when implemented against the existing Claude path first. Runtime topology is handled by a separate proposal after `ContextBundle` is frozen.

## Verification

### Contract Tests

- Shared `cases.json` validates Python schema/parser and TypeScript message/context types.
- Same inputs and budget produce byte-stable normalized ContextSelection.
- Current input appears exactly once.
- Complete turns remain paired under truncation.
- Question answers may enter context; permission/plan decisions cannot.
- Claude renderer golden fixture preserves role/order/trust boundaries.

### Store and Transaction Tests

- Fail each accept step and verify full rollback.
- Retry same `client_message_id` and verify same Task/message IDs.
- Commit succeeds while Redis is down; dispatcher later publishes once logically.
- Concurrent message inserts preserve Topic sequence.
- History cursor does not skip/duplicate messages during concurrent append.
- Interaction CAS rejects stale version and returns existing result for duplicate decision.

### Context and Session Tests

- Snapshot sources do not exceed watermark.
- Failed/cancelled empty responses are excluded.
- Credential/private connection fields never enter snapshot JSON.
- Matching checkpoint uses `resume` and only current input.
- Lost Session, changed fingerprint, ahead/behind watermark and orphan checkpoint use `rebuild`.
- Failed/cancelled execution does not advance checkpoint.

### Local E2E

Use repository defaults: MySQL `127.0.0.1:3316`, Redis `127.0.0.1:6379`, `.venv-py313`, frontend after `nvm use`.

Required smoke paths:

- Message accepted, Task transitions `waiting -> running -> terminal`, assistant message persists.
- HTTP retry does not create duplicate message/Task/tool execution.
- Redis interruption recovers through outbox.
- Page refresh rebuilds semantic history and active SDK-event blocks.
- Long conversation uses summary + recent complete turns and records exclusions.
- Claude Session deletion causes deterministic ContextBundle rebuild without duplicate current input.
- Permission/question/plan interaction survives page refresh and duplicate decision submission.

If full local E2E is not run, implementation PRs must state the exact untested path; unit/contract tests alone are not “fully verified”.
