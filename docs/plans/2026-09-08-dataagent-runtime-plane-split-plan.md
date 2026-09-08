# DataAgent 控制面/数据面拆分与 Pi Agent 运行时接入计划

配套设计：`docs/design/2026-09-08-dataagent-runtime-plane-split-design.md`

## 受影响栈

- DataAgent backend：`core/task_executor.py`、`core/agent_runtime.py`、
  `core/pi_event_writer.py`（新增）、`core/boundary_policy.py`（新增）、`config.py`。
- DataAgent 运行时数据面：`dataagent/dataagent-runtime-pi/`（Node 22 + TypeScript）。
- 契约：`dataagent/contracts/`（agent events、cell frames、boundary policy 与一致性夹具）。
- DataAgent 前端：`dataagent-frontend/src/views/intelligence/`（reducer 与两处分流点）。
- 部署：`deploy/.env.example`、`deploy/docker-compose.*.yml`、
  `.github/workflows/docker-build.yml`。

## 任务

### 阶段 0：解除阻塞（必须先于一切）

1. 删除 `dataagent/dataagent-backend/core/agent_runtime/` 整个目录
   （遮蔽既有 `core/agent_runtime.py`）。
2. 删除 `dataagent/dataagent-backend/runtime_gateway/` 整个目录。
3. 删除依附于上述两者的测试：`tests/test_runtime_gateway_*.py`、
   `tests/test_runtime_event_spool.py`、`tests/test_runtime_security.py`、
   `tests/test_runtime_e2e_gateway_and_client.py`、`tests/test_runtime_control_plane.py`、
   `tests/test_agent_runtime_contracts.py`。
4. 验证：`pytest tests/test_agent_runtime.py` 回到 56 passed；
   `python -c "import sandbox_runner_main"` 成功。

### 阶段 1：控制面分区与派发

5. `config.py`：新增 `dataagent_runtime_kind: str = "claude_code"`。
6. `core/agent_runtime.py`：原地按注释分区为「中立控制面 / SDK 适配 / Pi 适配」，
   **对外导出符号保持不变**（`core/task_executor.py:34` 的 23 个 import 不动）。
7. `core/task_executor.py`：
   - `execute_task_stream` 的外层 if 链保持不变（sandbox 维度优先）。
   - 在 `_execute_task_stream_local` 中，共享准备（provider/model、cancel 闭包、
     prompt、skill runtime、system prompt）之后、
     `from claude_agent_sdk import ...`（现 927 行）之前插入 runtime_kind 分叉，
     命中 `pi_agent_core` 时转入 `_execute_task_stream_via_pi_runtime`。
   - sandbox runner 内部（`sandbox_task_main.py`）做同样分叉，保证隔离拓扑与
     引擎选择正交。

### 阶段 2：边界策略规格与一致性夹具

8. 新增 `core/boundary_policy.py`：`build_boundary_policy(project_cwd, skill_runtime,
   scratch_dirs, tool_result_root) -> dict`，从既有常量
   （`_BASH_READONLY_COMMANDS`、`WRITE_TOOL_NAMES`、`_build_workspace_allowed_roots`）
   导出，**不手写重复清单**。
9. 新增 `dataagent/contracts/boundary/v1/boundary-policy.schema.json`。
10. 新增 `dataagent/contracts/boundary/v1/conformance-cases.json`：
    从 `tests/test_agent_runtime.py` 现有 22 个 boundary 用例导出为语言中立用例表。
11. Python 侧一致性测试 `tests/test_boundary_conformance.py`：读夹具，
    对 `_validate_bash_workspace_boundary` / `_validate_workspace_tool_boundary` 全表断言。
12. TS 侧 `src/policy/workspace-boundary-enforcer.ts` + `test/boundary_conformance.test.ts`：
    读**同一份**夹具，全表断言。

### 阶段 3：Pi Cell 修复与收敛

13. 修 `src/main.ts`：注入 `streamFn`（经 `ModelRegistry` / `ProviderConfig` /
    `CredentialResolver`）与 `CanonicalToolRegistry`，
    当前 `new DataAgentPiKernel()` 无 `streamFn`，任何 run 都会立即抛错。
14. 帧契约收敛为 `cell.init` / `run.event` / `run.cancel` / `cell.shutdown`，
    Python 与 Node 两侧使用同一份 JSON Schema；
    删除 PR #450 中 `payload={"request": ...}` 与 `payload.get("event")` 的不对称。
15. 事件生成统一走 `PiEventNormalizer`，删除 `dataagent-pi-kernel.ts` 内联的重复实现
    （内联版发出的 `"turn_start"` 不是合法事件类型，会被 Python 侧拒绝）。
16. 接入 `limits`：总超时 / 空闲超时 / max_turns / max_tool_calls。
17. `ProcessExecutor` 改为白名单式构造 env，不再透传 `process.env`
    （当前会把 provider key、DB 口令暴露给模型驱动的 shell）。
18. 删除里程碑 1 用不到的模块：`portal-mcp-client.ts`（返回伪造成功的桩）、
    `interaction-broker.ts`（里程碑 2 再引入）。
19. `Dockerfile`：把 `ENV NODE_ENV=production` 移到 `npm run build` 之后
    （npm 7+ 在 `NODE_ENV=production` 时 `npm ci` 会 omit devDependencies，
    导致 `tsc` 缺失、构建失败）。

### 阶段 4：适配器与持久化

20. 新增 `core/pi_event_writer.py`：与 `SdkBlockWriter` 平级，
    `ingest(event: dict)` 写 `record_type="pi_event"`、`event_type=event["type"]`，
    共用 `topic_task_store.append_sdk_record`。
21. 在 `core/agent_runtime.py` 的 Pi 适配区实现
    `_execute_task_stream_via_pi_runtime`：拉起子进程、发 `cell.init`、
    读 stdout NDJSON 事件泵、写 `PiEventWriter`、轮询 `is_cancel_requested`、
    组装 `TaskExecutionResult`（`session_id = f"pi-{topic_id}-{task_id}"`）。
22. 子进程收尾必须 `kill()`；非 JSON 行与空行跳过而非拆通道。

### 阶段 5：前端

23. `agentEvents/reducer.js`：block 字段对齐 v2 形状
    （`output` / `is_error` / `inputJson`，而非 `result` / `error`），
    否则 `blockToToolProp` 读不到值，Pi 工具调用会静默渲染成 pending。
24. `useNl2SqlChat.js:301` 与 `widget/WidgetChat.vue`：各加一次 `record_type` 分流。
25. 补 `RUN_SUSPENDED` 分支（当前缺失，suspended 会永久停在 `streaming`）。

### 阶段 6：部署

26. `deploy/.env.example`：`DATAAGENT_RUNTIME_KIND=claude_code`（缺省不改行为），
    移除 PR #450 引入的 `DATAAGENT_GATEWAY_URL`（无 Gateway）。
27. `deploy/docker-compose.dev.yml`：给 `dataagent-backend` 与
    `dataagent-sandbox-runner` 挂载 Pi 运行时产物或改用含 Node 的镜像。
28. `.github/workflows/docker-build.yml`：把 `dataagent-runtime-pi` 加入构建矩阵
    （PR #450 的 Dockerfile 不在矩阵内，从未被验证）。

## 验证通过标准

1. `pytest tests/test_agent_runtime.py` = 56 passed（与 `origin/main` 一致）。
2. `python -c "import sandbox_runner_main; import core.task_executor"` 成功。
3. `pytest tests/test_boundary_conformance.py` 全表通过。
4. `cd dataagent/dataagent-runtime-pi && npm run typecheck && npm test && npm run build` 通过，
   其中包含 `test/boundary_conformance.test.ts` 与一条真正拉起子进程的
   Python↔Node 契约测试。
5. `cd dataagent/dataagent-frontend && nvm use && npx vitest run` 通过。
6. 本地端到端 smoke：`DATAAGENT_RUNTIME_KIND=pi_agent_core`，
   MySQL `127.0.0.1:3316`、Redis `127.0.0.1:6379`、`.venv-py313`，
   提交 `你好，请直接回复 smoke-ok。` 与 `最近 30 天工作流发布次数趋势` 两条，
   验证任务创建 → 事件写入 `da_agent_sdk_record` → 终态落库 → 前端渲染。
7. 未跑到的层次必须在验证记录中显式写明。

## 回退

- 运行时回退：`DATAAGENT_RUNTIME_KIND=claude_code`，链路完全回到现状。
- 代码回退：阶段 1 之后的所有改动都在 `_execute_task_stream_local` 的分叉之下，
  revert 该分叉即可；`core/agent_runtime.py` 的分区是纯注释与顺序调整，
  对外导出未变，不影响调用方。
- 无 schema 变更、无数据迁移；历史 `pi_event` 记录对 SDK 路径读取无影响。

## 验证记录（2026-09-08）

### 环境

- Python：临时 venv（3.11.15），按 `dataagent-backend/requirements.txt` 安装。
  仓库默认的 `.venv-py313` 在本容器不存在。
- Node：系统 `/opt/node22/bin/node` v22.22.2。**本容器无 nvm**，`.nvmrc` 指定
  22.19.0，实际使用 22.22.2（同一 major）。
- 容器运行时：`docker` CLI 存在但无 daemon，`podman` 不存在。
- MySQL / Redis：均不可用。

### 已执行并通过

| 层次 | 命令 | 结果 |
|---|---|---|
| Python 回归基线 | `pytest tests/` on `origin/main` | 435 passed |
| Python 全量 | `pytest tests/` 本分支 | **508 passed**，零回归 |
| 边界一致性（Python） | `pytest tests/test_boundary_conformance.py` | 43 passed（40 用例 + 3 策略断言）|
| 边界一致性（TS） | `node --test dist/test/boundary_conformance.test.js` | **41 passed，同一份夹具** |
| 投影契约（Python） | `pytest tests/test_sdk_block_projection_contract.py` | 2 passed（20 用例，含 6 条 Pi）|
| 跨进程协议 | `pytest tests/test_pi_runtime_contract.py` | 11 passed（真实子进程）|
| 端到端 Python↔Node | `pytest tests/test_pi_runtime_e2e.py` | **3 passed（真实构建的 Node Cell）** |
| Node 全量 | `npm run typecheck && npx tsc && node --test dist/test/*.test.js` | 0 错误，54 passed |
| 前端全量 | `npx vitest run` | 42 files，**428 passed** |
| npm NODE_ENV 行为 | 实测 `NODE_ENV=production npm ci` | 确认不安装 devDependencies，验证 Dockerfile 修复必要性 |

变异检验（确认一致性夹具能失败而非空转）：

- 把 discard sink 从精确匹配改为前缀匹配 → 1 条失败（`/dev/null/passwd` 会被放行）
- 去掉 bash 赋值语句里的路径提取 → 6 条失败（`dd if=`、`PYTHONPATH=`、变量间接引用等）

### 未执行 —— 必须在合入前补齐

1. **本地端到端 smoke（AGENTS.md 要求的最低验证）未执行。**
   本容器无 MySQL、无 Redis、无容器运行时，无法按
   「Intelligent Query local smoke method」启动依赖。因此以下路径**未经真实验证**：
   - `DATAAGENT_RUNTIME_KIND=pi_agent_core` 下的真实 NL2SQL 请求
   - 任务创建 → 事件写入 `da_agent_sdk_record` → 终态落库 → 前端渲染的完整链路
   - 真实 provider 凭据下的模型调用（`stream-fn-resolver` 只经过类型检查与
     单元测试，从未对真实 Anthropic/OpenAI 端点发过请求）
2. **所有 Docker 改动未经构建验证。** 无 daemon 可用。涉及：
   - `dataagent-runtime-pi/Dockerfile`
   - `dataagent-backend/Dockerfile` 与 `Dockerfile.runner` 新增的 Pi Cell 构建阶段
   - 特别是 `COPY --from=node:22-bookworm-slim /usr/local/bin/node`——该模式与
     仓库既有的 `COPY --from=docker:27-cli` 同源，且两侧均为 debian bookworm，
     但**未实际构建过**
3. **里程碑 2（确认/暂停回路）未实现**，按设计属于独立范围。当前 Pi 数据面
   不提供写确认；`policy.require_write_confirmation` 在该引擎下无效。
4. compose 改动仅通过 YAML 语法校验，未 `docker compose up` 验证。
