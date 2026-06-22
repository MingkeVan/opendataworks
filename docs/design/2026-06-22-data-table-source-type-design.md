# 数据表数据源类型判定设计

- 日期: 2026-06-22
- 背景: PR #376 本地端到端验证发现，新建元数据表因 `data_table.replica_num` 默认值被既有 `isDorisTable` 误判为 Doris 表。
- 影响栈: 后端（Java · Spring Boot 2.7 · MyBatis-Plus）。不涉及前端、DataAgent、部署。
- 性质: 行为修正 + 语义收敛。

## 1. 现状

`data_table` 当前同时保存三类信息:

- SQL 对象类型: `table_type`，现有语义为 `BASE TABLE` / `VIEW` / `MATERIALIZED VIEW`。
- 数据源归属: `cluster_id` 指向 `doris_cluster`；该表虽仍命名为 Doris cluster，但 V16 已扩展 `source_type`，表示 `DORIS` / `MYSQL` 等数据源类型。
- Doris 建表属性: `table_model`、`bucket_num`、`replica_num`、`distribution_column`、`key_columns`、`partition_column`、`doris_ddl`。

`DataTableService` 和 `DataTableAutoPurgeTask` 仍使用 `isDorisTable` 启发式判断物理 Doris 表。该判断把 `is_synced=1` 或任意 Doris 属性存在都视为 Doris 表，包括 `replica_num > 0`。

由于 `V1__init.sql` 中 `replica_num INT DEFAULT 1`，普通新建元数据表会天然带上 `replica_num=1`，从而在字段增删改、软删除、恢复、清理等路径误入 Doris DDL 同步逻辑。

## 2. 问题

- `replica_num` 是 Doris 建表参数，不是表所属引擎或物理同步状态。
- `table_type` 已用于 SQL 对象类型，不能复用为 `DORIS/MYSQL/OCEANBASE` 这类引擎类型。
- `is_synced=1` 只表示本地元数据对应外部物理对象，不表示该对象一定是 Doris。
- 后续引入 OceanBase 等数据源时，继续用 Doris 属性推断会让非 Doris 表误走 Doris DDL API。

## 3. 目标语义

数据表相关判断拆成三件事:

| 概念 | 字段/来源 | 示例 | 用途 |
|------|-----------|------|------|
| SQL 对象类型 | `data_table.table_type` | `BASE TABLE` / `VIEW` | 区分表、视图、物化视图 |
| 数据源引擎 | `data_table.cluster_id -> doris_cluster.source_type` | `DORIS` / `MYSQL` / 后续 `OCEANBASE` | 判断由哪个物理连接/handler 处理 |
| 物理同步状态 | `data_table.is_synced` | `0` / `1` | 判断本地变更是否需要同步到外部物理对象 |

因此，Doris DDL 同步条件为:

```text
is_synced = 1
AND actual_cluster_id is present
AND datasource.source_type = DORIS
```

其中 `actual_cluster_id` 使用请求参数优先、表记录 `cluster_id` 兜底，保留既有 API 支持调用方显式指定数据源的能力。

## 4. 新建表语义

- 普通元数据表:
  - `is_synced=0`
  - `cluster_id` 可为空，也可指向一个设计目标数据源
  - `table_type='BASE TABLE'`
  - 即使 `replica_num` 有默认值，也只作为属性保存，不触发物理 DDL。
- Doris 表设计器创建并同步:
  - 校验目标数据源 `source_type=DORIS`
  - 写入 `cluster_id`
  - Doris DDL 执行成功后设置 `is_synced=1`
  - 后续字段/生命周期变更按 Doris DDL 路径处理。
- Doris 表设计器只保存草稿:
  - 写入 Doris 设计属性和 DDL
  - `is_synced=0`
  - 后续变更只改本地元数据，不执行 Doris DDL。
- 元数据同步导入:
  - 同步服务根据数据源写入 `cluster_id`、`table_type`、`is_synced=1`
  - 数据源引擎永远从 `doris_cluster.source_type` 解析。

## 5. 方案

### 5.1 引入数据源类型归一化

新增轻量枚举 `DatasourceType`，集中归一化 `source_type` 字符串:

- `DORIS`
- `MYSQL`
- `OCEANBASE`
- `UNKNOWN`

本轮不扩大 `DorisClusterService` 当前支持范围；未知或未来类型仅用于避免误走 Doris DDL。

### 5.2 收敛 Doris DDL 判定

在 `DataTableService` 内新增统一判定:

- `requiresDorisPhysicalSync(table, clusterId)`:
  - 未同步表直接返回 `false`
  - 已同步表必须解析实际数据源
  - `source_type=DORIS` 返回 `true`
  - 非 Doris 类型抛出明确“不支持同步该数据源表变更”的异常，避免静默只改本地导致物理库不一致

所有表结构和生命周期写路径使用该判定:

- `updateTable`
- `updateTableComment`
- `softDeleteTable`
- `restoreTable`
- `purgeTableNow`
- `createField`
- `updateField`
- `deleteField`

### 5.3 自动清理任务

`DataTableAutoPurgeTask` 不再复制 `isDorisTable` 启发式逻辑。它委托 `DataTableService.requiresDorisPhysicalSync(table)` 判断是否需要 Doris 物理删除:

- 未同步表: 只清理元数据。
- 已同步 Doris 表: 先 drop Doris 表，再清理元数据。
- 已同步非 Doris 表或缺失数据源: 抛出明确异常并保留记录，等待后续对应 handler。

## 6. 不做范围

- 不重命名 `doris_cluster` 表；这是更大规模的数据源模型重命名。
- 不新增 OceanBase 物理 DDL handler。
- 不把 Doris 建表字段迁移到独立扩展表。
- 不修改 `replica_num` schema 默认值；误判根因由判定逻辑修复，后续如需清理历史数据可单独做数据修正。

## 7. 风险与权衡

- 已同步但 `cluster_id` 缺失的历史记录将不再凭 `is_synced=1` 直接走 Doris DDL；调用方需要传入 `clusterId`，或先修复表归属数据。
- 已同步非 Doris 表的结构/生命周期写操作会从“误调用 Doris API”变为明确拒绝。这是行为修正，但可能暴露以前被错误路径掩盖的数据源类型问题。
- 本轮保持 `source_type` 字符串模型，不引入跨模块 datasource 抽象，避免扩大 PR #376 的 refactor 面。

## 8. 验证

- 新增 `DataTableServiceTest` 覆盖:
  - `replicaNum=1` 的未同步表新增字段不要求 Doris 集群、不调用 Doris DDL。
  - 已同步 Doris 表使用表所属 `cluster_id` 执行 Doris DDL。
  - 已同步 MySQL 表拒绝 Doris DDL 路径。
- 运行 PR #376 既有目标测试。
- 如本地 MySQL/Redis 可用，重复表生命周期 HTTP smoke，确认不再需要手工清空 `replica_num`。
