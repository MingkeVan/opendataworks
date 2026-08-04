# Workflow Backfill Guard and Execution View Unification Plan

**Date:** 2026-08-04
**Design:** [2026-08-04-workflow-backfill-and-execution-view-design.md](../design/2026-08-04-workflow-backfill-and-execution-view-design.md)

## Implementation Tasks

### 1. 补数禁止未来日期

- [x] 新增纯函数模块 `frontend/src/views/workflows/backfillForm.js`：`isAfterToday`、`validateBackfillRange`、`parseScheduleDateList`、`validateBackfillForm`、`buildBackfillPayload`。
- [x] `WorkflowBackfillDialog.vue` 日期选择器挂 `:disabled-date="isAfterToday"`，补 `:default-time` 使起止落在当天首尾。
- [x] `validateForm()` 改调纯函数；列表模式补齐格式校验与未来日期校验。
- [x] 弹窗提示文案与列表 placeholder 写明「不支持晚于今天的日期」。
- [x] 新增 `WorkflowBackfillValidator`，在 `WorkflowExecutionService.backfillWorkflow` 于建执行日志之前调用。
- [x] `DolphinSchedulerService.buildComplementScheduleTime` 去掉重复的存在性守卫，只保留 JSON 组装。

### 2. 共享实例表格

- [x] 新增 `frontend/src/components/workflowInstanceDisplay.js` 承接行级展示纯函数。
- [x] 新增 `frontend/src/components/WorkflowInstanceTable.vue`，自持任务实例懒加载与 Dolphin 链接渲染。
- [x] `ExecutionMonitor.vue` 改为统计卡 + 筛选栏 + 共享表格 + 分页。
- [x] `executionMonitorModel.js` 只留 `buildWorkflowExecutionParams`。
- [x] `WorkflowDetail.vue` 执行历史 tab 改用共享表格 + 分页，首次切到该 tab 时才请求，`workflowId` 恒定来自路由。
- [x] 删除 `WorkflowDetail.vue` 的 `buildDolphinInstanceUrl` / `openDolphinInstance`。
- [x] 清理 `workflowDisplay.js` 中已无消费方的 `getInstanceStateType` / `getInstanceStateText` / `getTriggerText` / `formatDuration`。

### 3. 调度日期贯通

- [x] `WorkflowInstanceSummary` 增加 `scheduleTime` 与 `workflowCode`，由新增的 `toSummary` 统一构建。
- [x] 新增迁移 `V49__add_schedule_time_to_workflow_instance_cache.sql`（`schedule_time` 列 + `idx_start_time`）。
- [x] `WorkflowInstanceCache` 实体与 `WorkflowInstanceCacheService.buildCache` 落库 `scheduleTime`。
- [x] `RuntimeWorkflowInstance.fromSummary` / `fromCache` 携带 `scheduleTime`。
- [x] `WorkflowInstanceExecution` 增加 `scheduleTime`，在 `mapRuntimeInstance` 填入。
- [x] 共享表格新增「调度日期」列；demo mock 补该字段。

### 4. Dolphin 跳转

- [x] `DolphinExecutionMapper.workflowInstanceUrl` 纯函数，缺参返回 `null`。
- [x] `WorkflowInstanceExecution` 增加 `dolphinInstanceUrl`。
- [x] `WorkflowExecutionMonitorService` 按 `dolphinConfigId` 记忆 WebUI base URL，填充两个 map 方法。
- [x] 共享表格按 `dolphinInstanceUrl` 有无决定渲染 `el-link` 还是纯文本。

### 5. 监控取数改造

- [x] 新增 `DolphinSchedulerService.listRecentProjectInstances(configId, limit)`，复用已有的项目级 `listProcessInstances`。
- [x] `WorkflowExecutionMonitorService` 拆分单工作流路径与全局路径，常量 `RECENT_INSTANCE_WINDOW = 50`。
- [x] 抽出 `mergeWithLocalLogs`，两条路径共用本地日志合并。
- [x] 全局路径按 `dolphinConfigId` 分组、按 `processDefinitionCode` 反查、丢弃非本平台实例、只读不写缓存。
- [x] 新增 `WorkflowInstanceCacheService.listRecentAcrossWorkflows`，作为全局查询失败的降级。
- [x] `loadLocalLogsByWorkflow` 加 limit 收口。
- [x] 移除时间范围筛选整条链路：前端 picker 与 `dateRange`、`buildWorkflowExecutionParams` 分支、`TaskExecutionController` 两个 `@RequestParam`、Service 签名与两条 `.filter`、demo mock 过滤。
- [x] 监控页副标题写明「取最近 50 次」，页大小选项收为 `[10, 20, 50]`。

### 6. 测试

- [x] 新增 `backfillForm.spec.js`：倒置、未来、格式非法、列表含未来项、今天 23:59:59 边界、载荷构造。
- [x] 新增 `WorkflowBackfillDialog.spec.js`：`disabled-date` 已挂上、未来范围与未来列表项被拦且不发请求、过去范围可提交。
- [x] 新增 `workflowInstanceDisplay.spec.js`：含 `backfill → 补数`。
- [x] 更新 `executionMonitorModel.spec.js`（含「不携带时间参数」断言）、`workflowDisplay.spec.js`。
- [x] `WorkflowDetail.smoke.spec.js` 增加作用域断言：未开 tab 不请求、开 tab 后请求必带 `workflowId`。
- [x] 新增 `WorkflowBackfillValidatorTest`，与前端同一组边界。
- [x] `WorkflowExecutionMonitorServiceTest` 覆盖：全局路径按配置去重且不写缓存、多工作流共用一配置只查一次、丢弃非本平台实例、全局失败降级、`scheduleTime` 与深链映射、WebUI 未配置时深链为 `null`。
- [x] `WorkflowExecutionServiceTest` 增加「未来范围在写日志前被拒」，并把原有happy path 请求改为合法的过去区间。
- [x] `TaskExecutionControllerTest` 跟随签名调整。

### 7. 文档

- [x] 本设计与计划文档。
- [x] 订正 `website/api/workflow.md` 的补数请求体（原文档字段与 `WorkflowBackfillRequest` 完全不符），并写明日期规则。
- [x] 更新 `docs/design/2026-07-31-task-list-execution-monitoring-design.md` 的接口段：新增响应字段、移除时间参数、取数方式改为项目级最近 N 条。

## Verification

已执行：

- `npm --prefix frontend run test` — 42 个文件 / 265 用例全部通过。
- `npm --prefix frontend run build` — 通过。
- `npm --prefix frontend run lint` — 0 error（243 条既有 warning）。
- `mvn -pl backend -am test` — 461 用例，新增 16 条全部通过。

未执行：

- 本地端到端走查（补数弹窗、两处表格、Dolphin 跳转、调度日期实际取值）未运行——当前环境没有 MySQL 与 DolphinScheduler。
- Flyway `V49` 未在真实库上验证，仅经语法审阅。
- 全量后端测试中有 18 条 `@SpringBootTest` 集成用例因 `Communications link failure` 报错；改动前后基线一致（445/18 → 461/18），属环境缺库，非本次引入。

## Rollout

- 无配置项变更。
- 部署时 Flyway 自动应用 V49；`schedule_time` 为可空列，`idx_start_time` 为普通索引，对存量数据无破坏。
- 存量缓存行的 `schedule_time` 为 `NULL`，`WorkflowExecutionSyncJob`（每 5 分钟）或下一次详情页加载会回填。

## Backout

- 回滚代码即可；V49 新增的列与索引对旧代码无影响，可保留不删。
- 若需彻底回退 schema：`ALTER TABLE workflow_instance_cache DROP COLUMN schedule_time, DROP INDEX idx_start_time;`
