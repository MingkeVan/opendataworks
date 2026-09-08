# DataAgent Pi 官方 Agent 内核 Task 0 Spike 验证报告

**日期:** 2026-09-08  
**评估对象:** `@earendil-works/pi-agent-core@0.85.1`, `@earendil-works/pi-ai@0.85.1`  
**测试套件:** `tools/dataagent-evals/pi-kernel-spike`  
**决策结论:** **GO** (通过全项核心契约验证，无需 fork 或 patch 上游代码)

---

## 1. 测试环境与基线配置

- **操作系统:** macOS Darwin 24.6.0 (arm64)
- **Node.js 运行时:** Node.js `v22.19.0` (npm `v10.9.3`)，通过 nvm 安装并独立隔离
- **容器环境:** Podman `5.6.0` (满足无 Docker socket 最小权限隔离要求)
- **Python 运行时:** Python 3.13 (`dataagent/dataagent-backend/.venv-py313`)
- **锁定版本:**
  - `@earendil-works/pi-agent-core`: `0.85.1` (license: MIT, unpacked size: 3.6MB)
  - `@earendil-works/pi-ai`: `0.85.1` (license: MIT)

---

## 2. 核心契约验证矩阵与结果

验证套件共包含 5 组测试文件、10 项细分测试，测试执行结果 **100% PASS**（耗时 572ms）。

| 验证领域 | 核心测试点 | 测试用例 | 结果 | 关键发现与契约结论 |
| :--- | :--- | :--- | :---: | :--- |
| **1. 流式与事件屏障** | `Agent.subscribe` 订阅机制与事件顺序 | `stream_events.test.js` | **PASS** | 完整按序发出 `agent_start` -> `turn_start` -> `message_start` -> `message_update` -> `message_end` -> `turn_end` -> `agent_end`。`agent_end` 的异步 subscriber 会被完整 `await`，具备作为事件落盘和持久化 flush barrier 的绝对可靠性。 |
| **2. 顺序工具调度** | 多工具调用严格串行执行 | `tools_execution.test.js` | **PASS** | 设置 `toolExecution: "sequential"` 后，模型单 turn 返回多个 tool call 时，工具 1 彻底执行并落库后，工具 2 才会开始；每个工具独立生成一条 `toolResult` 消息。 |
| **3. 工具流式进度** | 工具执行中进度推送 | `tools_execution.test.js` | **PASS** | 工具内部通过 `onUpdate(partialResult)` 回调成功触发 `tool_execution_update` 事件，可直接向前端推送长任务进度。 |
| **4. 交互式异步挂起与恢复** | `beforeToolCall` 权限确认与人工接入 | `interaction_hooks.test.js` | **PASS** | `beforeToolCall` 返回挂起的 Promise 时，Agent Loop 自动挂起等待；外部传入 resolve 信号后无缝恢复执行并调度工具，无需重置或重新 prompt。 |
| **5. 策略阻断与终止** | `beforeToolCall` 权限拒绝 | `interaction_hooks.test.js` | **PASS** | 返回 `{ block: true, reason: "...", terminate: true }` 时，工具坚决不执行，转录本记录包含拒绝原因的 error toolResult，Agent 立即优雅终态。 |
| **6. 结果脱敏与重写** | `afterToolCall` 敏感数据处理 | `interaction_hooks.test.js` | **PASS** | `afterToolCall` 可精准覆盖 `content`、`details`，有效阻断敏感凭据流入历史转录本或模型上下文。 |
| **7. 取消信号全链路响应** | `agent.abort()` 流式取消 | `cancellation_abort.test.js` | **PASS** | 流式过程中调用 `abort()`，AbortSignal 立即触发，stream 终止，正常结算 `agent_end`。 |
| **8. 挂起交互期间取消** | `agent.abort()` 交互等待取消 | `cancellation_abort.test.js` | **PASS** | `beforeToolCall` 监听 `signal.addEventListener("abort")`，用户取消任务时能立即打破挂起状态并安全退出。 |
| **9. 上下文与格式映射** | `transformContext` 与 `convertToLlm` | `context_transform.test.js` | **PASS** | 成功在请求模型前剔除内部自定义字段、将自定义审计消息映射为标准模型消息，并验证了 `shouldStopAfterTurn` 的多 turn 终止控制。 |
| **10. Skill 脚本执行契约** | Node 子进程拉起 Python 脚本 | `skill_execution.test.js` | **PASS** | 验证了 Node 进程使用 `DATAAGENT_PYTHON_BIN` 调用 `DATAAGENT_SKILL_ROOT/scripts/<name>.py` 约定，成功捕获标准输出 JSON、错误输出 stderr 与退出码。 |

---

## 3. 架构设计关键修正点（Spike 沉淀）

在编写测试套件并深度分析 `@earendil-works/pi-agent-core` 源码实现时，发现如下重要细节，需沉淀至后续开发规范：

1. **工具结果消息结构**：
   - 顺序执行（sequential）模式下，若单 turn 模型产生 $N$ 个工具调用，Pi transcript 会生成 $N$ 条独立的 `role: "toolResult"` 消息，而非合并为一条数组。后续 `PiEventNormalizer` 和历史投影逻辑应按此结构处理。
2. **`beforeToolCall` 的 AbortSignal 幂等守卫**：
   - 在 `beforeToolCall` 实现异步等待（如等待用户审批）时，必须**先检查 `if (signal?.aborted)`**，再挂载 `signal.addEventListener("abort", ..., { once: true })`。
   - 收到取消信号时，必须显式返回 `{ block: true, reason: "Operation aborted", terminate: true }`，确保批次终止标志 `terminate` 被置为 true，避免 Loop 在下一 turn 重新调度未完成的工具调用。
3. **Provider Stream 对 `signal` 的终止约定**：
   - 自定义 `streamFn` 必须将 `options.signal` 传导至底层 HTTP 请求。当 `signal.aborted` 为 true 时，Provider 必须产出带 `stopReason: "aborted"` 的消息，以确保 Loop 识别为主动取消退出。

---

## 4. 门禁决议 (Gate 0 / Gate 1 Outcome)

- **决议:** **GO**
- **理由:**
  1. 官方 `@earendil-works/pi-agent-core@0.85.1` 与 `@earendil-works/pi-ai@0.85.1` 完全具备 DataAgent 所需的流式、推理、顺序工具调用、异步挂起恢复、上下文转换及强取消能力。
  2. 无需对 Pi 官方包打补丁（zero patch），依赖纯净，符合 MIT 开源合规。
  3. Node 22 环境在开发机已验证就绪，与前端 Node 20 基准完全隔离，具备生产容器化条件。
- **后续实施步骤:**
  - 推进 **Task 2**: 冻结语言中立的 JSON Schema 契约（Runtime Request, Agent Event, Interaction, Tool）。
  - 推进 **Task 5**: 创建独立的 `dataagent/dataagent-runtime-pi` 生产包与 Cell 通信框架。
