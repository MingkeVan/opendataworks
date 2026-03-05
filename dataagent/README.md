# dataagent

统一后的 DataAgent 目录：

- `dataagent-backend`：Python/FastAPI 服务（原 `nl2sql-service`）
- `dataagent-web`：前端组件库（原 `dataagent-front`）

说明：

- 原 `frontend` 内 NL2SQL 页面已移除，NL2SQL 能力统一收敛到 `dataagent-web`。
- 原 Java `dataagent-backend` 模块已删除。
