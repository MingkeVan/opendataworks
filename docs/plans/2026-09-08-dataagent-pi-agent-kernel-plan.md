# DataAgent Pi Official Agent Kernel Implementation Plan

> Design: [DataAgent Pi Official Agent Kernel](../design/2026-09-08-dataagent-pi-agent-kernel-design.md)

**Goal:** 在不改变部署期单 Runtime 约束的前提下，交付一个由 Pi 官方 `pi-agent-core + pi-ai` 驱动、由 DataAgent 掌握业务语义和持久化的生产级 Runtime Plane。
**Tech Stack:** Python/FastAPI/AnyIO/MySQL/Redis Control Plane，Python Runtime Gateway，Node.js 22.19+/TypeScript Pi Runtime Cell，Docker/Podman，Portal MCP，Vue 3

## Architecture Summary

实施按“先契约、后内核、再迁移”的顺序推进：

```text
Conversation/Context contracts
  -> Runtime/Event/Interaction/Tool contracts
  -> generic Gateway and durable spool
  -> official Pi Runtime Cell
  -> Context/Provider/Tool/Policy integration
  -> Control persistence and neutral frontend
  -> single-active deployment cutover
```

Pi Runtime 是独立 Node 22 package/image；仓库根 Node 20 前端基线不升级。Control Plane 仍是 Python。Gateway/Cell 不持有业务 MySQL 凭据，Pi state 不成为消息或历史权威来源。

粗略工作量为 10–18 engineer-weeks，前提是 PR #448 的 Conversation/Context 契约已完成且 Pi Gate 没有发现必须 fork 上游的问题。计划中的 Gate 是生产实现前置条件，不是边做边验证的普通任务。

## Task 0: Complete Product and Official Pi Go/No-Go

**Files:**

- `docs/reports/<date>-dataagent-pi-kernel-spike.md`
- `tools/dataagent-evals/` or a dedicated temporary spike package

**Steps:**

1. 记录 product owner、目标环境、采用 Pi Kernel 而非现成 Runtime Adapter 的主要驱动和长期维护 owner。
2. 固定 `@earendil-works/pi-agent-core@0.85.1`、`@earendil-works/pi-ai@0.85.1`、Node `>=22.19.0`、upstream commit、license、tarball integrity 和镜像基线。
3. 用官方 `Agent` 和 `pi-ai` 实测 text、reasoning、tool、multi-turn、usage、retry、abort、context overflow。
4. 实测 awaited `subscribe()`、`beforeToolCall` async wait、`afterToolCall`、`shouldStopAfterTurn`、sequential tool execution、steer 和 follow-up。
5. 验证主 Anthropic/compatible Provider 和一个 OpenAI-compatible Provider 的 base URL、headers、credential、tool call 和 token accounting。
6. 在 Node 22 + Python 3.11+ 容器内执行一个真实 Skill 脚本并调用 Portal MCP。
7. 记录 GO/NO-GO；任何关键需求必须 patch/fork Pi 私有实现时判 NO-GO，不开始 Task 1–12 的生产代码。

**Expected Result:**

- 有可复核的版本化 spike 报告和明确 GO/NO-GO。
- 没有以“后续实现时再解决”绕过的核心 API 或安全前提。

## Task 1: Land Conversation and Context Prerequisites

**Files:**

- Files defined by [PR #448](https://github.com/opendata-lab/opendataworks/pull/448)
- `dataagent/dataagent-backend/core/context/`
- `dataagent/dataagent-backend/core/conversation/`
- `dataagent/dataagent-backend/alembic/versions/*conversation_context*.py`

**Steps:**

1. 先实现/合入原子 `SendMessageCommand`、ConversationMessage、HistoryQuery、ContextPolicy、ContextSnapshot 和 ContextBundle。
2. 把 `_build_history`/`_build_prompt` 从 UI message 读取路径迁移到 versioned ContextAssembler。
3. 保留现有 Claude renderer 作为前置迁移验证，证明 ContextBundle 与 Runtime 无关。
4. 为 context watermark、idempotency、visibility vs eligibility 和 reproducible snapshot 增加测试。
5. 在 ContextBundle 中加入 renderer target/version、tool catalogue digest、policy version 和 content digest。

**Expected Result:**

- Pi 实现只接收一个不可变 ContextBundle，不访问 Topic history/MySQL。
- 即使 Pi 项目停止，Conversation/Context 改造也保持独立价值。

## Task 2: Freeze Runtime, Event, Interaction and Tool Contracts

**Files:**

- `dataagent/contracts/runtime/v1/runtime-request.schema.json`
- `dataagent/contracts/runtime/v1/runtime-manifest.schema.json`
- `dataagent/contracts/runtime/v1/cell-frame.schema.json`
- `dataagent/contracts/agent-events/v1/agent-event.schema.json`
- `dataagent/contracts/agent-events/v1/compatibility-cases.json`
- `dataagent/contracts/interactions/v1/*.schema.json`
- `dataagent/contracts/tools/v1/*.schema.json`
- `dataagent/dataagent-backend/core/agent_runtime/contracts.py`
- `dataagent/dataagent-runtime-pi/src/contracts/generated/`
- `dataagent/dataagent-frontend/src/views/intelligence/agentEvents/`

**Steps:**

1. 定义 AgentRunRequest、RuntimeManifest、KernelCapabilities、KernelRunResult 和 cell stdio frames。
2. 定义 AgentEvent discriminated union、sequence/state validator、size limit、redaction 和唯一 terminal 规则。
3. 定义 Interaction requested/resolved/expired/cancelled 和 run/tool/policy binding。
4. 定义 Canonical Tool ID、alias、side-effect classification、ToolCall 和 ToolResult。
5. 用同一 JSON fixtures 生成/验证 Python Pydantic、TypeScript types/validators 和 Vue reducer 输入。
6. 把现有 `sdk-block-projection` fixtures 接入 neutral compatibility projector，覆盖 text、reasoning、tool、permission、question、plan、error 和 terminal。

**Expected Result:**

- Runtime、存储和前端动工前已有语言中立、可执行的协议。
- 后续实现不需要临时 event 格式或厂商 block 穿透。

## Task 3: Extract the Generic Runtime Gateway

**Files:**

- `dataagent/dataagent-backend/sandbox_runner_main.py`
- `dataagent/dataagent-backend/runtime_gateway/__init__.py`
- `dataagent/dataagent-backend/runtime_gateway/app.py`
- `dataagent/dataagent-backend/runtime_gateway/supervisor.py`
- `dataagent/dataagent-backend/runtime_gateway/cell_protocol.py`
- `dataagent/dataagent-backend/runtime_gateway/workspace.py`
- `dataagent/dataagent-backend/tests/test_runtime_gateway_*.py`

**Steps:**

1. 将 container/warm Topic affinity/workspace lifecycle 从 Claude `TaskExecutionInput` 和 Python child entry 中抽离。
2. 实现 bidirectional framed NDJSON stdio channel，严格区分 stdout protocol 和 stderr logs。
3. 实现一 Cell 一 active run、`(run_id, task_attempt_id)` 幂等 start 和 bounded queues/backpressure。
4. 实现 resolve/steer/follow-up/cancel/shutdown command routing。
5. 保留 Docker/Podman backend 和 warm reaper，但 child image/command 来自锁定 Runtime manifest。
6. 删除 Gateway 对 TopicTaskStore/MySQL 的调用，改为只处理 Runtime request、spool、cell 和 control stream。

**Expected Result:**

- Gateway 可以用 fake Runtime child 完成 start/event/interaction/cancel/settle/restart。
- Gateway 不 import Claude SDK 或 Pi package，也不持有业务数据库凭据。

## Task 4: Implement Gateway Durable Event Spool and Security

**Files:**

- `dataagent/dataagent-backend/runtime_gateway/event_spool.py`
- `dataagent/dataagent-backend/runtime_gateway/capability.py`
- `dataagent/dataagent-backend/core/agent_runtime/security.py`
- `dataagent/dataagent-backend/tests/test_runtime_event_spool.py`
- `dataagent/dataagent-backend/tests/test_runtime_security.py`
- `deploy/docker/dataagent/`

**Steps:**

1. 实现 checksummed append-only spool、fsync-before-publish、contiguous ack 和 terminal retention。
2. 提供正常长连接 SSE、`after_sequence` replay 和诊断分页读取。
3. 实现 Control -> Gateway mTLS identity 验证。
4. 实现 per-run signed capability、replay cache、expiry、audience 和 task-attempt binding。
5. 实现短生命周期 Provider/MCP secret envelope，确保 secret 不写日志/spool/workspace。
6. 实现 Gateway restart recovery、Cell loss seal 和 control-originated terminal。

**Expected Result:**

- Cell 退出后，所有已经对 Control 可见的事件仍可重放。
- Runtime 数据面移除业务 MySQL/Redis credential 不会降低已发布事件的耐久性。

## Task 5: Create the Official Pi Runtime Package and Cell Shell

**Files:**

- `dataagent/dataagent-runtime-pi/.nvmrc`
- `dataagent/dataagent-runtime-pi/package.json`
- `dataagent/dataagent-runtime-pi/package-lock.json`
- `dataagent/dataagent-runtime-pi/tsconfig.json`
- `dataagent/dataagent-runtime-pi/src/main.ts`
- `dataagent/dataagent-runtime-pi/src/server/cell-channel.ts`
- `dataagent/dataagent-runtime-pi/src/server/run-service.ts`
- `dataagent/dataagent-runtime-pi/src/kernel/dataagent-pi-kernel.ts`
- `dataagent/dataagent-runtime-pi/src/kernel/pi-agent-factory.ts`
- `dataagent/dataagent-runtime-pi/test/`

**Steps:**

1. 建立 Node 22.19+ TypeScript package，精确安装官方 `pi-agent-core`/`pi-ai`，commit lockfile。
2. 实现 hello/manifest/version/artifact digest/readiness。
3. 实现 AgentKernel 接口和一 run 一个官方 `Agent` 的 lifecycle。
4. 注入 fake `streamFn`，完成 run、turn、tool、cancel 和 terminal 单元/E2E。
5. 将 Pi imports 限制在 `kernel/`/`providers/`，RunService 和 cell protocol 只依赖 DataAgent contract。
6. 禁止加载 `~/.pi`、coding-agent extensions/packages 和 runtime npm installs。

**Expected Result:**

- Fake Provider 下 Pi Cell 能通过完整 Runtime contract，而不依赖 Control 数据库或前端。
- Pi 版本、Node 版本和 artifact digest 在 manifest 中可审计。

## Task 6: Implement Context and Message Rendering

**Files:**

- `dataagent/dataagent-runtime-pi/src/context/pi-context-renderer.ts`
- `dataagent/dataagent-runtime-pi/src/context/message-converter.ts`
- `dataagent/dataagent-runtime-pi/src/context/token-budget.ts`
- `dataagent/dataagent-runtime-pi/test/context/`
- `dataagent/contracts/context/pi-renderer-v1/fixtures.json`

**Steps:**

1. 把 ContextBundle system instructions/messages/attachments/artifacts 转为 Pi `initialState`。
2. 实现 deterministic renderer version 和 rendered digest。
3. 实现 custom AgentMessage 到 LLM message 的 `convertToLlm`。
4. 将 `transformContext` 限制为预算校验和确定性安全转换，禁止静默丢历史。
5. 定义 `context_too_large`/`compaction_required`，记录 ContextSnapshot ID/digest。
6. 覆盖 mixed messages、tool summaries、attachments、failed partial messages、watermark 和 locale/timezone fixtures。

**Expected Result:**

- 相同 ContextBundle 在固定 renderer 版本下产生相同 Pi transcript。
- Pi transcript 可以随时从 Control authority 重建，不依赖 native session。

## Task 7: Integrate `pi-ai` Providers and Ephemeral Credentials

**Files:**

- `dataagent/dataagent-runtime-pi/src/providers/model-registry.ts`
- `dataagent/dataagent-runtime-pi/src/providers/provider-config.ts`
- `dataagent/dataagent-runtime-pi/src/providers/credential-resolver.ts`
- `dataagent/dataagent-runtime-pi/test/providers/`
- `dataagent/dataagent-backend/core/provider_runtime.py`
- `dataagent/dataagent-backend/core/skill_admin_service.py`

**Steps:**

1. 建立 DataAgent provider ID -> Pi Provider factory/model 的显式 allowlist。
2. 映射现有 Anthropic-compatible 和 OpenAI-compatible base URL、headers、model options 和 usage。
3. 从 per-run envelope 解析 credential，接入 dynamic API key/provider factory，终态清理引用。
4. 实现 Provider error、retry、rate limit、timeout、content filter、context overflow 和 cancel 到 canonical error code 的映射。
5. 为主 Provider 运行真实 streaming/tool/reasoning/usage tests。
6. 对 OpenCode Zen/Go 只按 Provider 能力验证，不宣称支持 OpenCode Agent Server 行为。

**Expected Result:**

- 只有通过 conformance 的 Provider 可以进入生产 manifest。
- Runtime 固定为 Pi，Provider/model 配置仍可按现有业务规则选择。

## Task 8: Implement Canonical Tools, Policy and Interactions

**Files:**

- `dataagent/dataagent-runtime-pi/src/tools/canonical-tool-registry.ts`
- `dataagent/dataagent-runtime-pi/src/tools/tool-aliases.ts`
- `dataagent/dataagent-runtime-pi/src/tools/tool-result-normalizer.ts`
- `dataagent/dataagent-runtime-pi/src/tools/executors/`
- `dataagent/dataagent-runtime-pi/src/policy/policy-enforcer.ts`
- `dataagent/dataagent-runtime-pi/src/policy/workspace-boundary.ts`
- `dataagent/dataagent-runtime-pi/src/interactions/interaction-broker.ts`
- `dataagent/dataagent-backend/core/permission_gate.py`
- `dataagent/dataagent-backend/core/ask_user_question.py`
- `dataagent/dataagent-runtime-pi/test/tools/`

**Steps:**

1. 建立 Canonical Tool registry 和迁移期 Pi alias：Read/LS/Glob/Grep/Bash/Skill/AskUserQuestion。
2. 实现 filesystem/process executors、result limit、Artifact 外置和统一错误。
3. 将现有 workspace boundary/permission semantics 重写为 language-neutral fixtures，再实现 TypeScript PolicyEnforcer。
4. 在 `beforeToolCall` 实现 allow/deny/require_interaction；在 `afterToolCall` 实现 redaction/audit/terminate。
5. 实现 InteractionBroker Promise、resolve/timeout/cancel/cell-loss 并发状态机。
6. 首版启用 sequential tools；只给显式 `parallel_safe` read tool 增加后续 opt-in 测试。
7. 验证 symlink、redirect、parent traversal、scratch roots、offloaded artifact 和 write confirmation。

**Expected Result:**

- Tool alias 不再承担权限/审计身份，所有策略以 Canonical ID 执行。
- Permission、Question 和 Plan 不依赖扫描 event trace，可以可靠 suspend/resume。

## Task 9: Integrate Skills and Portal MCP

**Files:**

- `dataagent/dataagent-runtime-pi/src/skills/skill-loader.ts`
- `dataagent/dataagent-runtime-pi/src/mcp/portal-mcp-client.ts`
- `dataagent/dataagent-runtime-pi/src/tools/executors/process-executor.ts`
- `dataagent/dataagent-runtime-pi/test/skills/`
- `dataagent/dataagent-runtime-pi/test/mcp/`
- `dataagent/.claude/skills/`
- `dataagent/portal-mcp/`

**Steps:**

1. 只从 ContextBundle enabled skills 和 allowlisted roots 加载 Skill。
2. 实现 `Skill` tool，把 SKILL.md 指令作为标准 tool result 注入当前 Agent Loop。
3. 在 Pi Cell image 中提供 `DATAAGENT_PYTHON_BIN` 和 `DATAAGENT_SKILL_ROOT` canonical env。
4. 通过 process executor 直接运行 Skill-local Python scripts，不在 Runtime 硬编码具体脚本名。
5. 实现 Portal MCP tool discovery/allowlist/data-scope token/canonical ID 映射。
6. 对 MCP/Skill output 做 size/redaction/artifact 处理。
7. 用真实 NL2SQL skill 跑 `最近 30 天工作流发布次数趋势`。

**Expected Result:**

- 现有 Skill bundle 不因 Runtime 切换失去单一事实来源。
- Pi Runtime 不持有业务数据库凭据，数据查询继续经过 Portal MCP/Skill contract。

## Task 10: Normalize Pi Events and Finalize Semantic Messages

**Files:**

- `dataagent/dataagent-runtime-pi/src/kernel/pi-event-normalizer.ts`
- `dataagent/dataagent-runtime-pi/src/kernel/run-state-machine.ts`
- `dataagent/dataagent-runtime-pi/src/observability/redaction.ts`
- `dataagent/dataagent-runtime-pi/test/events/`
- `dataagent/dataagent-backend/core/agent_runtime/event_ingestor.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/alembic/versions/*agent_run_event*.py`
- `dataagent/dataagent-backend/tests/test_agent_event_*.py`

**Steps:**

1. 映射 Pi agent/turn/message/tool events 到 neutral AgentEvent。
2. 为 text/reasoning content 建立稳定 IDs 和 started/delta/completed 配对。
3. 在 `turn_end` 生成 usage/turn.completed，在 awaited `agent_end` barrier 后生成唯一 terminal。
4. 实现 sequence/state/size/redaction validator 和 late callback quarantine。
5. Control 以 `(run_id, sequence)` 幂等、串行写 `da_agent_run_event`。
6. 从 `kind=answer` neutral content 投影最终 assistant ConversationMessage；failed partial 不进入成功历史。
7. 实现 bounded legacy `da_agent_sdk_record` compatibility projector，并用现有 fixtures 验证。

**Expected Result:**

- Pi/Provider native events 不进入数据库公共契约或前端。
- Event terminal、Task terminal 和 assistant message finalization 有明确原子/幂等边界。

## Task 11: Connect the Python Control Plane

**Files:**

- `dataagent/dataagent-backend/core/agent_runtime/client.py`
- `dataagent/dataagent-backend/core/agent_runtime/event_ingestor.py`
- `dataagent/dataagent-backend/core/agent_runtime/interaction_service.py`
- `dataagent/dataagent-backend/core/agent_runtime/deployment_lock.py`
- `dataagent/dataagent-backend/core/task_executor.py`
- `dataagent/dataagent-backend/core/task_coordinator.py`
- `dataagent/dataagent-backend/core/task_submission_service.py`
- `dataagent/dataagent-backend/config.py`
- `dataagent/dataagent-backend/tests/test_task_executor.py`
- `dataagent/dataagent-backend/tests/test_task_coordinator.py`

**Steps:**

1. 将 TaskCoordinator 输入改为 AgentRunRequest + ContextBundle，不再构造 Claude history/prompt。
2. RuntimePlaneClient 负责 manifest/readiness/start/SSE replay/cancel/interaction resolution。
3. TaskEventIngestor 使用 long-lived SSE，断线从 highest contiguous sequence 重连。
4. InteractionService 持久化 requested/resolved 状态并幂等转发。
5. 添加 task attempt/run identity、runtime audit metadata 和部署锁校验。
6. 移除 Runtime child 对 TopicTaskStore 的直接访问；Control 是唯一业务 MySQL writer。
7. 保持旧 Claude path 在迁移期可由旧部署 artifact 使用，但单一环境只激活一个 Runtime。

**Expected Result:**

- Python Control 只依赖中立 Runtime/Context/Event/Interaction 契约。
- 同一 task 不会因 Control/Gateway 重试而重复开始 Pi tool execution。

## Task 12: Migrate Chat and Widget to Neutral Events

**Files:**

- `dataagent/dataagent-frontend/src/views/intelligence/agentEvents/reducer.js`
- `dataagent/dataagent-frontend/src/views/intelligence/agentEvents/types.js`
- `dataagent/dataagent-frontend/src/views/intelligence/useNl2SqlChat.js`
- `dataagent/dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`
- `dataagent/dataagent-frontend/src/widget/WidgetChat.vue`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/agentEventReducer.spec.js`
- `dataagent/dataagent-frontend/src/widget/__tests__/WidgetChat.spec.js`

**Steps:**

1. 新增 AgentEventReducer，将 neutral content/tool/interaction/terminal 转为现有 view model。
2. Chat 和 Widget 只消费 reducer 输出，不识别 Anthropic/Pi provider events。
3. 实现 `after_id` 断线恢复、duplicate event、sequence gap 和 terminal UI。
4. 将 reasoning visibility、tool label、interaction cards 和 partial-failed 状态作为 UI policy。
5. 迁移 fixtures 和组件测试；兼容期旧 endpoint 仍由 projector 支持。
6. 在独立 cleanup PR 删除 `v2StreamParser` 和新任务的 `/sdk-events` 写路径。

**Expected Result:**

- 前端不再解析 Anthropic 原生 block，也不感知 Pi event。
- Chat 和 Widget 对同一 neutral stream 产生一致展示。

## Task 13: Build, Deploy and Lock the Pi Runtime Flavor

**Files:**

- `dataagent/dataagent-runtime-pi/Dockerfile`
- `dataagent/dataagent-backend/Dockerfile.sandbox-runner`
- `deploy/docker-compose.dev.yml`
- `deploy/docker-compose.prod.yml`
- `deploy/env.example`
- `.github/workflows/`
- `docs/handbook/` deployment/runtime documentation

**Steps:**

1. 构建独立 Node 22 + Python 3.11+ Pi Cell image，非 root、read-only rootfs、无 Docker socket。
2. 仅安装 official Pi core/ai exact versions，不安装 coding-agent CLI；使用 `npm ci --ignore-scripts`。
3. Gateway image继续管理 container backend/socket，但与 Cell 权限隔离。
4. 配置 `DATAAGENT_RUNTIME_KIND=pi_agent_core`、protocol versions、expected Pi version 和 digest-pinned cell image。
5. 实现部署锁和 startup/readiness fail-closed。
6. 生成 SBOM、dependency/license report、image checksum 和签名。
7. 更新 dev/prod compose、health checks、resource limits、network egress 和 volume mounts。

**Expected Result:**

- Pi Runtime 有独立可复现制品，不影响前端 Node 20 构建。
- 部署配置只能选择一个 Runtime，且运行中不能通过请求切换。

## Task 14: Run Layered Verification and Production Cutover

**Files:**

- `scripts/validate_live_nl2sql_scenarios.py`
- `tools/dataagent-evals/`
- `docs/reports/<date>-dataagent-pi-runtime-validation.md`
- `docs/handbook/` runbook/backout documentation

**Steps:**

1. 跑 contract/unit/integration/security/recovery/performance 分层测试。
2. 在本地 MySQL `127.0.0.1:3316`、Redis `127.0.0.1:6379` 完成真实 HTTP full-flow。
3. 运行发布阻断集：text、真实只读 NL2SQL、permission/question、cancel、workspace escape、Cell loss、event replay。
4. 运行主 Provider 的真实模型测试，并记录 credential 是否真实、环境、版本、模型和结果。
5. 对比 Claude baseline 的任务成功率、tool count、TTFT、总延迟、token/cost 和人工干预率。
6. 在独立环境进行 read-only canary；通过预算后按排空步骤切生产。
7. 观察期内保留上一 Claude-compatible artifacts 和明确回退命令，不启用自动 fallback。

**Expected Result:**

- 有可审计的验证报告，明确通过/跳过的层级。
- 生产环境切换后只运行 Pi Runtime，出现问题时可通过发布回退而不是请求级切换。

## Verification

### Required Commands

实际实现时至少执行并记录等价命令：

```bash
# Pi Runtime
cd dataagent/dataagent-runtime-pi
nvm use
npm ci --ignore-scripts
npm run typecheck
npm test
npm run build

# DataAgent backend
cd dataagent/dataagent-backend
.venv-py313/bin/python -m pytest \
  tests/test_agent_event_contract.py \
  tests/test_runtime_gateway.py \
  tests/test_runtime_event_spool.py \
  tests/test_task_executor.py \
  tests/test_task_coordinator.py

# DataAgent frontend
cd dataagent/dataagent-frontend
nvm use
npm test -- --run agentEventReducer WidgetChat NL2SqlChatV2
npm run build
```

Pi subproject必须使用自己的 Node 22 `.nvmrc`；frontend commands 必须使用仓库规定的 Node 20 基线。

### Release-Blocking Real Scenarios

| Scenario | Required before Pi production |
| --- | --- |
| Minimal text stream `smoke-ok` | Yes |
| Real read-only NL2SQL Skill + Portal MCP | Yes |
| Tool success/error/invalid args | Yes |
| Permission allow/deny and AskUserQuestion | Yes |
| Cancel during Provider stream and tool | Yes |
| Context overflow deterministic failure | Yes |
| Workspace/symlink/shell escape denied | Yes |
| Gateway reconnect and spool replay | Yes |
| Cell killed mid-run | Yes |
| Final assistant message history | Yes |
| Secondary Provider matrix | Periodic unless deployed |
| Steering/follow-up stress | Periodic after base contract passes |

### Performance Gates

- P95 first-content overhead relative to Claude baseline `<100 ms`.
- P99 per-event transport/persistence overhead `<250 ms`.
- Event batching never waits more than 20 ms solely for batch size.
- Long output/tool progress respects bounded memory and applies backpressure.
- Runtime Cell cold/warm startup and memory limits are recorded before production sizing.

## Rollout / Backout

### Stage A: Contracts Only

- Rollout: additive schemas, ContextBundle, AgentEvent fixtures and compatibility projector.
- Backout: stop new writers/use old Claude path; keep additive tables and rows.

### Stage B: Gateway and Pi in Non-Production

- Rollout: deploy fake/read-only Pi cell in isolated environment; production stays Claude.
- Backout: disable Pi environment and remove non-production cells; no production message migration involved.

### Stage C: Production Pi Cutover

- Rollout: stop intake, drain Claude runs, deploy digest-pinned Pi artifacts, update deployment lock, run smoke, reopen intake.
- Backout: stop intake, drain/cancel Pi runs, restore previous Control/Gateway/Claude artifacts and deployment lock, run Claude smoke, reopen intake.

### Data and Side-Effect Rules

- Never delete neutral events, ContextSnapshots or completed messages during backout.
- Never convert an active Pi run into a Claude native session.
- Retried work gets a new run/attempt ID and links the previous failure.
- A run that may have completed a write tool is never automatically retried; use business compensation or explicit operator action.
- Runtime schema changes remain additive until the previous production artifact is outside the rollback window.

