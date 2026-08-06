# 类 dbt 数据新鲜度检查实施计划

> Design: [2026-08-05-dbt-style-freshness-check-design.md](../design/2026-08-05-dbt-style-freshness-check-design.md)

**Goal:** 交付表级新鲜度契约、检查执行与结果留痕，未配置契约的表不参与检查；`data_freshness` 巡检规则改为消费检查结果。
**Tech Stack:** 后端（Java 8 · Spring Boot 2.7 · MyBatis-Plus · Flyway · Doris JDBC）、前端（Vue 3 · Element Plus · Vitest）、数据库（MySQL 8）。

## Architecture Summary

```
三个触发点 ─ FreshnessScheduledTask（定时）
           ├ POST /tables/{id}/freshness/check（按需）
           ├ WorkflowExecutionSyncJob（工作流成功后，查它写出的表）
           └ DataFreshnessRuleHandler（每日巡检）
                        │  同一套逻辑，触发点不改变判定
                        ▼
              FreshnessCheckService
   ├─ FreshnessContractResolver（表级 ⊕ 规则 defaults，逐字段合并；无阈值 → 不检查）
   ├─ 取值：column / custom_sql / partition / metadata，用户显式选择，无自动兜底
   ├─ 判定：pass | warn | error | runtime_error，阈值比较严格大于
   └─ 留痕：table_freshness_result + data_table.freshness_status/checked_at
                        │
                        ▼
        非 pass → InspectionSupport.createIssue/insertIssue（沿用既有问题面）
```

Task 1-5 构成可独立发布的后端闭环，Task 6-7 为接口与前端，Task 8 为文档收尾。

---

## Task 1: 数据库迁移与实体

**Files:**
- `backend/src/main/resources/db/migration/V50__table_freshness.sql`
- `backend/src/main/java/com/onedata/portal/entity/TableFreshnessConfig.java`
- `backend/src/main/java/com/onedata/portal/entity/TableFreshnessResult.java`
- `backend/src/main/java/com/onedata/portal/entity/DataTable.java`
- `backend/src/main/java/com/onedata/portal/mapper/TableFreshnessConfigMapper.java`
- `backend/src/main/java/com/onedata/portal/mapper/TableFreshnessResultMapper.java`

**Steps:**
1. 按设计文档 `Interfaces / Data Model` 建 `table_freshness_config`、`table_freshness_result`，`data_table` 增加 `freshness_status`、`freshness_checked_at`。
2. 同一迁移插入 `DATA_FRESHNESS_CHECK` 规则种子，`enabled = 0`，`rule_config` 含 `warnSeverity` / `queryTimeoutSeconds` / `maxConcurrentPerCluster` / `reportUnconfigured` / `defaults`，用 `ON DUPLICATE KEY UPDATE` 保持幂等。
3. 新增两个实体（`@TableName` + Lombok `@Data`，时间字段用 `@TableField(fill = ...)`，风格对齐 `InspectionIssue`）与对应 Mapper。
4. `DataTable` 补两个字段。

**Expected Result:**
- `mvn -pl backend -am test-compile -DskipTests` 通过；迁移在本地 MySQL 可重复执行且幂等。

## Task 2: 契约模型与解析

**Files:**
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessPeriod.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessMode.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessContract.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessContractResolver.java`

**Steps:**
1. `FreshnessPeriod` 枚举 `MINUTE/HOUR/DAY`，提供 `toDuration(count)`；`FreshnessMode` 枚举 `COLUMN/CUSTOM_SQL/PARTITION/METADATA`。
2. `FreshnessContract` 为不可变值对象：`mode`、`loadedAtField`、`loadedAtQuery`、`partitionFormat`、`filterExpr`、`warnAfter`、`errorAfter`，外加各字段来源标记（`TABLE` / `RULE_DEFAULT`）供接口回显。
3. `FreshnessContractResolver.resolve(table, ruleConfig)` 返回 `Optional<FreshnessContract>`，**只有两层**，逐字段合并（对齐 dbt `merge_freshness`）：
   - 表级配置 `enabled = 0` → 短路返回空。
   - 逐字段优先级：表级配置 → `rule_config.defaults[]` 中按 `clusterIds/dbNames/layers` 命中的项。
   - 合并后 `warnAfter` 与 `errorAfter` 均为空 → 返回空，该表不检查。
4. **不实现**任何从 `statistics_cycle` / `schedule_cron` / 列名模式推导的逻辑。

**Expected Result:**
- 新增 `FreshnessContractResolverTest`：无任何配置返回空、`enabled = 0` 短路、表级只声明 `errorAfter` 时 `warnAfter` 取自规则默认、`scope` 不命中时不套用默认。

## Task 3: 检查执行服务

**Files:**
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessCheckResult.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessCheckService.java`
- `backend/src/main/java/com/onedata/portal/service/DorisConnectionService.java`

**Steps:**
1. `DorisConnectionService` 增加 `probeMaxLoadedAt(clusterId, database, tableName, loadedAtField, filterExpr, timeoutSeconds)`：单条 SQL 同时取 `MAX(col)` 与 `NOW()`，标识符反引号包裹，`filterExpr` 拼在 `WHERE` 后，设置 `Statement#setQueryTimeout`。
2. 增加 `probeMaxLoadedAtByQuery(clusterId, database, loadedAtQuery, timeoutSeconds)`：`SELECT (<loadedAtQuery>) AS max_loaded_at, NOW() AS snapshotted_at`，形状照搬 dbt `default__collect_freshness_custom_sql`——用户查询只返回时间戳。
3. 增加 `probeMetadataUpdateTime(clusterId, database, tableName)`，实时查 `information_schema.tables.UPDATE_TIME`。
4. `FreshnessCheckService.check(DataTable, FreshnessContract, triggerType, operator)`：
   - `COLUMN` / `CUSTOM_SQL` / `METADATA` 分别调上述三个探针；`PARTITION` 复用 `listPartitions` 取最新分区，按 `partitionFormat` 解析分区值为业务日期，解析失败判 `runtime_error`。
   - 判定：`age > errorAfter` → `error`，`age > warnAfter` → `warn`，**严格大于**（对齐 dbt `Time.exceeded`），先 error 后 warn；`max_loaded_at` 为空判 `error` 且 `reason = never_loaded`；异常/超时判 `runtime_error`。
   - 写 `table_freshness_result`，回写 `data_table.freshness_status` 与 `freshness_checked_at`。
5. `checkBatch(List<DataTable>, ruleConfig, triggerType, operator)`：按 `clusterId` 分组，并发上限取 `rule_config.maxConcurrentPerCluster`（默认 4），单表异常不影响其余表；无契约的表直接跳过、不落结果。

**Expected Result:**
- 新增 `FreshnessCheckServiceTest`：mock `DorisConnectionService`，覆盖四种状态、边界（`age == warnAfter` / `age == errorAfter` 判上一档，`阈值 + 1s` 才升档）、`never_loaded`、四种模式取数、`partition` 解析失败、单表异常隔离、无契约不落结果。

## Task 4: 巡检规则改造

**Files:**
- `backend/src/main/java/com/onedata/portal/service/inspection/DataFreshnessRuleHandler.java`
- `backend/src/test/java/com/onedata/portal/service/inspection/InspectionRuleHandlerCoverageTest.java`

**Steps:**
1. 注入 `FreshnessCheckService` 与 `FreshnessContractResolver`，**删除** `parseUpdateCycle` / `calculateDelayHours` / `calculateFreshnessSeverity` / `getCycleDescription` / `generateFreshnessSuggestion`。
2. 取表范围仍走 `support.applyTableScope`，去掉 `statistics_cycle` 非空的过滤条件；逐表解析契约，解析不到的跳过。
3. 只对非 `pass` 结果调 `support.createIssue` + `support.insertIssue`；severity 映射：`warn` → `rule_config.warnSeverity`（默认 `medium`）、`error` → `critical`、`runtime_error` → `high`。
4. 问题文案写清 `currentValue`（最后加载时间 + 已延迟时长；`never_loaded` 写「从未产出过数据」而非天文数字）、`expectedValue`（warn/error 阈值）、`suggestion`（`runtime_error` 用检查列名/权限/连通性的清单）。
5. `rule_config.reportUnconfigured = true` 时，为 scope 内解析不到契约的表产出一条治理型问题（`severity = low`，描述引导去配置契约）；默认 `false` 不产出。
6. `ruleType()` 保持 `data_freshness`。

**Expected Result:**
- 新增 `DataFreshnessRuleHandlerTest`：warn/error/runtime_error → issue 映射、`pass` 不产生 issue、无契约表默认不产生 issue、`reportUnconfigured = true` 时产生治理型 issue。
- `InspectionRuleHandlerCoverageTest` 仍通过。

## Task 5: 定时与工作流触发

**Files:**
- `backend/src/main/java/com/onedata/portal/scheduled/FreshnessScheduledTask.java`
- `backend/src/main/java/com/onedata/portal/scheduled/WorkflowExecutionSyncJob.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessCheckService.java`
- `backend/src/main/resources/application.yml`

**Steps:**
1. `FreshnessScheduledTask`：`@Scheduled(fixedDelayString = "${freshness.check.fixed-delay-ms:900000}")`，开关 `${freshness.check.enabled:true}`。候选集 = 存在启用中 `table_freshness_config` 的表；到期判定 `nextCheckAt = freshnessCheckedAt + max(freshness.check.min-interval-ms, warnAfter / 2)`。整体 try/catch 记日志不外抛。
2. `WorkflowExecutionSyncJob` 在同步中识别**新变为成功**的工作流实例（以缓存中已有状态与本次同步结果比对，避免重复触发），取该工作流各任务经 `table_task_relation`（`relation_type = write`）关联的表，调 `checkBatch(..., "workflow", "system")`。触发失败只记日志，不影响同步作业主流程。
3. `application.yml` 增加 `freshness.check.*` 默认值与注释。

**Expected Result:**
- 新增 `FreshnessScheduledTaskTest`：无契约表不入候选、未到期不检查、异常不外抛。
- 新增 `WorkflowFreshnessTriggerTest`：只对 `relation_type = write` 的表触发、同一实例不重复触发、触发异常不影响同步。

## Task 6: REST 接口

**Files:**
- `backend/src/main/java/com/onedata/portal/service/freshness/TableFreshnessService.java`
- `backend/src/main/java/com/onedata/portal/controller/DataTableController.java`
- `backend/src/main/java/com/onedata/portal/controller/InspectionController.java`
- `backend/src/main/java/com/onedata/portal/service/InspectionService.java`
- `backend/src/main/java/com/onedata/portal/dto/TableFreshnessRequest.java`
- `backend/src/main/java/com/onedata/portal/dto/TableFreshnessResponse.java`

**Steps:**
1. `TableFreshnessService` 承载契约 upsert / 删除 / 查询 / 单表检查 / 历史查询。保存校验：
   - `loadedAtField` 必须命中 `data_field` 中该表真实列名（大小写不敏感）。
   - `filterExpr` 拒绝分号与注释符，限长 512。
   - `loadedAtQuery` 必须以 `SELECT` 开头，拒绝分号与注释符，限长 2048，需管理员权限。
   - `loadedAtField` 与 `loadedAtQuery` 互斥。
   - `COLUMN` 必须有 `loadedAtField`，`CUSTOM_SQL` 必须有 `loadedAtQuery`，`PARTITION` 必须有 `partitionFormat` 且表有分区列。
   - 查询接口回显生效契约时带上每个字段来源（表级 / 规则默认）。
2. `DataTableController` 增加 `GET/PUT/DELETE /{id}/freshness`、`POST /{id}/freshness/check`、`GET /{id}/freshness/history`；控制器只做绑定 + 鉴权注解 + `try/catch → Result.fail(e.getMessage())`。
3. `InspectionController` 增加 `GET /freshness`、`POST /freshness/run`，以及 `PUT /rules/{ruleId}`（更新 `ruleConfig` / `severity` / `ruleName` / `description`，`ruleType` 与 `ruleCode` 不可改）；`InspectionService` 补对应方法。

**Expected Result:**
- 新增/更新 `TableFreshnessServiceTest`、`DataTableControllerTest`、`InspectionControllerTest` 全绿，含各项校验拒绝用例。

## Task 7: 前端

**Files:**
- `frontend/src/api/*`（新增新鲜度接口封装，沿用现有 api 模块组织方式）
- `frontend/src/views/datastudio/components/DataStudioRightPanelFreshness.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`
- `frontend/src/views/inspection/InspectionView.vue`
- `frontend/src/views/datastudio/__tests__/freshnessPanel.spec.js`

**Steps:**
1. 新增右侧面板「数据新鲜度」页签（`lazy`），插在「访问情况」之后。契约用 `el-descriptions :column="2"` 展示（列数按容器宽度自适应），规则默认来源的字段标注「继承自规则默认」。
2. 未配置契约时展示引导（说明该表未纳入新鲜度管理 + 「去配置」入口），不展示空表格、不展示假状态。
3. 编辑表单按 `mode` 联动显隐 `loadedAtField` / `loadedAtQuery` / `partitionFormat`；选择 `metadata` 时给出提示：只能发现长期无写入，发现不了写入了旧数据。
4. 结果历史用 `el-table` 带 border；状态 `el-tag` 着色：`pass` 成功、`warn` 警告、`error` 危险、`runtime_error` 信息灰。顶部「立即检查」。
5. `InspectionView.vue` 保留 `data_freshness` 中文名，新增按新鲜度状态筛选的表级视图入口。
6. 不套多余卡片；兄弟页签保持一致的扁平结构。

**Expected Result:**
- `npm --prefix frontend run test -- src/views/datastudio/__tests__/freshnessPanel.spec.js` 通过；`npm --prefix frontend run build` 通过。

## Task 8: 文档与收尾

**Files:**
- `docs/handbook/features/data-freshness.md`
- `CHANGELOG.md`

**Steps:**
1. 新增 handbook 文档：契约三要素（时间字段、warn、error）、四种模式选型与各自能不能发现「写入了旧数据」、两层继承、状态语义（含严格大于的边界）、`partition` 模式的 age 语义提醒（T+1 表 age 恒 ≥ 24h，阈值怎么设）、与 dbt 的对齐与偏离对照表、常见故障排查。
2. `CHANGELOG.md` 记录：新增表级新鲜度契约与检查；**行为变更**——规则语义由「按 `statistics_cycle` 推断」改为「按表级契约」，未配置契约的表不再产出新鲜度问题。
3. 回填本 plan 的任务勾选与实际验证结论。

**Expected Result:**
- 文档目录、命名与交叉链接符合 `docs/design/README.md` 与 `docs/plans/README.md` 规则。

---

## Verification

```bash
mvn -pl backend -am \
  -Dtest='FreshnessContractResolverTest,FreshnessCheckServiceTest,DataFreshnessRuleHandlerTest,FreshnessScheduledTaskTest,WorkflowFreshnessTriggerTest,TableFreshnessServiceTest,InspectionRuleHandlerCoverageTest,DataTableControllerTest,InspectionControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```bash
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use
npm --prefix frontend run test -- src/views/datastudio/__tests__/freshnessPanel.spec.js
npm --prefix frontend run build
```

**端到端冒烟（需要可访问的 Doris 集群）：**

1. 一张未配置契约的表，`POST /v1/inspections/freshness/run` 后确认它**不产生任何结果行、不产生 issue**。
2. 给一张有加载时间列的表 `PUT` 契约：`mode = column`、`warnAfter = 2 hour`、`errorAfter = 4 hour`，`POST .../freshness/check` 应返回 `pass` 并落一行结果。
3. 阈值调到 1 分钟重跑得 `warn`，`errorAfter` 也调到 1 分钟得 `error`。
4. 表级只留 `errorAfter`、清空 `warnAfter`，确认 `warnAfter` 由规则默认补齐且接口回显标注来源。
5. 切 `mode = custom_sql`，`loadedAtQuery` 填 `select max(order_time) from <db>.<tbl>`，结果应与 `column` 模式一致；同时提交 `loadedAtField` 与 `loadedAtQuery` 应被拒绝。
6. 切 `mode = partition`，`partitionFormat = yyyyMMdd`，确认取到最新分区业务日期；把 `partitionFormat` 改错应得 `runtime_error` 而非 `pass`。
7. 空表检查应得 `error` + `reason = never_loaded`，描述不出现天文数字延迟。
8. 跑一次工作流，成功后 5 分钟内确认它写出的表自动产生一条 `trigger_type = workflow` 的结果。
9. 启用 `DATA_FRESHNESS_CHECK` 跑 `POST /v1/inspections/run`，确认非 `pass` 的表产生 issue、`pass` 的不产生、无契约的默认不产生；打开 `reportUnconfigured` 后无契约表产生治理型 issue。

若 Doris 不可用，须在提交说明与 plan 回填中写明：已验证到单测与迁移层，真实取数路径未跑。

## Rollout / Backout

**Rollout：**

- 种子规则 `enabled = 0`，且未配置任何表级契约时检查为空跑，升级后行为与升级前一致。
- 灰度顺序：先给少量核心表配契约并手动触发 → 观察结果与 Doris 负载 → 打开 `DATA_FRESHNESS_CHECK` 接入每日巡检 → 依赖定时与工作流触发覆盖小时级 SLA → 最后按需打开 `reportUnconfigured` 推动补配。
- `freshness.check.enabled=false` 可单独关掉定时调度，不影响巡检与按需路径。

**Backout：**

- 关闭 `DATA_FRESHNESS_CHECK` 规则 + `freshness.check.enabled=false`，即可停止全部新鲜度行为，无需回滚代码。
- 回滚代码时 `V50` 新增的两张表与两列可保留（不被旧代码读写），不做 `down` 迁移，避免丢历史结果。
