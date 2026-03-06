# 场景 Playbooks

先结论：本技能优先覆盖统计、对比、趋势、占比、明细、诊断六类问题。每类都先确认指标和维度，再决定是否下钻 metadata 与执行 SQL。

## 统计

- 典型问题：今日订单数、昨日新增用户、本月支付订单数
- 先确认：
  - 统计指标
  - 时间范围
  - 是否需要去重
- 推荐顺序：
  1. `21-metric-index.md`
  2. `inspect_metadata.py`
  3. `run_sql.py`
- 默认输出：表格
- 追问条件：
  - 指标口径不清
  - 时间范围不清

## 对比

- 典型问题：各业务线订单数对比、不同来源新增用户对比
- 先确认：
  - 对比维度
  - 指标
  - 时间范围是否一致
- 推荐顺序：
  1. `21-metric-index.md`
  2. `20-term-index.md`
  3. `inspect_metadata.py`
  4. `run_sql.py`
  5. `build_chart_spec.py`
- 默认图表：条形图
- 回退输出：表格

## 趋势分析

- 典型问题：最近 7 天订单金额趋势、近 30 天支付订单走势
- 先确认：
  - 指标
  - 时间粒度（日 / 周 / 月）
  - 时间范围
- 推荐顺序：
  1. `21-metric-index.md`
  2. `22-sql-example-index.md`
  3. `inspect_metadata.py`
  4. `run_sql.py`
  5. `build_chart_spec.py`
- 默认图表：折线图
- 回退输出：表格

## 占比

- 典型问题：昨日新增用户按来源分布、各渠道订单金额占比
- 先确认：
  - 分类维度
  - 指标
  - 类别数量是否适合占比图
- 推荐顺序：
  1. `20-term-index.md`
  2. `21-metric-index.md`
  3. `inspect_metadata.py`
  4. `run_sql.py`
  5. `build_chart_spec.py`
- 默认图表：饼图
- 回退条件：
  - 类别超过 8 个
  - 更适合条形图

## 明细

- 典型问题：查看昨日支付订单明细、最近注册用户列表
- 先确认：
  - 明细对象
  - 过滤条件
  - 需要哪些字段
- 推荐顺序：
  1. `20-term-index.md`
  2. `30-tool-recipes.md`
  3. `inspect_metadata.py`
  4. `run_sql.py`
- 默认输出：表格
- 约束：
  - 必须带 LIMIT
  - 不要强行出图

## 诊断

- 典型问题：为什么订单金额下降、为什么新增用户异常
- 先确认：
  - 异常指标
  - 对比基线
  - 是否已有怀疑维度
- 推荐顺序：
  1. `21-metric-index.md`
  2. `40-runtime-metadata.md`
  3. `inspect_metadata.py`
  4. 必要时 `resolve_datasource.py`
  5. `run_sql.py`
- 默认输出：表格 + 诊断结论

## 术语解释

- 典型问题：什么是活跃用户、GMV 是什么
- 推荐顺序：
  1. `20-term-index.md`
  2. 必要时回看 `assets/term_explanations.json`
- 通常不执行 SQL

## SQL 示例

- 典型问题：给我一个趋势分析 SQL、对比分析 SQL 怎么写
- 推荐顺序：
  1. `22-sql-example-index.md`
  2. 必要时回看 `assets/sql_examples.json`
- 输出要求：
  - 标明适用场景和引擎
  - 明确“示例仅用于参考，落地前需按真实库表校正”
