# 工具配方（调用顺序与参数）

全部为 portal MCP 工具。`default` 权限模式下所有写工具都会触发对话内确认；`acceptEdits` 下草稿写操作自动执行，高危工具（`portal_create_table`、`portal_publish_workflow`、`portal_workflow_schedule_online`）仍触发确认；`bypassPermissions` 下写工具自动执行，但 deploy/online/schedule-online 仍必须提供后端 preview token。

## 1. 探查表与 SQL

```
portal_search_tables {database?, table?, keyword?, table_limit?}
portal_get_table_ddl {database, table}   # 或 {table_id}
portal_analyze_sql   {sql, database?, cluster_id?}
  -> 返回输入/输出表、操作类型、风险告警
```

## 2. 建表（新目标表）

建表 DDL 规范见 `reference/40-ddl-standards.md`，默认值见 `assets/engine-ddl-rules.json`。先预览、核对，再执行。表名由分层等组件生成，不用手填 `tableName`。

```
# 需要目标 Doris 集群/数据源时，用 portal_resolve_datasource 得到 cluster_id
portal_preview_create_table {
  db_name, layer, business_domain?, data_domain?, custom_identifier?,
  statistics_cycle?, update_type?, table_comment?,
  table_model?:"DUPLICATE",            # 明细=DUPLICATE(默认)/去重=UNIQUE/聚合=AGGREGATE
  key_columns?:[...], distribution_columns?:[...], bucket_num?:10, replica_num?:3,
  partition_column?,
  columns:[ { column_name, data_type, type_params?, nullable?, primary_key?,
              partition_column?, default_value?, comment? }, ... ]
}
  -> 返回生成的表名 + 规范化 DDL；把表名/DDL 展示给用户核对

# 用户确认后执行(高危,批准即建表)。执行必须带 doris_cluster_id。
portal_create_table {
  <同上字段>, doris_cluster_id,
  title?:"新建表 <层>_<主题>", summary?:"<DDL 摘要/字段概览>"
}
```

高级:可直接传 `doris_ddl` 提供完整 Doris CREATE TABLE(表名仍以生成结果为准,避免名称与 DDL 不一致)。建成后进入建任务环节维护血缘。

## 3. 创建任务（draft）

```
portal_create_task {
  task: { taskName, taskType:"batch", engine:"dolphin",
          dolphinNodeType:"SQL", taskSql, datasourceName, datasourceType,
          taskDesc, status:"draft" },
  input_table_ids:  [<来自 analyze>],
  output_table_ids: [<来自 analyze>]
}
```
更新：`portal_update_task {task_id, task, input_table_ids, output_table_ids}`（仅 draft）。

## 4. 组装工作流（draft）

```
portal_create_workflow { workflow: { workflowName, tasks:[...], edges:[...], globalParams? } }
portal_update_workflow { workflow_id, workflow: {...} }
```
绑定后向用户复述 DAG，确认后进入发布。

## 5. 发布与上线（强制顺序）

```
portal_preview_publish { workflow_id }
  -> 展示 diffSummary / errors / warnings；有 error 则停止
  -> 取得 preview_token

# 把操作目标与差异摘要放进 title/summary，便于确认卡片展示
portal_publish_workflow {
  workflow_id, operation:"deploy", preview_token,
  title?:"发布工作流 #<id>", summary?:"<diff 摘要>"
}
# 成功后
portal_publish_workflow { workflow_id, operation:"online", preview_token }
```
下线：`portal_publish_workflow { workflow_id, operation:"offline" }`。

## 6. 调度

```
portal_upsert_schedule { workflow_id, schedule: { scheduleCron, scheduleTimezone, ... } }
portal_workflow_schedule_online  { workflow_id, preview_token }   # 高危
portal_workflow_schedule_offline { workflow_id }
```

## 失败恢复

- `portal_create_table` 失败 → 读取报错(表已存在/字段或类型非法/集群不可达);回 `40-ddl-standards.md` 修正后重新预览,不要盲目重试同一请求。
- `portal_publish_workflow` 失败 → 读取返回报错；结构问题回 draft 改后重走 preview；引擎问题提示检查 DolphinScheduler 配置。
- preview 的 `errors` 非空 → 不发布，逐条转成修复建议。
- preview_token 过期或工作流版本已变 → 重新 `portal_preview_publish` 取新 token。
