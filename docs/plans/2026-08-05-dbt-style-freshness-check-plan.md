# 类 dbt 数据新鲜度检查实施计划

> Design: [2026-08-05-dbt-style-freshness-check-design.md](../design/2026-08-05-dbt-style-freshness-check-design.md)

**Goal:** 交付表级新鲜度契约、独立的新鲜度检查执行与结果留痕，并把 `data_freshness` 巡检规则改为消费该结果。
**Tech Stack:** 后端（Java 8 · Spring Boot 2.7 · MyBatis-Plus · Flyway · Doris JDBC）、前端（Vue 3 · Element Plus · Vitest）、数据库（MySQL 8）。

## Architecture Summary

```
FreshnessScheduledTask ─┐
POST /tables/{id}/freshness/check ─┤
DataFreshnessRuleHandler ─┘
              │
              ▼
      FreshnessCheckService
   ├─ FreshnessContractResolver（表级 → 规则 defaults → statistics_cycle → 不检查）
   ├─ 取值：column(MAX+NOW) / partition(SHOW PARTITIONS) / metadata(UPDATE_TIME，单层降级)
   ├─ 判定：pass | warn | error | runtime_error
   └─ 留痕：table_freshness_result + data_table.freshness_status/checked_at
              │
              ▼
   非 pass → InspectionSupport.createIssue/insertIssue（沿用既有问题面）
```

按任务顺序实施，前 5 个任务构成可独立发布的后端闭环，任务 6-7 为接口与前端，任务 8 为文档与收尾。

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
1. 按设计文档 `Interfaces / Data Model` 建 `table_freshness_config`、`table_freshness_result` 两张表，`data_table` 增加 `freshness_status`、`freshness_checked_at`。
2. 同一迁移插入 `DATA_FRESHNESS_CHECK` 规则种子，`enabled = 0`，`rule_config` 含 `warnSeverity` / `queryTimeoutSeconds` / `maxConcurrentPerCluster` / `defaults`，使用 `ON DUPLICATE KEY UPDATE` 保持幂等。
3. 新增两个实体（`@TableName` + Lombok `@Data`，时间字段用 `@TableField(fill = ...)`，与 `InspectionIssue` 风格一致）与对应 MyBatis-Plus Mapper。
4. `DataTable` 补两个字段，字段名与列名一致处不加 `@TableField`，与现有写法保持一致。

**Expected Result:**
- `mvn -pl backend -am test-compile -DskipTests` 通过。
- 本地 MySQL 上迁移可重复执行且幂等。

## Task 2: 契约模型与继承链解析

**Files:**
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessPeriod.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessMode.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessContract.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessContractResolver.java`
- `backend/src/main/java/com/onedata/portal/util/UpdateCycleParser.java`

**Steps:**
1. `FreshnessPeriod` 枚举 `MINUTE/HOUR/DAY`，提供 `toDuration(count)`；`FreshnessMode` 枚举 `COLUMN/PARTITION/METADATA`。
2. `FreshnessContract` 为不可变值对象：`mode`、`loadedAtField`、`filterExpr`、`warnAfter`、`errorAfter`、`source`（`TABLE/RULE_DEFAULT/STATISTICS_CYCLE`）。
3. `UpdateCycleParser.parse(String)` 返回 `Optional<Duration>`：支持 `s/m/h/d/w`，`m` 一律按**分钟**处理，识别 `realtime` 返回 15 分钟基准，不再支持「月」。
4. `FreshnessContractResolver.resolve(table, ruleConfig)` 按设计文档四层顺序返回 `Optional<FreshnessContract>`：表级配置（`enabled = 0` 直接返回空）→ `rule_config.defaults[]` 按 `clusterIds/dbNames/layers` 匹配 → `statistics_cycle` 推导（`warn = 2×cycle`、`error = 3×cycle`、模式固定 `METADATA`）→ 空。
5. 命中即返回，不做跨层合并。

**Expected Result:**
- 新增 `UpdateCycleParserTest`、`FreshnessContractResolverTest` 覆盖 5 个前端取值、非法值、四层命中顺序与显式关闭。

## Task 3: 检查执行服务

**Files:**
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessCheckResult.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessCheckService.java`
- `backend/src/main/java/com/onedata/portal/service/DorisConnectionService.java`

**Steps:**
1. `DorisConnectionService` 增加 `Optional<FreshnessProbe> probeMaxLoadedAt(clusterId, database, tableName, loadedAtField, filterExpr, timeoutSeconds)`：单条 SQL 同时取 `MAX(col)` 与 `NOW()`，标识符反引号包裹，`filterExpr` 直接拼在 `WHERE` 后，设置 `Statement#setQueryTimeout`。
2. 增加 `Optional<LocalDateTime> probeMetadataUpdateTime(clusterId, database, tableName)`，实时查 `information_schema.tables.UPDATE_TIME`。
3. `FreshnessCheckService.check(DataTable, FreshnessContract, triggerType, operator)`：
   - `COLUMN` → `probeMaxLoadedAt`；`PARTITION` → 复用 `listPartitions` 取最新分区可见版本时间；`METADATA` → `probeMetadataUpdateTime`，失败时**单层降级**到 `data_table.doris_update_time` 并置 `degraded = true`。
   - 按设计文档判定 `pass | warn | error | runtime_error`；`max_loaded_at` 为空判 `error`，异常/超时判 `runtime_error`。
   - 写 `table_freshness_result`，回写 `data_table.freshness_status` 与 `freshness_checked_at`。
4. `checkBatch(List<DataTable>, ruleConfig, triggerType, operator)`：按 `clusterId` 分组，线程池并发上限取 `rule_config.maxConcurrentPerCluster`（默认 4），单表异常不影响其余表。

**Expected Result:**
- 新增 `FreshnessCheckServiceTest`：mock `DorisConnectionService`，覆盖四种状态、阈值边界（`age == warnAfter` / `age == errorAfter`）、metadata 降级、单表异常隔离。

## Task 4: 巡检规则改造

**Files:**
- `backend/src/main/java/com/onedata/portal/service/inspection/DataFreshnessRuleHandler.java`
- `backend/src/test/java/com/onedata/portal/service/inspection/InspectionRuleHandlerCoverageTest.java`

**Steps:**
1. `DataFreshnessRuleHandler` 注入 `FreshnessCheckService` 与 `FreshnessContractResolver`，删除本地 `parseUpdateCycle` / `calculateDelayHours` / `calculateFreshnessSeverity`。
2. 取表范围仍走 `support.applyTableScope`，但不再强制要求 `statistics_cycle` 非空——契约可能来自表级配置或规则默认；解析不到契约的表跳过。
3. 只对非 `pass` 结果调用 `support.createIssue` + `support.insertIssue`；severity 映射：`warn` → `rule_config.warnSeverity`（默认 `medium`）、`error` → `critical`、`runtime_error` → `high`。
4. 问题文案写清 `currentValue`（最后加载时间 + 已延迟时长）、`expectedValue`（warn/error 阈值）、`suggestion`（保留现有排查清单，`runtime_error` 换成检查列名/权限/连通性的清单）。
5. `ruleType()` 保持 `data_freshness`，覆盖测试的规则类型清单不变。

**Expected Result:**
- 新增 `DataFreshnessRuleHandlerTest` 覆盖 warn/error/runtime_error → issue 映射与 `pass` 不产生 issue。
- `InspectionRuleHandlerCoverageTest` 仍通过。

## Task 5: 独立调度

**Files:**
- `backend/src/main/java/com/onedata/portal/scheduled/FreshnessScheduledTask.java`
- `backend/src/main/resources/application.yml`

**Steps:**
1. 新增 `@Scheduled(fixedDelayString = "${freshness.check.fixed-delay-ms:900000}")` 任务，开关 `${freshness.check.enabled:true}`。
2. 候选集 = 有表级契约的表 ∪ 能由 `statistics_cycle` 推导契约的表；到期判定 `nextCheckAt = freshnessCheckedAt + max(freshness.check.min-interval-ms, warnAfter/2)`。
3. 调 `FreshnessCheckService.checkBatch(..., "schedule", "system")`，整体 try/catch 记日志，不抛出。
4. `application.yml` 增加 `freshness.check.*` 默认值并注释说明。

**Expected Result:**
- 新增 `FreshnessScheduledTaskTest` 覆盖「无契约表不入候选」「未到期不检查」「异常不外抛」。

## Task 6: REST 接口

**Files:**
- `backend/src/main/java/com/onedata/portal/controller/DataTableController.java`
- `backend/src/main/java/com/onedata/portal/controller/InspectionController.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/TableFreshnessService.java`
- `backend/src/main/java/com/onedata/portal/dto/TableFreshnessRequest.java`
- `backend/src/main/java/com/onedata/portal/dto/TableFreshnessResponse.java`
- `backend/src/main/java/com/onedata/portal/service/InspectionService.java`

**Steps:**
1. `TableFreshnessService` 承载契约 upsert / 删除 / 查询 / 单表检查 / 历史查询；保存时校验 `loadedAtField` 命中 `data_field` 中该表真实列名，`filterExpr` 拒绝分号与注释符并限长 512，`COLUMN` 模式必须有 `loadedAtField`。
2. `DataTableController` 增加 `GET/PUT/DELETE /{id}/freshness`、`POST /{id}/freshness/check`、`GET /{id}/freshness/history`，控制器只做绑定 + 鉴权注解 + `try/catch → Result.fail(e.getMessage())`，业务全部在服务层（沿用 2026-06-21 设计确立的下沉约定）。
3. `InspectionController` 增加 `GET /freshness`、`POST /freshness/run`，以及 `PUT /rules/{ruleId}`（更新 `ruleConfig` / `severity` / `ruleName` / `description`，`ruleType` 与 `ruleCode` 不可改）。
4. `InspectionService` 增加对应的规则更新与新鲜度结果查询方法。

**Expected Result:**
- 新增/更新 `DataTableControllerTest`、`InspectionControllerTest`、`TableFreshnessServiceTest`（含列名白名单与 `filter` 拒绝用例）全绿。

## Task 7: 前端

**Files:**
- `frontend/src/api/*`（新增新鲜度接口封装，沿用现有 api 模块组织方式）
- `frontend/src/views/datastudio/components/DataStudioRightPanelFreshness.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`
- `frontend/src/views/inspection/InspectionView.vue`
- `frontend/src/views/datastudio/__tests__/freshnessPanel.spec.js`

**Steps:**
1. 新增右侧面板「数据新鲜度」页签（`lazy`），插在「访问情况」之后：契约用 `el-descriptions :column="2"` 展示（列数按容器宽度自适应，参考同目录既有面板做法），编辑用抽屉或内联表单；下方 `el-table` 带 border 展示最近结果历史，顶部「立即检查」按钮。
2. 状态用 `el-tag` 着色：`pass` 成功、`warn` 警告、`error` 危险、`runtime_error` 信息灰。
3. `InspectionView.vue` 中 `data_freshness` 的中文名保持「数据新鲜度」，新增按新鲜度状态筛选的表级视图入口。
4. 不再额外包一层卡片；同容器兄弟页签保持一致的扁平结构。

**Expected Result:**
- `npm --prefix frontend run test -- src/views/datastudio/__tests__/freshnessPanel.spec.js` 通过。
- `npm --prefix frontend run build` 通过。

## Task 8: 文档与收尾

**Files:**
- `docs/handbook/features/data-freshness.md`
- `CHANGELOG.md`
- `docs/plans/2026-08-05-dbt-style-freshness-check-plan.md`

**Steps:**
1. 新增 handbook 文档：契约字段含义、三种模式的选型建议、继承链、状态语义、与 dbt 概念对照表、常见故障排查。
2. `CHANGELOG.md` 记录新增能力，并**显式标注行为变更**：周期后缀 `m` 由「月」改为「分钟」，`realtime` 不再被跳过。
3. 回填本 plan 的任务勾选状态与实际验证结论。

**Expected Result:**
- 文档目录、命名与交叉链接符合 `docs/design/README.md` 与 `docs/plans/README.md` 规则。

---

## Verification

```bash
mvn -pl backend -am \
  -Dtest='UpdateCycleParserTest,FreshnessContractResolverTest,FreshnessCheckServiceTest,DataFreshnessRuleHandlerTest,FreshnessScheduledTaskTest,TableFreshnessServiceTest,InspectionRuleHandlerCoverageTest,DataTableControllerTest,InspectionControllerTest' \
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

1. 对一张有加载时间列的表 `PUT /v1/tables/{id}/freshness`，设 `mode = column`、`warnAfter = 1 hour`、`errorAfter = 3 hour`。
2. `POST /v1/tables/{id}/freshness/check` 应返回 `pass`，`table_freshness_result` 落一行。
3. 把 `warnAfter` 调到 1 分钟后重跑，应返回 `warn`；`errorAfter` 调到 1 分钟应返回 `error`。
4. 把 `loadedAtField` 改成不存在的列（绕过接口直接改库）后重跑，应返回 `runtime_error` 而非 `pass`。
5. 启用 `DATA_FRESHNESS_CHECK` 规则跑一次 `POST /v1/inspections/run`，确认非 `pass` 的表在 `inspection_issue` 中生成对应问题、`pass` 的表不生成。

若 Doris 不可用，须在提交说明与 plan 回填中写明：已验证到单测与迁移层，`column` / `partition` 真实取数路径未跑。

## Rollout / Backout

**Rollout：**

- 迁移种子规则 `enabled = 0`，不配置任何表级契约时新鲜度检查为空跑，升级后行为与升级前一致。
- 灰度顺序：先对少量核心表配置契约并手动触发检查 → 观察 `table_freshness_result` 与 Doris 负载 → 再打开 `DATA_FRESHNESS_CHECK` 规则接入每日巡检 → 最后依赖独立调度覆盖小时级 SLA。
- `freshness.check.enabled=false` 可单独关掉独立调度，不影响巡检路径。

**Backout：**

- 关闭 `DATA_FRESHNESS_CHECK` 规则 + `freshness.check.enabled=false`，即可停止全部新鲜度行为，无需回滚代码。
- 需要回滚代码时，`V50` 新增的两张表与两列可保留（不被旧代码读写），不做 `down` 迁移，避免丢历史结果。
