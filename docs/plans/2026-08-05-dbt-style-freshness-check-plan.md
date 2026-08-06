# 类 dbt 数据新鲜度检查实施计划

> Design: [2026-08-05-dbt-style-freshness-check-design.md](../design/2026-08-05-dbt-style-freshness-check-design.md)

**Goal:** 交付表级新鲜度契约、检查执行与结果留痕，未配置契约的表不参与检查；`data_freshness` 巡检规则改为消费检查结果。
**Tech Stack:** 后端（Java 8 · Spring Boot 2.7 · MyBatis-Plus · Flyway · Doris JDBC）、前端（Vue 3 · Element Plus · Vitest）、数据库（MySQL 8）。

## 实施状态（2026-08-06 回填）

八个任务全部完成。后端定向单测 49 项、前端 `freshnessPanel.spec.js` 4 项 + RightPanel smoke 16 项全绿，前端生产构建通过。**真实 Doris 全链路未跑**：当前环境无可访问的 Doris 实例，`column` / `custom_sql` / `partition` 的真实取数路径与端到端冒烟（下方「端到端冒烟」9 步）未执行；已验证到单测、迁移、前端构建层。实现与计划的少量偏差记录在各任务后。

## Architecture Summary

```
两个触发点 ─ WorkflowExecutionSyncJob（工作流成功后，查它写出的表 — 主，事件驱动）
           └ POST /tables/{id}/freshness/check | /inspections/freshness/run（按需）
                        │  同一套逻辑，触发点不改变判定；不设墙钟轮询、无每日巡检
                        ▼
              FreshnessCheckService
   ├─ FreshnessContractResolver（仅表级契约；无契约/显式关闭/无阈值 → 不检查）
   ├─ 取值：column / custom_sql / partition / metadata，用户显式选择，无自动兜底
   ├─ 判定：pass | warn | error | runtime_error，阈值比较严格大于
   └─ 留痕：table_freshness_result + data_table.freshness_status/checked_at
                        │
                        ▼
        table_freshness_result 留痕 + data_table.freshness_status（不建 inspection_issue）
```

Task 1-5 构成可独立发布的后端闭环，Task 6-7 为接口与前端，Task 8 为文档收尾。

---

## Task 1: 数据库迁移与实体 — ✅ 已完成

**Files:**
- `backend/src/main/resources/db/migration/V50__table_freshness.sql`
- `backend/src/main/java/com/onedata/portal/entity/TableFreshnessConfig.java`
- `backend/src/main/java/com/onedata/portal/entity/TableFreshnessResult.java`
- `backend/src/main/java/com/onedata/portal/entity/DataTable.java`
- `backend/src/main/java/com/onedata/portal/mapper/TableFreshnessConfigMapper.java`
- `backend/src/main/java/com/onedata/portal/mapper/TableFreshnessResultMapper.java`

**Steps:**
1. 按设计文档 `Interfaces / Data Model` 建 `table_freshness_config`、`table_freshness_result`，`data_table` 增加 `freshness_status`、`freshness_checked_at`。
2. **不种子 `inspection_rule`**（freshness 不是巡检规则，见 Task 4 收敛）。运行期参数（超时/并发）在 `application.yml` 的 `FreshnessCheckProperties`。
3. 新增两个实体（`@TableName` + Lombok `@Data`，时间字段用 `@TableField(fill = ...)`，风格对齐 `InspectionIssue`）与对应 Mapper。
4. `DataTable` 补两个字段。

**Expected Result:**
- `mvn -pl backend -am test-compile -DskipTests` 通过；迁移在本地 MySQL 可重复执行且幂等。

## Task 2: 契约模型与解析 — ✅ 已完成

> **收敛记录**：初版是「表级 + 规则默认」两层逐字段合并（对齐 dbt `merge_freshness`）。评审确认丢掉 `defaults`，改为**仅表级一层**——每张表各自声明 SLA。随之删除 `FreshnessDefault` / `FreshnessRuleConfig` / `FreshnessRuleConfigLoader`。`UpdateCycleParser` 始终未实现（无 `statistics_cycle` 推导）。`FreshnessSource` 保留（字段来源恒为 `TABLE`，供接口回显）。

**Files:**
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessPeriod.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessMode.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessThreshold.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessContract.java`
- `backend/src/main/java/com/onedata/portal/service/freshness/FreshnessContractResolver.java`

**Steps:**
1. `FreshnessPeriod` 枚举 `MINUTE/HOUR/DAY`，`toDuration(count)`；`FreshnessMode` 枚举 `COLUMN/CUSTOM_SQL/PARTITION/METADATA`；`FreshnessThreshold` 承载 `count + period`。
2. `FreshnessContract` 为不可变值对象：`mode`、`loadedAtField`、`loadedAtQuery`、`partitionFormat`、`filterExpr`、`warnAfter`、`errorAfter`，字段来源恒为 `TABLE`。
3. `FreshnessContractResolver.resolve(table, tableConfig)` 返回 `Optional<FreshnessContract>`，**仅表级一层**：`tableConfig` 为 null 或 `enabled = 0` → 空；`warnAfter`/`errorAfter` 均为空 → 空（该表不检查）。
4. **不实现**任何从 `statistics_cycle` / `schedule_cron` / 列名 / 规则默认推导的逻辑。

**Expected Result:**
- `FreshnessContractResolverTest`：无契约返回空、`enabled = 0` 短路、有阈值即可检查、只声明 warn 或只声明 error 均可检查、模式非法或无阈值返回空。

## Task 3: 检查执行服务 — ✅ 已完成

> 偏差：探针失败以异常向上抛出（而非 `Optional`），`FreshnessCheckService.evaluate` 捕获后判 `runtime_error`，以便区分「空表（never_loaded）」与「取数失败」；`data_table` 最新态回写改用列名 `UpdateWrapper`（无 Spring 上下文单测下 `LambdaUpdateWrapper` 拿不到 lambda 缓存）。`checkBatch(tables, triggerType, operator)` 返回 `List<FreshnessCheckResult>`（单层 resolver，超时/并发取自 `FreshnessCheckProperties`）。metadata 探针失败直接判 `runtime_error`（与收敛后的设计一致，不做跨源降级）。注：初版曾返回 `BatchOutcome`（含未配置表）供治理上报，随 Task 4 移除巡检后简化为 `List`。

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

## Task 4: 从巡检子系统移除 freshness — ✅ 已完成（2026-08-06 按评审收敛）

> **设计收敛**：初版把 freshness 挂成 `data_freshness` 巡检规则（`DataFreshnessRuleHandler`），随每日巡检建 `inspection_issue`、做 `reportUnconfigured`。评审确认「不需要每日巡检」——每日巡检是时钟驱动的兜底，与「检查绑定到运行」矛盾。故 freshness 完全脱离巡检子系统：不建 issue、不做每日巡检、不种子巡检规则。红/黄状态只活在 `table_freshness_result` 与 `data_table.freshness_status`（对齐 dbt 只写 `sources.json`、不建"问题"）。

**Files:**
- `backend/src/main/java/com/onedata/portal/service/inspection/DataFreshnessRuleHandler.java`（删除）
- `backend/src/main/resources/db/migration/V50__table_freshness.sql`（移除 `DATA_FRESHNESS_CHECK` 规则种子）
- `backend/src/test/java/com/onedata/portal/service/inspection/InspectionRuleHandlerCoverageTest.java`（移除 `data_freshness`，规则数 15 → 14）

**Steps:**
1. 删除 `DataFreshnessRuleHandler` 及其 `parseUpdateCycle` / `calculateDelayHours` / `calculateFreshnessSeverity` 等辅助；`data_freshness` 不再是巡检规则类型，`InspectionRuleRegistry` 不再收集它。
2. V50 迁移去掉 `DATA_FRESHNESS_CHECK` 种子；`inspection_rule` 不含 freshness。
3. 覆盖测试的期望集合移除 `data_freshness`，规则数改为 14；`InspectionRuleRegistryTest` 仍通过。

**Expected Result:**
- `InspectionRuleHandlerCoverageTest` / `InspectionRuleRegistryTest` 通过；freshness 不再产生任何 `inspection_issue`。

## Task 5: 工作流触发（事件驱动） — ✅ 已完成（2026-08-06 按评审收敛）

> **设计收敛**：初版含固定 15min 墙钟轮询 `FreshnessScheduledTask`。评审指出数据只在生产任务运行时变动，对未变动表反复取数是浪费；正确模型是「检查绑定到运行」。故**删除墙钟轮询**，收敛到：工作流完成后即检查其写出表（主，事件驱动）+ 按需 + 每日巡检兜底。`FreshnessScheduledTask` 及其单测、`FreshnessCheckProperties` 的 `fixedDelayMs`/`minIntervalMs`、`application.yml` 的对应键一并移除；保留 `freshness.check.enabled` 门控工作流触发。
>
> 工作流触发抽为独立组件 `WorkflowFreshnessTrigger`（便于单测），由 `WorkflowExecutionSyncJob` 在检测到「新变为成功」的实例后调用 `checkBatch(tables, "workflow", "system")`。`WorkflowFreshnessTriggerTest` 覆盖写关系触发、开关关闭不触发与异常隔离。

**Files:**
- `backend/src/main/java/com/onedata/portal/service/freshness/WorkflowFreshnessTrigger.java`
- `backend/src/main/java/com/onedata/portal/scheduled/WorkflowExecutionSyncJob.java`
- `backend/src/main/java/com/onedata/portal/config/FreshnessCheckProperties.java`
- `backend/src/main/resources/application.yml`

**Steps:**
1. `WorkflowExecutionSyncJob` 在同步中识别**新变为成功**的工作流实例（以缓存中已有状态与本次同步结果比对，避免重复触发），取该工作流各任务经 `table_task_relation`（`relation_type = write`）关联的表，经 `WorkflowFreshnessTrigger` 调 `checkBatch(..., "workflow", "system")`。触发失败只记日志，不影响同步作业主流程。开关 `${freshness.check.enabled:true}`。
2. 非工作流产出的表（外部同步等无完成事件）由按需接口覆盖，不做高频轮询、无每日巡检兜底。

**Expected Result:**
- 新增 `WorkflowFreshnessTriggerTest`：只对 `relation_type = write` 的表触发、开关关闭不触发、触发异常不影响同步。

## Task 6: REST 接口 — ✅ 已完成

> 说明：`loadedAtQuery` 的「仅管理员」限制目前落在 `@RequireAuth`（要求已认证）+ SQL 形状校验层面，未接入更细的 RBAC 角色判定；如需严格管理员门槛，后续在 auth 模块补角色注解。

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
   - 查询接口回显生效契约（字段来源恒为表级）。
2. `DataTableController` 增加 `GET/PUT/DELETE /{id}/freshness`、`POST /{id}/freshness/check`、`GET /{id}/freshness/history`；控制器只做绑定 + 鉴权注解 + `try/catch → Result.fail(e.getMessage())`。
3. `InspectionController` 增加 `GET /freshness`、`POST /freshness/run`，以及 `PUT /rules/{ruleId}`（更新 `ruleConfig` / `severity` / `ruleName` / `description`，`ruleType` 与 `ruleCode` 不可改）；`InspectionService` 补对应方法。

**Expected Result:**
- 新增/更新 `TableFreshnessServiceTest`、`DataTableControllerTest`、`InspectionControllerTest` 全绿，含各项校验拒绝用例。

## Task 7: 前端 — ✅ 已完成

> 说明：`freshnessPanel.spec.js` 聚焦挂载与 API 接线（EP 组件在 shallowMount 下 DOM 断言较脆，故不做像素级断言）；巡检页新鲜度视图以卡片形式加入 `InspectionView.vue`（该视图是卡片布局而非页签）。

**Files:**
- `frontend/src/api/*`（新增新鲜度接口封装，沿用现有 api 模块组织方式）
- `frontend/src/views/datastudio/components/DataStudioRightPanelFreshness.vue`
- `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`
- `frontend/src/views/inspection/InspectionView.vue`
- `frontend/src/views/datastudio/__tests__/freshnessPanel.spec.js`

**Steps:**
1. 新增右侧面板「数据新鲜度」页签（`lazy`），插在「访问情况」之后。契约用 `el-descriptions :column="2"` 展示（字段来源恒为表级；`withSource` 逻辑保留但当前不追加来源后缀）。
2. 未配置契约时展示引导（说明该表未纳入新鲜度管理 + 「去配置」入口），不展示空表格、不展示假状态。
3. 编辑表单按 `mode` 联动显隐 `loadedAtField` / `loadedAtQuery` / `partitionFormat`；选择 `metadata` 时给出提示：只能发现长期无写入，发现不了写入了旧数据。
4. 结果历史用 `el-table` 带 border；状态 `el-tag` 着色：`pass` 成功、`warn` 警告、`error` 危险、`runtime_error` 信息灰。顶部「立即检查」。
5. `InspectionView.vue` 保留 `data_freshness` 中文名，新增按新鲜度状态筛选的表级视图入口。
6. 不套多余卡片；兄弟页签保持一致的扁平结构。

**Expected Result:**
- `npm --prefix frontend run test -- src/views/datastudio/__tests__/freshnessPanel.spec.js` 通过；`npm --prefix frontend run build` 通过。

## Task 8: 文档与收尾 — ✅ 已完成

**Files:**
- `docs/handbook/features/data-freshness.md`
- `CHANGELOG.md`

**Steps:**
1. 新增 handbook 文档：契约三要素（时间字段、warn、error）、四种模式选型与各自能不能发现「写入了旧数据」、仅表级契约（无 defaults/继承）、状态语义（含严格大于的边界）、`partition` 模式的 age 语义提醒（T+1 表 age 恒 ≥ 24h，阈值怎么设）、两个触发点（工作流完成 + 按需，无每日巡检）、与 dbt 的对齐与偏离对照表、常见故障排查。
2. `CHANGELOG.md` 记录：新增表级新鲜度契约与检查；**行为变更**——规则语义由「按 `statistics_cycle` 推断」改为「按表级契约」，未配置契约的表不再产出新鲜度问题。
3. 回填本 plan 的任务勾选与实际验证结论。

**Expected Result:**
- 文档目录、命名与交叉链接符合 `docs/design/README.md` 与 `docs/plans/README.md` 规则。

---

## Verification

```bash
mvn -pl backend -am \
  -Dtest='FreshnessContractResolverTest,FreshnessCheckServiceTest,WorkflowFreshnessTriggerTest,TableFreshnessServiceTest,InspectionRuleHandlerCoverageTest,InspectionRuleRegistryTest,DataTableControllerTest' \
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

1. 一张未配置契约的表，`POST /v1/inspections/freshness/run` 后确认它**不产生任何结果行**。
2. 给一张有加载时间列的表 `PUT` 契约：`mode = column`、`warnAfter = 2 hour`、`errorAfter = 4 hour`，`POST .../freshness/check` 应返回 `pass` 并落一行结果，`data_table.freshness_status = pass`。
3. 阈值调到 1 分钟重跑得 `warn`，`errorAfter` 也调到 1 分钟得 `error`。
4. 切 `mode = custom_sql`，`loadedAtQuery` 填 `select max(order_time) from <db>.<tbl>`，结果应与 `column` 模式一致；同时提交 `loadedAtField` 与 `loadedAtQuery` 应被拒绝。
5. 切 `mode = partition`，`partitionFormat = yyyyMMdd`，确认取到最新分区业务日期；把 `partitionFormat` 改错应得 `runtime_error` 而非 `pass`。
6. 空表检查应得 `error` + `reason = never_loaded`，描述不出现天文数字延迟。
7. 跑一次工作流，成功后 5 分钟内确认它写出（配了契约）的表自动产生一条 `trigger_type = workflow` 的结果；确认全程**不产生任何 `inspection_issue`**。
8. 确认 `inspection_rule` 表中**没有** `data_freshness` 规则行，巡检页规则列表不含新鲜度规则。

若 Doris 不可用，须在提交说明与 plan 回填中写明：已验证到单测与迁移层，真实取数路径未跑。

## Rollout / Backout

**Rollout：**

- 未配置任何表级契约时检查为空跑，升级后行为与升级前一致（freshness 不种子巡检规则、不建 issue）。
- 灰度顺序：先给少量核心表配契约并手动触发 → 观察结果与 Doris 负载 → 依赖工作流完成触发覆盖「产出即检查」→ 逐步为更多产出表补契约。
- `freshness.check.enabled=false` 可单独关掉工作流触发，不影响按需路径。

**Backout：**

- `freshness.check.enabled=false` + 不配任何契约，即可停止全部新鲜度行为，无需回滚代码。
- 回滚代码时 `V50` 新增的两张表与两列可保留（不被旧代码读写），不做 `down` 迁移，避免丢历史结果。
