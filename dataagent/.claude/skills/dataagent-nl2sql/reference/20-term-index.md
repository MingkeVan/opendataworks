# 术语索引

先结论：遇到业务术语、别名、口径不清的问题，先看本页；仍不明确时，再下钻到对应资产或追问用户。

## 术语解释资产

### 新增用户
- 别名：新用户、注册用户
- 解释：在指定时间窗口内首次注册或首次建档的用户数，通常按注册时间字段统计。
- 易混术语：活跃用户、留存用户
- 推荐追问：请确认新增的定义是注册用户还是首次下单用户，以及统计时间范围。
- 相关指标：注册用户数
- 相关表：ods_user
- 来源：`assets/term_explanations.json`

### 来源分布
- 别名：渠道分布、来源占比
- 解释：按来源、渠道、入口等分类字段统计数量或金额占比。类别较少时更适合用饼图。
- 易混术语：业务线分布、地区分布
- 推荐追问：请确认来源字段是渠道、推广来源还是业务线，并确认是否需要占比展示。
- 相关指标：注册用户数、订单金额
- 相关表：ods_user、ods_order
- 来源：`assets/term_explanations.json`

### 活跃用户
- 别名：DAU、日活、活跃用户数
- 解释：在给定时间窗口内发生某种活跃行为的去重用户数。活跃行为通常是登录、访问、下单或支付。
- 易混术语：新增用户、留存用户
- 推荐追问：请确认活跃的判定行为，以及需要按天、按周还是按月统计。
- 相关指标：注册用户数
- 相关表：ods_user
- 来源：`assets/term_explanations.json`

### 订单金额
- 别名：GMV、成交额、销售额
- 解释：订单金额通常是订单总金额求和，但是否只统计已支付订单、是否扣除退款需要额外确认。
- 易混术语：支付金额、实收金额
- 推荐追问：请确认是否只统计已支付订单，以及是否需要扣除退款或取消订单。
- 相关指标：订单金额、支付订单数
- 相关表：ods_order、dws_order_daily
- 来源：`assets/term_explanations.json`

## 业务概念补充

### 注册用户数
- 说明：用户注册数量
- 默认映射：`ods_user / user_id / count`
- 来源：`assets/business_concepts.json`

### 订单数
- 说明：订单记录数量
- 默认映射：`ods_order / order_id / count`
- 来源：`assets/business_concepts.json`

### 订单金额
- 说明：订单总金额
- 默认映射：`ods_order / total_amount / sum`
- 来源：`assets/business_concepts.json`

## 语义映射补充

### 注册用户数
- 同义词：新增用户、新注册用户
- 候选表字段：`ods_user / user_id`
- 说明：通常结合注册时间字段按时间粒度做新增统计。
- 来源：`assets/semantic_mappings.json`

### 活跃用户数
- 同义词：活跃用户、DAU、MAU
- 候选表字段：`- / -`
- 说明：活跃口径不固定，必须先确认事件定义与时间粒度。
- 来源：`assets/semantic_mappings.json`

### 订单数
- 同义词：订单量、下单数
- 候选表字段：`ods_order / order_id`
- 说明：默认使用订单主键计数，若涉及支付订单需切到支付口径。
- 来源：`assets/semantic_mappings.json`

### 订单金额
- 同义词：GMV、成交额、销售额
- 候选表字段：`ods_order / total_amount`
- 说明：金额类指标默认使用求和；是否只看已支付订单需要确认。
- 来源：`assets/semantic_mappings.json`
