# 后端核心服务拆分执行计划（WorkflowService 优先）

- 日期: 2026-06-16
- 关联设计: `docs/design/2026-06-16-backend-service-decomposition-design.md`
- 关联报告: `docs/reports/2026-06-16-main-full-code-review.md`（后端 4.1）
- 影响栈: 后端（Java · Spring Boot 2.7 · MyBatis-Plus）
- 原则: 行为保持、一次一职责、每步独立提交可回退、公有 API 不变

> 本文为执行计划，聚焦可执行任务、触及文件、验证、回滚。背景与方案见配套设计文档。

---

## 任务分解（按执行顺序）

### T0 — 测试网先行（重构前置，阻塞后续所有抽取）
- 目标: 为 `WorkflowService` 13 个公有方法补特征化（characterization）测试，锁定当前行为作为回归基线。
- 触及文件:
  - 新增 `backend/src/test/java/com/onedata/portal/service/WorkflowServiceCharacterizationTest.java`
  - 必要时复用现有测试基建（参考 `WorkflowRuntimeSyncRealIntegrationTest`、`DataQueryServiceTest` 的搭法）
- 做法: 优先覆盖纯逻辑路径（定义 JSON 组装/规范化、`buildDefinitionJsonForExport`、`refreshTaskRelations` 的关系计算）；外部依赖（DolphinScheduler、Mapper）用 mock 固定输入输出。
- 验证: `mvn -pl backend -am test`，新增测试全绿。
- 回退: 删除新增测试文件（纯增量，无生产影响）。

### T1 — 抽取 JsonCanonicalizer（最低风险，已完成首个切片）
- 目标: 先把 `WorkflowService` 与 `TableMetadataVersionService` 中重复的 JSON 规范化 / SHA-256 哈希逻辑下沉到纯工具，消除重复实现并为后续有状态拆分铺路。
- 触及文件:
  - 新增 `backend/src/main/java/com/onedata/portal/util/JsonCanonicalizer.java`
  - 改 `WorkflowService.java` 与 `TableMetadataVersionService.java`：删除重复私有实现，委托 `JsonCanonicalizer`
  - 新增 `backend/src/test/java/com/onedata/portal/util/JsonCanonicalizerTest.java`
- 做法: 保持原规范化输出、空白输入哈希返回 `null` 等边界语义不变；只搬迁纯逻辑，不改变公有 API、schema、部署或事务边界。
- 验证: `mvn -pl backend -am test`（新增工具单测 + 受影响服务测试）。
- 回退: 单提交回退；公有 API 不变，调用方不受影响。

### T2 — 抽取 WorkflowQueryService（读取，已完成）
- 目标: `WorkflowQueryService` 已承接 `list` / `getDetail` 的纯读取逻辑，包含列表分页、详情组装、当前版本号填充、DolphinScheduler 最近实例实时读取与缓存兜底；`WorkflowService` 保持公有 API 并委托。
- 触及文件: `WorkflowQueryService.java`、`WorkflowService.java`、`WorkflowQueryServiceTest.java`、`WorkflowQueryServiceIntegrationTest.java`。
- 边界: `buildDefinitionJsonForExport` 在 `definition_json` 缺失时会根据任务关系重建并写回工作流，不是纯查询；该带写回副作用的公有 API 保留在 `WorkflowService` facade。
- 验证: `SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/opendataworks?... SPRING_DATASOURCE_USERNAME=opendataworks SPRING_DATASOURCE_PASSWORD=opendataworks123 mvn -pl backend -am -Dtest=WorkflowQueryServiceTest,WorkflowQueryServiceIntegrationTest -DfailIfNoTests=false test`；结果 `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`。
- 回退: 单提交回退。

### T3 — 抽取 WorkflowTaskRelationService（任务绑定/拓扑刷新，已完成）
- 目标: 迁移 `refreshTaskRelations`、任务绑定还原、taskId 去重收集、关系硬删重建与任务归属校验（与已有 `WorkflowTopologyService` 协作，避免重复）。
- 触及文件: 新增 `WorkflowTaskRelationService.java` + 单测/真实 MySQL 集成测试；改 `WorkflowService.java` 委托。
- 验证: `mvn -pl backend -am -Dtest=WorkflowTaskRelationServiceTest,WorkflowTaskRelationServiceIntegrationTest,WorkflowServiceMetadataPersistenceTest,WorkflowSchedulerEngineSwitchTest -DfailIfNoTests=false test`；回归任务关系刷新、拓扑 entry/exit、上下游计数和真实表约束。
- 回退: 单提交回退。

### T4 — 抽取 WorkflowExecutionService（运行触发，已完成）
- 目标: 迁移 `executeWorkflow` / `backfillWorkflow` / `switchSchedulerEngine` 的编排逻辑（执行日志先持久化，DolphinScheduler 触发后回写运行态，失败时标记日志失败；调度引擎切换保持连接校验、项目解析、运行态字段清空和定义 JSON 运行态绑定重写顺序）。
- 触及文件: 新增 `WorkflowExecutionService.java` + 单测；改 `WorkflowService.java` 委托；新增 `WorkflowDefinitionAssembler.java` 承接调度引擎切换所需的定义运行态绑定刷新。
- 边界: `WorkflowDefinitionAssembler` 已扩展承接 `createWorkflow` / `updateWorkflow` 和 `switchSchedulerEngine` 需要的定义装配、运行态绑定刷新、catalog 回填、任务元数据规范化和调度默认值处理。
- 验证: `mvn -pl backend -am -Dtest=WorkflowExecutionServiceTest,WorkflowServiceMetadataPersistenceTest,WorkflowSchedulerEngineSwitchTest -DfailIfNoTests=false test`；回归执行、回填、失败日志和引擎切换既有保护。
- 回退: 单提交回退。

### T5 — 抽取 WorkflowCommandService（CRUD，已完成）
- 目标: 迁移写命令逻辑；`WorkflowCommandService` 已承接 `createWorkflow` / `updateWorkflow` / `deleteWorkflow`，包含定义写入、任务关系重建、任务元数据规范化、版本快照、变更判定、relation version 回写、DolphinScheduler 远端清理和可选任务/血缘/表关系级联软删。
- 触及文件: `WorkflowCommandService.java`、`WorkflowService.java`、`WorkflowServiceMetadataPersistenceTest.java`、`WorkflowSchedulerEngineSwitchTest.java`；复用 `WorkflowDefinitionAssembler` 处理定义装配、任务元数据规范化和调度默认值。
- 边界: `buildDefinitionJsonForExport` 和 `normalizeAndPersistMetadata` 仍保留在 `WorkflowService` facade，因为它们既有公有 API 会在读取/规范化时写回 `definition_json`，后续如需继续下沉应作为独立行为保持切片处理。
- 事务: `@Transactional` 保留在 `WorkflowService` facade；协作者随 facade 事务传播，不另起独立事务。
- 验证: `mvn -pl backend -am -Dtest=WorkflowServiceMetadataPersistenceTest,WorkflowSchedulerEngineSwitchTest,WorkflowExecutionServiceTest -DfailIfNoTests=false test`；重点回归 create/update 版本快照、任务关系版本回写、级联删除和调度引擎切换。
- 回退: 单提交回退。

### T6 — 收尾
- 目标: `WorkflowService` 已收敛为 facade；清理迁移后残留的未用私有方法/导入，并保留对带写回副作用公有 API 的显式边界说明。
- 验证: `mvn -pl backend -am test`；统计 `WorkflowService` 行数下降、依赖收敛。
- 回退: 单提交回退。

---

## 验证总览

- 每个任务: `mvn -pl backend -am test`，T0 基线 + 各步新增测试必须全绿。
- 关键回归面: 创建/更新/级联删除、发布链路依赖、运行触发与回填、调度引擎切换、`refreshTaskRelations`、导出 JSON。
- 若本地可启动后端 + MySQL，对发布/运行链路做一次冒烟（评审报告已说明本仓库智能问数冒烟方法，此处为工作流链路类比）。

### 2026-06-18 本地有状态验证

- 环境:
  - MySQL: `127.0.0.1:3306/opendataworks`，容器 `data-portal-mysql`，版本 `8.0.43`。
  - Redis: `127.0.0.1:6379`，容器 `odw-local-redis`，`redis-cli ping` 返回 `PONG`。
  - DolphinScheduler: `127.0.0.1:12345/dolphinscheduler`，容器 `dolphinscheduler-standalone-server`，版本 `3.2.0`，健康检查 `UP`。
  - 本地 Dolphin 验证环境修正: `reserved-memory` 调整为 `0.01`，并将 `mysql-connector-j-8.0.33.jar` 放入 `/opt/dolphinscheduler/libs/api-server/` 与 `/opt/dolphinscheduler/libs/worker-server/`，否则 DS 3.2 standalone 会分别出现 master overload 或 SQL task `ClassNotFoundException: com.mysql.cj.jdbc.Driver`。
- 代码修正验证:
  - `WorkflowInstanceCache.createdAt` 为 `Date` 类型，原 MyBatis-Plus 自动填充只覆盖 `LocalDateTime`；已扩展 `MybatisPlusConfig` 同时填充 `Date`，并在 `WorkflowQueryServiceIntegrationTest` 断言真实插入后 `createdAt` 非空。
- 命令与结果:
  - `SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/opendataworks?... SPRING_DATASOURCE_USERNAME=opendataworks SPRING_DATASOURCE_PASSWORD=opendataworks123 mvn -pl backend -am -Dtest=WorkflowQueryServiceIntegrationTest -DfailIfNoTests=false test` -> `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。
  - 同环境 `mvn -pl backend -am -Dtest=WorkflowRuntimeSyncRealIntegrationTest -DfailIfNoTests=false test` -> `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`；覆盖真实 Dolphin 发布、反向同步、运行实例、SQL 节点执行、结果表断言和清理。
  - 同环境 `mvn -pl backend -am -Dtest=WorkflowQueryServiceTest,WorkflowQueryServiceIntegrationTest,WorkflowTaskRelationServiceTest,WorkflowTaskRelationServiceIntegrationTest,WorkflowExecutionServiceTest,WorkflowSchedulerEngineSwitchTest,WorkflowServiceMetadataPersistenceTest,WorkflowVersionComparePersistenceIntegrationTest,WorkflowPublishPreviewIntegrationTest -DfailIfNoTests=false test` -> `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`。
  - 同环境 `mvn -pl backend -am -Dtest=WorkflowLifecycleFullTest,WorkflowLifecycleIntegrationTest -DfailIfNoTests=false test` -> `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`。
  - 同环境 `mvn -pl backend -am test` -> `Tests run: 294, Failures: 0, Errors: 0, Skipped: 2`。

## 回滚策略

- 每个 T 任务为**独立提交**，问题时按提交回退。
- 公有 API 全程不变，`WorkflowController`、5 个同族服务、`DataTaskService` 无需改动，回退不外溢。
- T0 测试网先落地并保持常绿，任一步骤打破基线即停止并回退该步。

## 排期建议

- T0 必须最先完成并通过，作为后续所有抽取的前置门槛。
- T1 → T2 → T3 → T4 → T5 严格按风险递增顺序，逐步推进，不并行。
- T6 收尾后再评估是否启动同类技术债（`InspectionService` 规则注册表、`DataTableController` 逻辑下沉）的独立设计。
