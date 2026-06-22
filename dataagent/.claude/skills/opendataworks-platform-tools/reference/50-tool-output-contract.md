# 工具输出契约

先结论：结果表达统一走工具输出。`sql_validation` 负责 SQL 执行前门禁，`sql_execution` 负责结果明细，`chart_spec` 负责前端渲染所需的严格图表契约。

## 输出种类

- `metadata_snapshot`
- `datasource_resolution`
- `table_ddl`
- `sql_validation`
- `sql_execution`
- `python_execution`
- `chart_spec`

## SQL 验证

`sql_validation` 示例：

```json
{
  "kind": "sql_validation",
  "valid": true,
  "errors": [],
  "warnings": [],
  "passed": ["只读 SQL 检查通过", "表名 schema 前缀检查通过"],
  "error_code": null,
  "failure_attribution": [],
  "retryable": false,
  "stop_reason": "",
  "ontology": "<ontology-path-from-caller>"
}
```

验证失败时，必须修正 SQL 后再进入 `run_sql.py`；不要绕过 `sql_validation` 直接执行。

## SQL 执行

`sql_execution` 示例：

```json
{
  "kind": "sql_execution",
  "tool_label": "SQL 执行",
  "engine": "mysql",
  "database": "example_schema",
  "sql": "SELECT category, COUNT(*) AS row_count FROM example_schema.example_table GROUP BY category LIMIT 20",
  "columns": ["category", "row_count"],
  "rows": [{"category": "A", "row_count": 3}],
  "row_count": 1,
  "has_more": false,
  "truncated_by_size": false,
  "notice": null,
  "duration_ms": 120,
  "summary": "返回分组统计结果",
  "result_state": "success",
  "error_code": null,
  "failure_attribution": [],
  "retryable": false,
  "stop_reason": "",
  "error": null
}
```

`sql_execution` 必须提供这些收口字段：

- `result_state`: `success`、`empty_result` 或 `failed`
- `error_code`: 成功时为 `null`；空结果为 `empty_result`；按体积截断为 `result_truncated`；失败时为 `permission_denied`、`datasource_mismatch`、`unknown_table`、`unknown_column`、`tool_timeout`、`non_readonly_sql` 或 `query_failed`
- `failure_attribution`: 用于报告归因，例如 `empty_result`、`permission_denied`、`schema_mismatch`、`datasource_mismatch`、`tool_timeout`、`invalid_sql`
- `retryable`: 当前 agent 是否应该继续重试
- `stop_reason`: 失败、空结果或按体积截断时给模型的中文收口理由

结果体积守卫：

- 后端 `/v1/ai/query/read` 在源头按字节预算（默认 512KB）截断返回行，避免单条工具结果撑爆运行时 JSON 缓冲。
- 截断时返回 `truncated_by_size=true`、`has_more=true` 与中文 `notice`/`stop_reason`。
- 收到截断信号应缩小查询范围（增加过滤、聚合或降低 LIMIT）后再查，不要对同一口径重复执行；若样本已足够回答也可直接基于已返回行作答并说明结果不完整。

## SQL 导出

`export_query.py` 把全量结果写工作区 `output/` 目录下的 CSV，只回路径与预览（`kind=sql_export`）。可下载产物一律写 `output/`，其它位置的文件不会进会话文件面板：

```json
{
  "kind": "sql_export",
  "tool_label": "SQL 导出",
  "engine": "mysql",
  "database": "example_schema",
  "sql": "SELECT ...",
  "file_path": "/path/to/workspace/output/result.csv",
  "file_format": "csv",
  "columns": ["col_a", "col_b"],
  "row_count": 621,
  "has_more": false,
  "preview_rows": [{ "col_a": "x", "col_b": 1 }],
  "summary": "已导出 621 行到 /path/to/workspace/output/result.csv",
  "result_state": "success",
  "error_code": null,
  "failure_attribution": [],
  "retryable": false,
  "stop_reason": "",
  "error": null
}
```

- 全量数据在文件里，不在 `preview_rows`；后续处理（如生成 Excel）应让 Python 读 `file_path`，不要把整份 CSV 读进上下文。
- `has_more=true` 表示命中行数上限（默认/最大 10000），应改用更精确的过滤或聚合。

## 图表契约

图表输出统一通过 `chart_spec`，由 `chart_type` 区分：

- `table`：明细表格
- `bar`：分类对比 / TopN（`--stack` 可堆叠，`orientation:horizontal` 可横向）
- `line`：时间趋势
- `area`：趋势 + 累积量强调（line 的填充版，`--stack` 可堆叠）
- `scatter`：两个数值字段的相关性（x、y 均为数值轴）
- `combo`：组合双轴，首个数值走柱状/左轴，其余走折线/右轴
- `radar`：多指标对比（每行一个指标轴，建议指标轴 ≥ 3）
- `funnel`：转化漏斗（阶段 + 单数值）
- `gauge`：单 KPI 仪表盘（取首行单数值）
- `pie`：占比（类别 2~8）

```json
{
  "kind": "chart_spec",
  "version": 1,
  "chart_type": "line",
  "title": "趋势图",
  "description": "按时间展示指标变化",
  "x_field": "stat_time",
  "series": [
    { "name": "数量", "field": "metric_value", "type": "line" }
  ],
  "dataset": [
    { "stat_time": "2026-05-01", "metric_value": 3 }
  ],
  "error": null
}
```

## 图表规则

- 时间维度 + 数值指标：优先 `line`；强调累积量用 `area`
- 分类维度 + 对比或 TopN：优先 `bar`；多分组堆叠用 `--stack`
- 占比分析且类别数 2 到 8：优先 `pie`
- 两个数值字段的相关性：用 `scatter`
- 同一维度上量级与比率/增速混合对比：用 `combo`（双轴）
- 少数对象在多指标上的对比：用 `radar`
- 阶段转化、逐级流失：用 `funnel`
- 单一关键指标当前值：用 `gauge`
- 明细场景且明确要求独立表格时，才输出 `table`
- 不适合图表时，不输出 `chart_spec`，只保留 `sql_execution`
- 生成图表时，优先把完整 `sql_execution` JSON 直接作为输入传入；只有 JSON 过长时才落临时文件。
- `chart_spec.dataset` 必须是传入的完整结果行集，不得抽样或只截取前 N 行；时间序列按完整时间范围渲染，前端可自行做标签抽稀，但不能丢数据点。
- 如果 `sql_execution` 已经 `has_more=true`、`truncated_by_size=true` 或 `error_code=result_truncated`，不得生成图表；必须先改写 SQL 聚合/过滤到完整有界结果，再重新生成 `chart_spec`。
- 对比 / 趋势 / 占比场景，必须显式传 `--chart-type`。

## 前端渲染边界

- 前端是唯一图表渲染器；后端和脚本不生成 PNG、SVG 或静态图片 URL。
- `table` 必须显式提供 `columns`。
- `bar` / `line` / `area` / `scatter` / `combo` / `radar` 必须显式提供 `x_field` 和 `series`。
- `pie` / `funnel` 必须且只能提供 1 个 `series`。
- `gauge` 必须且只能提供 1 个 `series`，`x_field` 可选（取首行单值）。
- `combo` 的 `series[].axis` 取 `left`/`right`，`series[].type` 取 `bar`/`line`。
- `scatter` 的 `x_field` 必须是数值字段。
- `dataset` 顺序由技能决定，前端按原顺序渲染。
- `version` 当前固定为 `1`。
