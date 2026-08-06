# 类 dbt 数据新鲜度（Freshness）检查设计

**Date:** 2026-08-05
**Goal:** 用「表级新鲜度契约 + 独立检查执行 + 结果留痕」替代当前仅依赖元数据同步时间的 `data_freshness` 巡检规则，对齐 dbt `source freshness` 的 `loaded_at_field` / `filter` / `warn_after` / `error_after` 语义。
**Tech Stack:** 后端（Java 8 · Spring Boot 2.7 · MyBatis-Plus · Flyway · Doris JDBC）、前端（Vue 3 · Element Plus）、数据库（MySQL 8）。不涉及 DataAgent 模块，不改部署拓扑。

---

## Scope

**做：**

- 新增表级新鲜度契约（取数模式、`loadedAtField`、过滤条件、warn/error 两级阈值）及其继承链解析。
- 新增 `FreshnessCheckService`，支持 `column` / `custom_sql` / `partition` / `metadata` 四种新鲜度取值模式，判定 `pass | warn | error | runtime_error`。
- 新增检查结果留痕表，`pass` 也落库，用于「上次检查时间」与新鲜度趋势。
- `DataFreshnessRuleHandler` 改为委托 `FreshnessCheckService`，仅把 `warn/error/runtime_error` 转成 `inspection_issue`，问题面保持单一。
- 修正周期解析缺陷：`10m/30m` 当前被解析为「月」，`realtime` 被静默跳过。
- 补齐配置可达性：种子化 `DATA_FRESHNESS_CHECK` 规则，新增规则配置更新接口，新增表级新鲜度 REST 与前端页签。
- 新增独立于每日 02:00 全量巡检的新鲜度检查调度。

**不做（本设计之外）：**

- 不新增告警通道（邮件、Webhook、IM）。告警面沿用现有 `inspection_issue`。
- 不做工作流阻断式门禁。本设计只提供只读的新鲜度状态查询接口，供后续门禁能力复用。
- 不改 `DorisMetadataSyncService` 元数据同步链路与 `data_table.doris_update_time` 的写入语义。
- 不引入 dbt 本体、不生成 `sources.json` / `manifest.json` 文件产物。
- 不改 `inspection_record` / `inspection_issue` 表结构与既有 REST 响应契约。

## Current State

### 规则实现

`InspectionService.runFullInspection` 遍历启用规则，经 `InspectionRuleRegistry` 分发到各 handler（见 `docs/design/2026-06-21-inspection-registry-datatable-sink-design.md`）。新鲜度规则由 `backend/src/main/java/com/onedata/portal/service/inspection/DataFreshnessRuleHandler.java` 承载，逻辑为：

1. 取 `data_table` 中 `status = active` 且 `statistics_cycle` 非空的表（`DataFreshnessRuleHandler.java:43`）。
2. 用 `parseUpdateCycle(statistics_cycle)` 把周期换算成小时（`DataFreshnessRuleHandler.java:98`）。
3. 阈值 = 周期 + 规则级 `toleranceHours`（默认 2 小时，`DataFreshnessRuleHandler.java:40`）。
4. 比较 `data_table.doris_update_time` 与阈值，超期则按延迟倍数推导 `severity` 并写入 `inspection_issue`（`DataFreshnessRuleHandler.java:68`）。

### 信号来源

`doris_update_time` 的链路是：`DorisConnectionService` 读 `information_schema.tables.UPDATE_TIME`（`DorisConnectionService.java:1115`）→ `DorisMetadataSyncService` 落到 `data_table.doris_update_time`（`DorisMetadataSyncService.java:1138`）。同步由 `DataSourceMetadataStatisticsSyncTask`（`fixedDelay` 默认 600000 ms）与 `DataSourceMetadataAutoSyncTask`（按数据源 cron，每分钟扫描）驱动。

### 阈值来源

`statistics_cycle` 并不是 SLA 字段，而是**表命名规范的一段**：`TableNameGeneratorService` 会把它拼进表名（`TableNameGeneratorService.java:60`）。前端可选值只有 5 个（`frontend/src/views/datastudio/CreateTableDrawer.vue:338`）：`10m`、`30m`、`1h`、`1d`、`realtime`。

### 可达性

`inspection_rule` 的种子数据只覆盖 8 条规则（`V1__init.sql:294`）加 `TABLET_SIZE_CHECK`（`V22__inspection_rules_default_disabled.sql:29`），**不含 `data_freshness`**。`InspectionController` 只暴露 `GET /rules` 与 `PUT /rules/{ruleId}/enabled`，没有创建规则或编辑 `rule_config` 的接口。

### 结果留痕

只有命中问题时才写 `inspection_issue`；`pass` 不留痕。调度入口只有 `InspectionScheduledTask` 的每日 02:00 全量巡检。

## Problem

1. **信号口径不对。** `information_schema.tables.UPDATE_TIME` 是物理写入时间，compaction、DDL、空导入都会推进它，而业务关心的是「数据加载到了哪个时点」。且该值经过一次元数据同步落库，本身最多滞后一个同步周期（默认 10 分钟），分钟级 SLA 不可用。
2. **阈值解析有缺陷。** `parseUpdateCycle` 把后缀 `m` 当月处理，于是 `10m` → 7200 小时、`30m` → 21600 小时；`realtime` 无法解析直接 `continue`。前端能产生的 5 个取值中，3 个失效、2 个（`1h` / `1d`）可用。
3. **语义不足。** 没有 `loaded_at_field`、没有 `filter`；没有 warn/error 两级阈值（`severity` 由延迟倍数推导，不可配置）；阈值只有规则级 `toleranceHours`，没有表级覆盖。
4. **出厂不可用。** 没有种子规则行，也没有写 `rule_config` 的接口，启用该规则必须直接改数据库。
5. **观测不足。** 只有问题留痕，没有「已检查且新鲜」的记录，回答不了「这张表上次检查是什么时候」「近 7 天 SLA 达成率」。
6. **时机不足。** 唯一入口是每日 02:00 全量巡检，小时级与分钟级 SLA 无法覆盖。

## Design

### 1. 新鲜度契约与继承链

契约字段对齐 dbt：`mode`、`loadedAtField`、`loadedAtQuery`、`filter`、`warnAfter{count, period}`、`errorAfter{count, period}`。`period` 枚举 `minute | hour | day`，与 dbt 的 `TimePeriod` 一致，解析为 `Duration`。

解析采用**逐字段合并**，由具体到通用，与 dbt `merge_freshness` / `merge_freshness_time_thresholds` 的语义一致——上层只覆盖它自己声明过的字段，不是整块二选一：

1. `table_freshness_config` 表级显式配置。`enabled = 0` 表示显式关闭，**短路**整个解析并跳过该表，等价 dbt 的 `freshness: null`。
2. 规则配置 `rule_config.defaults[]`：每项含 `scope`（`clusterIds` / `dbNames` / `layers`）与阈值，命中项提供表级未声明的字段。等价 dbt 的 source 级默认。
3. 阈值仍为空时，由 `statistics_cycle` 推导兜底：`warnAfter = 2 × cycle`、`errorAfter = 3 × cycle`，`realtime` 按 `15 分钟 / 30 分钟` 处理，`mode` 缺省为 `metadata`。
4. 合并后 `warnAfter` 与 `errorAfter` 仍全为空 → 该表不参与新鲜度检查（等价 dbt 中未声明 `freshness` 的 source）。

因此「表级只覆盖 `errorAfter`、`warnAfter` 沿用规则默认」是受支持的写法。取数字段（`mode` / `loadedAtField` / `loadedAtQuery` / `filter`）同样逐字段合并，但规则默认一般不提供 `loadedAtField`——列名逐表不同。

第 3 层要求先修正周期解析：后缀 `m` 一律按**分钟**处理，支持 `s/m/h/d/w`，识别 `realtime`，不再支持「月」。当前无任何入口能产生月周期，且该规则未种子化，故存量影响为零。

### 2. 取值模式

| 模式 | 取数方式 | 适用场景 |
| --- | --- | --- |
| `column` | `SELECT MAX(\`col\`) AS max_loaded_at, NOW() AS snapshotted_at FROM \`db\`.\`tbl\` [WHERE filter]` | 首选，等价 dbt `loaded_at_field` |
| `custom_sql` | `SELECT (<loadedAtQuery>) AS max_loaded_at, NOW() AS snapshotted_at` | 加载时点需要自定义算法，等价 dbt 1.10 的 `loaded_at_query` |
| `partition` | 复用 `DorisConnectionService.listPartitions`（`SHOW PARTITIONS`）取最新分区的可见版本时间 | 大表、按天分区、`MAX()` 成本高 |
| `metadata` | 实时查 `information_schema.tables.UPDATE_TIME` | 无加载时间列的表，等价 dbt 的 metadata-based freshness |

`custom_sql` 照搬 dbt `collect_freshness_custom_sql` 的形状：用户配置的 `loadedAtQuery` 只负责返回一个时间戳，被包成**标量子查询**，`snapshotted_at` 仍由平台自己拼。好处是用户查询与快照时间的取数保持同源同事务，且用户不需要知道结果列名约定。`loadedAtQuery` 与 `loadedAtField` 互斥（与 dbt 一致），保存时校验。

`snapshotted_at` 一律取自 Doris 侧（同一条 SQL 内的 `NOW()`），避免门户与 Doris 时钟偏差。

**单层兜底**：`metadata` 模式实时查询失败时，退回已同步的 `data_table.doris_update_time`，并在结果中标记 `degraded = true`。`column` / `partition` 模式失败不做跨模式兜底，直接判 `runtime_error`——符合 AGENTS.md「fallback 最小、显式、单层」。

### 3. 判定规则

```
age = snapshotted_at - max_loaded_at

max_loaded_at 为空          -> error        (reason = never_loaded)
age >  errorAfter           -> error
age >  warnAfter            -> warn
否则                        -> pass
SQL 失败 / 超时 / 列不存在  -> runtime_error
```

比较用**严格大于**，与 dbt `Time.exceeded` 的 `actual_age > difference` 一致：`age` 恰好等于阈值判 `pass`。先判 `errorAfter` 再判 `warnAfter`，顺序同 `FreshnessThreshold.status`。

`runtime_error` 独立于 `pass`，与 dbt 的 runtime error 语义一致：检查没跑成功不等于数据新鲜。

**有意偏离 dbt 的一点**：dbt 不为「从未加载」单列状态，而是在 `_create_freshness_response` 里把缺失的 `last_modified` 塞成公元 1 年（`datetime(1, 1, 1, tzinfo=UTC)`），让 `age` 溢出成天文数字后自然落进 `error`。本设计改为显式判 `error` 并带 `reason = never_loaded`，理由是问题描述要直接告诉用户「这张表从未产出过数据」，而不是甩一个 `已延迟 17,750,000 小时`。状态结论与 dbt 相同，只是可读性更好。

### 4. 执行与留痕

- `FreshnessCheckService.check(table, contract)` 返回 `FreshnessCheckResult`，每次检查都写一行 `table_freshness_result`（含 `status`、`max_loaded_at`、`snapshotted_at`、`age_seconds`、`mode`、`degraded`、`error_message`、`trigger_type`、`checked_by`）。
- `data_table` 冗余最新态两列 `freshness_status`、`freshness_checked_at`，供表列表与巡检页按状态过滤，避免每行关联最新结果。
- 批量检查按 `clusterId` 分组，固定大小线程池 + 每集群并发上限 + JDBC `queryTimeout`（默认 30s），避免巡检把 Doris 打满。

### 5. 与巡检的关系

`DataFreshnessRuleHandler` 保留 `ruleType = data_freshness`，改为：解析 `scope` → 取表集合 → 调 `FreshnessCheckService` 批量检查 → 只把非 `pass` 结果转成 `inspection_issue`。严重程度映射：`warn` → 规则配置的 `warnSeverity`（默认 `medium`），`error` → `critical`，`runtime_error` → `high`。既有问题查询、状态流转与修复链路不变。

### 6. 调度

新增 `FreshnessScheduledTask`，`fixedDelayString = ${freshness.check.fixed-delay-ms:900000}`（15 分钟）。每轮只挑「到期需检查」的表：

```
nextCheckAt = lastCheckedAt + max(freshness.check.min-interval-ms, warnAfter / 2)
```

未配置契约且 `statistics_cycle` 无法推导的表不进入候选集，因此在没有任何表配置契约时该任务为空跑，出厂安全。

### 7. 安全约束

- `loadedAtField` 必须精确命中该表在 `data_field` 中的真实列名（大小写不敏感），否则保存即拒绝；执行时统一反引号包裹标识符。
- `filter` 仅作为 `WHERE` 谓词拼接：禁止分号与注释符（`--`、`/*`），长度上限 512，保存需要与巡检规则一致的管理权限。
- `loadedAtQuery` 是自由 SQL，约束比 `filter` 更严：必须以 `SELECT` 开头、禁止分号与注释符、长度上限 2048，且只允许管理员配置。它被包成标量子查询执行，语法上无法携带第二条语句。
- 检查走 `DorisConnectionService` 的只读查询路径，带 `queryTimeout`，不复用 `DataQueryService` 的用户态权限链路（检查是系统态行为）。

## Interfaces / Data Model

### 数据库（Flyway `V50__table_freshness.sql`）

```sql
CREATE TABLE table_freshness_config (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_id          BIGINT      NOT NULL,
  mode              VARCHAR(16) NOT NULL DEFAULT 'metadata',  -- column | custom_sql | partition | metadata
  loaded_at_field   VARCHAR(128) DEFAULT NULL,
  loaded_at_query   VARCHAR(2048) DEFAULT NULL,               -- 与 loaded_at_field 互斥
  filter_expr       VARCHAR(512) DEFAULT NULL,
  warn_after_count  INT         DEFAULT NULL,
  warn_after_period VARCHAR(16) DEFAULT NULL,                 -- minute | hour | day
  error_after_count INT         DEFAULT NULL,
  error_after_period VARCHAR(16) DEFAULT NULL,
  enabled           TINYINT(1)  NOT NULL DEFAULT 1,
  created_by        VARCHAR(50) DEFAULT NULL,
  updated_by        VARCHAR(50) DEFAULT NULL,
  created_at        DATETIME    DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_table (table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表级数据新鲜度契约';

CREATE TABLE table_freshness_result (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_id       BIGINT      NOT NULL,
  cluster_id     BIGINT      DEFAULT NULL,
  db_name        VARCHAR(128) DEFAULT NULL,
  table_name     VARCHAR(128) DEFAULT NULL,
  mode           VARCHAR(16) NOT NULL,
  status         VARCHAR(16) NOT NULL,        -- pass | warn | error | runtime_error
  max_loaded_at  DATETIME    DEFAULT NULL,
  snapshotted_at DATETIME    DEFAULT NULL,
  age_seconds    BIGINT      DEFAULT NULL,
  warn_after_seconds  BIGINT DEFAULT NULL,
  error_after_seconds BIGINT DEFAULT NULL,
  degraded       TINYINT(1)  NOT NULL DEFAULT 0,
  error_message  VARCHAR(512) DEFAULT NULL,
  trigger_type   VARCHAR(16) DEFAULT NULL,    -- manual | schedule | inspection
  checked_by     VARCHAR(50) DEFAULT NULL,
  created_at     DATETIME    DEFAULT CURRENT_TIMESTAMP,
  KEY idx_table_time (table_id, created_at),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据新鲜度检查结果';

ALTER TABLE data_table
  ADD COLUMN freshness_status     VARCHAR(16) DEFAULT NULL COMMENT '最新新鲜度状态',
  ADD COLUMN freshness_checked_at DATETIME    DEFAULT NULL COMMENT '最近新鲜度检查时间';
```

同一迁移种子化规则（`enabled = 0`，与 V22 的默认关闭策略一致）：

```sql
INSERT INTO inspection_rule (rule_code, rule_name, rule_type, severity, description, rule_config, enabled)
VALUES ('DATA_FRESHNESS_CHECK', '数据新鲜度检查', 'data_freshness', 'high',
        '按表级新鲜度契约检查数据是否在约定时限内更新',
        '{"warnSeverity":"medium","queryTimeoutSeconds":30,"maxConcurrentPerCluster":4,"defaults":[]}', 0)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), description = VALUES(description);
```

### REST

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/tables/{id}/freshness` | 返回生效契约（含来源层级）与最近一次结果 |
| PUT | `/v1/tables/{id}/freshness` | upsert 表级契约，含列名白名单与 `filter` 校验 |
| DELETE | `/v1/tables/{id}/freshness` | 删除表级契约，回落到默认继承 |
| POST | `/v1/tables/{id}/freshness/check` | 按需单表检查，等价 `dbt source freshness --select` |
| GET | `/v1/tables/{id}/freshness/history?limit=` | 结果历史 |
| GET | `/v1/inspections/freshness` | 按 `status`/`clusterId`/`dbName` 列出各表最新结果 |
| POST | `/v1/inspections/freshness/run` | 按 scope 批量检查 |
| PUT | `/v1/inspections/rules/{ruleId}` | 补齐规则 `rule_config` / `severity` 编辑 |

响应统一走既有 `Result<T>` 包装；失败沿用控制器 `try/catch → Result.fail(e.getMessage())` 的现行约定。

### 前端

- Data Studio 右侧面板新增 `DataStudioRightPanelFreshness.vue` 页签：契约表单 + 最近结果 + 历史列表 + 「立即检查」。
- 巡检页新增新鲜度视图：按状态过滤的表级最新结果列表。
- 遵循前端视觉规范：`el-descriptions :column="2"` 呈现契约，`el-table` 带 border 呈现历史，不再套一层卡片；同容器的兄弟页签保持同样的扁平处理。

## Risks / Alternatives

- **大表 `MAX()` 扫描成本。** 缓解：优先 `partition` 模式、支持 `filter`（dbt 的同款答案）、`queryTimeout` + 每集群并发上限；文档建议 `loadedAtField` 选 key 列或分区列。
- **时区与时钟偏差。** `max_loaded_at` 与 `snapshotted_at` 同源于 Doris；仅 `metadata` 降级路径使用门户时间，结果以 `degraded = true` 标注。
- **行为变更：`m` 由「月」改为「分钟」。** 影响面仅限新鲜度兜底路径，且该规则当前未种子化，存量为零。需在 CHANGELOG 与 plan 中显式记录。
- **调度叠加负载。** 15 分钟一轮但只取到期表，且未配置契约的表不入候选；出厂空集。
- **`filter` / `loadedAtQuery` 是自由 SQL。** 通过位置约束（谓词 / 标量子查询）+ 字符黑名单 + 长度上限 + 权限控制降低风险，不做通用 SQL 解析。

**与 dbt 的对齐与偏离清单**（便于后续对照 dbt 版本演进）：

| 维度 | dbt 行为 | 本设计 |
| --- | --- | --- |
| 阈值比较 | `Time.exceeded` 用 `age > threshold` | 一致，严格大于 |
| 判定顺序 | 先 `error_after` 后 `warn_after` | 一致 |
| 配置继承 | `merge_freshness` 逐字段合并 source / table | 一致，逐字段合并三层来源 |
| 快照时间 | 仓库侧 `current_timestamp()`，与 `max_loaded_at` 同查询 | 一致，Doris 侧 `NOW()` |
| 自定义取数 | 1.10 `loaded_at_query`，包成标量子查询 | 一致，`custom_sql` 模式 |
| 从未加载 | `max_loaded_at` 置公元 1 年，靠溢出落进 error | **偏离**：显式 `error` + `reason = never_loaded` |
| 元数据取数 | `get_relation_last_modified` 按 schema 批量查 | **偏离**：当前按表查 `information_schema.tables`，批量化留作后续优化 |
| 结果产物 | `target/sources.json` 文件 | **偏离**：落 `table_freshness_result` 表，便于查询与趋势 |
| 触发方式 | 独立命令 `dbt source freshness`，不随 `dbt build` | 一致：独立调度 + 按需接口，同时额外接入每日巡检 |

**备选方案：**

1. **只修 `parseUpdateCycle`。** 成本最低，但信号仍是 `UPDATE_TIME`、没有 warn/error 两级、没有留痕，达不到 dbt 语义。判定为「必要但不充分」，已并入本设计第 1 阶段。
2. **引入 dbt-core 执行 `source freshness`。** 需要 Python 运行时、`profiles.yml`、`sources.yml` 与本仓元数据双写，和现有元数据中心职责重复，运维成本高。否决。
3. **每张表建一个 DolphinScheduler 校验任务。** 调度侧对象膨胀，且结果与巡检问题面割裂。否决；工作流门禁后续以只读状态接口形式实现。
4. **只存最新态、不存历史。** 省一张表，但失去趋势与 SLA 达成率，也无法回答「上次检查时间」。否决。

## Verification

- **后端单测：** 周期解析（`10m/30m/1h/1d/realtime/非法值`）、状态判定边界（`age` 恰好等于阈值判 `pass`、超过 1 秒判 `warn`/`error`、`max_loaded_at` 为空、`runtime_error`）、继承链逐字段合并（表级只覆盖 `errorAfter` 时 `warnAfter` 来自规则默认）与显式关闭短路、`loadedAtField` 白名单与 `filter` / `loadedAtQuery` 校验（含二者互斥）、handler → `inspection_issue` 映射、控制器参数透传。
- **迁移：** `V50` 在本地 MySQL 上 `flyway migrate` 通过；`InspectionRuleHandlerCoverageTest` 同步更新。
- **前端：** `nvm use` 后跑新增组件 Vitest 与生产构建。
- **端到端：** 完整链路需要可访问的 Doris 集群（`column` / `partition` 模式依赖真实表与真实列）。当前仓库环境不具备 Doris 实例，如果实施时仍不可用，须在提交说明中明确标注「未跑真实 Doris 全链路」，并说明已验证到哪一层。
