# 工具输出契约

先结论：结果表达统一走工具输出。表格保底来自 `sql_execution`，图表来自 `chart_spec`。

## 输出种类

- `metadata_snapshot`
  - 表、字段、血缘定位
- `datasource_resolution`
  - engine / database / cluster 确认
- `sql_execution`
  - SQL 文本、表格结果、耗时、错误
- `python_execution`
  - 脚本执行摘要和结构化返回
- `chart_spec`
  - 条形图、折线图、饼图

## 表格保底

表格不是单独的 `kind`，而是 `sql_execution` 的默认承载方式。

```json
{
  "kind": "sql_execution",
  "tool_label": "SQL 执行",
  "engine": "doris",
  "database": "ads_sales",
  "sql": "select ...",
  "columns": ["dt", "gmv"],
  "rows": [{"dt": "2026-03-01", "gmv": 123}],
  "row_count": 7,
  "has_more": false,
  "duration_ms": 120,
  "summary": "返回最近7天趋势数据",
  "error": null
}
```

## 图表契约

图表输出统一通过 `chart_spec`，由 `chart_type` 区分：

- `bar`
- `line`
- `pie`

```json
{
  "kind": "chart_spec",
  "chart_type": "line",
  "title": "最近7天订单趋势",
  "description": "按天展示订单金额变化",
  "x_field": "dt",
  "series": [
    { "name": "订单金额", "field": "gmv", "type": "line" }
  ],
  "dataset": [
    { "dt": "2026-03-01", "gmv": 123 }
  ],
  "error": null
}
```

## 图表规则

- 时间维度 + 数值指标：优先 `line`
- 分类维度 + 单指标且类别数 2 到 8：优先 `pie`
- 分类维度 + 对比或 TopN：优先 `bar`
- 不适合图表时，不输出 `chart_spec`，只保留表格

## 图表模板来源

图表语义模板在：

- `assets/chart-template/table.json`
- `assets/chart-template/bar.json`
- `assets/chart-template/line.json`
- `assets/chart-template/pie.json`

`chart_spec` 应当与这些模板的语义约束保持一致，而不是任意扩写前端 option。
