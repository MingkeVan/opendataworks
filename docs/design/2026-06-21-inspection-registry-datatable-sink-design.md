# 巡检规则注册表化与数据表控制器逻辑下沉设计

**Date:** 2026-06-21
**Goal:** 将 `InspectionService` 的规则分发由硬编码 `switch` 改造为「策略 + 注册表」，并把 `DataTableController` 的业务编排（生命周期/统计/导出/审计/同步）下沉到服务层，使两处「上帝类」回归单一职责。
**Tech Stack:** 后端（Java 8 · Spring Boot 2.7 · MyBatis-Plus · Lombok）。不涉及前端、DataAgent、部署，不改数据库 schema，不改 REST 路由与响应契约。

> 本设计承接 `docs/design/2026-06-16-backend-service-decomposition-design.md` §9「同类技术债 roadmap」中明确列出的两项：`InspectionService`(规则注册表化)、`DataTableController`(逻辑下沉)。性质与前者一致：**行为保持型重构，无功能变更、无对外接口变更**。配套执行计划见 `docs/plans/2026-06-21-inspection-registry-datatable-sink-plan.md`。

---

## Scope

**做：**

- `InspectionService`：引入 `InspectionRuleHandler` 策略接口 + `InspectionRuleRegistry` 注册表，把 15 个 `checkXxx` 规则各自抽成独立 handler 组件；共享辅助逻辑下沉到 `InspectionSupport`。`runFullInspection` 改为经注册表分发。
- `DataTableController`：把表生命周期（更新/改注释/软删/恢复/清理/待删除视图/字段校验）、只读查询（统计/DDL/预览/导出/访问统计/统计历史）、元数据同步与稽核的编排逻辑下沉到服务层，控制器仅保留请求绑定、鉴权注解、HTTP 响应组装与 `try/catch → Result` 映射。

**不做（本设计之外）：**

- 不改 `InspectionController`/`DataTableController` 的 REST 路径、入参、响应 JSON 结构与状态码语义。
- 不改 `inspection_rule` 等表结构，不改规则配置语义。
- 不改 `DorisConnectionService`/`DorisMetadataSyncService`/`HealthCheckService` 等被依赖服务的对外方法。
- 不引入 Repository 抽象层、不做 CQRS 等更激进结构；MyBatis-Plus 直用模式保留。

## Current State

### InspectionService（2032 行）

- 入口 `runFullInspection` 遍历启用规则，逐条调用 `executeRule(recordId, rule)`。
- `executeRule` 是一个 **15 分支 `switch(rule.getRuleType())`**，分发到 `checkTableNaming`/`checkReplicaCount`/`checkTabletCount`/`checkTabletSize`/`checkTableOwner`/`checkTableComment`/`checkTaskFailure`/`checkTaskSchedule`/`checkTableLayer`/`checkDataFreshness`/`checkDataVolumeSpike`/`checkServiceHealth`/`checkDorisNodeResources`/`checkOrphanTables`/`checkDeprecatedTables`。
- 这些 `checkXxx` 与大量私有辅助（`createIssue`/`parseRuleConfig`/`applyTableScope`+其取值辅助/`formatBytes`/`isViewTable`/`resolveActualTableName`/`resolveDorisClusterIds`/`hasUpstreamLineage`/`hasDownstreamLineage`）、以及若干规则**专属**辅助（如新鲜度的 `parseUpdateCycle`/`calculateFreshnessSeverity`、孤立表/废弃表的建议生成等）混在同一个类里。
- 同类内还承载与「规则分发」无关的另一职责：问题修复链路（`fixIssue`/`getIssueFixPlan`/`buildReplicaIssueFixPlan`/`buildTabletIssueFixPlan`/`resolveIssueTableContext` 等）与记录/问题/规则的查询接口。

### DataTableController（1187 行）

- 注入 9 个服务，方法体内承载大量业务编排：
  - **重复的库表名解析块**（`dbName 非空 → 去前缀；否则 tableName 含点拆分；否则 fail("表未配置数据库名，请先设置 dbName 字段")`）在 `update`/`updateTableComment`/`softDeleteTable`/`getStatistics`/`getTableDdl`/`previewTableData`/`exportTableData`/`syncTableMetadata` 等处**复制了 7+ 份**。
  - 私有业务辅助 `isDorisTable`/`isPositive`/`resolveTableRef`/`extractActualTableName`/`resolveRestoreTableName`/`calculateRemainingDays`/`isConfirmTableNameMatched`/内部 `TableRef`，其中 `isDorisTable`/`resolveTableRef`/`extractActualTableName`/`TableRef` 与 `DataTableService` 内的私有实现**完全重复**。
  - 重编排方法：`update`（Doris 改名/改注释/改分桶/改副本同步）、`softDeleteTable`、`restoreTable`、`purgeTableNow`、4 个 `sync*Metadata`（记录同步历史 + `buildSyncResponse`）、`auditAllMetadata`、`getStatistics`（缓存 + 取数 + 落历史）、`exportTableData`（取数 + 格式分支 + 文件名编码）。

### 关键约束：异常 → 响应契约

`DataTableController` 现状大量使用 `try { ... } catch (Exception e) { return Result.fail(e.getMessage()); }` 或前置 `return Result.fail("...")`，最终响应均为 **HTTP 200 + `{code:500, message:原文}`**。而 `GlobalExceptionHandler` 对逃逸异常会**改写消息前缀并改 HTTP 状态**（如 `RuntimeException → 500 + "操作失败: "+msg`，`IllegalArgumentException → 400 + "参数错误: "+msg`）。因此下沉后**控制器必须保留薄 `try/catch → Result.fail(e.getMessage())` 包装**，让服务层抛出「最终态消息」的异常，避免响应体/状态码漂移。

## Problem

- **单一职责被破坏**：`InspectionService` 同时承担规则分发、规则实现、共享工具、问题修复与查询；`DataTableController` 把库表解析、Doris 同步编排、导出/同步/稽核都堆在控制器。
- **重复实现**：库表名解析与 `isDorisTable`/`resolveTableRef` 在控制器与服务两处重复，易产生「改一处漏一处」的漂移。
- **新增规则成本高**：加一个巡检规则要改 `switch` 并在 2000 行大类里塞方法，回归面大。
- **可测性差**：控制器内嵌编排难以脱离 MockMvc 单测；巡检规则无法逐条独立测试。

## Design

### Part 1 — InspectionService 规则注册表化（策略 + 注册表）

新增包 `com.onedata.portal.service.inspection`：

```
InspectionService（薄编排：runFullInspection 遍历规则 → registry 分发；保留修复/查询接口）
      │
      ▼
InspectionRuleRegistry（@Component：收集所有 InspectionRuleHandler，按 ruleType 建索引，
                        重复 ruleType 启动期 fail-fast；dispatch 未知类型 → warn + 空列表）
      │ dispatch(recordId, rule)
      ▼
InspectionRuleHandler（接口：String ruleType(); List<InspectionIssue> check(Long recordId, InspectionRule rule)）
      ├── TableNamingRuleHandler          ├── DataFreshnessRuleHandler
      ├── ReplicaCountRuleHandler         ├── DataVolumeSpikeRuleHandler
      ├── TabletCountRuleHandler          ├── ServiceHealthRuleHandler
      ├── TabletSizeRuleHandler           ├── DorisNodeResourcesRuleHandler
      ├── TableOwnerRuleHandler           ├── OrphanTablesRuleHandler
      ├── TableCommentRuleHandler         ├── DeprecatedTablesRuleHandler
      ├── TaskFailureRuleHandler          └── TableLayerRuleHandler
      └── TaskScheduleRuleHandler
              │ 共享辅助 + 共享依赖
              ▼
InspectionSupport（@Component：insertIssue/createIssue/parseRuleConfig/applyTableScope(+取值辅助)/
                   formatBytes/isViewTable/resolveActualTableName/resolveDorisClusterIds/
                   hasUpstreamLineage/hasDownstreamLineage）
```

- **handler 划分原则**：每个 `ruleType` 对应一个 handler，方法体由原 `checkXxx` **逐字迁移**；规则**专属**辅助（新鲜度周期解析、孤立/废弃表建议生成、Doris 节点问题构造、租户级 sql 生成等）随对应 handler 迁移，不进 `InspectionSupport`。
- **共享辅助归位**：被多个规则复用的逻辑进入 `InspectionSupport`，由各 handler 注入复用；问题入库统一走 `support.insertIssue(issue)`，保持「check 内即插入并返回」的现有副作用契约。
- **事务边界不变**：`@Transactional` 仍在 `InspectionService.runFullInspection`；handler/registry/support 无独立事务，随同线程事务传播。
- **`InspectionService` 收敛**：`runFullInspection` 改为 `for (rule) registry.dispatch(record.getId(), rule)`，删除 `executeRule` 与全部 `checkXxx`/已迁移辅助；保留的修复链路所需的 `parseRuleConfig`/`formatBytes`/`resolveActualTableName` 改为委托 `InspectionSupport`，依赖随之收敛（不再需要 `dataTaskMapper`/`executionLogMapper`/`dataLineageMapper`/`healthCheckService`/`dorisClusterService`/`objectMapper`）。

### Part 2 — DataTableController 逻辑下沉

控制器只保留：路由/参数绑定、`@RequireAuth`、HTTP 响应组装（`Result`/`ResponseEntity`、导出文件名编码）、`try/catch → Result.fail(e.getMessage())` 映射。业务编排下沉到三处服务：

1. **`DataTableService`（既有，扩展）** — 表身份解析 + 生命周期写命令：
   - 新增公有 `TableLocation requireTableLocation(DataTable)`（库名+实际表名解析，缺失抛 `"表未配置数据库名，请先设置 dbName 字段"`），统一替换控制器 7 份重复块，并复用消化掉服务内既有 `resolveTableRef`/`extractActualTableName` 的重复。
   - 新增编排方法：`updateTable(id, patch, clusterId)`、`updateTableComment(id, comment, clusterId)`、`softDeleteTable(id, clusterId, confirmTableName)`、`restoreTable(id, clusterId)`、`purgeTableNow(id, clusterId, confirmTableName)`、`deleteTable(id, confirmTableName)`、`listPendingDeletionView(clusterId)`、字段三方法的 `id` 版（内含表存在性与 Doris 集群校验）。各方法以抛「最终态消息」异常表达失败。
2. **`DataTableQueryService`（新增）** — Doris 只读查询编排：`getStatistics`(缓存+取数+落历史)、`getAccessStats`、`getDatabaseStatistics`、`getStatisticsHistory`/`getLast7DaysHistory`/`getLast30DaysHistory`、`getTableDdl`/`getTableDdlByName`、`previewTableData`、`exportTableData`(返回 `TableExport{bytes,contentType,fileExtension,tableName}`，控制器仅负责文件名时间戳与编码)。
3. **`DataTableMetadataSyncService`（新增）** — 同步/稽核编排：`auditAllMetadata(clusterId)` 返回响应 Map；`syncAll`/`syncDatabase`/`syncTableByName`/`syncTable` 各自完成 clusterId/cluster 校验（抛异常）、同步历史记录、`buildSyncResponse` 组装与附加字段，返回响应 Map。控制器仅按 `status` 做「成功/部分/失败」**端点专属文案**映射，仍统一 `Result.success(map, message)`（失败也是 200，保持现状）。

重构后控制器注入由 9 个收敛为 3 个：`DataTableService`、`DataTableQueryService`、`DataTableMetadataSyncService`。`DataTableControllerTest` 同步更新构造参数；同步链路的「记录历史 + 组装响应 + 回填 tableId」改由 `DataTableMetadataSyncServiceTest` 覆盖。

## Interfaces / Data Model

- **对外 REST**：`/v1/inspections/*` 与 `/v1/tables/*` 的路径、方法、入参、响应 JSON 字段与状态码**全部不变**。
- **数据模型**：无 schema 变更。
- **新增对内类型**：`InspectionRuleHandler`(接口)、`InspectionRuleRegistry`、`InspectionSupport`、15 个 `*RuleHandler`；`DataTableQueryService`、`DataTableMetadataSyncService`、值对象 `TableLocation`、`TableExport`。均为 `@Service`/`@Component` + 构造注入。
- **依赖方向**：控制器 → 服务 → Mapper；handler → support → Mapper；不出现反向或环依赖。

## Risks / Alternatives

- **主要风险：行为漂移**。控制措施：辅助/规则方法**逐字迁移**；保留控制器薄 `try/catch`，服务抛「最终态消息」异常以复刻响应体与状态码；新增/更新单测锁定分发与同步链路。
- **风险：异常语义**。下沉的校验失败必须复刻原文案（如「请指定数据源」「表未配置数据库名，请先设置 dbName 字段」「修改表注释失败: …」），由控制器 `catch` 原样回填，避免被 `GlobalExceptionHandler` 改写。
- **风险：同步异常吞没语义**。`syncAllMetadata` 等原本**内部捕获**同步异常并落为 FAIL 结果后仍返回 200，下沉时须保留该「捕获-记录-返回」语义，仅对 clusterId/cluster 前置校验抛异常。
- **替代方案**：
  - 「仅把 `switch` 换成 `Map<String,BiFunction>` 内联注册表、不拆 handler」——改动更小但不解决上帝类与可测性，且偏离 roadmap 明确的「策略 + 注册表承载各规则」，否决。
  - 「控制器逻辑全塞进 `DataTableService` 单类」——会把它推成新的上帝类，否决；改为按读/写/同步三聚焦协作者下沉。
  - 「让异常逃逸交由 `GlobalExceptionHandler`」——会改变响应体与状态码，违背行为保持，否决。

## Verification

- 每步：`mvn -q -pl backend -am test-compile -DskipTests` 编译通过。
- 针对性测试：`mvn -q -pl backend -am test -Dtest=DataTableControllerTest,DataTableMetadataSyncServiceTest,InspectionRuleRegistryTest,<handler tests> -DfailIfNoTests=false` 全绿。
- 重点回归：
  - 巡检：注册表对 15 个已知 `ruleType` 均可解析、未知类型返回空且告警；代表性规则（如 `table_naming`/`table_owner`）行为与迁移前一致。
  - 数据表：`sync-metadata/.../table/{tableName}` 成功路径回填 `tableId`、缺 clusterId 返回「请指定数据源」；改注释/软删/恢复/清理的失败文案与状态码不变。
- 本重构为纯后端结构调整，不具备改动相关的端到端外部依赖（Doris/MySQL 实例）触发条件；不进行 app 级冒烟，验证范围限定为编译 + 上述单测，并在提交说明中如实标注未跑 app 级全链路。
