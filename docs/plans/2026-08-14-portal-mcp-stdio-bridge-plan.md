# Portal MCP stdio Bridge 计划

配套设计：`docs/design/2026-08-14-portal-mcp-stdio-bridge-design.md`

前置阶段：`docs/design/2026-06-24-portal-mcp-keepalive-session-expired-design.md`（keepalive + 无状态固化，保持生效，本次不动）

## 受影响栈

- DataAgent 后端（`dataagent/dataagent-backend`：新增桥脚本、Settings、MCP server 配置构造）
- 部署：compose、`Dockerfile`、`Dockerfile.runner` 无需改动（后端目录整体 `COPY`，桥脚本自动进入
  两个镜像）；更新 `deploy/.env.example`，公开 transport/timeout 配置并写明 sandbox 回退需要重建
  backend 与 runner。

## 任务

1. `dataagent/dataagent-backend/core/portal_mcp_stdio_bridge.py`（新增）
   - stdin 行分隔 JSON-RPC → POST 到 `PORTAL_MCP_BRIDGE_URL` → 响应写回 stdout。
   - 从成功的 `InitializeResult.protocolVersion` 保存协商版本，后续 POST 注入
     `MCP-Protocol-Version`；`initialize` 本身不带该头。
   - `application/json` 与 `text/event-stream` 两种合法响应体都能解析；SSE 同一 event 的多行
     `data:` 按规范拼接；无 `id` 的通知不回写。
   - `httpx.AsyncClient(limits=Limits(max_keepalive_connections=0))`，每次请求新连接。
   - 仅对 `ConnectError`/`ConnectTimeout` 重试（最多 2 次，退避 0.2s/0.5s）；其余错误直接映射为 JSON-RPC `-32603`。
   - 对所有带 `id` 的请求保证响应：HTTP 200 空体和非 HTTP 内部异常也返回 `-32603`。
   - stdin 单帧解码/读取错误不结束进程；并发处理 + stdout 写锁；task 逃逸异常和其它日志全部走 stderr。
   - `PORTAL_MCP_BRIDGE_HEADERS` 缺失或解析失败时启动失败，禁止无认证信息运行。
2. `dataagent/dataagent-backend/config.py`
   - 新增 `dataagent_portal_mcp_transport: str = "stdio"`（回滚开关，另一取值 `http`）。
   - 新增 `dataagent_portal_mcp_request_timeout_seconds: int = 600`。
3. `dataagent/dataagent-backend/core/agent_runtime.py`
   - `_build_portal_mcp_servers` 按 transport 返回 stdio 配置（`command`/`args`/`env`）或原 http 配置。
   - transport 未知取值直接拒绝，避免回退拼写错误时 fail-open 到 stdio。
   - 保留 URL 归一化（`/mcp` → `/mcp/`）与 `X-Agent-Data-Scope` 注入，两种 transport 一致。
4. 测试
   - `tests/test_portal_mcp_stdio_bridge.py`（新增）：请求转发与响应回写、请求头注入、初始化协议版本
     提取及后续请求转发、通知不回写、SSE 响应解析、非 2xx → `-32603`、连接错误重试后成功、
     不可重试错误只发一次、200 空体和任意内部异常回错误、stdin 非法字节不中断、真实 stdout
     写路径、并发多请求全部有回复；测试创建的 `AsyncClient` 全部显式关闭。
   - `tests/test_agent_runtime.py`：默认 stdio 配置断言；`transport="http"` 回滚分支断言并覆盖
     `X-Agent-Data-Scope`；未知 transport 拒绝；URL 归一化断言改到 headers/env 上。
   - `tests/test_task_executor.py`：`mcp_servers` 期望值同步为 stdio 形态。
5. 文档
   - 本设计/计划；在 `2026-06-24` 设计文档末尾追加指针，说明后续项已落地。
   - `deploy/.env.example` 增加 `DATAAGENT_PORTAL_MCP_TRANSPORT` 与
     `DATAAGENT_PORTAL_MCP_REQUEST_TIMEOUT_SECONDS`，回退说明覆盖 runner/warm child。

## 验证（已执行）

1. `pytest dataagent/dataagent-backend/tests -q`：**446 passed**（排除 6 个本环境预先就无法 collect 的模块）。
   排除项 `test_admin_routes` / `test_auth_routes` / `test_readonly_query_proxy` / `test_routes_contract` /
   `test_runtime_excludes_eval_api` / `test_widget_runtime_routes` 在 `git stash` 后的未改动树上同样报
   `ModuleNotFoundError: No module named 'pymysql.cursors'`，属本容器环境问题，与本次改动无关。
2. `pytest dataagent/portal-mcp/tests -q`：**30 passed**（服务端契约未受本次改动影响）。
3. 桥 × 真实 `portal-mcp` app（测试内 ASGI transport）：`initialize` → `notifications/initialized` →
   `tools/list`，断言返回真实工具清单而非 mock。
4. 桥作为**真实子进程**对真实 uvicorn 服务端跑通，并逐条复现历史故障场景：
   - `--timeout-keep-alive 1` + 空闲 3s 后继续调用 → 成功（阶段一的空闲断连故障模式）
   - 服务端进程被杀 → 返回 JSON-RPC `-32603` 工具级错误，消息中不含 `Connection closed`
   - 服务端重启后**同一个桥进程**继续正常服务（阶段二的 server 重启 / OOM 故障模式）
   - 关闭 stdin → 退出码 0
5. 初始验证使用宿主机 `@anthropic-ai/claude-code` 2.1.42；该结果只作为历史故障样本，不能替代
   生产运行时验证。
6. 修复 review 后，使用仓库锁定的 `claude-agent-sdk==0.2.114` 自带 Claude CLI `2.1.205`：
   - 二进制核对确认 HTTP POST 默认 timeout 为 60000ms，`Connection closed` 分支受 transport
     类型约束；通用 `404`/session `400` matcher 不受 transport 限制，因此桥必须归一化 HTTP
     失败为 `-32603`。
   - 以 stdio 方式挂载本桥，对真实 uvicorn portal-mcp 运行 `claude mcp list/get`，均报告
     `Status: ✔ Connected`。
   - `tests/test_portal_mcp_stdio_bridge.py` 的真实 portal-mcp ASGI 流程断言 initialize 不带版本头，
     后续 `notifications/initialized` 与 `tools/list` 均发送协商得到的 `MCP-Protocol-Version`。
7. 第二轮 review 加固验证：
   - `pytest tests/test_portal_mcp_stdio_bridge.py tests/test_agent_runtime.py tests/test_task_executor.py -q`：
     **128 passed**；其中覆盖非 HTTP 异常、响应处理异常、200 空体、响应 id 不匹配、跨行 SSE、
     stdin 解码/OSError、后台 task 异常可观测、真实 stdout、header fail-fast、未知 transport 与
     HTTP 回退 scope。
   - `pytest dataagent/portal-mcp/tests -q`：**30 passed**。
   - 真实桥子进程 + 本地 HTTP server 探针：200 空体返回 `-32603`；跨行 SSE 正常返回；二进制
     stdin 写入非法 UTF-8 帧后下一请求仍成功；关闭 stdin 后退出码 0。
   - `py_compile` 与 `git diff --check` 通过；测试创建的 `AsyncClient` 均由 async context manager 关闭。
8. 第三轮 review 修复（stdin 读失败热循环）：
   - 探针确认加固版在 `readline` 持续抛 `OSError(EBADF)` 时 5s 内不退出（空转死循环），
     且 `ValueError: readline of closed file` 仍会杀死桥进程。改为「读失败按 EOF 处理」后两者都
     干净退出。
   - `test_run_loop_exits_on_stdin_read_error` 参数化覆盖 `UnicodeDecodeError` / `OSError` /
     `ValueError`，并断言 `readline` 只被调用一次（锁住不重试，防止回退成空转）。
   - `test_answered_request_is_not_followed_by_an_error_frame` 锁住「一个 id 只写一个响应帧」。
   - `test_success_without_matching_response_id_becomes_jsonrpc_error` 改为断言夹带的服务端通知
     被转发后再补 `-32603`，而不是连通知一起丢弃。
   - `pytest tests/test_portal_mcp_stdio_bridge.py tests/test_agent_runtime.py tests/test_task_executor.py -q`
     与真实子进程冒烟重跑通过。

## 未覆盖

- 当前会话环境不具备 MySQL / Redis / 真实模型凭据，**未执行** AGENTS.md 要求的本地全链路智能问数冒烟
  （真实 widget 对话 → 任务创建 → 事件流 → 终态落库）。
- 已验证的是：transport 挂载、协议往返、故障注入下的降级行为、CLI 侧连通性。**未验证**的是：
  真实模型驱动的多轮对话中密集工具调用时的现场表现。上线后按下面的观察项确认。

## 上线观察

- `dataagent-backend` 日志中 `task.start ... mcp_servers=portal` 仍出现，说明工具已挂载。
- CLI MCP 日志中不应再出现 `session expired`；桥的异常一律以 `portal-mcp-bridge` 前缀出现在 stderr。
- 关注是否出现新的 `-32603` 工具级错误——那是原本被吞成 session expired 的失败被正确暴露，需按其 message 定位 portal-mcp 侧问题。

## 回退

- 一级（无需重建镜像）：`DATAAGENT_PORTAL_MCP_TRANSPORT=http`，用新环境重新创建
  `dataagent-backend` 与 `dataagent-sandbox-runner`。runner 重建会清理旧 warm children；只重启
  backend 不足以改变 sandbox 执行路径。
- 二级：回滚本次提交。`portal-mcp` 侧 keepalive 与无状态化不受影响，无需同步回滚。

## 后续项（不在本 PR）

桥是兼容层，退出条件见设计文档「退出条件：何时删除本兼容层」。在它被删除之前，若需要长期保留，
按优先级补下面两项——两项都改变行为契约，应各自单独提 PR 并配套设计更新：

1. **cancellation 映射**：维护 `request_id → asyncio.Task`，收到 `notifications/cancelled` 时取消
   对应的在途 `httpx` 请求，让服务端感知 socket 断开。注意这只保证释放桥与 `portal-mcp` 侧的
   连接与 ASGI task；底层 SQL 是否真的被杀，取决于 `portal-mcp` 是否把取消继续传播到 Java 后端，
   不要在文档里过度承诺。代价：给一个刻意无状态的转发器引入按 request id 的状态，需要限定在
   transport 层、不得外溢成业务状态。
2. **分层超时**：现在交互与后台共用 600s，交互路径永远拿不到桥自己的 `ReadTimeout` 诊断。按
   `portal-mcp → Java 后端 30s`、`portal_query_readonly` 契约上限 120s 推算，交互档 `150-180s`
   足够覆盖最坏合法情况并早于 360s run 预算触发；后台档需要单独取值（后台 run 预算 1800s、
   `agent_background_sql_read_timeout_seconds` 900s）。代价：一个值变两个值，
   `_build_portal_mcp_servers` 需要拿到 `execution_mode`，是一处真实的参数透传改动。
