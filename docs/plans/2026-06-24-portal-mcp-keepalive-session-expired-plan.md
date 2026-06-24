# Portal MCP Keep-Alive / Session Expired 计划

配套设计：`docs/design/2026-06-24-portal-mcp-keepalive-session-expired-design.md`

## 受影响栈

- DataAgent / portal-mcp（Python，FastMCP + uvicorn）
- 部署（deploy compose、`.env.example`、Dockerfile）

## 执行步骤

1. `dataagent/portal-mcp/Dockerfile`
   - `CMD` 改为 `sh -c` 形式，追加 `--timeout-keep-alive ${PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS:-600}`
   - 不修改 `config.py` 或 `app.py`
2. `deploy/docker-compose.dev.yml`、`deploy/docker-compose.prod.yml`
   - `portal-mcp.environment` 增加 `PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS: ${PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS:-600}`
3. `deploy/.env.example`
   - 增加 `PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS=600` 及说明

## 验证

- portal-mcp 单元测试：`pytest dataagent/portal-mcp/tests -q`
- 复现脚本（本地）：默认 keep-alive 空闲 7s 断连；设 600 后空闲 7s/30s 复用成功
- 文档：设计/计划同 slug、目录与命名符合规范

## 回退

- 还原 `Dockerfile` 的 `CMD` 为 `["uvicorn", "portal_mcp.app:app", "--host", "0.0.0.0", "--port", "8801"]`
- 移除 compose 和 `.env.example` 中的 `PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS`

## 备注

- 默认 600s 的取值依据见设计文档“超时链分析”（≥ 交互 run 总超时 360s）。
- 本变更不触碰 CLI、权限门与技能链路；CLI→portal-mcp 为容器内直连，不涉及反向代理超时。
