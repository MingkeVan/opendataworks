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

## 后续修正执行记录（2026-07-31）

评审后的最小修正，不新增表、不新增 Flyway 迁移、不改动接口与前端。

### 改动文件

- `backend/src/main/java/com/onedata/portal/service/audit/DorisAuditAccessSyncService.java`
  - 删除 `queryAvailableStart()`，`initializeCheckpoint()` 改为增量起点并在已有汇总时沿用最早日期
  - 新增 `queryAuditSourceNow()`，安全上界改用审计源时钟并记录时钟偏差
- `backend/src/main/java/com/onedata/portal/service/audit/DorisAuditSqlTableParser.java`：系统库 deny-list
- `backend/src/main/java/com/onedata/portal/service/DorisTableAccessService.java`：Dashboard 聚合窗口收敛为 `max(hotDays, coldDays)`
- `backend/src/main/java/com/onedata/portal/mapper/TableAccessDailyMapper.java`：新增 `selectEarliestAccessDate`
- `backend/src/main/java/com/onedata/portal/scheduled/DorisAuditAccessSyncTask.java`：清理任务无条件注册，同步入口加异常边界
- `backend/src/main/java/com/onedata/portal/entity/DorisAuditAccessCheckpoint.java`：`IdType.INPUT`
- `backend/src/main/java/com/onedata/portal/config/DorisAuditAccessSyncProperties.java`、`application.yml`、`deploy/.env.example`：删除 `initial-history-days`

### 验证

- 定向后端测试 21 项通过：`mvn -pl backend -am -Dtest=DorisAuditSqlTableParserTest,DorisAuditAccessSyncPropertiesTest,DorisTableAccessServiceTest,DorisAuditAccessBatchServiceTest,DorisAuditAccessSyncTaskTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 后端打包通过：`mvn -pl backend -am -DskipTests package`
- 新增用例：审计源与系统库被丢弃、同语句中的业务表保留、首日覆盖不足时冷表沉默、关闭同步后清理仍执行、同步异常不逃逸到调度线程
- 未执行：真实 Doris 集群上的增量消费与 `SELECT NOW()` 时钟校验，需在具备审计表的环境中补做

### 回退

删除 `initial-history-days` 属于减法式变更，旧配置文件中残留该环境变量不会导致启动失败。无迁移，无需数据回退。
