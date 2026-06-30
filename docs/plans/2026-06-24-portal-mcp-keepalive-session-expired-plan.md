# Portal MCP Keep-Alive / Session Expired 计划

配套设计：`docs/design/2026-06-24-portal-mcp-keepalive-session-expired-design.md`

## 受影响栈

- DataAgent / portal-mcp（Python，FastMCP + uvicorn）
- 部署（deploy compose、`.env.example`、Dockerfile）

## 阶段一（2026-06-24，已完成）：keepalive

1. `dataagent/portal-mcp/Dockerfile`：`CMD` 改 `sh -c`，追加 `--timeout-keep-alive ${PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS:-600}`。
2. `deploy/docker-compose.dev.yml`、`prod.yml`：`portal-mcp.environment` 增加 `PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS`。
3. `deploy/.env.example`：增加 `PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS=600` 及说明。

> 该阶段只解决 uvicorn idle close 子情况。现场在 keepalive 已生效、小 topic、短间隔下仍复发，触发下面阶段二的根因校正与范围收窄（详见设计文档「更新（2026-06-30）」）。

## 阶段二（2026-06-30，本次）：无状态固化 + 版本钉死 + 文档更正

范围决策：不在 dataagent-backend 做外层 turn retry（属 Claude CLI 客户端边界，且会扩散到 stream 持久化/前端/副作用判断）。只做低风险主线。

1. `dataagent/portal-mcp/requirements.txt`
   - `mcp[cli]`、`uvicorn[standard]` 从 `>=` 钉到具体版本（本地验证 `mcp==1.28.1`、`uvicorn==0.49.0`，非从目标镜像实测；仅 pin 顶层，transitive 仍可变，非完全可复现构建）。
2. `dataagent/portal-mcp/tests/test_app.py`
   - 新增 `test_build_mcp_server_is_stateless`：断言 `build_mcp_server(PortalToolService(FakeBackendClient())).settings.stateless_http is True`。
   - 在 `test_mcp_path_accepts_docker_hostname` 的 `initialize` POST 补 `Accept: application/json, text/event-stream`（使其 200），并断言 `"mcp-session-id" not in response.headers`。
3. `deploy/.env.example`
   - keepalive 注释补充：适用边界（仅 idle close）、`latest` 浮动 tag 升级需 `docker compose pull`、OOM churn 排查命令。
4. `docs/design/2026-06-24-portal-mcp-keepalive-session-expired-design.md`
   - 追加「更新（2026-06-30）」：根因校正、范围收窄、明确不做外层 turn retry。
5. 不做：`Dockerfile` 启动方式、`config.py`/`app.py` 逻辑、`dataagent-backend`（task_executor / sdk_block_writer / accumulator / permission gate）、前端。

## 验证

- portal-mcp 单元测试：`pytest dataagent/portal-mcp/tests -q`（含 stateless 与无 `mcp-session-id` 头断言）。
- 阶段一复现脚本（本地）：默认 keep-alive 空闲 7s 断连；设 600 后空闲 7s/30s 复用成功。
- DataAgent 后端本次无代码改动，仅 docs/static 检查（同 slug 设计/计划目录与命名一致）。
- 端到端 smoke（可选）：同一 topic 连续两轮真实 NL2SQL，观察是否仍复发；若复发，据此立「外层重试/客户端层」后续设计项。

## 回退

- 阶段二：`requirements.txt` 版本回 `>=`；`test_app.py` 新断言、docs/.env 注释为低风险项，可独立保留或回退。
- 阶段一：还原 `Dockerfile` 的 `CMD` 为 `["uvicorn", "portal_mcp.app:app", "--host", "0.0.0.0", "--port", "8801"]`，移除 compose/.env 中的 `PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS`。

## 备注

- 默认 600s 取值依据见设计文档「超时链分析」（≥ 交互 run 总超时 360s）。
- 真正的「丢弃失效 session→新握手→重试」属 Claude CLI 客户端边界，本次不做外层重跑；复发再单独设计。
- 参考：`anthropics/claude-code#27142`、`openai/codex#13969`、`danny-avila/LibreChat#11868`、`Doist/todoist-ai#304`、`encode/httpx#2056`、modelcontextprotocol.io — Transports。
