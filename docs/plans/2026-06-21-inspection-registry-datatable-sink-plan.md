# 巡检规则注册表化与数据表控制器逻辑下沉执行计划

> Design: [对应 design 文档](../design/2026-06-21-inspection-registry-datatable-sink-design.md)

**Goal:** 把 `InspectionService` 规则分发改造为「策略 + 注册表」，并把 `DataTableController` 的生命周期/查询/导出/同步/稽核编排下沉到服务层，控制器回归薄；全程行为保持。
**Tech Stack:** 后端（Java 8 · Spring Boot 2.7 · MyBatis-Plus · Lombok · JUnit5 · Mockito）。不涉及前端、DataAgent、部署、DB schema、REST 契约。

## Architecture Summary

- 新增 `com.onedata.portal.service.inspection` 包：`InspectionRuleHandler`(接口) + `InspectionRuleRegistry` + `InspectionSupport` + 15 个 `*RuleHandler`。`InspectionService` 收敛为薄编排，仅保留入口、修复链路与查询接口。
- 新增 `DataTableQueryService`、`DataTableMetadataSyncService`；扩展 `DataTableService`（表身份解析 + 生命周期写命令）。`DataTableController` 注入由 9 收敛为 3。
- 行为保持手段：方法逐字迁移；控制器保留薄 `try/catch → Result.fail(e.getMessage())`，服务抛「最终态消息」异常。

## 验证总约定

- 每步：`mvn -q -pl backend -am test-compile -DskipTests`（编译 0 error）。
- 受影响单测：`mvn -q -pl backend -am test -Dtest=<...> -DfailIfNoTests=false`（全绿）。
- 每个任务独立提交、可独立回退。
- 纯后端结构重构，不跑依赖 Doris/MySQL 的 app 级链路；提交说明如实标注验证范围。

---

## Task 1: 设计与计划文档

**Files:**
- docs/design/2026-06-21-inspection-registry-datatable-sink-design.md
- docs/plans/2026-06-21-inspection-registry-datatable-sink-plan.md

**Steps:**
1. 产出 design（现状/问题/范围/方案/接口/取舍/验证）。
2. 产出本 plan（任务清单 + 验证 + 回滚）。

**Expected Result:**
- 文档命名与目录符合仓库规则，design 先于 plan，共享同一 topic slug。

---

## Task 2: 巡检共享支撑 `InspectionSupport` + 注册表骨架

**Files:**
- backend/src/main/java/com/onedata/portal/service/inspection/InspectionRuleHandler.java（新增）
- backend/src/main/java/com/onedata/portal/service/inspection/InspectionRuleRegistry.java（新增）
- backend/src/main/java/com/onedata/portal/service/inspection/InspectionSupport.java（新增）

**Steps:**
1. `InspectionRuleHandler`：`String ruleType();`、`List<InspectionIssue> check(Long recordId, InspectionRule rule);`。
2. `InspectionSupport`（@Component）：迁入共享辅助与所需依赖（`InspectionIssueMapper`/`DataTableMapper`/`DataLineageMapper`/`DorisClusterService`/`ObjectMapper`）；对外暴露 `insertIssue`/`createIssue`/`parseRuleConfig`/`applyTableScope`/`formatBytes`/`isViewTable`/`resolveActualTableName`/`resolveDorisClusterIds`/`hasUpstreamLineage`/`hasDownstreamLineage`，方法体逐字迁移。
3. `InspectionRuleRegistry`（@Component）：构造注入 `List<InspectionRuleHandler>`，按 `ruleType` 建 `Map`，重复 ruleType 启动期抛异常；`dispatch(recordId, rule)` 未命中时 `log.warn("Unknown rule type: {}")` 并返回空列表（复刻现状）。

**Expected Result:**
- 编译通过；注册表/支撑就位，暂未接线。

---

## Task 3: 15 个规则 handler 迁移

**Files:**（均新增于 `backend/src/main/java/com/onedata/portal/service/inspection/`）
- TableNamingRuleHandler / ReplicaCountRuleHandler / TabletCountRuleHandler / TabletSizeRuleHandler
- TableOwnerRuleHandler / TableCommentRuleHandler / TaskFailureRuleHandler / TaskScheduleRuleHandler
- TableLayerRuleHandler / DataFreshnessRuleHandler / DataVolumeSpikeRuleHandler / ServiceHealthRuleHandler
- DorisNodeResourcesRuleHandler / OrphanTablesRuleHandler / DeprecatedTablesRuleHandler

**Steps:**
1. 每个 handler `implements InspectionRuleHandler`，`ruleType()` 返回对应字符串常量。
2. `check(...)` 由原 `checkXxx` 逐字迁移；共享调用改走 `support.*`，问题入库走 `support.insertIssue`。
3. 规则**专属**辅助随对应 handler 迁移：
   - DataFreshness：`parseUpdateCycle`/`calculateDelayHours`/`calculateFreshnessSeverity`/`getCycleDescription`/`generateFreshnessSuggestion`。
   - TabletSize：`generateTabletSizeSuggestion`。
   - ServiceHealth：`generateServiceHealthSuggestion`。
   - DorisNodeResources：`createDorisNodeIssue`。
   - OrphanTables：`calculateOrphanTableSeverity`/`generateOrphanTableSuggestion`。
   - DeprecatedTables：`generateDeprecatedTableSuggestion`。
4. 各 handler 注入 `InspectionSupport` + 其专属依赖（如 `DorisConnectionService`/`HealthCheckService`/`DataTaskMapper`/`TaskExecutionLogMapper`）。

**Expected Result:**
- 编译通过；15 handler 成为独立 Spring bean。

---

## Task 4: `InspectionService` 接入注册表并收敛

**Files:**
- backend/src/main/java/com/onedata/portal/service/InspectionService.java（改）

**Steps:**
1. 注入 `InspectionRuleRegistry` + `InspectionSupport`；`runFullInspection` 内 `executeRule(...)` 改为 `registry.dispatch(record.getId(), rule)`。
2. 删除 `executeRule` 及 15 个 `checkXxx` 与已迁移的共享/专属辅助。
3. 保留修复链路（`fixIssue`/`getIssueFixPlan`/`buildReplicaIssueFixPlan`/`buildTabletIssueFixPlan`/`resolveIssueTableContext`/`resolveTargetReplicaNum`/`loadRuleConfig` 等）与查询接口；其中 `parseRuleConfig`/`formatBytes`/`resolveActualTableName` 调用改为委托 `InspectionSupport`。
4. 清理不再使用的注入与 import。

**Expected Result:**
- 编译通过；`InspectionService` 行数大幅下降，仅余编排 + 修复 + 查询职责。

---

## Task 5: 巡检回归测试网

**Files:**
- backend/src/test/java/com/onedata/portal/service/inspection/InspectionRuleRegistryTest.java（新增）
- backend/src/test/java/com/onedata/portal/service/inspection/TableNamingRuleHandlerTest.java（新增）
- backend/src/test/java/com/onedata/portal/service/inspection/TableOwnerRuleHandlerTest.java（新增）

**Steps:**
1. `InspectionRuleRegistryTest`：用桩 handler 验证按 ruleType 解析、重复 ruleType fail-fast、未知类型 dispatch 返回空且不抛。
2. 代表性 handler 测试（Mockito 桩 mapper/support 协作者）：命中/不命中规则时的问题构造与入库次数与迁移前一致。

**Expected Result:**
- 新增测试全绿，锁定分发与代表规则行为。

---

## Task 6: `DataTableService` 表身份解析 + 生命周期写命令下沉

**Files:**
- backend/src/main/java/com/onedata/portal/service/DataTableService.java（改）
- backend/src/main/java/com/onedata/portal/dto/TableLocation.java（新增，或服务内静态类）

**Steps:**
1. 新增公有 `requireTableLocation(DataTable)`：复刻控制器解析块（dbName 优先去前缀；否则 tableName 拆点；否则抛 `"表未配置数据库名，请先设置 dbName 字段"`）；服务内既有 `resolveTableRef`/`extractActualTableName` 改为复用之，消除重复。
2. 迁入编排写命令（逐字迁移控制器方法体，失败改为抛「最终态消息」异常）：`updateTable`、`updateTableComment`、`softDeleteTable`、`restoreTable`、`purgeTableNow`、`deleteTable`、`listPendingDeletionView`、字段三方法 `id` 版（表存在性 + Doris 集群校验）。

**Expected Result:**
- 编译通过；服务承接表写命令；解析逻辑单一来源。

---

## Task 7: 新增 `DataTableQueryService`（只读编排下沉）

**Files:**
- backend/src/main/java/com/onedata/portal/service/DataTableQueryService.java（新增）
- backend/src/main/java/com/onedata/portal/dto/TableExport.java（新增）

**Steps:**
1. 注入 `DataTableService`/`DorisConnectionService`/`TableStatisticsCacheService`/`TableStatisticsHistoryService`/`DataExportService`/`DorisTableAccessService`。
2. 迁入：`getStatistics`(缓存+解析+取数+落缓存+落历史)、`getAccessStats`、`getDatabaseStatistics`、`getStatisticsHistory`/`getLast7DaysHistory`/`getLast30DaysHistory`、`getTableDdl`/`getTableDdlByName`、`previewTableData`、`exportTableData`（返回 `TableExport`，bytes/contentType/扩展名/表名）。
3. 库表解析统一走 `dataTableService.requireTableLocation`。

**Expected Result:**
- 编译通过；只读查询编排集中于查询服务。

---

## Task 8: 新增 `DataTableMetadataSyncService`（同步/稽核下沉）

**Files:**
- backend/src/main/java/com/onedata/portal/service/DataTableMetadataSyncService.java（新增）

**Steps:**
1. 注入 `DorisMetadataSyncService`/`MetadataSyncHistoryService`/`DorisClusterService`/`DataTableService`。
2. 迁入 `buildSyncResponse`、`auditAllMetadata`(组装响应 Map)、`syncAll`/`syncDatabase`/`syncTableByName`/`syncTable`：完成 clusterId/cluster 前置校验（抛异常）、`startedAt` 记录、同步调用的内部 try/catch-记录-返回语义、历史记录、附加 `database`/`tableName`/`tableId` 字段，返回响应 Map。

**Expected Result:**
- 编译通过；同步/稽核编排集中于同步服务，保留「同步异常被吞为 FAIL 结果仍返回」语义。

---

## Task 9: `DataTableController` 收敛为薄控制器 + 测试更新

**Files:**
- backend/src/main/java/com/onedata/portal/controller/DataTableController.java（改）
- backend/src/test/java/com/onedata/portal/controller/DataTableControllerTest.java（改）
- backend/src/test/java/com/onedata/portal/service/DataTableMetadataSyncServiceTest.java（新增）

**Steps:**
1. 控制器注入收敛为 `DataTableService`/`DataTableQueryService`/`DataTableMetadataSyncService`；删除重复私有辅助（`isDorisTable`/`resolveTableRef`/`extractActualTableName`/`resolveRestoreTableName`/`calculateRemainingDays`/`isConfirmTableNameMatched`/`TableRef`）。
2. 各方法改为「绑定参数 → 调服务 → 组装 `Result`/`ResponseEntity`」，保留薄 `try/catch → Result.fail(e.getMessage())`；同步端点按 `status` 做端点专属文案映射（提取一个控制器内私有 `respondSync` 辅助）。
3. 导出端点：服务返回 `TableExport`，控制器负责时间戳文件名 + URL 编码 + 响应头。
4. 更新 `DataTableControllerTest` 构造参数与同步用例（改为对 `DataTableMetadataSyncService` 桩或验证委托）；新增 `DataTableMetadataSyncServiceTest` 覆盖回填 `tableId`、缺 clusterId 抛「请指定数据源」、status→响应字段。

**Expected Result:**
- 编译通过；`DataTableControllerTest` 等单测全绿；控制器显著变薄。

---

## Verification

- 全量编译：`mvn -q -pl backend -am test-compile -DskipTests`。
- 受影响单测：`mvn -q -pl backend -am test -Dtest=DataTableControllerTest,DataTableMetadataSyncServiceTest,InspectionRuleRegistryTest,TableNamingRuleHandlerTest,TableOwnerRuleHandlerTest -DfailIfNoTests=false`。
- 重点回归：注册表分发 15 类 + 未知类型；同步成功回填 tableId、缺 clusterId 文案；改注释/软删/恢复/清理失败文案与状态码不变。
- 受限说明：纯后端结构重构，不触发 Doris/MySQL app 级冒烟；验证范围 = 编译 + 上述单测。

## Rollout / Backout

- 按任务分多次提交（文档 → 巡检支撑/注册表 → handler 迁移 → 接线收敛 → 巡检测试 → 数据表写命令 → 查询服务 → 同步服务 → 控制器收敛 + 测试）。
- 对外 REST 契约不变、调用方无需改动；任一步出问题按提交回退，不外溢到前端与其他服务。
