# 智能问数图表类型扩展 设计

## 背景与现状

智能问数（NL2SQL）的图表输出统一走 `chart_spec` 契约，链路分四层：

- 技能脚本 `dataagent/.claude/skills/opendataworks-platform-tools/scripts/build_chart_spec.py`：从 `sql_execution` 结果推断并生成 `chart_spec`。
- 前端解析 `dataagent/dataagent-frontend/src/views/intelligence/chartSpec.js`：白名单校验 + 构建 ECharts option。
- 前端渲染 `dataagent/dataagent-frontend/src/views/intelligence/ChartSpecView.vue`：按需注册 ECharts 图表组件并渲染。
- 契约文档/系统提示词/模板/测试：约束模型可用的 `chart_type`。

当前 `chart_type` 仅支持 `table`、`bar`、`line`、`pie`，无法覆盖相关性、转化、单 KPI、多指标对比等常见问数可视化诉求。`stack` 标志在前端已支持，但脚本没有 `--stack` 入口，堆叠柱实际不可达。

## 问题

- 可用图表类型过少，趋势/占比/排行之外的分析诉求（相关性、组合双轴、转化、单指标对比、单 KPI）无法可视化。
- 堆叠柱缺少脚本入口。

## 范围

本轮新增 6 种 `chart_type` 并补齐堆叠入口；明确不纳入 heatmap / treemap / sankey / boxplot（数据形态特殊、契约更重，留待后续按需扩展）。

新增类型与数据形态：

| chart_type | x_field | series | 说明 |
|---|---|---|---|
| `area` | 时间/分类 | 1~3 数值 | line 的填充版，独立一等类型，语义更清晰 |
| `scatter` | 数值 | 1~3 数值 | 相关性分析，x 轴为 value 轴 |
| `combo` | 分类/时间 | ≥2 数值 | 首列柱（左轴），其余折线（右轴），双 Y 轴 |
| `radar` | 分类(指标轴) | 1~3 数值 | 多指标对比，每行一个雷达指标 |
| `funnel` | 阶段 | 1 数值 | 转化分析 |
| `gauge` | 可选 | 1 数值 | 单 KPI，取首行 |

堆叠：`bar`/`area` 通过新增脚本参数 `--stack` 设置 `stack:true`，前端复用已有 `stack` 渲染逻辑。

## 接口与契约

`chart_spec` 字段保持兼容，新增/复用：

- `chart_type` 枚举扩展为 `table|bar|line|area|scatter|combo|radar|funnel|gauge|pie`。
- `series[].axis`：`"left"|"right"`，仅 `combo` 使用，决定 Y 轴归属；缺省 `left`。
- `series[].type`：`combo` 显式区分 `bar`/`line`；`area` 用 `line`；`scatter` 用 `scatter`；`radar/funnel/gauge` 的 type 不参与渲染（专用 builder 只读 `field`）。
- `stack`：`bar`/`area` 堆叠开关，沿用现有字段。

脚本调用契约扩展：

```
build_chart_spec.py --chart-type <table|bar|line|area|scatter|combo|radar|funnel|gauge|pie> \
  --input '<sql_execution JSON>' [--title ...] [--x-field ...] [--y-field ...] [--stack]
```

校验分组（前端 `validateChartSpec`）：

- `table`：必须 `columns`。
- `bar|line|area|scatter|combo|radar`：必须 `x_field` + `series`。
- `pie|funnel`：必须 `x_field` + 恰好 1 个 `series`。
- `gauge`：必须 1 个 `series`，`x_field` 可选。

## 渲染方案（前端）

- `chartSpec.js`：扩展 `CHART_TYPES`/`ECHART_TYPES`/`SERIES_TYPES`；`normalizeSeries` 透传 `axis`；新增 `buildScatterOption`/`buildComboOption`/`buildRadarOption`/`buildFunnelOption`/`buildGaugeOption`；`buildAxisOption` 兼容 `area`（areaStyle）与 `combo`（双轴 + 逐 series type/yAxisIndex）。
- `ChartSpecView.vue`：补注册 `ScatterChart`/`RadarChart`/`FunnelChart`/`GaugeChart` 与 `RadarComponent`。

## 取舍

- area 作为独立类型而非仅 `--area` 标志：模型按 `--chart-type area` 选用更直观，且与契约文档枚举一致。
- combo 采用“首列柱 + 其余折线右轴”的固定约定，避免引入复杂的逐列轴编排参数，保持单一稳定契约。
- gauge/funnel/radar 用专用 builder 而非复用轴 builder，数据形态差异大，专用实现更可控。
- 不一次铺满 heatmap/sankey 等重契约类型，控制改动面与模型误用风险。
