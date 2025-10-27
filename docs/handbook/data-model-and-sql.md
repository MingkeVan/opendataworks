# 数据模型与 SQL 策略

本节合并了原 `REQUIREMENTS_V2.md`、`schema.sql`、`sql-support-summary.md` 等文档，统一给出命名规范、表结构说明以及脚本入口。

## 数据库基线

- **库名**: `opendataworks`
- **编码**: `utf8mb4` / `utf8mb4_unicode_ci`
- **默认账号**: `opendataworks/opendataworks123`
- **脚本目录**: [`database/mysql`](../../database/mysql)

| 脚本 | 作用 | 自动执行 | 备注 |
| --- | --- | --- | --- |
| `00-bootstrap.sql` | 创建库/账号 | ✅ (Docker Compose) | 不再 `SOURCE` 其他脚本，顺序由文件名前缀控制 |
| `10-core-schema.sql` | 业务域、数据域、表/字段、任务、血缘、执行日志等核心表 | ✅ | 覆盖所有 Portal 核心表 |
| `20-inspection-schema.sql` | 巡检记录/规则/问题 | ✅ | 包含默认规则数据 |
| `30-sample-data.sql` | 演示表、字段、任务、血缘 | ✅ | 可重复执行（ON DUP KEY） |
| `addons/40-init-test-data.sql` | MySQL 模拟 Doris，生成批量数据、演示统计、跨库预览 | ❌（手动） | 在需要连通性/性能测试时执行 |

> 提示：原 `backend/src/main/resources/{schema,inspection,sample}.sql` 已移除，请统一从 `database/mysql` 读取。

## 命名规范

### 表命名

```
{分层}_{业务域}_{数据域}_{自定义标识}_{统计周期}_{更新类型}
```

示例：`dwd_tech_ops_cmp_performance_10m_di`

- **分层**: `ods` / `dwd` / `dim` / `dws` / `ads`
- **业务域** (organization view): 如 `tech`, `crm`, `trade`
- **数据域** (data subject view): 如 `ops`, `user`, `order`
- **自定义标识**: 业务含义 (`cmp_performance`)
- **统计周期**: `5m`, `30m`, `1d`, `realtime`
- **更新类型**: `di`(每日增量), `df`(每日全量), `hi`(小时增量) 等

### 业务域 & 数据域

- `business_domain` 代表组织/系统划分 (技术域、交易域...)
- `data_domain` 对应主题域 (用户域、订单域...)，与业务域多对一。
- `data_table` 中 `business_domain`、`data_domain` 字段引用以上字典，前端会提供选择器。

## 核心表速查

| 表 | 说明 | 重要字段 |
| --- | --- | --- |
| `business_domain` | 业务域字典 | `domain_code`, `domain_name`
| `data_domain` | 数据域字典 | `business_domain`, `domain_code`
| `doris_cluster` | Doris FE 连接元数据 | `fe_host`, `fe_port`, `is_default`
| `data_table` | 表元信息 | `layer`, `business_domain`, `data_domain`, `table_model`, `statistics_cycle`, `update_type`
| `data_field` | 字段定义 | `field_type`, `is_partition`, `is_primary`
| `data_task` | 调度任务 | `task_type`, `engine`, `schedule_cron`, `dolphin_*`, `dinky_job_id`
| `table_task_relation` | 表与任务的读/写关系 | `relation_type` (read/write)
| `data_lineage` | DAG 节点 | `lineage_type` (input/output)
| `data_query_history` | SQL 预览/查询历史 | `result_preview`
| `task_execution_log` | 执行记录 | `execution_id`, `trigger_type`, `rows_output`
| `inspection_record / issue / rule` | 巡检框架 | `rule_config` 为 JSON 存放链条逻辑 |
| `table_statistics_history` | Doris 统计快照 | 行数、分区、副本数等指标

## SQL 支持矩阵

| 能力 | 说明 | 入口 |
| --- | --- | --- |
| SQL 预览/执行 | Portal 保存查询历史，支持强制刷新、限制行数 | `/api/v1/query/history` |
| Doris DDL 生成 | 根据 `data_table` 属性生成 Doris 建表语句 | 前端“生成 Doris DDL” 按钮 |
| 任务 SQL 校验 | 新建任务时校验必填字段、调度策略、输入输出表 | 后端 `DataTaskService` 校验链 |
| 巡检校验 | 命名规范、Owner、注释、执行异常等规则 | `inspection_rule` 配置 |

## 变更策略

1. **修改核心 schema**：优先编辑 `database/mysql/10-core-schema.sql` 并同步新增 Flyway/V* 脚本，确保 CI/CD 可自动升级。
2. **新增巡检/示例数据**：修改相应脚本 (`20-`/`30-`)；若只影响已有环境，可新增增量 SQL 并在 README 中说明。
3. **测试数据**：需要大批量数据时执行 `addons/40-init-test-data.sql`，执行前确保 `opendataworks` 与 `doris_*` 库可写。
4. **文档同步**：任何字段命名变更请同步更新本文件与 [operations-guide.md](operations-guide.md) 中的数据库段落。
