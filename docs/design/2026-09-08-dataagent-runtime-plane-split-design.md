# DataAgent 控制面/数据面拆分与 Pi Agent 运行时接入设计

日期：2026-09-08

## Background

DataAgent 当前的智能问答执行链路是「一套代码」：`core/task_coordinator.py` 组装
`TaskExecutionInput`，`core/task_executor.py:execute_task_stream` 派发执行，
`core/agent_runtime.py` 提供提示词组装、运行时环境、工作区边界校验等共享能力。
底层执行引擎只有一个——`claude-agent-sdk`。

需要引入第二个数据面：基于 `@earendil-works/pi-agent-core@0.85.1` 的 Node 运行时
（下称 Pi Cell），同时保留现有 SDK 数据面，通过部署级开关切换。

前一版实现（PR #450）另起了一套并行体系：独立的 `AgentRunRequest` 契约、
监听 8002 的 FastAPI Gateway、磁盘 `EventSpool`、HMAC capability token、
以及新建的 `core/agent_runtime/` 包。该方案存在三个结构性问题：

1. `core/agent_runtime/`（包）遮蔽了既有的 `core/agent_runtime.py`（模块），
   `core.task_executor` / `core.task_coordinator` / `sandbox_runner_main` 全部
   import 失败，`tests/test_agent_runtime.py` 由 56 passed 变为 47 failed。
2. 独立 HTTP Gateway 引入了端口、自签名 token、自建磁盘 spool 三层分布式复杂度，
   而系统内已有等价资产（sandbox runner 的进程隔离、`da_agent_sdk_record` 的
   持久化与断线可读）。
3. 新体系与现有调度、持久化、前端渲染三条链路全部未接线。

本设计放弃并行体系，改为「控制面统一调度 + 进程级数据面适配 + 共享事件存储」。

## 现状事实

以下均为对当前 `main` 的代码核对结论，是本设计的前提：

- `execute_task_stream` 已经是一个 dispatcher（`task_executor.py:738`），
  在 `TaskExecutionInput → TaskExecutionResult` 契约下已有两个后端：
  sandbox runner（HTTP 派发到独立容器）与 local（进程内）。
- `_execute_task_stream_local` 的前 5 步——provider/model 解析、cancel 闭包、
  prompt 组装、skill runtime 解析、system prompt 组装——全部与引擎无关；
  引擎耦合从 `from claude_agent_sdk import ...`（`task_executor.py:927`）才开始。
- `core/agent_runtime.py` 共 928 行、对外导出 23 个符号，其中硬 SDK 耦合仅 5 个
  （`_build_workspace_boundary_hooks`、`_resolve_sdk_permission_mode`、
  `_result_subtype_to_reason`、`_extract_block`，以及 `_build_allowed_tools` 中的
  `mcp__portal__*` 命名）。约 110 行的
  `_validate_bash_workspace_boundary` / `_validate_workspace_tool_boundary`
  是引擎中立的策略实现。
- `da_agent_sdk_record` 表结构中立（`record_type` + `event_type` + `data JSON`），
  但 `core/sdk_block_writer.py` 不中立——它按 `type(msg).__name__` 分发
  `StreamEvent` / `AssistantMessage` / `UserMessage`，即 `claude_agent_sdk` 的
  Python 对象，无法接收中立 JSON 事件。
- 会话续接语义：`task_executor.py:912`
  `prompt = question if resume_session_id else _build_prompt(history, question)`。
  SDK 走引擎级 session 恢复，Pi 只能走 transcript replay。
- 确认/暂停回路由 `_build_can_use_tool_callback` → `core/permission_wait.py`
  `wait_for_decision`（MySQL 1s 轮询）→ `PARKED_TASK_STATUSES` 构成。
- 前端有两个流式消费点：`useNl2SqlChat.js:301` 与 `widget/WidgetChat.vue:360`，
  均调用 `v2StreamParser.js:processV2Record`；渲染适配器
  `blockToToolProp`（`v2StreamParser.js:249`）读取 `block.output` / `block.is_error`。

## Scope

范围内：

- 在既有 `execute_task_stream` 插槽下新增 Pi 数据面，不新建调度层、不新建 HTTP 服务。
- `core/agent_runtime.py` 原地分区（中立控制面 / SDK 适配 / Pi 适配），
  保持对外导出符号不变。
- 工作区边界策略：Python 生成策略规格，Node 侧执行。
- 事件持久化复用 `da_agent_sdk_record`，新增中立事件 writer。
- 前端按 `record_type` 分流，两套 reducer 收敛到同一 block 形状。

范围外：

- 不改 `core/task_coordinator.py`：调度层不感知 runtime_kind。
- 不改 `da_agent_sdk_record` 表结构，无 Alembic 迁移。
- 不改任何 Vue 渲染组件。
- 里程碑 1 不实现 Pi 侧的确认/暂停回路（见「里程碑」）。

## Solution

### 拓扑

```
core/task_coordinator.py                  控制面：组装 TaskExecutionInput（不感知 runtime_kind）
        │
        ▼
execute_task_stream                       派发维度一：隔离拓扑
        ├── _should_use_sandbox_runner ──▶ _execute_task_stream_via_runner ──▶ 独立容器
        └── ─────────────────────────────▶ _execute_task_stream_local
                                                    │
                          共享准备：provider/model、prompt、skill runtime、system prompt、
                          allowed roots、cancel 闭包
                                                    │
                                       派发维度二：runtime_kind
                          ┌─────────────────────────┴─────────────────────────┐
                          ▼                                                   ▼
                  claude_code                                          pi_agent_core
              claude_agent_sdk.query()                       Node 子进程 + stdio NDJSON
                          │                                                   │
                  SdkBlockWriter                                      PiEventWriter
                          └─────────────────┬─────────────────────────────────┘
                                            ▼
                                   da_agent_sdk_record
                                            │
                                            ▼
                          前端按 record_type 分流 → 同一 state.turns/blocks
```

关键点：**`runtime_kind` 与 `sandbox_mode` 是正交的两个维度**。前者选执行引擎，
后者选隔离拓扑。二者不能并列在同一个 if 链上——否则生产环境开启 sandbox 后切到 Pi，
Node 子进程会在 `dataagent-backend` 主进程内拉起，容器隔离失效。因此
`runtime_kind` 的分叉点下沉到 `_execute_task_stream_local` 与 sandbox runner 内部。

### 相对 PR #450 的删除项

| 组件 | 处置 | 理由 |
|---|---|---|
| `core/agent_runtime/`（包） | 删除 | 遮蔽既有模块，是 P0 阻塞问题 |
| `runtime_gateway/app.py` | 删除 | 无跨机调用，不需要监听端口 |
| `runtime_gateway/event_spool.py` | 删除 | `da_agent_sdk_record` 已提供持久化与断线可读 |
| `runtime_gateway/supervisor.py` | 删除 | 子进程生命周期收归适配器函数 |
| `runtime_gateway/capability.py`、`core/agent_runtime/security.py` | 删除 | 父子进程 stdio 隔离，无需自签名 token |
| `core/agent_runtime/contracts.py` | 删除 | 复用 `TaskExecutionInput`/`TaskExecutionResult` |

净减约 1000 行，同时消除端口、共享密钥、磁盘 spool 三处攻击面。

### Cell 启动契约

Python 侧在拉起 Node 子进程时，通过 **启动参数（stdin 首帧 `cell.init`）** 一次性
下发全部运行时配置，此后 stdio 上只流转事件与控制帧：

```jsonc
{
  "protocol_version": 1,
  "type": "cell.init",
  "payload": {
    "run_id": "<task_id>",
    "topic_id": "...",
    "system_prompt": "...",          // Python _build_system_prompt 产出
    "messages": [ ... ],             // Python 由 history + question 组装
    "model": { "provider_id": "...", "model_id": "...", "base_url": "..." },
    "limits": { "total_timeout_seconds": 360, "idle_timeout_seconds": 120,
                "max_turns": 30, "max_tool_calls": 50 },
    "boundary_policy": { ... },      // 见下节
    "skills": [ { "name": "...", "root_path": "..." } ],
    "runtime_env": { "DATAAGENT_PYTHON_BIN": "...", "DATAAGENT_SKILL_ROOT": "..." }
  }
}
```

Provider 凭据不进 `cell.init` payload，通过子进程环境变量下发，避免出现在
stdio 日志与任何持久化记录中。

### 工作区边界：Python 生成规格，Node 执行

**决策**：采用启动时下发固化策略、Node 侧自行判定，不做逐次工具调用的
stdio 往返。理由是往返会让每次工具调用增加一次 IPC 与一次 Python 事件循环调度，
而 Pi 的 `beforeToolCall` 是同步阻塞点。

该决策的已知代价是 bash 命令解析需要在 TS 侧重新实现，可能与 Python 侧漂移。
用两条措施把漂移收敛掉：

1. **单一策略规格**。Python 的 `_build_workspace_allowed_roots(project_cwd,
   skill_runtime, scratch_dirs)` 是纯函数，其输出加上 `tool_result_root` 构成
   完整策略。序列化为 `boundary_policy`：

   ```jsonc
   {
     "policy_version": 1,
     "allowed_roots": ["/dataagent_runtime/<topic>/workspace", "..."],
     "tool_result_root": "/.../tool-results",
     "discard_sinks": ["/dev/null"],
     "readonly_commands": ["cat", "head", "tail", "..."],   // _BASH_READONLY_COMMANDS
     "write_tool_names": ["Write", "Edit", "NotebookEdit", "..."]
   }
   ```

   Python 侧不再手写这份 JSON，由 `core/boundary_policy.py:build_boundary_policy()`
   从既有常量与纯函数导出，保证规格与 Python 执行路径同源。

2. **跨语言一致性夹具**。`dataagent/contracts/boundary/v1/conformance-cases.json`
   收录用例表（每条：`tool_name` / `tool_input` 或 `command`、`policy`、
   `expect: "allow" | "deny"`）。用例从既有 22 个 boundary 测试
   （`tests/test_agent_runtime.py`）导出，覆盖父目录遍历、赋值语句藏路径、
   `/dev/null` 重定向、offloaded tool result 的读放行/写拒绝、
   分页器查看拒绝、跨 topic 与 home 凭据拒绝、共享 runtime root 拒绝等。
   Python 与 TS 各有一个测试读同一份 JSON 跑同一张表。任一侧漂移即红。

TS 侧执行器 `src/policy/workspace-boundary-enforcer.ts` 挂到 Pi 的
`beforeToolCall`，判定为 deny 时返回 `{ block: true, reason }`，
并发出一条 `tool.denied` 事件供审计。

### 会话续接语义

| 维度 | claude_code 数据面 | pi_agent_core 数据面 |
|---|---|---|
| 续接机制 | 引擎级 session 恢复 | transcript replay |
| 首轮输入 | `question` | `messages = [user(question)]` |
| 多轮输入 | 仅 `question`（引擎自持上下文） | `messages = history + user(question)` |
| 缓存依赖 | 服务端 session | prompt caching（要求 system prompt 与前序 messages 字节稳定） |

Pi 适配器统一忽略 `params.resume_session_id`，恒定由 `params.history + question`
组装 `messages`，并保持确定性排序以命中 prompt cache。

回填 `TaskExecutionResult.session_id = f"pi-{topic_id}-{task_id}"`——**必须是 task
级唯一值而非 topic 级常量**。该值会被下一轮读回作为 `resume_session_id`；若为
topic 级常量，一旦部署切回 `claude_code`，SDK 分支会因 `resume_session_id` 非空
而走「只传本轮问题」路径，丢掉全部历史。

### 事件持久化

新增 `core/pi_event_writer.py`，与 `SdkBlockWriter` 平级，共用
`topic_task_store.append_sdk_record(task_id, topic_id, turn_index, record_type,
event_type, data)`：

| 字段 | 值 |
|---|---|
| `record_type` | `"pi_event"` |
| `event_type` | 中立事件类型（`content.delta`、`tool.started`、…） |
| `data` | 事件 payload |
| `turn_index` | 由 `turn.started` 递增，与 SDK writer 语义一致 |

表结构与迁移零改动。

### 事件契约与前端

Cell → Python 的中立事件沿用 PR #450 的 `AgentEventType` 枚举（
`run.started` / `turn.started` / `content.delta` / `tool.started` /
`tool.completed` / `run.completed` / `run.failed` / `run.cancelled` 等），
JSON Schema 保留在 `dataagent/contracts/agent-events/v1/`。

前端在两个消费点各加一次分流：

```js
// useNl2SqlChat.js:301 与 widget/WidgetChat.vue 同形
if (record.record_type === 'pi_event') {
  reduceAgentEvent(state, record.data)
} else {
  processV2Record(state, record)
}
```

**前提是两个 reducer 产出同形 block**。当前 PR #450 的 reducer 不满足：
它写 `block.result` / `block.error`，而 `blockToToolProp` 读 `block.output` /
`block.is_error`，且缺 `inputJson`。因此 reducer 必须对齐为：

```js
{ type: 'tool_use', id, name, input, inputJson, output, is_error, status, content }
```

对齐后渲染组件（`NL2SqlChatV2.vue`、`ToolOutputRenderer.vue`、`WidgetChat.vue`）
零改动。`createAgentEventChatState()` 的字段是 `createChatState()` 的超集
（多 `lastSequence`、`interactions`），可直接兼容。

### 超时链

按 AGENTS.md 的两级超时模型，Pi 数据面实现：

| 层 | 值来源 | 行为 |
|---|---|---|
| 单轮总超时 | `TaskExecutionInput.timeout_seconds`（缺省 360s） | 到期发 `run.cancel` 帧，2s 后 SIGKILL 子进程 |
| 空闲超时 | 缺省 120s | 无新事件或工具输出即判定停滞，同上处理 |
| 工具执行超时 | 由 `boundary_policy` 下发 | `ProcessExecutor` 逐次 kill |

Pi Cell 侧必须消费 `cell.init` 的 `limits`，不得像 PR #450 那样完全忽略
`request.limits`。子进程被 kill 时 Python 侧合成终态 `run.failed`
（`error_code: CELL_LOSS`）并写入 `da_agent_sdk_record`，保证任务不会悬挂。

## Error semantics

| 场景 | 处理 |
|---|---|
| Node 二进制或 `dist/src/main.js` 缺失 | `TaskExecutionResult(task_status="error", error.code="pi_runtime_missing")`，与既有 `sdk_not_installed` 对称 |
| 子进程启动后握手超时（5s） | kill + `run.failed`，`error_code: CELL_HANDSHAKE_TIMEOUT` |
| 子进程中途退出 | Python 侧合成 `run.failed`，`error_code: CELL_LOSS`；必须 `kill()` 而非仅摘除引用 |
| stdout 出现非 JSON 行 | 记 stderr 日志并跳过该行；不视为 EOF、不拆通道（PR #450 在此处会误杀通道） |
| stdout 出现空行 | 跳过；不视为 EOF |
| 边界策略拒绝 | `tool.denied` 事件 + Pi `beforeToolCall` 返回 block，不终止整个 run |
| 用户取消 | `is_cancel_requested` 命中 → `run.cancel` 帧 → `run.cancelled` 终态 |

## 里程碑

**里程碑 1（本次）**：单向执行链路打通。
控制面派发、Cell 启动契约、边界策略生成与执行、事件持久化、前端分流、超时链。
`policy.require_write_confirmation` 在 Pi 数据面下强制为 `false`，
即里程碑 1 的 Pi 运行时不提供写确认，仅供只读问答场景验证。

**里程碑 2（后续）**：确认/暂停回路。
Cell `beforeToolCall` 挂起 → `interaction.requested` 帧 → Python 侧复用
**同一个** `wait_for_decision` 与 `PARKED_TASK_STATUSES` → `interaction.resolve`
帧放行。前置条件是 Cell 侧 interaction 必须带超时（PR #450 的实现是永不超时的
裸 Promise，接入前必须修）。该里程碑独立评审，不与里程碑 1 混合。

## Verification gates

1. `tests/test_agent_runtime.py` 在改动后仍为 56 passed（回归基线）。
2. `sandbox_runner_main`、`core.task_coordinator`、`core.task_executor` 可正常 import。
3. 边界一致性：Python 与 TS 两侧对 `conformance-cases.json` 全表判定一致。
4. `dataagent-runtime-pi`：`npm run typecheck && npm test && npm run build` 通过；
   新增一条**真正拉起子进程**的 Python↔Node 契约测试（PR #450 缺此项，
   导致 `run.start` / `run.event` 两处 payload 形状不一致全部漏检）。
5. 前端 `vitest run`：两个 reducer 对同一逻辑轮次产出同形 `state.blocks`。
6. 本地端到端 smoke（按 AGENTS.md「Intelligent Query local smoke method」）：
   `DATAAGENT_RUNTIME_KIND=pi_agent_core` 下提交一次真实 NL2SQL 请求，
   验证任务创建、事件写入 `da_agent_sdk_record`、终态落库、前端渲染。

## Rollout and backout

- 部署开关：`DATAAGENT_RUNTIME_KIND`，缺省 `claude_code`（现状行为不变）。
- 灰度：先在 dev compose 打开，验证 smoke 后再考虑生产。
- 回退：将 `DATAAGENT_RUNTIME_KIND` 改回 `claude_code` 即完全恢复现有链路；
  无 schema 变更、无数据迁移，历史 `pi_event` 记录留在表内不影响 SDK 路径读取。

## References

- `docs/design/2026-09-08-dataagent-pi-agent-kernel-design.md`（前一版并行体系设计，本设计取代其运行时平面部分）
- `docs/design/2026-08-11-dataagent-workspace-scratch-allowlist-design.md`
- `docs/plans/2026-09-08-dataagent-runtime-plane-split-plan.md`
