# 类 dbt 数据新鲜度（Freshness）检查设计

**Date:** 2026-08-05
**Goal:** 把新鲜度做成「表级显式契约」——由用户指定时间字段与时效阈值，未配置的表不产出新鲜度结论，语义与 dbt `source freshness` 对齐。
**Tech Stack:** 后端（Java 8 · Spring Boot 2.7 · MyBatis-Plus · Flyway · Doris JDBC）、前端（Vue 3 · Element Plus）、数据库（MySQL 8）。不涉及 DataAgent 模块，不改部署拓扑。

---

## Scope

**做：**

- 新增表级新鲜度契约：时间字段取值方式 + `warnAfter` / `errorAfter` 两级阈值 + 可选 `filter`。
- 新增 `FreshnessCheckService`，判定 `pass | warn | error | runtime_error`，每次检查落一行结果。
- `DataFreshnessRuleHandler` 改为消费检查结果，只把非 `pass` 转成 `inspection_issue`。
- 一个检查、三个触发点：工作流执行完成后检查它写出的表（主，事件驱动）、页面按需、每日巡检（治理兜底）。不设固定间隔的墙钟轮询。
- 补齐配置可达性：种子化规则、规则配置更新接口、表级契约 REST 与前端页签。

**不做（本设计之外）：**

- **不猜阈值、不猜字段。** 不从 `statistics_cycle` 推导、不从 `schedule_cron` 反推、不按列名模式自动识别时间列。未配置契约的表**不参与检查、不产出新鲜度结论**，与 dbt 中未声明 `freshness` 的 source 一致。
- 不新增告警通道（邮件、Webhook、IM），沿用 `inspection_issue`。
- 不做工作流阻断式门禁，只提供只读状态查询接口。
- 不改 `DorisMetadataSyncService` 元数据同步链路，不改 `inspection_record` / `inspection_issue` 表结构与既有 REST 契约。
- 不引入 dbt 本体，不生成 `sources.json` 文件产物。

## Current State

新鲜度规则由 `backend/src/main/java/com/onedata/portal/service/inspection/DataFreshnessRuleHandler.java` 承载，经 `InspectionRuleRegistry` 分发（见 `docs/design/2026-06-21-inspection-registry-datatable-sink-design.md`）。现状逻辑：

1. 取 `status = active` 且 `statistics_cycle` 非空的表（`DataFreshnessRuleHandler.java:43`）。
2. `parseUpdateCycle(statistics_cycle)` 把周期换算成小时（`DataFreshnessRuleHandler.java:98`）。
3. 阈值 = 周期 + 规则级 `toleranceHours`（默认 2 小时）。
4. 比较 `data_table.doris_update_time`，超期则按延迟倍数推导 `severity` 并写 `inspection_issue`。

`doris_update_time` 来自 `information_schema.tables.UPDATE_TIME`（`DorisConnectionService.java:1115`），经 `DorisMetadataSyncService` 落库（`DorisMetadataSyncService.java:1138`），由 `DataSourceMetadataStatisticsSyncTask`（`fixedDelay` 默认 600000 ms）等同步任务驱动。

`statistics_cycle` 是**表命名规范的一段**（`TableNameGeneratorService.java:60`），前端可选值为 `10m` / `30m` / `1h` / `1d` / `realtime`（`CreateTableDrawer.vue:338`）。

`inspection_rule` 的种子数据不含 `data_freshness`（`V1__init.sql:294`、`V22__inspection_rules_default_disabled.sql:29`）；`InspectionController` 只有 `GET /rules` 与 `PUT /rules/{ruleId}/enabled`，没有写 `rule_config` 的接口。结果只在命中问题时写 `inspection_issue`，`pass` 不留痕；唯一入口是 `InspectionScheduledTask` 每日 02:00 的全量巡检。

## Problem

1. **信号测不出真正的故障。** `UPDATE_TIME` 是「容器有没有被动过」的信号，不是「里面装的是不是新数据」。空导入、重跑写入同一批数据都会推进它。**它只能证伪（长期不动 = 有问题），不能证实（动了 ≠ 数据新鲜）。** 要判断「新数据没写、还是老数据」，只有数据自身携带的时间标记能做到——分区值或时间列，没有第三种，因为 `information_schema` 里根本不包含「这批数据是哪天的」这个信息。
2. **阈值来源错位。** `statistics_cycle` 是命名约定，不是 SLA 声明。附带地，`parseUpdateCycle` 把后缀 `m` 当月处理（`10m` → 7200 小时）、`realtime` 解析失败被跳过——前端五个取值里三个失效。**本设计的修法是删掉这条推导路径，而不是修它。**
3. **语义不足。** 没有时间字段声明、没有 `filter`、没有 warn/error 两级阈值（`severity` 由延迟倍数推导，不可配置）、没有表级覆盖。
4. **出厂不可用。** 无种子规则行，也无写 `rule_config` 的接口，启用只能直接改库。
5. **观测与时机不足。** `pass` 不留痕，回答不了「上次检查是什么时候」；唯一触发点是每日 02:00，小时级 SLA 覆盖不到。

## Design

心智模型就是 dbt 那段 YAML：**哪个字段、多久 warn、多久 error**。其余都是平台形态差异。

### 1. 契约

表级契约字段：`mode`、`loadedAtField`、`loadedAtQuery`、`filter`、`warnAfter{count, period}`、`errorAfter{count, period}`。`period` 枚举 `minute | hour | day`，与 dbt `TimePeriod` 一致。

只有两层来源，逐字段合并，语义与 dbt `merge_freshness` 一致（上层只覆盖它自己声明过的字段）：

1. `table_freshness_config` 表级配置。`enabled = 0` 短路，等价 dbt 的 `freshness: null`。
2. 规则配置 `rule_config.defaults[]`：每项含 `scope`（`clusterIds` / `dbNames` / `layers`）与阈值，为表级未声明的字段兜底。等价 dbt 的 source 级默认。

合并后 `warnAfter` 与 `errorAfter` 仍全为空 → **该表不检查**，不产出任何新鲜度结论。

未配置契约的表不是「检查通过」，也不是「检查失败」，而是**未纳入管理**。规则配置 `reportUnconfigured`（默认 `false`）可开启后，为 scope 内未配置的表产出一条治理型问题，推动 owner 去配——默认关闭以免首次启用时刷屏。

### 2. 取值模式

四种模式**由用户显式选择**，没有自动阶梯、没有自动兜底：

| 模式 | 取数方式 | 能发现「还是老数据」 |
| --- | --- | --- |
| `column` | `SELECT MAX(\`col\`) AS max_loaded_at, NOW() AS snapshotted_at FROM \`db\`.\`tbl\` [WHERE filter]` | ✅ 等价 dbt `loaded_at_field` |
| `custom_sql` | `SELECT (<loadedAtQuery>) AS max_loaded_at, NOW() AS snapshotted_at` | ✅ 等价 dbt 1.10 `loaded_at_query` |
| `partition` | `SHOW PARTITIONS` 取最新分区，解析分区值为业务日期 | ✅ `ds` 场景零扫描成本 |
| `metadata` | `information_schema.tables.UPDATE_TIME` | ❌ 见下 |

`column` 与 `custom_sql` 的 `snapshotted_at` 取自 Doris 侧同一条 SQL 的 `NOW()`，避免门户与 Doris 时钟偏差。`custom_sql` 照搬 dbt `default__collect_freshness_custom_sql` 的形状：用户查询只返回一个时间戳，被包成**标量子查询**，快照时间由平台拼；`loadedAtQuery` 与 `loadedAtField` 互斥。

**`metadata` 模式的定位必须写清楚**：它只能发现「没人写了」（表被遗弃、生产任务挂掉），发现不了「写进去的是老数据」。因此它是**显式可选项，不是兜底档**——用户选它就意味着接受这个局限。文档与 UI 都要标注。

`partition` 模式比较的是**分区值解析出的业务日期**（`ds=20260806` → `2026-08-06`），不是分区的物理写入时间。注意其 age 语义：T+1 日表正常情况下 `max(ds)` 就是昨天，`age` 恒在 24～48 小时之间，阈值要按「今天几点还没出昨天的数」来设，不能照搬「2 小时没更新就告警」的直觉。

### 3. 判定

```
age = snapshotted_at - max_loaded_at

max_loaded_at 为空          -> error        (reason = never_loaded)
age >  errorAfter           -> error
age >  warnAfter            -> warn
否则                        -> pass
SQL 失败 / 超时 / 列不存在  -> runtime_error
```

比较用**严格大于**，与 dbt `Time.exceeded` 的 `actual_age > difference` 一致：`age` 恰好等于阈值判 `pass`。先判 `errorAfter` 再判 `warnAfter`，顺序同 `FreshnessThreshold.status`。`runtime_error` 独立于 `pass`：检查没跑成功不等于数据新鲜。

「从未加载」是有意偏离 dbt 的一点：dbt 在 `_create_freshness_response` 里把缺失的 `last_modified` 塞成公元 1 年，靠 `age` 溢出落进 error；本设计显式判 `error` 并带 `reason = never_loaded`，因为问题描述要直接说「这张表从未产出过数据」，而不是甩一个天文数字延迟。状态结论相同。

### 4. 执行与留痕

`FreshnessCheckService.check(table, contract)` 每次都写一行 `table_freshness_result`（`pass` 也写），这是 dbt `sources.json` 的持久化等价物；`data_table` 冗余 `freshness_status` / `freshness_checked_at` 供列表过滤。批量检查按 `clusterId` 分组，并发上限 + JDBC `queryTimeout`（默认 30s），单表异常不影响其余表。

### 5. 触发点

**数据只在生产任务运行时变动，因此检查绑定到「运行」而非固定时钟轮询。** 对上次产出后没动过的表反复取数只是浪费——两次产出之间 `age` 只线性增长、无新信息。这一点上本实现比 dbt 更进一步：dbt 不掌握外部 source 的加载时机，只能定时跑 `source freshness`；而本平台拥有生产工作流，知道每张表何时被写，可精确在写入后检查。

同一套检查逻辑，触发点只决定何时调用：

1. **工作流完成后（主，事件驱动）**：`WorkflowExecutionSyncJob`（`0 */5 * * * ?`）同步到工作流实例**新变为成功**时，对该工作流各任务经 `table_task_relation`（`relation_type = write`）关联的表触发一次检查。**只覆盖本次真正产出的表**，回答「任务报成功了，数据真的到了吗」。不改 DolphinScheduler 链路，只在既有同步作业里挂钩。
2. **按需**：`POST /v1/tables/{id}/freshness/check`，等价 `dbt source freshness --select`。
3. **每日巡检（治理兜底）**：`data_freshness` 规则随每日全量巡检运行，是唯一建 `inspection_issue` 的路径，也做 `reportUnconfigured` 治理上报，并**兜底覆盖非工作流产出的表**（外部同步、上游直灌等无完成事件的表）。

**不设固定间隔的墙钟轮询。** 工作流产出表由触发点 1 在产出后即时检查；非工作流表由触发点 3 每日兜底。二者之外没有需要高频轮询的场景。

**留痕与问题的分层（已知、可接受）**：触发点 1/2 实时更新 `data_table.freshness_status` 与结果行，Data Studio 面板与巡检新鲜度视图即时可见；转成被追踪的 `inspection_issue`（带 open/acknowledged/resolved 状态流）仍由每日巡检承担。若后续需要「运行即建问题」的即时告警，可让触发点 1 复用巡检的问题映射逻辑，在本设计范围之外。

### 6. 与巡检的关系

`DataFreshnessRuleHandler` 保留 `ruleType = data_freshness`，改为：解析 scope → 取表集合 → 逐表解析契约 → 有契约的调 `FreshnessCheckService` → 只把非 `pass` 转成 `inspection_issue`。严重程度映射：`warn` → `rule_config.warnSeverity`（默认 `medium`）、`error` → `critical`、`runtime_error` → `high`。既有问题查询、状态流转与修复链路不变，不新增告警通道。

原 handler 内的 `parseUpdateCycle` / `calculateDelayHours` / `calculateFreshnessSeverity` 随之删除。

### 7. 安全约束

- `loadedAtField` 必须精确命中该表在 `data_field` 中的真实列名（大小写不敏感），否则保存即拒绝；标识符统一反引号包裹。
- `filter` 仅作为 `WHERE` 谓词拼接：禁止分号与注释符（`--`、`/*`），长度上限 512。
- `loadedAtQuery` 约束更严：必须以 `SELECT` 开头、禁止分号与注释符、长度上限 2048，仅管理员可配置。它被包成标量子查询执行，语法上无法携带第二条语句。
- 检查走 `DorisConnectionService` 只读路径 + `queryTimeout`，不复用 `DataQueryService` 的用户态权限链路（检查是系统态行为）。

## Interfaces / Data Model

### 数据库（Flyway `V50__table_freshness.sql`）

```sql
CREATE TABLE table_freshness_config (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_id           BIGINT      NOT NULL,
  mode               VARCHAR(16) NOT NULL,          -- column | custom_sql | partition | metadata
  loaded_at_field    VARCHAR(128)  DEFAULT NULL,
  loaded_at_query    VARCHAR(2048) DEFAULT NULL,    -- 与 loaded_at_field 互斥
  partition_format   VARCHAR(32)   DEFAULT NULL,    -- partition 模式的分区值日期格式，如 yyyyMMdd
  filter_expr        VARCHAR(512)  DEFAULT NULL,
  warn_after_count   INT           DEFAULT NULL,
  warn_after_period  VARCHAR(16)   DEFAULT NULL,    -- minute | hour | day
  error_after_count  INT           DEFAULT NULL,
  error_after_period VARCHAR(16)   DEFAULT NULL,
  enabled            TINYINT(1)  NOT NULL DEFAULT 1,
  created_by         VARCHAR(50)   DEFAULT NULL,
  updated_by         VARCHAR(50)   DEFAULT NULL,
  created_at         DATETIME    DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_table (table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表级数据新鲜度契约';

CREATE TABLE table_freshness_result (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_id       BIGINT       NOT NULL,
  cluster_id     BIGINT       DEFAULT NULL,
  db_name        VARCHAR(128) DEFAULT NULL,
  table_name     VARCHAR(128) DEFAULT NULL,
  mode           VARCHAR(16)  NOT NULL,
  status         VARCHAR(16)  NOT NULL,        -- pass | warn | error | runtime_error
  reason         VARCHAR(32)  DEFAULT NULL,    -- never_loaded 等
  max_loaded_at  DATETIME     DEFAULT NULL,
  snapshotted_at DATETIME     DEFAULT NULL,
  age_seconds    BIGINT       DEFAULT NULL,
  warn_after_seconds  BIGINT  DEFAULT NULL,
  error_after_seconds BIGINT  DEFAULT NULL,
  error_message  VARCHAR(512) DEFAULT NULL,
  trigger_type   VARCHAR(16)  DEFAULT NULL,    -- manual | schedule | inspection | workflow
  checked_by     VARCHAR(50)  DEFAULT NULL,
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY idx_table_time (table_id, created_at),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据新鲜度检查结果';

ALTER TABLE data_table
  ADD COLUMN freshness_status     VARCHAR(16) DEFAULT NULL COMMENT '最新新鲜度状态',
  ADD COLUMN freshness_checked_at DATETIME    DEFAULT NULL COMMENT '最近新鲜度检查时间';
```

同一迁移种子化规则（`enabled = 0`，与 V22 默认关闭策略一致）：

```sql
INSERT INTO inspection_rule (rule_code, rule_name, rule_type, severity, description, rule_config, enabled)
VALUES ('DATA_FRESHNESS_CHECK', '数据新鲜度检查', 'data_freshness', 'high',
        '按表级新鲜度契约检查数据是否在约定时限内更新，未配置契约的表不参与检查',
        '{"warnSeverity":"medium","queryTimeoutSeconds":30,"maxConcurrentPerCluster":4,
          "reportUnconfigured":false,"defaults":[]}', 0)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), description = VALUES(description);
```

### REST

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/tables/{id}/freshness` | 生效契约（含每个字段来源）与最近一次结果 |
| PUT | `/v1/tables/{id}/freshness` | upsert 表级契约，含字段白名单与互斥校验 |
| DELETE | `/v1/tables/{id}/freshness` | 删除表级契约 |
| POST | `/v1/tables/{id}/freshness/check` | 按需单表检查 |
| GET | `/v1/tables/{id}/freshness/history?limit=` | 结果历史 |
| GET | `/v1/inspections/freshness` | 按 `status`/`clusterId`/`dbName` 列出各表最新结果 |
| POST | `/v1/inspections/freshness/run` | 按 scope 批量检查 |
| PUT | `/v1/inspections/rules/{ruleId}` | 补齐规则 `rule_config` / `severity` 编辑 |

响应走既有 `Result<T>` 包装，失败沿用控制器 `try/catch → Result.fail(e.getMessage())` 的现行约定。

### 前端

Data Studio 右侧面板新增「数据新鲜度」页签（`lazy`，插在「访问情况」之后）：契约用 `el-descriptions :column="2"` 展示并标注字段来源（表级 / 规则默认），编辑表单按 `mode` 联动显隐 `loadedAtField` / `loadedAtQuery` / `partitionFormat`，选择 `metadata` 时明示「只能发现长期无写入，发现不了写入了旧数据」；下方 `el-table` 带 border 展示结果历史，顶部「立即检查」。未配置契约时展示引导而非空表格。巡检页新增按新鲜度状态过滤的表级最新结果视图。遵循前端视觉规范，不套多余卡片，兄弟页签保持一致的扁平结构。

## Risks / Alternatives

- **覆盖率取决于用户配置。** 这是有意的取舍：宁可少测，不可假 `pass`。缺少可判定字段的表通过 `reportUnconfigured` 变成一条可治理的问题，而不是一个骗人的绿灯。
- **大表 `MAX()` 扫描成本。** 缓解：`partition` 模式零扫描、支持 `filter`、`queryTimeout` + 每集群并发上限；文档建议 `loadedAtField` 选 key 列或分区列。
- **`partition` 模式依赖分区值可解析为日期。** 由 `partitionFormat` 显式声明，解析失败判 `runtime_error` 而非静默通过。
- **时区。** `max_loaded_at` 与 `snapshotted_at` 同源于 Doris。`partition` / `metadata` 模式的快照时间取门户侧，需在文档注明两者需同时区部署。
- **`filter` / `loadedAtQuery` 是自由 SQL。** 位置约束 + 字符黑名单 + 长度上限 + 权限控制，不做通用 SQL 解析。
- **行为变更。** 规则语义由「按 `statistics_cycle` 推断」改为「按表级契约」，未配置契约的表不再产出新鲜度问题。因该规则从未种子化、也无配置接口，存量影响为零。

**与 dbt 的对齐与偏离清单：**

| 维度 | dbt | 本设计 |
| --- | --- | --- |
| 未配置的资源 | 不检查 | 一致 |
| 阈值比较 | `age > threshold` | 一致，严格大于 |
| 判定顺序 | 先 `error_after` 后 `warn_after` | 一致 |
| 配置继承 | `merge_freshness` 逐字段合并 source / table | 一致，规则默认 + 表级两层逐字段合并 |
| 快照时间 | 仓库侧 `current_timestamp()`，与 `max_loaded_at` 同查询 | 一致（`column` / `custom_sql`） |
| 自定义取数 | 1.10 `loaded_at_query`，包成标量子查询 | 一致，`custom_sql` 模式 |
| 元数据取数 | 无 `loaded_at_field` 时自动走 metadata | **偏离**：metadata 是显式可选项，不自动兜底，并标注其局限 |
| 分区值取数 | 无（可用表达式塞进 `loaded_at_field`） | **补充**：`partition` 模式，零扫描 |
| 从未加载 | 哨兵值让 `age` 溢出成 error | **偏离**：显式 `error` + `reason = never_loaded` |
| 结果产物 | `target/sources.json` | **偏离**：落 `table_freshness_result` 表 |
| 触发方式 | 独立命令，定时跑 `source freshness`（不掌握 source 加载时机） | **更进一步**：事件驱动，工作流完成后即检查其写出表；平台掌握生产时机，无需墙钟轮询 |

**备选方案：**

1. **只修 `parseUpdateCycle`。** 信号仍是 `UPDATE_TIME`，测不出「写入了旧数据」，且没有两级阈值与留痕。否决。
2. **为没有时间字段的表自动推导阈值与信号**（从 `schedule_cron`、`update_type`、`row_count` 增量组合）。评估后否决：这些都是「容器外部」的信号，`UPDATE_TIME` 推进不证明内容更新、全量覆盖表 `row_count` 不变、Unique/Aggregate 模型的 `TABLE_ROWS` 还会因 compaction 回落。复杂度换来的是假 `pass`，不是覆盖率。
3. **引入 dbt-core 执行 `source freshness`。** 需要 Python 运行时与 `sources.yml` 双写，与现有元数据中心职责重复。否决。
4. **每张表建一个 DolphinScheduler 校验任务。** 调度对象膨胀，结果与巡检问题面割裂。否决。

## Verification

- **后端单测：** 状态判定边界（`age` 恰好等于阈值判 `pass`、超过才升档、`never_loaded`、`runtime_error`）、两层契约逐字段合并与显式关闭短路、无契约表不参与检查、`loadedAtField` 白名单、`filter` / `loadedAtQuery` 校验与互斥、`partition` 值解析、handler → `inspection_issue` 映射、工作流完成触发只覆盖 `relation_type = write` 的表、控制器参数透传。
- **迁移：** `V50` 在本地 MySQL 上可重复执行且幂等；`InspectionRuleHandlerCoverageTest` 同步更新。
- **前端：** `nvm use` 后跑新增组件 Vitest 与生产构建。
- **端到端：** `column` / `custom_sql` / `partition` 模式依赖真实 Doris 表与真实列。当前仓库环境无 Doris 实例，如果实施时仍不可用，须在提交说明中明确标注「未跑真实 Doris 全链路」，并说明已验证到哪一层。
