---
name: dataagent-nl2sql
description: Use this skill for Chinese natural-language data analysis tasks including statistics, comparison, trend, share, detail lookup, diagnosis, term explanation, SQL examples, and chart-oriented answer planning across MySQL and Doris datasources.
---

# DataAgent NL2SQL Skill

## 适用范围

使用本技能处理以下问题：

- 数据问答、取数、核对
- 统计分析、对比分析、趋势分析、占比分析
- 明细查询、排查诊断
- 术语解释、指标口径解释
- SQL 示例参考
- 结果表达为表格、条形图、折线图、饼图

不要把本技能用于以下场景：

- 非数据类常识问答
- 需要跨多个数据源联邦 Join 的问题
- 写入、更新、删除类 SQL
- 无法确定库表且用户拒绝补充信息的场景

## 工作原则

- 先看地图，再看场景，再决定是否下钻资产或执行脚本。
- 每次问题最终只落到一个数据源执行，不做跨源联查。
- 无法唯一确定术语、指标、数据库、表或时间口径时，先追问。
- 图表不是必选项；不适合图表时只输出表格。
- SQL 必须只读，且保留行数保护。
- 最终回答用中文，先给结论，再给依据，不要复读大段工具原文。

## 固定阅读顺序

处理任何问题时，按下面顺序决定是否继续阅读：

1. 阅读 [`reference/00-skill-map.md`](reference/00-skill-map.md)
2. 阅读 [`reference/10-query-playbooks.md`](reference/10-query-playbooks.md)
3. 若缺少数据源判断，再读 [`reference/11-datasource-routing.md`](reference/11-datasource-routing.md)
4. 若缺少业务语义，再按需读：
   - [`reference/20-term-index.md`](reference/20-term-index.md)
   - [`reference/21-metric-index.md`](reference/21-metric-index.md)
   - [`reference/22-sql-example-index.md`](reference/22-sql-example-index.md)
5. 若缺少脚本和动态查询细节，再按需读：
   - [`reference/30-tool-recipes.md`](reference/30-tool-recipes.md)
   - [`reference/40-runtime-metadata.md`](reference/40-runtime-metadata.md)
   - [`reference/50-tool-output-contract.md`](reference/50-tool-output-contract.md)
6. 只有上述文档仍不足以消除具体疑问时，才下钻 `assets/*` 或执行 `scripts/*`

禁止一开始就通读大段 JSON 资产。

## 固定执行顺序

### A. 先判问题类型

优先把问题归到以下一种主类型：

- 统计
- 对比
- 趋势
- 占比
- 明细
- 诊断
- 术语解释
- SQL 示例

如果一个问题同时包含多个目标，先识别主目标，再补次目标。

### B. 再判是否需要追问

出现以下任一情况时，先追问，不要猜：

- 活跃用户、GMV、来源、业务线等术语口径不清
- 目标数据库或目标表不唯一
- 时间范围或时间粒度不清
- 用户要“对比”，但未说明对比维度
- 用户要“趋势”，但未说明指标

### C. 再决定是否执行脚本

脚本顺序遵循以下原则：

1. 术语不清：先看 `20/21/22`
2. 表字段不清：先用 `inspect_metadata.py`
3. 数据源不清：再用 `resolve_datasource.py`
4. SQL 明确后：再用 `run_sql.py`
5. 结果适合图表：再用 `build_chart_spec.py`
6. 需要压缩总结：再用 `format_answer.py`

不要在没明确数据库、指标、维度前直接执行 `run_sql.py`。

## 多数据源约束

- 平台元数据和 ODW 平台表属于 MySQL 路径。
- 业务分析表可能落到 MySQL 或 Doris，必须先做单源路由。
- 同一轮回答内，如果候选表分属不同引擎或不同数据库，先追问用户缩小范围。

## 图表约束

- 表格：默认保底输出
- 条形图：分类对比、TopN、排序展示
- 折线图：时间趋势
- 饼图：类别数较少的占比分析

图表是否生成，由场景和结果结构共同决定；不要为了“看起来丰富”而强行出图。

## 资产与脚本入口

### 主要文档

- [`reference/00-skill-map.md`](reference/00-skill-map.md)
- [`reference/10-query-playbooks.md`](reference/10-query-playbooks.md)
- [`reference/11-datasource-routing.md`](reference/11-datasource-routing.md)
- [`reference/20-term-index.md`](reference/20-term-index.md)
- [`reference/21-metric-index.md`](reference/21-metric-index.md)
- [`reference/22-sql-example-index.md`](reference/22-sql-example-index.md)
- [`reference/30-tool-recipes.md`](reference/30-tool-recipes.md)
- [`reference/40-runtime-metadata.md`](reference/40-runtime-metadata.md)
- [`reference/50-tool-output-contract.md`](reference/50-tool-output-contract.md)

### 脚本

- [`scripts/inspect_metadata.py`](scripts/inspect_metadata.py)
- [`scripts/resolve_datasource.py`](scripts/resolve_datasource.py)
- [`scripts/run_sql.py`](scripts/run_sql.py)
- [`scripts/build_chart_spec.py`](scripts/build_chart_spec.py)
- [`scripts/format_answer.py`](scripts/format_answer.py)
- [`scripts/build_reference_digest.py`](scripts/build_reference_digest.py)

### 结构化资产

- `assets/term_explanations.json`
- `assets/sql_examples.json`
- `assets/metrics.json`
- `assets/business_concepts.json`
- `assets/semantic_mappings.json`
- `assets/business_rules.json`
- `assets/few_shots.json`
- `assets/chart-template/*.json`

## 最终回答要求

- 先回答用户问题，再补充方法和限制
- 若已经执行查询，优先引用结构化结果，不重复堆原文
- 若只是术语解释或 SQL 示例问题，可不执行 SQL
- 若没有足够信息完成问数，明确说明缺什么，并提出最小追问
