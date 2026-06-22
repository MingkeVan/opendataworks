# 数据表数据源类型判定执行计划

- 日期: 2026-06-22
- 关联设计: `docs/design/2026-06-22-data-table-source-type-design.md`
- 影响栈: 后端（Java · Spring Boot 2.7 · MyBatis-Plus）
- 原则: 明确表对象类型、数据源引擎、物理同步状态的边界；最小化 PR #376 修复面。

## T1 — 文档化语义

- 目标: 记录 `table_type`、`source_type`、`is_synced` 的职责边界和新建表语义。
- 触及文件:
  - `docs/design/2026-06-22-data-table-source-type-design.md`
  - `docs/plans/2026-06-22-data-table-source-type-plan.md`
- 验证: 文件命名、路径和交叉链接符合仓库规则。

## T2 — 数据源类型归一化

- 目标: 新增轻量 `DatasourceType`，集中处理 `DORIS/MYSQL/OCEANBASE/UNKNOWN` 归一化，避免业务代码散落字符串判断。
- 触及文件:
  - 新增 `backend/src/main/java/com/onedata/portal/util/DatasourceType.java`
- 验证:
  - 编译通过。

## T3 — 收敛 DataTableService 判定

- 目标: 删除 `replica_num/table_model/bucket_num` 等 Doris 属性推断逻辑，按 `is_synced + actual cluster source_type` 判断是否需要 Doris DDL。
- 触及文件:
  - `backend/src/main/java/com/onedata/portal/service/DataTableService.java`
- 做法:
  - 新增 `requiresDorisPhysicalSync(DataTable, Long)`。
  - 请求参数 `clusterId` 优先，表上的 `cluster_id` 兜底。
  - 未同步表仅改本地元数据。
  - 已同步 Doris 表执行 Doris DDL。
  - 已同步非 Doris 表明确拒绝。
- 验证:
  - 新增/更新服务单测。

## T4 — 自动清理任务委托统一判定

- 目标: `DataTableAutoPurgeTask` 不再复制旧 `isDorisTable` 启发式逻辑。
- 触及文件:
  - `backend/src/main/java/com/onedata/portal/scheduled/DataTableAutoPurgeTask.java`
- 验证:
  - 服务单测覆盖判定；编译确认任务调用签名正确。

## T5 — 单元测试

- 目标: 锁定修复场景和未来非 Doris 扩展行为。
- 触及文件:
  - 新增 `backend/src/test/java/com/onedata/portal/service/DataTableServiceTest.java`
- 覆盖:
  - 未同步表带默认 `replicaNum=1` 时新增字段只写元数据。
  - 已同步 Doris 表即使请求未传 `clusterId`，也使用表所属数据源执行 Doris DDL。
  - 已同步 MySQL 表结构变更返回明确不支持，不调用 Doris DDL、不写入本地字段。

## T6 — 验证

- 目标: 证明 PR #376 原有重构测试仍通过，并补一轮生命周期 smoke。
- 命令:
  - `mvn -pl backend -am -DfailIfNoTests=false -Dtest=DataTableServiceTest,DataTableControllerTest,DataTableMetadataSyncServiceTest test`
  - `mvn -pl backend -am -DskipTests compile`
- 可选有状态验证:
  - 使用本地 Podman MySQL/Redis 启动 backend。
  - 创建普通元数据表后直接走字段 CRUD、软删除、恢复、清理，不再执行手工 SQL 清空 `replica_num`。

### 2026-06-22 本地验证结果

- 单元/控制器测试:
  - `mvn -pl backend -am -DfailIfNoTests=false -Dtest=DataTableServiceTest test`
    - 结果: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`
  - `mvn -pl backend -am -DfailIfNoTests=false -Dtest=DataTableServiceTest,DataTableControllerTest,DataTableMetadataSyncServiceTest test`
    - 结果: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`
- 编译/打包:
  - `mvn -pl backend -am -DskipTests compile` 通过。
  - `mvn -pl backend -am -DskipTests package` 通过。
- 有状态 smoke:
  - MySQL: Podman 容器 `data-portal-mysql`，`127.0.0.1:3306`，临时 schema `opendataworks_pr376_type_e2e`。
  - Backend: `SERVER_PORT=18080`，`AUTH_ANONYMOUS_ENABLED=true`，临时 schema 上 Flyway 成功应用 45 个迁移。
  - HTTP 流程:
    - `POST /api/v1/tables` 创建普通 metadata-only 表。
    - 直接查询 DB 确认新表为 `replica_num=1`、`is_synced=0`、`cluster_id=NULL`。
    - 不传 `clusterId` 完成字段新增、字段更新、字段删除、表注释更新、软删除、恢复、再次软删除、立即清理。
    - 清理后确认 `data_table.deleted=1`。
  - 结论: metadata-only 表即使保留默认 `replica_num=1`，也不再误触发 Doris DDL/cluster 校验。
  - 清理: 已停止临时 backend，并删除 `opendataworks_pr376_type_e2e` schema。

## 回滚

- 回退本计划对应提交即可恢复旧启发式判定。
- 文档、枚举、服务判定和测试均为单次收敛改动，不涉及 schema 和对外 REST 签名变更。
