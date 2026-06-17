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

### T2 — 抽取 WorkflowQueryService（读取）
- 目标: 迁移 `list` / `getDetail` 的纯读取逻辑，包含列表分页、详情组装、当前版本号填充、DolphinScheduler 最近实例实时读取与缓存兜底。
- 触及文件: 新增 `WorkflowQueryService.java` + 测试；改 `WorkflowService.java` 委托。
- 边界: `buildDefinitionJsonForExport` 在 `definition_json` 缺失时会根据任务关系重建并写回工作流，不是纯查询；本切片暂留在 `WorkflowService`，待 `WorkflowDefinitionAssembler` 抽取后再迁移。
- 验证: `mvn -pl backend -am test`；重点回归列表分页、详情组装、最近实例和版本号填充。
- 回退: 单提交回退。

### T3 — 抽取 WorkflowTaskRelationService（任务绑定/拓扑刷新，已完成）
- 目标: 迁移 `refreshTaskRelations`、任务绑定还原、taskId 去重收集、关系硬删重建与任务归属校验（与已有 `WorkflowTopologyService` 协作，避免重复）。
- 触及文件: 新增 `WorkflowTaskRelationService.java` + 单测/真实 MySQL 集成测试；改 `WorkflowService.java` 委托。
- 验证: `mvn -pl backend -am -Dtest=WorkflowTaskRelationServiceTest,WorkflowTaskRelationServiceIntegrationTest,WorkflowServiceMetadataPersistenceTest,WorkflowSchedulerEngineSwitchTest -DfailIfNoTests=false test`；回归任务关系刷新、拓扑 entry/exit、上下游计数和真实表约束。
- 回退: 单提交回退。

### T4 — 抽取 WorkflowExecutionService（运行触发，执行/补数已完成）
- 目标: 迁移 `executeWorkflow` / `backfillWorkflow` 的编排逻辑（执行日志先持久化，DolphinScheduler 触发后回写运行态，失败时标记日志失败）。
- 触及文件: 新增 `WorkflowExecutionService.java` + 单测；改 `WorkflowService.java` 委托。
- 边界: `switchSchedulerEngine` 仍调用 `refreshDefinitionRuntimeIds` 与 `enrichDefinitionMetadataFromCatalog`，会重写定义 JSON 中项目、调度、task-group 运行态绑定；本切片不复制这串定义装配私有逻辑，待 `WorkflowDefinitionAssembler` 抽取后再迁移。
- 验证: `mvn -pl backend -am -Dtest=WorkflowExecutionServiceTest,WorkflowServiceMetadataPersistenceTest,WorkflowSchedulerEngineSwitchTest -DfailIfNoTests=false test`；回归执行、回填、失败日志和引擎切换既有保护。
- 回退: 单提交回退。

### T5 — 抽取 WorkflowCommandService（CRUD，删除切片已完成）
- 目标: 迁移写命令逻辑；本切片已迁移 `deleteWorkflow`（含 DolphinScheduler 远端清理、关系硬删、可选任务/血缘/表关系级联软删）。
- 触及文件: 新增 `WorkflowCommandService.java`；改 `WorkflowService.java` 委托；复用既有删除行为测试覆盖真实协作者。
- 边界: `createWorkflow` / `updateWorkflow` 仍依赖 `resolveDefinitionJson`、版本快照、任务元数据规范化和 Dolphin datasource/task-group catalog 回填；这些逻辑应先随 `WorkflowDefinitionAssembler` 拆出后再迁移，避免在 CommandService 中复制大段定义装配私有方法。
- 事务: `@Transactional` 保留在 `WorkflowService` 编排层；协作者随编排事务传播，不另起独立事务。
- 验证: `mvn -pl backend -am -Dtest=WorkflowServiceMetadataPersistenceTest,WorkflowSchedulerEngineSwitchTest -DfailIfNoTests=false test`；重点回归级联删除和非级联删除的事务内副作用。
- 回退: 单提交回退。

### T6 — 收尾
- 目标: `WorkflowService` 收敛为薄编排 facade；清理迁移后残留的未用私有方法/导入。
- 验证: `mvn -pl backend -am test`；统计 `WorkflowService` 行数下降、依赖收敛。
- 回退: 单提交回退。

---

## 验证总览

- 每个任务: `mvn -pl backend -am test`，T0 基线 + 各步新增测试必须全绿。
- 关键回归面: 创建/更新/级联删除、发布链路依赖、运行触发与回填、调度引擎切换、`refreshTaskRelations`、导出 JSON。
- 若本地可启动后端 + MySQL，对发布/运行链路做一次冒烟（评审报告已说明本仓库智能问数冒烟方法，此处为工作流链路类比）。

## 回滚策略

- 每个 T 任务为**独立提交**，问题时按提交回退。
- 公有 API 全程不变，`WorkflowController`、5 个同族服务、`DataTaskService` 无需改动，回退不外溢。
- T0 测试网先落地并保持常绿，任一步骤打破基线即停止并回退该步。

## 排期建议

- T0 必须最先完成并通过，作为后续所有抽取的前置门槛。
- T1 → T2 → T3 → T4 → T5 严格按风险递增顺序，逐步推进，不并行。
- T6 收尾后再评估是否启动同类技术债（`InspectionService` 规则注册表、`DataTableController` 逻辑下沉）的独立设计。
