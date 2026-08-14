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

## 现状与根因（从本机 CLI 二进制查证）

查证对象：`/opt/node22/lib/node_modules/@anthropic-ai/claude-code/cli.js`（`VERSION: 2.1.42`）。以下均为直接读取压缩后源码得到的事实，不是推测。

### 1. `session expired` 的两条抛出路径都以 transport 类型为前置条件

工具调用的 catch 分支：

```js
let M = Xn7(X),
    P = "code" in X && X.code === -32000 && X.message.includes("Connection closed")
        && (K.type === "http" || K.type === "claudeai-proxy");
if (M || P) throw ..., await zE(q, K), new bPA(q)   // bPA = McpSessionExpiredError
```

其中：

```js
function Xn7(A){ if(("code" in A ? A.code : void 0) !== 404) return !1;
                 return A.message.includes('"code":-32001') || A.message.includes('"code": -32001') }
```

连接错误回调里同样有类型前置：

```js
if ((u === "http" || u === "claudeai-proxy") && Xn7(S)) { ...triggering reconnection... }
```

结论：**`McpSessionExpiredError` 只可能在 HTTP 系 transport 上产生**。`Xn7` 依赖一个 HTTP `404` 响应；`P` 显式要求 `K.type === "http"`。`stdio` transport 两条路径都不可达。

### 2. CLI 已有一次重试，说明现场是「连续两次失败」

```js
let D = 1;
for (let M = 0;; M++) try { let P = await IM1(A), W = await Pn7({...}); return {...} }
catch (P) { if (P instanceof bPA && M < D) { EA(A.name, `Retrying tool ... after session recovery`); continue } ... throw P }
```

`D = 1`：第一次 session expired 会清连接缓存（`zE`）并重连重试一次。用户能看到该错误，意味着**重试后再次命中同一路径**。而 `zE` → `cleanup()` → `client.close()` → `transport.close()` 会 abort 该 transport 上**全部在途请求**，被 abort 的兄弟请求随即以 `-32000 Connection closed` 落入同一分支、再次 `zE`——并行工具调用下会互相踢掉对方刚建好的连接，形成级联。这解释了「对话越到后面越容易出现」。

### 3. HTTP transport 上每个 POST 被硬性 60s abort

```js
function IPA(A){ return async (q, K) => {
  if ((K?.method ?? "GET").toUpperCase() === "GET") return A(q, K);
  let z = AbortSignal.timeout(jn7); ... } }
var jn7 = 60000;
```

`type === "http"` 分支构造 transport 时用的正是 `fetch: IPA(kK1())`，其调试日志直接打印 `timeoutMs: jn7`。而 MCP 工具级超时 `MCP_TOOL_TIMEOUT` 默认 `E_z = 1e8` ms（约 27.7 小时，等于不限），**唯一的单次硬上限就是这 60s**。

`portal_query_readonly` 的 `timeout_seconds` 上限是 120s，即工具契约允许的时长本身就能越过这条 60s 线。这条 60s 是阶段一设计中被划到范围外的「工具结果被中断/置空」问题的真实来源，且与 session expired 同源于 HTTP transport。

### 4. 服务端不是变量

`portal-mcp` 已是无状态：不下发 `Mcp-Session-Id`、不跟踪不淘汰 session，且 keepalive=600 已生效。服务端侧能做的都已经做完，仍复发 ⇒ 剩余成因全在「客户端 HTTP transport」这一跳。

## Scope

- 把 portal MCP 在 Claude CLI 侧的接入 transport 从 `http` 换成 `stdio`，中间加一个**通用 JSON-RPC 转发桥**：CLI ↔ 桥（stdio）↔ portal-mcp（Streamable HTTP）。
- 桥随 `dataagent-backend` 代码走，`Dockerfile` 与 `Dockerfile.runner` 都已整目录 `COPY`，无需改镜像构建。
- 提供一个显式回滚开关，可不重建镜像退回 `http`。

不在本次范围：

- `portal-mcp` 服务端逻辑、工具契约、部署编排（compose / `.env.example` 均不变，keepalive 与无状态化保持原样）。
- dataagent-backend 的「外层重跑整个 turn」兜底（阶段二已论证会扩散到 stream 持久化/前端渲染/副作用判断，风险不成比例）。
- 前端、权限门、技能链路。

## Solution

### 拓扑

```
Claude CLI ──stdio(JSON-RPC over pipe)──> portal_mcp_stdio_bridge.py ──HTTP POST──> portal-mcp:8801/mcp/
```

桥是**协议无关的转发器**：不认识任何 portal 工具，不复制任何 schema。`initialize` / `tools/list` / `tools/call` 一律原样透传，工具契约仍然只由 `portal-mcp` 定义。因此新增这一层不违反「不把 skill/工具专属行为搬进共享运行时」的模块规则。

### 为什么这层 wrapper 是必要的

AGENTS.md 要求「除非有已验证的运行时限制，否则不加额外 wrapper 层」。此处限制已在上文逐条查证：`session expired` 的两条抛出路径与 60s 单请求 abort **都以 `type === "http"` 为前置条件**，且都在 CLI 内部、不可配置、不可修改。换 transport 是我们边界内唯一能从结构上消除该类错误的做法。

### 换成 stdio 后各成因的归宿

| 成因 | HTTP transport | stdio + 桥 |
| --- | --- | --- |
| 404 + `-32001`（路径 M） | 抛 session expired | 结构上不可达（无 HTTP 响应码进入 CLI） |
| `-32000 Connection closed`（路径 P） | 抛 session expired | 分支显式要求 `http`/`claudeai-proxy`，不可达 |
| 单请求 60s 硬 abort | 工具结果被置空 | `IPA` 只包 HTTP/SSE fetch，不存在 |
| uvicorn 空闲回收连接 | 依赖 keepalive 调参 | 桥禁用连接池复用，每次工具调用新连接 |
| portal-mcp 重启 / OOM | 整个 session 失效 | 单次调用返回工具级错误，模型可重试，run 不死 |
| 并行工具调用级联 abort | 互相踢连接 | 每个请求独立 HTTP 连接，互不影响 |

### 桥的行为契约

- 输入：stdin 上的行分隔 JSON-RPC 消息（MCP stdio 标准帧）。
- 每条消息 POST 到 `PORTAL_MCP_BRIDGE_URL`，带上 `PORTAL_MCP_BRIDGE_HEADERS`（前门 token、`X-Agent-Data-Scope`）。
- 响应 `application/json` 直接回写；`text/event-stream` 抽取 `data:` 负载回写（Streamable HTTP 规范允许两种，服务端当前固定 JSON）。
- 通知（无 `id`）POST 后服务端返回 202 空体，不回写任何内容。
- 并发：每条消息一个 task，stdout 写入加锁串行化；JSON-RPC 靠 `id` 匹配，乱序回复合法。
- 连接池：`max_keepalive_connections=0`，每次请求新建连接。对应 `encode/httpx#2056` 对「连接池僵尸连接」的标准结论，也让服务端 keepalive 取值不再参与正确性。
- 重试：**仅** `ConnectError`/`ConnectTimeout` 重试（请求尚未送达，写工具重试也安全），最多 2 次、退避 0.2s/0.5s。`ReadTimeout`、`RemoteProtocolError` 等「可能已执行」的错误不重试。
- 失败映射：任何转发失败对有 `id` 的请求回 JSON-RPC `-32603`，即**工具级错误**，模型能看到、能改写重试，run 不中断。刻意不使用 `-32000` / `Connection closed` 文案。
- 日志一律走 stderr（stdout 是协议通道）；CLI 会 `stderr: "pipe"` 收集并写入 MCP 日志。

### 无状态假设

桥不跟踪 `Mcp-Session-Id`。这是单一已验证路径：`portal-mcp` 已固化 `stateless_http=True` 且有测试断言成功 `initialize` 响应不含该头。若响应里意外出现该头，桥向 stderr 打一条告警，便于定位服务端配置漂移，但不引入第二条有状态分支。

### Interfaces

新增 Settings（均可由环境变量覆盖，命名沿用现有 `DATAAGENT_PORTAL_MCP_*` 前缀）：

- `dataagent_portal_mcp_transport`（`stdio` | `http`，默认 `stdio`）：回滚开关，置 `http` 完全恢复原行为。
- `dataagent_portal_mcp_request_timeout_seconds`（int，默认 600）：桥的单次 HTTP 读超时。

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
- CLI 工具级超时：`MCP_TOOL_TIMEOUT` 未设置 ⇒ `1e8` ms，等于不限。
- 桥单次 HTTP 读超时：**600s**（新增的唯一硬上限）。> portal-mcp → Java 后端 30s，> `portal_query_readonly` 契约上限 120s，> 交互 run 360s；被后台 1800s 覆盖。
- portal-mcp keepalive：600s，保留但不再参与正确性（桥不复用连接）。
- CLI ↔ 桥为同容器内管道，反向代理与本跳无关。

净效果：**移除**了原本 60s 的隐式单请求上限，代之以一条显式、可配置、与链上其它值一致的 600s。

## 风险与回退

- 风险：每次 run 多一个短生命周期 Python 进程（随 CLI 退出而退出）。桥是纯转发，无状态、无磁盘写入。
- 风险：stdio 帧要求单行 JSON。大结果（`portal_query_readonly` 上限 10000 行）走管道，CLI 对超大工具结果本就有落盘 offload 机制，且不经过 SDK 控制通道（`max_buffer_size` 不受影响）。
- 风险：沙箱模式下 CLI 在子容器内运行。`Dockerfile.runner` 同样整目录 `COPY dataagent/dataagent-backend`，桥脚本路径与解释器路径在两个镜像中一致（`/opt/dataagent-backend/core/...`、`python:3.11-slim`）。
- 回退：设 `DATAAGENT_PORTAL_MCP_TRANSPORT=http` 重启 `dataagent-backend` 即可，无需重建镜像；`http` 分支代码原样保留。

## 参考

- 本机 `@anthropic-ai/claude-code` 2.1.42 `cli.js`：`bPA`/`McpSessionExpiredError`、`Xn7`、`IPA`/`jn7=60000`、`Fs`/`E_z=1e8`、`gR` 的 transport 分支。
- `anthropics/claude-code#27142`、`openai/codex#13969`、`danny-avila/LibreChat#11868`、`Doist/todoist-ai#304`、`encode/httpx#2056`、modelcontextprotocol.io — Transports。
