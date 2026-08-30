# DataAgent Runtime Plane and Single-Active Adapter Implementation Plan

**Date:** 2026-08-30
**Design:** [2026-08-30-dataagent-runtime-plane-adapter-design.md](../design/2026-08-30-dataagent-runtime-plane-adapter-design.md)
**Goal:** 在业务与 OpenCode 契约通过硬性 Gate 后，按协议、安全、Claude 基线、Neutral Event、OpenCode 和部署顺序交付单激活 Runtime Plane，任何 Gate 失败都停止后续代码工作。
**Tech Stack:** Python 3.11+、FastAPI、AnyIO、MySQL 8、Redis、Docker、Claude Agent SDK、OpenCode Server、HTTP/SSE、Vue 3/Vitest/Pytest

## Preconditions

- `DataAgent Conversation and Context Model` 已合入，`ContextBundle` v1 冻结。
- 当前 Claude 基线 smoke、SDK block golden fixtures 和测试结果有可比较记录。
- Gate 0 有命名业务 Owner、目标环境和预期收益。
- Gate 1 使用固定 OpenCode release 完成真实 Server spike。

Preconditions/Gates 未满足时，只允许修改 spike/report/contract fixture，不允许进入 Task 3 以后。

## Task 0: Record Business Go / No-Go

### Files

- `docs/reports/<date>-dataagent-runtime-plane-business-gate.md`
- `docs/design/2026-08-30-dataagent-runtime-plane-adapter-design.md`

### Steps

1. 记录 target environment、business owner、primary driver、expected benefit、deadline 和 production need。
2. 复核 13–25 engineer-weeks 粗估与持续双 Runtime 回归成本。
3. 明确 `GO | NO-GO-business` 并由 Owner 确认。
4. NO-GO 时停止本计划；Conversation/Context 继续，Runtime topology 不动。

### Verification

- Gate report 字段完整，有 Owner 和日期。

### Backout

- 无代码/Schema 变更，关闭提案即可。

## Task 1: Pin and Validate the OpenCode Contract

### Files

- `dataagent/dataagent-backend/tests/runtime_contract/fixtures/opencode/<version>/`
- `dataagent/dataagent-backend/tests/runtime_contract/test_opencode_server_contract.py`
- `docs/reports/<date>-opencode-runtime-contract-gate.md`

### Steps

1. 固定 release、binary checksum、license 和下载来源。
2. 保存经过脱敏的完整 OpenAPI/schema、SSE fixtures 和 version output。
3. 使用 API 发起的 run 验证 session create/get/prompt_async/abort/resume。
4. 验证 remote MCP、Skills、Provider credential 注入/清理和磁盘残留。
5. 验证 permission/question reply/reject，必须携带原始 workspace/directory routing 并观察 reply event。
6. 验证 plan approval/exit -> build continuation。
7. Kill/restart server，记录 pending interaction 和 Session 恢复语义。
8. 对照官方已知 permission/question issues，确认 pinned version 不受影响或有可验证修复。
9. 输出 `GO | NO-GO-contract`。

### Verification

- 真实 `opencode serve`，不以 mock 代替 mandatory interactions。
- 同一 fixture 重跑稳定。

### Backout

- NO-GO 时保留报告/fixtures，停止 Task 2+；不构建 Runtime Plane。

## Task 2: Freeze Runtime, Event and Compatibility Contracts

### Files

- `dataagent/contracts/runtime/v1/runtime-protocol.schema.json`
- `dataagent/contracts/runtime/v1/manifest.schema.json`
- `dataagent/contracts/runtime/v1/security-envelope.schema.json`
- `dataagent/contracts/agent-events/v1/agent-event.schema.json`
- `dataagent/contracts/agent-events/v1/agent-event-record.schema.json`
- `dataagent/contracts/agent-events/v1/compatibility-cases.json`
- `dataagent/dataagent-backend/core/agent_events/models.py`
- `dataagent/dataagent-backend/core/agent_events/validator.py`
- `dataagent/dataagent-backend/tests/test_agent_event_contract.py`

### Steps

1. 冻结 `AgentRunRequest/Result`、manifest、capabilities、Interaction 和错误码。
2. 冻结 AgentEvent envelope 和完整 union，包含显式 `turn.completed`。
3. 实现 sequence/state validator、terminal seal、control-originated cell-loss terminal。
4. 定义字段大小、redaction、Artifact ref 和 runtime/binary/protocol error distinction。
5. 构建 AgentEvent -> legacy SDK record -> existing Python/Vue block 的 compatibility fixtures。
6. 锁定 Runtime spool `after_sequence` 与 task-scoped DB `after_id` 语义。

### Verification

- JSON Schema/Pydantic fixtures。
- Illegal order, open turn, duplicate terminal, post-seal event tests。
- Existing SDK projection backend/frontend contract tests。

### Backout

- 契约尚未接线，直接 revert；不可用临时事件格式继续 Task 3。

## Task 3: Implement Security Channel and Fake Runtime Shell

### Files

- `dataagent/dataagent-backend/core/agent_runtime.py`（delete）
- `dataagent/dataagent-backend/core/agent_runtime/__init__.py`
- `dataagent/dataagent-backend/core/agent_runtime/contracts.py`
- `dataagent/dataagent-backend/core/agent_runtime/security.py`
- `dataagent/dataagent-backend/core/agent_runtime/client.py`
- `dataagent/dataagent-backend/runtime_plane/app.py`
- `dataagent/dataagent-backend/runtime_plane/api.py`
- `dataagent/dataagent-backend/runtime_plane/security.py`
- `dataagent/dataagent-backend/runtime_plane/manifest.py`
- `dataagent/dataagent-backend/runtime_plane/supervisor.py`
- `dataagent/dataagent-backend/runtime_plane/event_spool.py`
- `dataagent/dataagent-backend/runtime_plane_main.py`
- `dataagent/dataagent-backend/tests/test_runtime_security.py`
- `dataagent/dataagent-backend/tests/test_runtime_plane_api.py`

### Steps

1. 用 package 替换旧单文件，不能同名共存。
2. 实现 Control/Gateway mTLS identity/SAN/rotation/readiness。
3. 实现 Ed25519 run capability、scope/audience/attempt/jti/replay cache。
4. 实现 X25519/HPKE execution secret envelope、60s acceptance TTL 和 memory-only lifecycle。
5. 实现 fake adapter 的 start/status/cancel/interaction/event stream。
6. 实现 Gateway-managed run spool：append/checksum/fsync/replay/ack/quota/retention。
7. 正常事件 API 使用长连接 SSE；paged API 只作恢复/诊断。
8. Runtime services 无 DataAgent business MySQL env/credential。

### Verification

- Wrong/expired/revoked identity and replay tests。
- Secret leakage scan over logs/spool/workspace/snapshot fixtures。
- Fake run disconnect/replay/cancel/interaction tests。
- Cell and Gateway process kill tests。

### Backout

- 尚未接生产执行；停 Runtime services，删除未使用 dev cert/spool，Control 保持 legacy path。

## Task 4: Extract Claude Adapter on Frozen AgentEvent

### Files

- `dataagent/dataagent-backend/core/task_executor.py`
- `dataagent/dataagent-backend/runtime_plane/adapters/claude_agent_sdk.py`
- `dataagent/dataagent-backend/runtime_plane/adapters/claude_event_normalizer.py`
- `dataagent/dataagent-backend/runtime_plane/policy_enforcer.py`
- `dataagent/dataagent-backend/runtime_plane/interaction_waiter.py`
- `dataagent/dataagent-backend/tests/runtime_contract/test_claude_adapter.py`

### Steps

1. 移动 Claude import/options/query/session consumption 到 adapter。
2. 接收已冻结 ContextBundle，不重新查询 History。
3. Native event 直接映射已冻结 AgentEvent；禁止临时 journal payload。
4. 移动 MCP config、partial fallback、usage/error/session result mapping。
5. 移动 can_use_tool/workspace hook 并执行 signed policy snapshot。
6. InteractionWaiter 支持 permission/question/plan/cancel/timeout。
7. 通过 Runtime Protocol 运行 Claude，但 feature flag 默认 legacy。

### Verification

- Same Claude model/prompt baseline output/state/usage/session comparisons。
- Adapter conformance fixtures and real Claude smoke。
- Workspace escape and policy tamper fail closed。

### Backout

- Drain/cancel active Runtime runs，feature flag 切 legacy，停止 Runtime services。
- Runtime spool/event data保留用于审计。

## Task 5: Neutral Event Persistence, Compatibility and API

### Files

- `dataagent/dataagent-backend/core/agent_runtime/event_ingestor.py`
- `dataagent/dataagent-backend/core/agent_events/compatibility_projector.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_agent_run_events.py`
- `dataagent/dataagent-backend/tests/test_agent_event_ingestor.py`
- `dataagent/dataagent-backend/tests/test_agent_event_compatibility.py`

### Steps

1. 创建 `da_agent_run_event`，包含 `(run_id,sequence)`、task cursor 和归档索引。
2. 保证每个 Task 只有一个串行 TaskEventIngestor；禁止同 Task 并发 attempt。
3. 长连接 ingest，commit 后 ack contiguous runtime sequence。
4. 实现 task-scoped `/events` 和 `/events/stream`；V1 不提供 global/topic `after_id`。
5. Control 生成 cell-loss terminal 并 seal run。
6. Compatibility projector 产生旧 SDK records，并通过已有 Python/Vue fixture。
7. 新路径逐步停止 Runtime Cell 直写业务 MySQL。
8. Archive job 使用时间/topic 索引，只处理已终态和已 ack runs。

### Verification

- Sequence gap/reconnect/duplicate/transaction rollback tests。
- AUTO_INCREMENT concurrent-other-task visibility test，证明 task cursor 不跳本 Task record。
- Archive SQL index/retention test。
- Cell loss before/during/after event tests。

### Backout

- Stop submissions/drain runs，API/front-end 保持旧 SDK path，兼容 writer 恢复主写。
- Neutral rows/spool 保留，不做 destructive downgrade。

## Task 6: Frontend Neutral Event Reducer

### Files

- `dataagent/dataagent-frontend/src/contracts/agentEvents.v1.ts`
- `dataagent/dataagent-frontend/src/views/intelligence/agentEventReducer.js`
- `dataagent/dataagent-frontend/src/views/intelligence/useNl2SqlChat.js`
- `dataagent/dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`
- `dataagent/dataagent-frontend/src/widget/WidgetChat.vue`
- `dataagent/dataagent-frontend/src/api/nl2sql.js`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/agentEventProjection.contract.spec.js`

### Steps

1. TypeScript union 与 canonical schema 同 fixture。
2. Pure reducer 按 content/tool/interaction ID 投影，不依赖 vendor block index。
3. Transport 处理 DB cursor、重复和 gap；reducer 只收 validated events。
4. Chat/Widget 切 task-scoped Neutral SSE。
5. 旧 task/history 继续 `v2StreamParser` compatibility。
6. Instrument first-content/event latency。

### Verification

- Python/Chat/Widget fixtures converge。
- Reconnect/duplicate/gap/turn.completed/interaction/cell-loss UI tests。
- `nvm use` 后 targeted Vitest 和 frontend build。
- P95/P99 增量延迟 gate。

### Backout

- Frontend/API feature flag 回旧 SDK stream；不删除 Neutral events。

## Task 7: Runtime Session, Tool Identity and Interaction Store Integration

### Files

- `dataagent/dataagent-backend/core/agent_runtime/session_store.py`
- `dataagent/dataagent-backend/core/agent_runtime/tool_identity.py`
- `dataagent/dataagent-backend/core/agent_runtime/tool_policy.py`
- `dataagent/dataagent-backend/core/agent_runtime/interaction_service.py`
- `dataagent/dataagent-backend/runtime_plane/session_manager.py`
- `dataagent/dataagent-backend/alembic/versions/<revision>_runtime_session_tools_lock.py`
- `dataagent/dataagent-backend/tests/test_runtime_session_store.py`
- `dataagent/dataagent-backend/tests/test_runtime_interaction_service.py`

### Steps

1. Runtime Session 绑定 kind/context watermark/fingerprints/state。
2. Backfill 有效 Claude `chat_conversation_id`，占位 ID 忽略。
3. Canonical Tool IDs 和 Claude/OpenCode mappings。
4. Agent Profile allowed_tools additive migration。
5. Control Interaction Store 与 Runtime resolve 协议接线。
6. Cell loss cancels interaction；reply 验证 observed resolved event。

### Verification

- Runtime mismatch/session lost/rebuild tests。
- Unknown tool/policy mismatch/workspace escape tests。
- Permission/question/plan duplicate/timeout/cell-loss tests。

### Backout

- Session reads回旧 Claude field，tools回 compatibility mapping，interaction回旧 SDK records。
- 新 rows/columns保留，禁止跨 Runtime ID reuse。

## Task 8: Runtime Images, Deployment Lock and Configuration

### Files

- `dataagent/dataagent-backend/requirements-control-plane.txt`
- `dataagent/dataagent-backend/requirements-runtime-common.txt`
- `dataagent/dataagent-backend/requirements-runtime-claude.txt`
- `dataagent/dataagent-backend/requirements-runtime-opencode.txt`
- `dataagent/dataagent-backend/Dockerfile`
- `dataagent/dataagent-backend/Dockerfile.runner`
- `dataagent/dataagent-backend/runtime-manifest.json`
- `deploy/docker-compose.dev.yml`
- `deploy/docker-compose.prod.yml`
- `deploy/.env.example`
- `dataagent/dataagent-backend/tests/test_dataagent_requirements.py`

### Steps

1. Control image vendor-free；Runtime common/Claude/OpenCode separate targets。
2. OpenCode binary pinned/checksummed；production image不包含另一 Runtime。
3. Manifest contains binary/protocol/event/build/tool/security key data。
4. Authoritative env includes `DATAAGENT_AGENT_RUNTIME` and `OPENDATAWORKS_DATAAGENT_RUNTIME_IMAGE`。
5. Control/Gateway/Cell/image/DB lock handshake fail closed before Coordinator。
6. Runtime services remove business MySQL credentials。
7. SBOM/license/vulnerability scan。

### Verification

- Flavor/env/image mismatch cannot become ready。
- Same Runtime restart succeeds；different Runtime blocked。
- Production image contents/credentials assertions。

### Backout

- Stop Coordinator/drain tasks，deploy legacy combined Claude image，remove Runtime services。
- Keep deployment-lock row；legacy code ignores additive table。
- Rotate credentials if Runtime secret handling is suspect。

## Task 9: Implement OpenCode Process Manager and Adapter

### Files

- `dataagent/dataagent-backend/runtime_plane/transports/opencode_server.py`
- `dataagent/dataagent-backend/runtime_plane/adapters/opencode.py`
- `dataagent/dataagent-backend/runtime_plane/adapters/opencode_event_normalizer.py`
- `dataagent/dataagent-backend/tests/runtime_contract/test_opencode_adapter.py`
- `dataagent/dataagent-backend/tests/runtime_contract/test_opencode_process_manager.py`

### Steps

1. Spawn loopback random port, 256-bit Basic Auth, no CORS, supervised lifecycle。
2. Password exists only child env + in-memory supervisor handle；rotate on restart。
3. Implement session/prompt_async/SSE/abort against pinned fixtures。
4. Implement neutral events and canonical tools。
5. Implement MCP/Skills/Provider injection with tmpfs/per-credential lifetime and disk residue check。
6. Permission/question/plan replies include original workspace/directory and require observed resolution event。
7. Warm reuse allowed only when credential/config identity and cleanup invariants match。
8. Cell reaper closes OpenCode server and scrubs tmpfs secrets。

### Verification

- Task 1 real contract suite。
- Credential leakage/warm reuse tests。
- Interaction routing/restart/stale reply tests。
- Real OpenCode provider smoke。

### Backout

- OpenCode remains separate canary environment；stop environment and preserve neutral data。
- Do not hot-change lock to Claude or reuse Session IDs。

## Task 10: Recovery, Performance and Operations

### Files

- `dataagent/dataagent-backend/core/runtime_metrics.py`
- `dataagent/dataagent-backend/core/logging_config.py`
- `dataagent/dataagent-backend/scripts/validate_live_nl2sql_scenarios.py`
- `docs/handbook/` Runtime operations/runbooks
- `docs/reports/<date>-runtime-plane-recovery-performance.md`

### Steps

1. Metrics: first-content P50/P95/P99, event lag, spool bytes/age, ack lag, sequence gaps, Cell loss, secret/key rotation, interaction wait。
2. Spool quota admission: warn/high watermark/reject-new-runs thresholds。
3. Kill Cell/Gateway/host simulations and corrupt spool fixture。
4. Implement release-blocking/nightly suite CLI and machine-readable report。
5. Document Task2-before/neutral-event/split-topology/OpenCode backouts separately。
6. Verify Runtime services without business DB credentials。

### Verification

- P95 +100ms/P99 +250ms budgets。
- Cell loss and Gateway restart matrix。
- Runbooks executed in staging, not only reviewed。

### Backout

- Any recovery/security/latency gate failure blocks topology release and returns to previous phase feature flag/image set。

## Task 11: Claude Split-Topology Release

### Steps

1. Release Control + Claude Runtime flavor first。
2. Run release-blocking real E2E <= 30 minutes and selected nightly suite。
3. Verify old Topic/SDK events, neutral new tasks, resume/cancel/interaction/cell-loss。
4. Observe one release window before OpenCode canary。

### Backout

- Execute Phase 3 topology backout；do not describe as a single image revert。

## Task 12: OpenCode Canary and Production Decision

### Steps

1. Separate environment/database lock，low concurrency/read-only first。
2. Run release-blocking suite with real OpenCode/provider。
3. Run plan/MCP/Skills/nightly matrix and capacity test。
4. Confirm no credential disk residue and no stuck pending interactions。
5. Only then mark OpenCode production-ready。

### Backout

- Stop canary, preserve neutral data, rebuild/migrate offline if needed；never hot-switch Runtime。

## Verification Matrix

| Scenario | Contract/Unit | Release-blocking real E2E | Nightly/Periodic |
| --- | --- | --- | --- |
| Runtime/Event schema and manifest | Required | Required | Required |
| mTLS/capability/secret envelope | Required | Required | Required |
| Text stream/terminal | Required | Required both runtimes | Required |
| Session resume | Required | Required both runtimes | Required |
| Cancel | Required | Required both runtimes | Required |
| Permission allow/deny | Required | Required both runtimes | Required |
| Question | Required | Required both runtimes | Required |
| Workspace escape | Required | Required both runtimes | Required |
| Cell loss/spool replay | Required | Required both runtimes | Required |
| Plan approval -> build | Required | Selected canary | Required |
| Full Portal MCP/Skills | Required | Selected canary | Required |
| Gateway restart/spool quota | Required | Selected staging | Required |
| Model/provider matrix | Required | Selected release model | Required |
| Concurrency/capacity | Required | No | Required |

## Completion Criteria

- Gate 0/1 reports are GO and attached to the implementation series.
- Control Plane contains no vendor SDK and Runtime Plane contains no business MySQL credential.
- Claude/OpenCode implement the same frozen Runtime/Event/Interaction conformance suite.
- Cell loss, Gateway restart and spool corruption have deterministic terminal/recovery behavior.
- Streaming latency stays within release budget.
- Release-blocking real E2E passes for the flavor being released; periodic matrix has an owner and automated artifact.
- Every topology phase has an executed, documented backout path.
