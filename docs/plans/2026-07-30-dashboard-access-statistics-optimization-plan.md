# Dashboard 访问统计优化实施计划

相关设计：[Dashboard 访问统计优化设计](../design/2026-07-30-dashboard-access-statistics-optimization-design.md)

## 任务

1. 新增同步配置、四张 MySQL 表及对应实体/Mapper。
2. 拆分审计源读取、SQL 表引用解析、批次事务汇总和 checkpoint 管理。
3. 新增默认每 10 分钟运行、可配置关闭的同步与每日清理任务。
4. 将 Dashboard 和单表访问统计改为读取按天汇总，并增加同步/覆盖状态。
5. 合并 Dashboard 执行日志与巡检条件聚合查询。
6. 更新前端状态提示及部署环境变量示例。
7. 补充解析、去重、覆盖状态、关闭配置、Mapper/API 和前端回归测试。

## 主要文件

- 后端：`backend/src/main/java/com/onedata/portal/` 下的配置、实体、Mapper、Service、scheduled 与 DTO。
- 数据库：`backend/src/main/resources/db/migration/V48__dashboard_access_statistics_summary.sql`。
- 前端：Dashboard 和 Data Studio 访问统计面板。
- 部署：`deploy/.env.example`。

## 验证

- 运行访问统计相关后端单元测试和 Maven 编译。
- 使用项目 `.nvmrc` 后运行最小前端测试与构建。
- 环境允许时通过真实 MySQL、Doris、后端和前端执行读写 SQL，验证同步、幂等、状态与页面。
- 验证两个 HTTP 请求路径不再执行 Doris 审计明细查询。

## 发布与回退

- Flyway 加法迁移与应用代码同版本发布。
- 回填期间使用 `BACKFILLING` 和覆盖字段提示，不启用原始扫描回退。
- 可通过 `DORIS_AUDIT_ACCESS_SYNC_ENABLED=false` 停止同步；已有汇总保留。
- 旧版本回退时忽略新增表，不执行破坏性数据库回滚。
