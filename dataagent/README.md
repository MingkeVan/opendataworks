# dataagent

统一后的 DataAgent 目录：

- `dataagent-backend`：Python/FastAPI 服务（原 `nl2sql-service`）
- `.claude/`：DataAgent 运行时配置与 Skills 目录

说明：

- 主应用 `frontend` 已统一承载智能问数页面，入口为 `/intelligent-query`。
- 原 Java `dataagent-backend` 模块已删除。
- `dataagent/.claude/skills/dataagent-nl2sql` 现在只保留方法论、规则和语义映射等静态文件。
- 表元数据、血缘、数据源的动态查询示例放在 skill 的 `references/` 和 `scripts/` 中，不再同步成大块 JSON 快照。
- `dataagent-backend` 的表结构现在由 Alembic 管理；启动前需对 `SESSION_MYSQL_DATABASE` 执行 `alembic upgrade head`。
