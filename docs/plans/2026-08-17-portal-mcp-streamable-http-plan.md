# Portal MCP Streamable HTTP 直连可靠性计划

配套设计：`docs/design/2026-08-17-portal-mcp-streamable-http-design.md`

## 受影响栈

- DataAgent backend / sandbox runner 的 Claude Agent SDK 与 CLI 运行时。
- portal MCP 动态配置和超时链。
- deploy 环境模板与两个 DataAgent 容器。

## 任务

1. 升级官方客户端
   - `requirements.txt`: `claude-agent-sdk==0.2.115`。
   - 同步依赖锁定测试。
2. 收敛为单一 Streamable HTTP
   - `_build_portal_mcp_servers` 只返回 `type=http`。
   - 保留 URL 尾斜杠、token 与 `X-Agent-Data-Scope`。
   - 删除 `portal_mcp_stdio_bridge.py` 及其测试。
   - 删除 transport 开关和 bridge request timeout 配置。
   - 恢复旧设计/计划文档并标记 superseded，只保留取证历史。
3. 注入官方 CLI 配置
   - 新增 `dataagent_portal_mcp_tool_timeout_seconds=180`。
   - `_build_runtime_env` 输出 `MCP_TOOL_TIMEOUT=180000`。
4. 部署契约
   - `.env.example` 只公开 `DATAAGENT_PORTAL_MCP_TOOL_TIMEOUT_SECONDS=180`。
   - compose 同时注入 backend 和 sandbox runner，确保直连配置一致。
5. 测试
   - 更新 `test_agent_runtime.py` 与 `test_task_executor.py`。
   - 删除 bridge 测试。
   - 运行 DataAgent 针对性 pytest 和 portal-mcp pytest。
   - 用升级后 CLI 运行 HTTP 延迟、重启、并行故障探针。
   - 环境可用时跑一次真实 NL2SQL 全链路冒烟。

## 验证通过标准

- 代码与部署文件不再出现 bridge/transport 双路径。
- HTTP 配置持续包含认证和数据范围头。
- 65s/130s 工具调用不在旧 60s 上限失败。
- server 重启和并行单请求失败不造成会话级挂死。
- 所有带 id 的调用都有终态；不出现无声等待。

## 回退

回退本次整个提交并重建 backend/runner 镜像。不保留 stdio bridge 代码或
运行时 transport 开关。`portal-mcp` 服务端的无状态、keepalive 与版本锁定继续保留。

## 验证记录（2026-08-17）

已通过：

- SDK `0.2.115` 下 backend 测试 `435 passed`（排除本地既有的 6 个
  `pymysql.cursors` 收集失败模块），portal-mcp 测试 `30 passed`。
- 使用 SDK `0.2.115` 所带原生 Claude CLI `2.1.206`，直连本地真实 FastMCP
  Streamable HTTP 端点；65s 与 130s 工具调用分别在约 69.6s、135.6s 正常结束，
  均返回 `probe-ok`，CLI 退出码为 0。模型 API 由本地 SSE stub 提供，未调用外部模型。
- 同一 CLI 会话先调用 portal 工具成功，再杀死并重建 FastMCP 进程；重启前后的
  `marker` 均产生 `TOOL_COMPLETED`，最终返回 `restart-ok`，退出码为 0。
- 两个 HTTP MCP server 均完成初始化后，关闭其中一个并并发调用：健康 server 的 5s
  工具继续完成，关闭端口的调用产生独立 `is_error` tool result；模型收到两条结果并返回
  `parallel-ok:2`，退出码为 0，未发生级联取消。
- dev/prod compose 文件通过 YAML 解析，并确认 backend 与 sandbox runner 均注入
  `DATAAGENT_PORTAL_MCP_TOOL_TIMEOUT_SECONDS`。
- Python 编译检查、`git diff --check` 与 runtime/deploy 旧 bridge/transport 配置扫描通过；
  superseded 文档中的历史引用按预期保留。

未完成：

- 使用真实 provider 的 NL2SQL 全链路冒烟。
- 当前主机没有 Docker CLI，未运行 `docker compose config`；已用 YAML 解析代替语法检查。
