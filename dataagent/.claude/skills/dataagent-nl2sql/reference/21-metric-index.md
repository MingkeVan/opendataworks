# 指标索引

先结论：遇到统计、对比、趋势分析，先用本页确认指标公式、默认时间字段和关键口径约束。

## 全局约束

- 最大行数保护：1000
- 时区：Asia/Shanghai
- 禁止操作：drop、truncate、delete、alter、create、insert、update
- 来源：`assets/constraints.json`

## 指标清单

### 支付订单数
- Metric Key：`paid_order_cnt`
- 公式：`SUM(dws_order_daily.paid_orders)`
- 默认时间字段：`stat_date`
- 来源：`assets/metrics.json`

### 订单数
- Metric Key：`order_cnt`
- 公式：`COUNT(ods_order.order_id)`
- 默认时间字段：`order_time`
- 来源：`assets/metrics.json`

### 订单金额
- Metric Key：`order_amount`
- 公式：`SUM(ods_order.total_amount)`
- 默认时间字段：`order_time`
- 来源：`assets/metrics.json`

## 业务规则补充

### 对比分析
- 同义词：横向对比、分组对比
- 规则：默认要求对比项使用相同时间范围、相同过滤条件和相同统计口径。
- 来源：`assets/business_rules.json`

### 活跃用户
- 同义词：DAU、日活
- 规则：若用户未说明活跃行为，默认不能直接计算，必须追问是登录、访问、下单还是其他事件。
- 来源：`assets/business_rules.json`

### 订单金额
- 同义词：GMV、成交额
- 规则：若未说明，需确认是否统计已支付订单、是否扣除退款、是否含取消订单。
- 来源：`assets/business_rules.json`

### 趋势分析
- 同义词：走势、变化趋势
- 规则：未指定时间粒度时默认按天；若时间跨度较长可建议按周或按月汇总。
- 来源：`assets/business_rules.json`
