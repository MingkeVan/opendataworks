# 技能地图

先结论：任何问题都先分类，再决定阅读哪类摘要或执行哪类脚本。不要一上来扫描全部资产。

## 问题类型到执行路径

| 问题类型 | 先看什么 | 优先脚本 | 默认结果 |
| --- | --- | --- | --- |
| 统计 | `10-query-playbooks.md`、`21-metric-index.md` | `inspect_metadata.py` -> `run_sql.py` | 表格 |
| 对比 | `10-query-playbooks.md`、`21-metric-index.md` | `inspect_metadata.py` -> `run_sql.py` -> `build_chart_spec.py` | 条形图 / 表格 |
| 趋势 | `10-query-playbooks.md`、`21-metric-index.md` | `inspect_metadata.py` -> `run_sql.py` -> `build_chart_spec.py` | 折线图 / 表格 |
| 占比 | `10-query-playbooks.md`、`20-term-index.md` | `inspect_metadata.py` -> `run_sql.py` -> `build_chart_spec.py` | 饼图 / 表格 |
| 明细 | `10-query-playbooks.md`、`30-tool-recipes.md` | `inspect_metadata.py` -> `run_sql.py` | 表格 |
| 诊断 | `10-query-playbooks.md`、`40-runtime-metadata.md` | `inspect_metadata.py` -> `resolve_datasource.py` -> `run_sql.py` | 表格 |
| 术语解释 | `20-term-index.md` | 无，必要时回看资产 | 中文解释 |
| SQL 示例 | `22-sql-example-index.md` | 无，必要时回看资产 | SQL 模板示例 |

## 快速判断规则

- 问“多少、总数、总金额”通常是统计
- 问“哪个更多、各业务线、各来源”通常是对比或占比
- 问“最近 7 天、按天变化、趋势”通常是趋势
- 问“明细、列表、最近订单”通常是明细
- 问“为什么异常、为什么下降、排查”通常是诊断
- 问“什么是 GMV、活跃用户是什么意思”属于术语解释
- 问“给个 SQL、类似 SQL 怎么写”属于 SQL 示例

## 先追问的情形

- 活跃用户、支付金额、GMV、来源、业务线口径不清
- 对比维度没说清
- 趋势指标没说清
- 数据库不清或有多个候选库
- 时间范围与时间粒度不清

## 何时下钻资产

- `20-term-index.md` 仍无法消除术语歧义时，查看 `assets/term_explanations.json`
- `21-metric-index.md` 仍无法确认默认聚合或时间字段时，查看 `assets/metrics.json`、`assets/business_rules.json`
- `22-sql-example-index.md` 仍无法找到合适模板时，查看 `assets/sql_examples.json`

## 何时执行脚本

- 只要库表字段不清，先 `inspect_metadata.py`
- 只要引擎不清，先 `resolve_datasource.py`
- 只有 SQL 已明确、且数据库已明确时，才 `run_sql.py`
- 只有结果结构适合图表时，才 `build_chart_spec.py`
