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
  - 删除 `queryAvailableStart()`，`initializeCheckpoint()` 一律从当前安全上界开始
  - 新增 `queryAuditSourceNow()`，安全上界改用审计源时钟并记录时钟偏差
  - 新增时钟回拨保护：源时间早于已保存水位时保留游标并降级返回
- `backend/src/main/java/com/onedata/portal/service/audit/DorisAuditSqlTableParser.java`：系统库 deny-list、字符串与注释屏蔽（含转义引号）、CTE 名称排除
- `backend/src/main/java/com/onedata/portal/service/DorisTableAccessService.java`：聚合窗口收敛并对齐冷表阈值所在自然日
- `backend/src/main/java/com/onedata/portal/scheduled/DorisAuditAccessSyncTask.java`：清理任务无条件注册，同步入口加异常边界
- `backend/src/main/java/com/onedata/portal/entity/DorisAuditAccessCheckpoint.java`：`IdType.INPUT`
- `backend/src/main/java/com/onedata/portal/config/DorisAuditAccessSyncProperties.java`、`application.yml`、`deploy/.env.example`：删除 `initial-history-days`

### 验证

- 定向后端测试 27 项通过：`mvn -pl backend -am -Dtest=DorisAuditSqlTableParserTest,DorisAuditAccessSyncPropertiesTest,DorisTableAccessServiceTest,DorisAuditAccessBatchServiceTest,DorisAuditAccessSyncTaskTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 后端打包通过：`mvn -pl backend -am -DskipTests package`
- 新增用例：审计源与系统库被丢弃、同语句中的业务表保留、CTE 名称排除但保留其来源表与同名限定表、字符串与注释中的表名被忽略、首日覆盖不足时冷表沉默、关闭同步后清理仍执行、同步异常不逃逸到调度线程
- 新增用例：转义引号不吞掉真实表、冷表聚合起点对齐阈值自然日、`markFailure` 保留水位供下一轮续读
- 未执行：真实 Doris 集群上的增量消费与 `SELECT NOW()` 时钟校验，需在具备审计表的环境中补做

### 发布

不做存量 checkpoint 的自动升级推断。若旧版本已在生产运行过，发布时执行一次性清理，接受统计重新积累：

```sql
DELETE FROM doris_audit_access_checkpoint;
```

若旧版本尚未投入生产，无需任何额外操作。

### 回退

删除 `initial-history-days` 属于减法式变更，旧配置文件中残留该环境变量不会导致启动失败。无迁移，无需数据回退。

### 另行跟踪，不在本 PR 范围

- 逐事件 `INSERT IGNORE`：出现真实同步超时后再批量化
- `DEGRADED` 恢复不重扫 overlap 窗口
- 摄入期元数据表 allow-list
