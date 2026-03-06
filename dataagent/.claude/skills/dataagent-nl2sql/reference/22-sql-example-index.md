# SQL 示例索引

先结论：需要 SQL 参考时，先看本页匹配场景和引擎；示例只用于校准结构，不要直接照抄到最终回答。

## SQL 示例

### 新增用户来源占比
- 场景：占比分析
- 引擎：`mysql`
- 问题：昨日新增用户按来源分布
- SQL 摘要：`SELECT source, COUNT(DISTINCT user_id) AS new_user_cnt`
- 注意事项：若来源字段类别过多，优先回退为条形图或表格。、需要确认新增用户是否以注册时间为准。
- 相关术语：新增用户、来源分布
- 来源：`assets/sql_examples.json`

### 订单数分组对比
- 场景：对比分析
- 引擎：`doris`
- 问题：本月各业务线订单数对比
- SQL 摘要：`SELECT biz_line, COUNT(order_id) AS order_cnt`
- 注意事项：若业务线字段不明确，先查元数据再决定维度字段。、对比分析默认使用相同时间范围和相同过滤条件。
- 相关术语：订单数、对比分析
- 来源：`assets/sql_examples.json`

### 订单明细查询
- 场景：明细查询
- 引擎：`mysql`
- 问题：查看昨日支付成功订单明细
- SQL 摘要：`SELECT order_id, user_id, total_amount, pay_status, order_time`
- 注意事项：明细查询必须带 LIMIT。、若需要更多字段，先通过 inspect_metadata.py 确认字段含义。
- 相关术语：订单明细、支付订单
- 来源：`assets/sql_examples.json`

### 订单金额趋势
- 场景：趋势分析
- 引擎：`doris`
- 问题：最近 7 天订单金额趋势
- SQL 摘要：`SELECT stat_date, SUM(total_amount) AS order_amount`
- 注意事项：先确认金额口径是否只看已支付订单。、优先使用日汇总表，避免直接扫明细事实表。
- 相关术语：订单金额、趋势分析
- 来源：`assets/sql_examples.json`

## Few-shot 提示补充

### 最近 7 天订单金额趋势
- 标签：趋势分析、折线图、订单金额
- 答案摘要：先按趋势分析处理。优先确认时间字段与订单金额口径，定位到订单事实表或日汇总表后，按天聚合订单金额，默认输出折线图，并保留表格结果作为核对依据。
- 来源：`assets/few_shots.json`

### 本月各业务线订单数对比
- 标签：对比分析、条形图、订单数
- 答案摘要：先按对比分析处理。确认业务线维度字段、统计周期和订单数定义后，按业务线聚合订单数，输出条形图；若业务线口径不唯一，必须先追问。
- 来源：`assets/few_shots.json`

### 昨日新增用户按来源分布
- 标签：占比分析、饼图、新增用户
- 答案摘要：先按占比分析处理。确认新增用户定义和来源字段后，统计昨日各来源用户数；类别数较少时输出饼图，否则回退为表格或条形图。
- 来源：`assets/few_shots.json`
