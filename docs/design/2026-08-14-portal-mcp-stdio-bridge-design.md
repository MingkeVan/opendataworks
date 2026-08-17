# Portal MCP stdio Bridge 设计（消除单次对话内的 session expired）

## Background

平台 widget 助手在**一次对话执行过程中**仍会中断并报：

```
MCP server "portal" session expired
```

前置两阶段（见 `docs/design/2026-06-24-portal-mcp-keepalive-session-expired-design.md`）已做：

- 阶段一：`portal-mcp` 的 uvicorn `--timeout-keep-alive` 默认拉到 600s，消除服务端 5s 空闲断连。
- 阶段二：`portal-mcp` 固化为无状态（`stateless_http=True`，不下发 `Mcp-Session-Id`），`mcp`/`uvicorn` 版本钉死。

阶段二明确把「客户端层重连/重试」列为后续设计项：**若无状态化后仍复发，再单独立项**。现场仍复发，本设计即该后续项。

## 现状与根因（按生产运行时基线查证）

DataAgent 默认不使用宿主机全局安装的 Claude CLI，而是使用
`claude-agent-sdk==0.2.114` 自带的 CLI `2.1.205`。最初对宿主机
`/opt/node22/lib/node_modules/@anthropic-ai/claude-code/cli.js`（`2.1.42`）的取证只用于形成
假设；实现和发布验证必须以 SDK 自带的 `2.1.205` 为准，不能用全局 CLI 的结果替代生产运行时。

以下结论以 `2.1.205` 二进制为准，验证记录见配套计划。`2.1.42` 的旧分支只保留为历史对照；
两个版本的 stale-session 判定并不完全相同，不能混用。

### 1. `session expired` 有两类判定，只有 `Connection closed` 显式限制 transport

CLI `2.1.205` 的 stale-session matcher 会把错误码 `404`，以及带特定 session 文案的 `400`
识别为 session 失效：

```js
function p0s(error) {
  if (error instanceof McpSessionExpiredError) return true;
  let code = "code" in error ? error.code : undefined;
  if (code === 404) return true;
  return code === 400 && /Server not initialized|No valid session ID|Mcp-Session-Id header is required/i.test(error.message);
}
```

工具调用 catch 分支把 stale-session matcher 与 `Connection closed` 分开判断：

```js
let stale = p0s(error);
let closed = error.code === -32000 && error.message.includes("Connection closed")
  && (config.type === "http" || config.type === "claudeai-proxy");
if (stale || closed) throw new McpSessionExpiredError(serverName);
```

结论：`Connection closed` 路径显式限制为 HTTP 系 transport；`404`/`400` matcher 本身不限制
transport。因此本方案生效依赖两个同时成立的条件：CLI 看到的是 `stdio` config，且桥把远端 HTTP
失败统一归一化为 JSON-RPC `-32603`，不能把 HTTP `404` 原样伪装成 stdio JSON-RPC 错误。当前
portal-mcp 工具契约也不生成正数 `404` 的 JSON-RPC tool error。不能再笼统描述为“只要换成 stdio，
两条路径都结构上不可达”。

### 2. CLI 已有一次重试，说明现场是「连续两次失败」

```js
let D = 1;
for (let M = 0;; M++) try { let P = await IM1(A), W = await Pn7({...}); return {...} }
catch (P) { if (P instanceof bPA && M < D) { EA(A.name, `Retrying tool ... after session recovery`); continue } ... throw P }
```

`D = 1`：第一次 session expired 会清连接缓存（`zE`）并重连重试一次。用户能看到该错误，意味着**重试后再次命中同一路径**。而 `zE` → `cleanup()` → `client.close()` → `transport.close()` 会 abort 该 transport 上**全部在途请求**，被 abort 的兄弟请求随即以 `-32000 Connection closed` 落入同一分支、再次 `zE`——并行工具调用下会互相踢掉对方刚建好的连接，形成级联。这解释了「对话越到后面越容易出现」。

### 3. HTTP transport 上每个 POST 默认 60s abort

```js
function g0s(config) { ... return config?.timeout ?? Z0d; }
function Azr(fetch, config) {
  let timeout = g0s(config);
  ... setTimeout(() => controller.abort(), timeout);
}
var Z0d = 60000;
```

`type === "http"` 分支构造 transport 时会用 `Azr` 包装 fetch，未配置时取 `Z0d = 60000`。
这条 60s 是 HTTP POST 的默认上限，但不是整条 MCP 调用链的唯一上限：
生产基线 CLI `2.1.205` 还存在默认 5 分钟的 MCP tool idle timeout。后者大于 portal 工具当前
120s 的契约上限，不影响本次故障修复，但必须保留在超时链说明中。

`portal_query_readonly` 的 `timeout_seconds` 上限是 120s，即工具契约允许的时长本身就能越过这条 60s 线。这条 60s 是阶段一设计中被划到范围外的「工具结果被中断/置空」问题的真实来源，且与 session expired 同源于 HTTP transport。

### 4. 服务端不是变量

`portal-mcp` 已是无状态：不下发 `Mcp-Session-Id`、不跟踪不淘汰 session，且 keepalive=600 已生效。服务端侧能做的都已经做完，仍复发 ⇒ 剩余成因全在「客户端 HTTP transport」这一跳。

## Scope

- 把 portal MCP 在 Claude CLI 侧的接入 transport 从 `http` 换成 `stdio`，中间加一个**通用 JSON-RPC 转发桥**：CLI ↔ 桥（stdio）↔ portal-mcp（Streamable HTTP）。
- 桥随 `dataagent-backend` 代码走，`Dockerfile` 与 `Dockerfile.runner` 都已整目录 `COPY`，无需改镜像构建。
- 提供一个显式回滚开关，可不重建镜像退回 `http`。

不在本次范围：

- `portal-mcp` 服务端逻辑、工具契约、compose 编排（keepalive 与无状态化保持原样）。
- dataagent-backend 的「外层重跑整个 turn」兜底（阶段二已论证会扩散到 stream 持久化/前端渲染/副作用判断，风险不成比例）。
- 前端、权限门、技能链路。

## Solution

### 拓扑

```
Claude CLI ──stdio(JSON-RPC over pipe)──> portal_mcp_stdio_bridge.py ──HTTP POST──> portal-mcp:8801/mcp/
```

桥是**协议无关的转发器**：不认识任何 portal 工具，不复制任何 schema。`initialize` / `tools/list` / `tools/call` 一律原样透传，工具契约仍然只由 `portal-mcp` 定义。因此新增这一层不违反「不把 skill/工具专属行为搬进共享运行时」的模块规则。

### 为什么这层 wrapper 是必要的

AGENTS.md 要求「除非有已验证的运行时限制，否则不加额外 wrapper 层」。此处限制需在仓库锁定的
CLI `2.1.205` 上查证：60s POST abort 与 `Connection closed` session-recovery 路径依赖 HTTP
transport；通用 stale-session matcher 则会识别任意 transport 传入的 `404`/特定 `400`。较新
CLI 可配置 HTTP request timeout 只能处理 60s 上限，不能消除 session-recovery 分支。本方案因此
必须同时换成 stdio，并把桥的 HTTP 失败归一化为 `-32603`。

### 换成 stdio 后各成因的归宿

| 成因 | HTTP transport | stdio + 桥 |
| --- | --- | --- |
| HTTP 404 / session 400 | 抛 session expired | 桥归一化成 `-32603`，不把 HTTP 状态伪装成 JSON-RPC 404/400 |
| `-32000 Connection closed`（路径 P） | 抛 session expired | 分支显式要求 `http`/`claudeai-proxy`，不可达 |
| 单请求默认 60s abort | 工具结果被置空 | CLI 的 timeout fetch wrapper 只包 HTTP/SSE，不存在 |
| uvicorn 空闲回收连接 | 依赖 keepalive 调参 | 桥禁用连接池复用，每次工具调用新连接 |
| portal-mcp 重启 / OOM | 整个 session 失效 | 单次调用返回工具级错误，模型可重试，run 不死 |
| 并行工具调用级联 abort | 互相踢连接 | 每个请求独立 HTTP 连接，互不影响 |

### 桥的行为契约

- 输入：stdin 上的行分隔 JSON-RPC 消息（MCP stdio 标准帧）。
- 每条消息 POST 到 `PORTAL_MCP_BRIDGE_URL`，带上 `PORTAL_MCP_BRIDGE_HEADERS`（前门 token、`X-Agent-Data-Scope`）。
- `initialize` 请求不带协议版本头；桥从成功的 `InitializeResult.protocolVersion` 保存协商结果，
  并在后续 HTTP POST 上发送 `MCP-Protocol-Version`。这保证无状态服务端不会把新协议会话静默
  回退成 `2025-03-26`。
- 响应 `application/json` 直接回写；`text/event-stream` 按 SSE event 边界解析，同一 event 的多条
  `data:` 用换行拼接后再解析 JSON（Streamable HTTP 规范允许两种，服务端当前固定 JSON）。
- 通知（无 `id`）POST 后服务端返回 202 空体，不回写任何内容。
- 请求（JSON 对象中存在 `id`）必须产出响应：HTTP 200 空体、无法解析的合法响应形态、桥内部的
  非 HTTP 异常都归一化为单条 `-32603`，禁止让 per-message task 静默结束。
- 并发：每条消息一个 task，stdout 写入加锁串行化；JSON-RPC 靠 `id` 匹配，乱序回复合法。
- stdin 的**解码**失败与**读取**失败必须区别对待：
  - 单帧解码失败（非法 UTF-8、非法 JSON）只记录到 stderr 并跳过该帧，桥继续服务后续帧。
  - `readline` 本身失败（`OSError`，以及 stdin 被关闭后的 `ValueError: readline of closed file`）
    按 EOF 处理，收尾在途请求后退出。字节流读不动之后无法重新对齐，`continue` 会退化成 100% CPU
    的空转死循环（EBADF 这类错误是持续性的），比进程干净退出更糟；退出后 CLI 能明确观察到 stdio
    server 消失。
- task done callback 必须读取并记录仍然逃逸的异常，避免后台 task 无声失败。
- 同一个 `id` 只写出一个响应帧：转发成功后即便后续写入失败，也不再补发 `-32603`。
- 连接池：`max_keepalive_connections=0`，每次请求新建连接。对应 `encode/httpx#2056` 对「连接池僵尸连接」的标准结论，也让服务端 keepalive 取值不再参与正确性。
- 重试：**仅** `ConnectError`/`ConnectTimeout` 重试（请求尚未送达，写工具重试也安全），最多 2 次、退避 0.2s/0.5s。`ReadTimeout`、`RemoteProtocolError` 等「可能已执行」的错误不重试。
- 失败映射：任何转发失败对有 `id` 的请求回 JSON-RPC `-32603`，即**工具级错误**，模型能看到、能改写重试，run 不中断。刻意不使用正数 HTTP `400`/`404`，也不使用 `-32000` / `Connection closed` 文案。
- 日志一律走 stderr（stdout 是协议通道）；CLI 会 `stderr: "pipe"` 收集并写入 MCP 日志。

### 无状态假设

桥不跟踪 `Mcp-Session-Id`。这是单一已验证路径：`portal-mcp` 已固化 `stateless_http=True` 且有测试断言成功 `initialize` 响应不含该头。若响应里意外出现该头，桥向 stderr 打一条告警，便于定位服务端配置漂移，但不引入第二条有状态分支。

### Interfaces

新增 Settings（均可由环境变量覆盖，命名沿用现有 `DATAAGENT_PORTAL_MCP_*` 前缀）：

- `dataagent_portal_mcp_transport`（`stdio` | `http`，默认 `stdio`）：回滚开关，置 `http` 完全恢复原行为；未知值直接拒绝，避免拼错后静默留在 stdio 路径。
- `dataagent_portal_mcp_request_timeout_seconds`（int，默认 600）：桥的单次 HTTP 读超时。

桥进程的 `PORTAL_MCP_BRIDGE_HEADERS` 是 backend 构造的认证/数据范围契约。缺失、非法 JSON、
非对象或空对象均在启动时返回退出码 2，不允许无 token 启动后把所有工具调用退化成 HTTP 401。

`_build_portal_mcp_servers` 在 stdio 模式下返回：

```python
{"portal": {"type": "stdio", "command": sys.executable,
            "args": [".../core/portal_mcp_stdio_bridge.py"],
            "env": {"PORTAL_MCP_BRIDGE_URL": ..., "PORTAL_MCP_BRIDGE_HEADERS": ...,
                    "PORTAL_MCP_BRIDGE_TIMEOUT_SECONDS": ...}}}
```

工具名不变（仍是 `mcp__portal__*`），因此 `allowed_tools`、权限门（`can_use_tool`）、前端渲染、技能文档全部不受影响。

## 超时链分析

按智能问数超时规则，把这一跳放回整条链核对：

- 后端 run 总超时：交互 360s / 后台 1800s；idle 90s / 300s。
- CLI `2.1.205` MCP tool idle timeout：默认 5 分钟；大于 portal 工具当前 120s 契约上限。
- 桥单次 HTTP 读超时：**600s**。> portal-mcp → Java 后端 30s，> `portal_query_readonly` 契约上限 120s，> 交互 run 360s；被后台 1800s 覆盖。
- portal-mcp keepalive：600s，保留但不再参与正确性（桥不复用连接）。
- CLI ↔ 桥为同容器内管道，反向代理与本跳无关。

净效果：移除 HTTP transport 原本 60s 的隐式单请求上限，桥这一跳改为显式、可配置的 600s；
CLI 自身的 5 分钟 idle timeout 与后端总 run timeout 仍保留，不能把桥的读超时描述为整条链的
唯一硬上限。

## 风险与回退

- 风险：每次 run 多一个短生命周期 Python 进程（随 CLI 退出而退出）。桥是纯转发，无状态、无磁盘写入。
- 风险：stdio 帧要求单行 JSON。大结果（`portal_query_readonly` 上限 10000 行）走管道，CLI 对超大工具结果本就有落盘 offload 机制，且不经过 SDK 控制通道（`max_buffer_size` 不受影响）。
- 风险：沙箱模式下 CLI 在子容器内运行。`Dockerfile.runner` 同样整目录 `COPY dataagent/dataagent-backend`，桥脚本路径与解释器路径在两个镜像中一致（`/opt/dataagent-backend/core/...`、`python:3.11-slim`）。
- 风险：`notifications/cancelled` 会转发到无状态服务端，但桥没有按 request id 取消另一个并发
  `self._client.post`；服务端无法跨独立 POST 关联时，被放弃的查询可能继续执行，直到请求完成、桥的
  600s 读超时或桥进程随 CLI 退出。后续若要解决，需要显式维护 request id → task 映射，不能靠
  transport 的隐式 session。
- 风险：桥的 600s 读超时大于交互 run 的 360s 总预算，因此交互路径通常先呈现 agent 总超时，
  而不是桥生成的 `ReadTimeout` 工具错误；600s 主要覆盖后台路径和显式诊断，不应被当作交互错误的
  可见上限。
- 回退：设 `DATAAGENT_PORTAL_MCP_TRANSPORT=http` 后重新创建 `dataagent-backend` 和
  `dataagent-sandbox-runner`。sandbox 模式在 runner/child 内构造 MCP 配置，只重启 backend 不会
  更新 runner 或已预热 child 的环境；runner 重建时会清理并按新环境重建 warm children。无需重建
  镜像，`http` 分支代码原样保留。

## 定位：这是兼容层，不是目标形态

MCP 规范对 transport 的定位是按**服务端部署形态**划分的：stdio 用于客户端拉起的本地子进程，
Streamable HTTP 用于独立部署的远程服务。`portal-mcp` 是后者，这一点本次没有改变——它仍然是一个
独立部署、独立认证、统一数据范围边界的 Streamable HTTP 远程服务。改变的只是**某一个客户端**
（锁定版本的 Claude CLI）如何抵达它：

```
原来：Claude CLI ──Streamable HTTP──> portal-mcp
现在：Claude CLI ──stdio──> bridge ──Streamable HTTP──> portal-mcp
```

HTTP 没有消失，只是不再由 CLI 自己管理。桥的唯一价值是建立**故障隔离边界**：把 HTTP/网络故障
归一化成一次工具级错误，不让它升级成整个 MCP session 失效。因此本层：

- 只做 transport 转换、错误归一化、协议头透传、进程生命周期。
- **不得**包含任何 portal 工具定义、schema、业务规则或数据范围判断——那些仍然只属于 `portal-mcp`。
- 必须有明确的删除条件（下一节），不能沉淀成永久架构。

被否掉的两个替代方案：

- **把 `portal-mcp` 整体改成本地 stdio server**：要求每个 backend/sandbox 容器各自运行完整
  `portal-mcp`，破坏独立服务、前门认证与统一数据范围边界，部署与升级成本显著上升。
- **改用旧版 HTTP+SSE transport**：那是规范里的兼容路径，新服务不应回退过去；且 CLI 对 `sse`
  同样套用 HTTP fetch 超时。

## 退出条件：何时删除本兼容层

长期目标是回到 CLI 直连 Streamable HTTP。触发条件不是「有新版本了」，而是**在即将上线的那个
锁定版本上重新验证过下列行为**。任何一项不通过就继续保留桥。

前置：验证对象必须是 `dataagent/dataagent-backend/requirements.txt` 里锁定的
`claude-agent-sdk` 所自带的 CLI，不是 `latest`，也不是开发机上恰好装着的那个。

```bash
# 定位待验证的 CLI 二进制（本仓库 2026-08 基线为 claude-agent-sdk==0.2.114 → CLI 2.1.205）
find "$(python -c 'import claude_agent_sdk,pathlib;print(pathlib.Path(claude_agent_sdk.__file__).parent)')" \
     -name cli.js 2>/dev/null || which claude
```

### A. 二进制静态核对（便宜，先做）

| # | 待确认 | 判据 |
| --- | --- | --- |
| A1 | 单请求硬超时 | 不再存在对 HTTP POST 无条件套用的 `AbortSignal.timeout(60000)`；或该值可由环境变量配置到 ≥ 交互 run 预算 |
| A2 | stale-session matcher | HTTP `404` / session `400` 不再被无条件识别为 session expired；至少不应把普通后端故障升级成 session 失效 |
| A3 | connection-closed 恢复 | `-32000 "Connection closed"` 的处理不再清理共享 transport，或清理范围不波及其它在途请求 |

### B. 真实运行时验证（需要真实 portal-mcp，A 全过之后再做）

| # | 场景 | 通过标准 |
| --- | --- | --- |
| B1 | 90–120s 的 `portal_query_readonly` | 正常返回结果，不在 60s 被截断、不返回空结果 |
| B2 | 调用中途重启 `portal-mcp` | 只有当前这次调用失败，**后续调用自动恢复**，会话存活 |
| B3 | 并发多个 portal 工具调用，其中一个连接出错 | 其余调用不受影响，不出现级联失败 |
| B4 | 服务端返回 HTTP 400/404 | 呈现为工具级错误，会话继续，不出现 `session expired` |
| B5 | 协议版本头 | `initialize` 之后的请求携带协商得到的 `MCP-Protocol-Version` |
| B6 | 取消 | 取消一次进行中的工具调用后，服务端侧请求确实被中断 |
| B7 | 深会话回归 | 一次真实 widget 对话内密集调用 portal 工具，不复现 `session expired` |

B1–B4、B7 可复用本次的现成资产：`tests/test_portal_mcp_stdio_bridge.py` 的真实 ASGI 流程、
`deploy/docker-compose.dev.yml` 起的 `portal-mcp`，以及把 `DATAAGENT_PORTAL_MCP_TRANSPORT`
切到 `http` 做 A/B 对比。B6、B7 需要真实模型凭据。

### 删除动作

全部通过后：`dataagent_portal_mcp_transport` 默认值改回 `http` → 观察一个发布周期 → 删除
`core/portal_mcp_stdio_bridge.py`、其测试、`dataagent_portal_mcp_request_timeout_seconds`
与 `deploy/.env.example` 中的相关条目，并在本设计文档追加「已退役」说明。

### 保留期间的已知取舍

- 取消未映射到在途 POST（见「风险与回退」）。
- SSE 是整体读完再回写，不是增量消费；因此长工具调用期间的服务端进度通知不会实时到达 CLI。
  当前 `portal-mcp` 固定 `json_response=True`，没有增量流可丢，但这条限制会随服务端改流式而生效。
- 单一 600s 读超时同时服务交互与后台两档，交互路径拿不到桥自己的 `ReadTimeout` 诊断。

## 参考

- `claude-agent-sdk==0.2.114` 自带 Claude CLI `2.1.205`（生产运行时基线）。
- MCP 规范 Transports：stdio 用于本地子进程，Streamable HTTP 用于远程服务。
- 本机 `@anthropic-ai/claude-code` 2.1.42 `cli.js`：初始取证样本，仅作为历史对照，不用于生产
  runtime 结论。
- `anthropics/claude-code#27142`、`openai/codex#13969`、`danny-avila/LibreChat#11868`、`Doist/todoist-ai#304`、`encode/httpx#2056`、modelcontextprotocol.io — Transports。
