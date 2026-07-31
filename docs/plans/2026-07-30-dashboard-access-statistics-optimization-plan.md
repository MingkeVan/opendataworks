# Dashboard 访问统计优化实施计划

相关设计：[Dashboard 访问统计优化设计](../design/2026-07-30-dashboard-access-statistics-optimization-design.md)

## 任务

1. 新增同步配置、四张 MySQL 表及对应实体/Mapper。
2. 拆分审计源读取、SQL 表引用解析、批次事务汇总和 checkpoint 管理。
3. 新增默认每 10 分钟运行的增量同步任务，以及不受同步开关影响的每日清理任务。
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

已执行的定向后端测试（27 项通过）：

```
mvn -pl backend -am -Dtest=DorisAuditSqlTableParserTest,DorisAuditAccessSyncPropertiesTest,\
DorisTableAccessServiceTest,DorisAuditAccessBatchServiceTest,DorisAuditAccessSyncTaskTest \
-Dsurefire.failIfNoSpecifiedTests=false test
```

后端打包通过：`mvn -pl backend -am -DskipTests package`。

尚未执行：真实 Doris 集群上的增量消费与 `SELECT NOW()` 时钟校验，需在具备审计表的环境中补做。

## 发布与回退

- Flyway 加法迁移与应用代码同版本发布。
- 覆盖不足期间使用状态与覆盖字段提示，不启用原始扫描回退。
- 可通过 `DORIS_AUDIT_ACCESS_SYNC_ENABLED=false` 停止同步；已有汇总保留，清理任务继续运行。
- 旧版本回退时忽略新增表，不执行破坏性数据库回滚。

不做存量 checkpoint 的自动升级推断。若旧版本已在生产运行过，需在**旧调度停止后、新版本启动前**执行一次性清理，接受统计重新积累：

```sql
DELETE FROM doris_audit_access_checkpoint;
```

顺序很重要：旧调度仍在运行时清理，会被旧逻辑立即重新写入一个按 90 天回填的 checkpoint。若旧版本尚未投入生产，无需任何额外操作。

## 另行跟踪，不在本次范围

- 逐事件 `INSERT IGNORE` 批量化：出现真实同步超时后再处理。
- 从 `DEGRADED` 恢复时重扫 overlap 窗口。
- 摄入期元数据表 allow-list。
