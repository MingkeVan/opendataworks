# Portal MCP Keep-Alive / Session Expired 设计

## Background

平台 widget 助手（浮窗智能体对话）在一次会话中“对话到后面”执行工具时会报错：

```
MCP server "portal" session expired
```

该报错来自 Claude Code CLI 内部的 MCP HTTP 客户端（错误类 `McpSessionExpiredError`，消息模板 `MCP server "<name>" session expired`），`<name>` 即运行时注入的 MCP server 名 `portal`（见 `dataagent/dataagent-backend/core/agent_runtime.py` 的 `PORTAL_MCP_SERVER_NAME = "portal"`）。

链路为：widget → `dataagent-backend`（`claude_query`）→ Claude CLI 子进程 → 通过 Streamable HTTP 连接 `portal-mcp`（`http://portal-mcp:8801/mcp/`）。

## 现状与根因

### CLI 侧触发条件

逆向 CLI（`@anthropic-ai/claude-code/cli.js`）后确认，`McpSessionExpiredError` 在工具调用阶段只由两条路径抛出：

- 路径 M：HTTP `404` 且响应体 JSON-RPC error code 为 `-32001`（`-32001` 是 TypeScript SDK 的 `RequestTimeout`/会话相关码）。
- 路径 P：JSON-RPC error code `-32000`（`ConnectionClosed`）且消息包含 `Connection closed`，且 transport 类型为 `http`。

### Server 侧事实

`portal-mcp` 使用 Python `mcp` SDK 的 `FastMCP`，且运行时确认为**无状态**：

- `mcp.settings.stateless_http = True`、`json_response = True`；运行时 `session_manager.stateless == True`（已实测）。
- 无状态模式下每个请求新建一次性 transport（`mcp_session_id=None`），不跟踪、不校验、不淘汰 session，因此**不会**返回 `404 Session not found`。
- 即便返回 `404`，Python SDK 用的是 `INVALID_REQUEST = -32600`，并非 CLI 路径 M 所需的 `-32001`。

结论：路径 M 不成立；真正触发的是**路径 P（`-32000 Connection closed`）**，即 CLI↔portal-mcp 的连接在工具调用在途/会话存续期间被断开。

### 被验证的服务端缺陷：uvicorn 空闲 keep-alive = 5s

`portal-mcp` 的 `Dockerfile` 启动命令为：

```
CMD ["uvicorn", "portal_mcp.app:app", "--host", "0.0.0.0", "--port", "8801"]
```

未设置 `--timeout-keep-alive`，采用 uvicorn 默认值 **5 秒**：任何空闲超过 5s 的 keep-alive 连接都会被服务端主动关闭。

本地复现（真实 `portal-mcp` app + uvicorn）：

- 默认 keep-alive，空闲 3s（<5s）→ 连接复用成功。
- 默认 keep-alive，空闲 7s（>5s）→ 服务端已关闭空闲连接，复用即 EOF（`RESULT=CONNECTION_DROPPED`）。
- 设 `timeout_keep_alive=120` 后，空闲 7s / 30s 均复用成功。
- 标准 GET SSE 通道（transport 主通道）为活跃响应，不受 keep-alive 超时影响（实测保持打开）。

这与“对话到后面才报错”的现象一致：会话越深，单步推理（模型思考 + 非 portal 工具）之间的间隔越容易超过 5s，使 CLI 与 portal-mcp 之间用于工具调用的连接在思考间隙被服务端关闭，随后工具调用命中已断开连接，最终以 `-32000 Connection closed` 暴露为 `session expired`。

> 说明：被证实的是服务端 5s 空闲断连这一确定性缺陷与其时序吻合；CLI 内部从“连接被断”到具体抛出 `-32000` 的精确微观时序无法在本仓库内驱动 TS 客户端复现，已在“风险与回退”中标注后续排查方向。

## Scope

- 仅调整 `portal-mcp` 的 HTTP 服务端 keep-alive 行为，使其覆盖单次 agent run 内合理的工具调用间隔。
- 将 keep-alive 超时纳入 Settings，支持环境变量配置，并在 deploy 层暴露与默认。

不在本次范围：

- DataAgent 会话/run/worker 架构、权限门、技能链路。
- CLI 侧 MCP 客户端逻辑（不可改）。
- `portal_query_readonly` 单次最长 120s 与 CLI 单请求 60s（`MCP_TIMEOUT`）的关系（属另一类“工具结果被中断/置空”问题，非本次 `session expired`）。

## 超时链分析（遵循智能查询 timeout 规则）

将相关超时视为一条链，确认 keep-alive 的取值边界：

- 后端 agent run 总超时：交互 `agent_interactive_timeout_seconds = 360s`，后台 `agent_background_timeout_seconds = 1800s`。
- 后端 idle/进度超时：交互 `90s`，后台 `300s`（无新流/工具输出才判定停滞）。
- portal 只读 SQL：单次 `timeout_seconds` 默认 30s、上限 120s；运行期 env `agent_*_sql_read_timeout_seconds` 300s/900s。
- CLI→portal-mcp 为容器内直连，不经反向代理，反向代理超时与本跳无关。

要保证“单次交互 run 内 portal-mcp 连接不因 keep-alive 被断”，keep-alive 必须 ≥ 交互 run 总超时（360s）。因此默认取 **600s**：

- `> 360s` 交互总超时，覆盖整段交互 run（widget 即交互路径）。
- 有界（非无限），仍能在 10 分钟内回收真正失活的连接。
- 后台重场景可按需经环境变量上调（趋近 1800s）。

## Solution

最小化修复：保持原 uvicorn CLI 启动方式，仅在 Dockerfile CMD 中追加 `--timeout-keep-alive`，通过 `sh -c` 引用环境变量实现可配置。

1. `Dockerfile`：`CMD` 改为 `sh -c` 形式，追加 `--timeout-keep-alive ${PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS:-600}`。不改变 `config.py` 或 `app.py`，不引入 Python 侧启动入口。
2. deploy：`docker-compose.dev.yml`、`docker-compose.prod.yml` 的 `portal-mcp` 增加 `PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS` 环境变量；`deploy/.env.example` 增加说明与默认。

### Interfaces

- 新环境变量：`PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS`（int，秒，默认 600）。
- 启动方式不变，仍为 uvicorn CLI。

## 风险与回退

- 取值过小仍可能在深会话中复发；过大仅延长失活连接回收时间，对单客户端内部服务影响可忽略。
- 若上线后仍偶发 `session expired`，下一步排查方向：portal-mcp 进程因 OOM/崩溃触发 `restart: always` 重启导致主通道断开，或标准 GET SSE 通道在重连churn中被撕裂；可结合 portal-mcp 重启计数与 CLI MCP 调试日志定位。
- 回退：将 Dockerfile `CMD` 恢复为 `["uvicorn", "portal_mcp.app:app", "--host", "0.0.0.0", "--port", "8801"]`，移除 compose 和 `.env.example` 中的 `PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS` 即可。
