# 数据新鲜度（Freshness）检查

## 功能概述

数据新鲜度检查回答一个问题：**这张表的数据，是否在约定的时限内更新了？** 设计对齐 dbt 的 `source freshness`——由用户为表显式声明「用哪个时间字段」和「多久算 warn / 多久算 error」，未配置契约的表不参与检查、不产出结论。

心智模型就是一段契约：

```
表 orders
  时间字段: _etl_loaded_at
  warn_after:  2 小时
  error_after: 4 小时
  filter:      env = 'prod'    # 可选
```

## 为什么不靠元数据更新时间自动判断

一个常见的误解是「看表最后一次被写的时间就行」。实际不行：`information_schema.tables.UPDATE_TIME` 只能**证伪**，不能**证实**——

- 空导入、重跑写入同一批数据、compaction 都会推进它；
- 全量覆盖（`insert overwrite`）把昨天的数据再写一遍，它照样前进。

要判断「新数据到底写没写、还是又是昨天那批」，只有**数据自身携带的时间标记**能做到（业务时间列或分区值），因为 `information_schema` 里根本不包含「这批数据是哪天的」这个信息。因此本功能不做任何从命名规范、调度 cron、行数变化去推断阈值的「猜测」——猜出来的只会是骗人的绿灯。

## 四种取值模式

模式由用户显式选择，没有自动阶梯、没有自动兜底。

| 模式 | 取数方式 | 能发现「写入了旧数据」 | 何时用 |
| --- | --- | --- | --- |
| `column` | `SELECT MAX(\`col\`)` | ✅ | 首选。表有加载时间列或业务时间列（`etl_time` / `order_time` / `event_time`） |
| `custom_sql` | 用户查询包成标量子查询取时间戳 | ✅ | 加载时点需要自定义算法（对齐 dbt 1.10 `loaded_at_query`）。与 `column` 互斥 |
| `partition` | `SHOW PARTITIONS` 解析最新分区业务日期 | ✅ | 大表、按天分区（`ds` / `dt`），`MAX()` 成本高时零扫描 |
| `metadata` | `information_schema.tables.UPDATE_TIME` | ❌ | 兜底档，见下方说明 |

`column` / `custom_sql` 的「快照时间」取自 Doris 侧同一条 SQL 的 `NOW()`，避免门户与 Doris 时钟偏差。

### metadata 模式的局限（重要）

`metadata` 模式**只能发现「没人写了」**（任务挂掉、表被遗弃 → `UPDATE_TIME` 长期不动），**发现不了「写进去的是旧数据」**。选它就意味着接受这个局限。只有当表既没有可用时间列、也没有分区时，才退到这一档；能上其他三种就别用它。

### partition 模式的语义提醒

`partition` 比较的是**分区值解析出的业务日期**（`ds=20260806` → `2026-08-06`），不是分区的物理写入时间。注意它的 age 语义：一张 T+1 日表，正常情况下 `max(ds)` 就是昨天，`age` 恒在 24～48 小时之间。阈值要按「今天几点还没出昨天的数」来设，**不能**照搬「2 小时没更新就告警」的直觉，否则天天误报。`partitionFormat` 应写成匹配分区名数字串的格式，如 `yyyyMMdd`。

## 阈值继承（两层，逐字段合并）

阈值与取值字段有两层来源，**逐字段合并**（对齐 dbt `merge_freshness`）：上层只覆盖它自己声明过的字段。

1. **表级契约** `table_freshness_config`。`enabled = 0` 表示显式关闭该表检查（等价 dbt 的 `freshness: null`），短路。
2. **规则默认** `rule_config.defaults[]`：每项含 `scope`（`clusterIds` / `dbNames` / `layers`）与阈值，为表级未声明的字段兜底（等价 dbt 的 source 级默认）。

因此「表级只声明取数方式（`mode` + `loadedAtField`），阈值统一由某个 layer 的规则默认给出」是受支持的写法。合并后若仍无任何阈值，则该表不检查。

规则默认配置示例（`DATA_FRESHNESS_CHECK` 规则的 `rule_config`）：

```json
{
  "warnSeverity": "medium",
  "queryTimeoutSeconds": 30,
  "maxConcurrentPerCluster": 4,
  "reportUnconfigured": false,
  "defaults": [
    {
      "scope": { "layers": ["DWD"] },
      "warnAfter": { "count": 2, "period": "hour" },
      "errorAfter": { "count": 4, "period": "hour" }
    }
  ]
}
```

## 状态判定

```
age = snapshotted_at - max_loaded_at

max_loaded_at 为空          -> error        (reason = never_loaded)
age >  errorAfter           -> error
age >  warnAfter            -> warn
否则                        -> pass
SQL 失败 / 超时 / 列不存在  -> runtime_error
```

- 比较用**严格大于**：`age` 恰好等于阈值判 `pass`（对齐 dbt `Time.exceeded`）。先判 error 后判 warn。
- 「从未加载」显式判 `error` 且带 `reason = never_loaded`，问题描述直接说「该表从未产出过数据」，而非甩一个天文数字延迟。
- `runtime_error` 独立于 `pass`：检查没跑成功不等于数据新鲜。

## 触发时机

一个检查，三个触发点。触发点不改变判定逻辑，只决定何时运行。

1. **定时**：`FreshnessScheduledTask`（默认 15 分钟一轮）。候选集是「有启用中表级契约」的活跃表，每轮只挑到期的（`nextCheckAt = 上次检查 + max(minInterval, warnAfter/2)`）。无表配置契约时空跑。
2. **按需**：Data Studio「数据新鲜度」页签「立即检查」，或 `POST /v1/tables/{id}/freshness/check`。
3. **工作流完成后**：`WorkflowExecutionSyncJob` 识别新变为成功的实例，检查该工作流经写关系关联的表——回答「任务报成功了，数据真的到了吗」。

开关：`freshness.check.enabled`（默认 true，仅控制定时与工作流触发；按需与巡检路径不受影响）。

## 与巡检的关系

`data_freshness` 巡检规则改为消费检查结果，只把非 `pass` 结果转成 `inspection_issue`：`warn → warnSeverity`（默认 `medium`）、`error → critical`、`runtime_error → high`。规则默认关闭；启用后接入每日巡检。打开 `reportUnconfigured` 后，scope 内未配置契约的表会产出一条低优先级的治理型问题，推动 owner 去配置，而不是被静默忽略。

## 缺少可判定字段的表怎么办

全量覆盖、没有任何时间列、也没有分区的表，当前**无法可靠判定新鲜度**。对这类表，正确做法不是编一个假的 `pass`，而是：

- 借 `reportUnconfigured` 把「未纳入新鲜度管理」作为一条治理项暴露出来；
- 推动补一列写入时间戳（如 `etl_time DATETIME`），一次 DDL 换永久可检。这也是 dbt 生态里 `_etl_loaded_at` 成为惯例的原因。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/tables/{id}/freshness` | 生效契约（含字段来源）与最近一次结果 |
| PUT | `/v1/tables/{id}/freshness` | upsert 表级契约 |
| DELETE | `/v1/tables/{id}/freshness` | 删除表级契约 |
| POST | `/v1/tables/{id}/freshness/check` | 按需单表检查 |
| GET | `/v1/tables/{id}/freshness/history?limit=` | 结果历史 |
| GET | `/v1/inspections/freshness` | 各表最新结果（按 `status` / `clusterId` / `dbName` 过滤） |
| POST | `/v1/inspections/freshness/run` | 按 scope 批量检查 |
| PUT | `/v1/inspections/rules/{ruleId}` | 编辑规则 `ruleConfig` / `severity` 等 |

保存契约时的校验：`loadedAtField` 必须命中该表真实列名；`loadedAtQuery` 必须以 `SELECT` 开头且不含分号/注释符（上限 2048）；`filter` 不含分号/注释符（上限 512）；`loadedAtField` 与 `loadedAtQuery` 互斥；`partition` 模式要求表有分区列。

## 与 dbt 的对齐与偏离

| 维度 | dbt | 本实现 |
| --- | --- | --- |
| 未配置的资源 | 不检查 | 一致 |
| 阈值比较 | `age > threshold` | 一致（严格大于） |
| 判定顺序 | 先 error 后 warn | 一致 |
| 配置继承 | `merge_freshness` 逐字段合并 | 一致（规则默认 + 表级两层） |
| 自定义取数 | 1.10 `loaded_at_query` 标量子查询 | 一致（`custom_sql`） |
| 元数据取数 | 无字段时自动走 metadata | 偏离：metadata 为显式可选项，不自动兜底，并标注局限 |
| 分区取数 | 无（可用表达式塞进字段） | 补充：`partition` 模式零扫描 |
| 从未加载 | 哨兵值让 age 溢出成 error | 偏离：显式 `error` + `reason = never_loaded` |
| 结果产物 | `target/sources.json` | 偏离：落 `table_freshness_result` 表 |
| 触发方式 | 独立命令，不随 `dbt build` | 补充：定时 / 按需 / 工作流完成后 |

## 常见故障排查

- **结果一直 `runtime_error`**：检查契约的时间字段/分区格式/自定义查询是否正确、检查账号是否有该表读取权限、Doris 连通性与表是否存在。
- **`partition` 模式总是 error**：确认 `partitionFormat` 匹配分区名中的数字串，以及阈值是否按 T+1 的 age 语义设置（日表 age 恒 ≥ 24h）。
- **`metadata` 模式看着正常但数据其实是旧的**：这是该模式的固有局限，换用 `column` / `custom_sql` / `partition`。
- **配了契约却不检查**：确认 `enabled` 未被置为关闭、且至少配置了一档阈值（warn 或 error），否则视为未配置。
