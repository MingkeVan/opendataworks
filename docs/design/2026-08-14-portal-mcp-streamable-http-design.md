# Portal MCP Streamable HTTP 直连可靠性设计

## Background

DataAgent 通过 `claude-agent-sdk` 挂载远程 `portal-mcp`。原运行时锁定
`claude-agent-sdk==0.2.114`，其内置 Claude CLI `2.1.205`。现场曾出现
`MCP server "portal" session expired`；同时，`portal_query_readonly` 允许最长 120s，
而未显式配置的 HTTP MCP POST 会命中 60s 请求上限。

前续服务端措施保持不变：

- `portal-mcp` 使用 `stateless_http=True`，不下发 `Mcp-Session-Id`。
- uvicorn keepalive 默认 600s。
- MCP/uvicorn 顶层依赖锁定，避免镜像重建导致语义漂移。

## 官方客户端能力校正

Claude Code 后续版本已提供直接的 Streamable HTTP 解法：

- `2.1.142` 修复 `MCP_TOOL_TIMEOUT` 无法抬高 HTTP/SSE 单请求 60s 上限。
- `2.1.187` 增加独立的 MCP tool idle timeout，默认网络 transport 300s。
- `2.1.206` 修复 `--mcp-config` 新会话忽略 per-server request timeout。

因此，不再使用“CLI 视为 stdio，再由自建桥转 HTTP”的双 transport 方案。
桥会复制 JSON-RPC/SSE 帧处理、错误归一化、超时、取消和进程生命周期，
与官方客户端形成两套语义，长期成本高于直接升级。

## Scope

- 升级 `claude-agent-sdk` 到 `0.2.115`（内置 Claude CLI `2.1.206`）。
- portal MCP 只使用 Streamable HTTP 直连。
- 通过 ClaudeAgentOptions 的运行时 env 注入官方 MCP 超时。
- 删除 stdio bridge、bridge 测试、transport 开关与 bridge 专用配置。

不改 `portal-mcp` 工具 schema、鉴权头、数据范围头和权限门。

## Solution

### 单一拓扑

```text
Claude CLI -- Streamable HTTP --> portal-mcp:8801/mcp/
```

`_build_portal_mcp_servers` 只返回：

```python
{
    "portal": {
        "type": "http",
        "url": "http://portal-mcp:8801/mcp/",
        "headers": {
            "X-Portal-MCP-Token": "...",
            "X-Agent-Data-Scope": "...",
        },
    }
}
```

URL 继续强制带尾部 `/`，避免 Starlette mount 对 POST 返回重定向。

### CLI 运行时配置

DataAgent 新增 `dataagent_portal_mcp_tool_timeout_seconds=180`，运行时转换为：

```text
MCP_TOOL_TIMEOUT=180000
```

- 180s 覆盖 portal 工具 120s 契约上限，且小于交互 run 360s 总预算。

Python SDK `0.2.115` 的 `McpHttpServerConfig` TypedDict 仍未公开 per-server timeout 字段。
本方案使用官方文档化的 `MCP_TOOL_TIMEOUT`，不向 SDK TypedDict 塞未声明字段。

选择 `0.2.115` 而非直接跳到调研时的最新 `0.2.139`，是为了保持小步升级：
`0.2.115` 比原版本只高一个 patch，且刚好携带 CLI `2.1.206` 的 request-timeout 修复。
实测 SDK `0.2.139` 的 CLI `2.1.233` 在当前无 AVX 机器进入 MCP 命令时，
其 Bun 1.4 runtime 会因 `Illegal instruction` 崩溃，不适合未经生产 CPU 基线审计就直接引入。

### 超时链

- portal MCP tool：最长 120s。
- Claude CLI MCP wall-clock timeout：180s。
- Claude CLI network idle timeout：默认 300s，但会被有效 wall-clock timeout 封顶为 180s。
- DataAgent run：交互 360s，后台 1800s。
- `agent_*_idle_timeout_seconds` 目前只是配置项，task 执行路径尚未消费；当前有效的
  DataAgent 外层保护是 run 总超时。后续若接入 idle 判定，必须识别工具在途状态，或把
  交互 idle 设为大于 120s 的 portal 工具契约上限，不能恢复出一个更短的隐形上限。

## Error semantics

- MCP 端点的真实 401/403/404 是配置或鉴权错误，允许快速失败，不把它伪装成工具业务错误。
- portal 工具业务失败必须返回 HTTP 200 下的 MCP tool error / JSON-RPC error，
  不用 HTTP 404 表达“业务对象不存在”。
- `portal-mcp` 是无状态服务；连接中断时当前在途调用失败，后续调用通过新的 HTTP 请求
  恢复，不在 DataAgent 外层重跑整个 turn，避免写工具被重复执行。

## Verification gates

1. 单测锁定 HTTP config、鉴权/数据范围头、URL 归一化与 CLI env。
2. 使用升级后 CLI 直连真实 portal-mcp，验证 initialize/tools/list。
3. 延迟 65s（越过旧 60s 上限）和 130s（覆盖 120s 工具契约）的调用均正常返回。
4. portal-mcp 被杀死并重启后，同一 CLI 会话可继续调用。
5. 并行调用中一个失败时，其它在途调用不被级联取消。
6. 本地真实 NL2SQL 入口完成任务创建、事件流、终态和消息持久化。

## Rollout and backout

- 发布前必须重建 `dataagent-backend` 与 `dataagent-sandbox-runner`，确保两个镜像都包含
  SDK `0.2.115` 的内置 CLI。
- 回退以整个提交/镜像版本为单位，不保留运行时 transport 双开关。
- `portal-mcp` 无状态、keepalive 和依赖锁定不回退。

## References

- Claude Code environment variables: `MCP_TOOL_TIMEOUT`,
  `CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT`.
- Claude Code changelog: `2.1.142`, `2.1.187`, `2.1.206`.
- Claude Agent SDK Python release `0.2.115` (bundled Claude CLI `2.1.206`).
