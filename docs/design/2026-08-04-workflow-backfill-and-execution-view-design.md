# Workflow Backfill Guard and Execution View Unification Design

**Date:** 2026-08-04
**Goal:** 补数禁止选择晚于今天的日期；执行监控与工作流详情「执行历史」复用同一张实例表格；实例行补充「调度日期」并支持跳转 DolphinScheduler；执行监控取数从按工作流扇出收敛为项目级最近 N 条。
**Tech Stack:** Vue 3 + Element Plus；Spring Boot 2.7 + MyBatis-Plus + Flyway；DolphinScheduler 3.x OpenAPI；既有 `workflow_instance_cache`、`task_execution_log`。

## Current State

- **补数没有日期约束。** `WorkflowBackfillDialog.vue` 的 `el-date-picker` 没有 `disabled-date`，`validateForm()` 只检查数组长度为 2；后端 `WorkflowBackfillRequest` 无任何 Bean Validation。起止倒置、跨多年、未来时间都会原样透传给 `COMPLEMENT_DATA`，直接产生一批无意义实例。列表模式完全没有格式校验。
- **同一种数据两套实现。** 执行监控 `views/executions/ExecutionMonitor.vue` 走 `/v1/executions/workflow-instances`，用小写 `status`，有分页、筛选、统计与任务实例展开，但没有 Dolphin 跳转；工作流详情「执行历史」读详情接口顺带的 `recentInstances` 缓存快照，用大写 `state`，固定 10 条无分页，触发方式映射表缺 `backfill`（补数记录显示为生字符串），`RUNNING` 配色也与监控页不一致，却是唯一有 Dolphin 跳转的地方。
- **调度日期被丢弃。** `DolphinProcessInstance.scheduleTime` 已解析，但在 `DolphinSchedulerService.listWorkflowInstances` 构建 `WorkflowInstanceSummary` 时丢失，全链路再无踪迹。
- **跳转 URL 前端重复拼装。** `WorkflowList.vue` 与 `WorkflowDetail.vue` 各拼一遍 DS 3.x 实例 URL。
- **监控取数按工作流扇出。** 不传 `workflowId` 时遍历全部工作流，逐个调 Dolphin 拉最多 100 条并逐个 `replaceCache` 删了重插；`loadLocalLogsByWorkflow` 全量无 limit 捞 `task_execution_log`。为显示 10 行拉了 N×100 行。

## Scope

本次包含：

- 补数前后端统一按日粒度拦截晚于今天的日期，并补齐格式与起止顺序校验。
- 抽出共享组件 `WorkflowInstanceTable`，两处复用；执行历史改用执行监控接口。
- `scheduleTime` 贯通至前端「调度日期」列，含 `workflow_instance_cache` 落库。
- 实例详情深链由后端下发。
- 执行监控全局取数改为项目级最近 `RECENT_INSTANCE_WINDOW`(50) 条，并移除时间范围筛选。

本次不包含：

- 不改动 `WorkflowDetailResponse.recentInstances` 字段本身（保留，不破坏 API）。
- 不改造 `WorkflowList.vue` 的「最近实例」单元格及其局部映射函数副本。
- 不修正 `TaskExecutionService` 中遗留的 DS 2.x 实例 URL 形状（见 Known Gaps）。

## 两处作用域差异（本次复用的边界）

两处展示的是同一种行，但作用域不同。**共享的只是「怎么渲染一行实例」，不是「查哪些实例」。**

| | 执行监控 `/workflows?tab=monitor` | 工作流详情「执行历史」 |
|---|---|---|
| 作用域 | 全部工作流 | 仅当前 `route.params.id` 这一个工作流 |
| 请求参数 | 不传 `workflowId`（或由筛选栏下拉指定） | 恒定传 `workflowId`，不可被用户改 |
| 工作流列 | 显示（`showWorkflowName`） | 隐藏——整屏都是同一个工作流，这一列是噪音 |
| 筛选栏 | 工作流下拉 + 快捷状态 | 无 |
| 统计卡 | 有 | 无 |
| 空状态文案 | 「当前筛选条件下暂无工作流执行记录」 | 「暂无执行记录」 |
| 取数方式 | 项目级一次查询，取最近 50 条 | 按 `processDefinitionCode` 服务端过滤 |

**设计约束：`WorkflowInstanceTable` 不持有任何列表查询逻辑**，不知道自己在哪一屏，也不发实例列表请求。它只接收 `instances` 并渲染；作用域完全由调用方通过 `buildWorkflowExecutionParams` 决定。唯一由组件自行发起的请求是展开行的任务实例懒加载，那是行级的、与作用域无关。

后续改动请守住这条线：不要把筛选栏塞进共享组件，也不要让详情页漏传 `workflowId` 而拉到全量数据。`WorkflowDetail.smoke.spec.js` 有一条针对性的作用域断言。

## Solution

### 补数日期约束

规则按**日粒度**：不晚于今天，今天当天任意时刻可选。前后端同一条规则，两侧都实现，前端负责即时反馈，后端负责兜底。

- 前端纯函数模块 `frontend/src/views/workflows/backfillForm.js`：`isAfterToday` 同时用作 `disabled-date`；`validateBackfillRange` 覆盖存在性、起止顺序、不晚于今天；`parseScheduleDateList` 逐项严格解析 `YYYY-MM-DD HH:mm:ss`（dayjs `customParseFormat` 严格模式）并逐项检查；`buildBackfillPayload` 顺带归一化列表空白。
- 后端 `WorkflowBackfillValidator.validate(request)`，在 `WorkflowExecutionService.backfillWorkflow` 中于 `createWorkflowExecutionLog` **之前**调用——现有代码先写日志再调 Dolphin，把校验提前可避免被拒的请求在 `task_execution_log` 留下垃圾行。
- 存在性校验单点收敛到 validator，`DolphinSchedulerService.buildComplementScheduleTime` 只保留 JSON 组装，不再重复守卫。

### 共享表格

- 新增 `frontend/src/components/WorkflowInstanceTable.vue`：props `instances / loading / emptyText / showWorkflowName / showSource / showErrorMessage / expandable`；自持展开行的任务实例懒加载与重试；实例ID 列在 `row.dolphinInstanceUrl` 有值时渲染为 `el-link`，否则纯文本。
- 新增 `frontend/src/components/workflowInstanceDisplay.js` 承接行级展示纯函数；`executionMonitorModel.js` 只留页面级的 `buildWorkflowExecutionParams`。
- 执行历史改用 `GET /v1/executions/workflow-instances?workflowId=X`，在首次切到该 tab 时加载，顺带获得分页、任务实例展开、统一状态映射与正确的补数标签。

### 调度日期

`DolphinProcessInstance.scheduleTime` → `WorkflowInstanceSummary.scheduleTime` → `workflow_instance_cache.schedule_time`（V49）→ `RuntimeWorkflowInstance.scheduleTime` → `WorkflowInstanceExecution.scheduleTime` → 前端「调度日期」列（排在实例ID 之后）。定时同步任务 `WorkflowExecutionSyncJob` 走 `replaceCache`，自动覆盖。平台已触发但 Dolphin 侧还没有实例的本地行留空。

### Dolphin 深链

后端下发，避免前端第三处拼装。`DolphinExecutionMapper.workflowInstanceUrl(baseUrl, projectCode, workflowCode, instanceId)` 为纯函数，任一参数缺失返回 `null`；`WorkflowExecutionMonitorService` 用请求内 `Map<dolphinConfigId, baseUrl>` 记忆 `getWebuiBaseUrl`，解析失败记空串且同一配置不再重试。URL 形状沿用前端现用的 DS 3.x：`{base}/ui/projects/{projectCode}/workflow/instances/{instanceId}?code={workflowCode}`。

### 监控取数

`workflowId != null` 时路径不变。`workflowId == null` 时：按 `dolphinConfigId` 分组，每组调一次 `DolphinSchedulerService.listRecentProjectInstances(configId, 50)`（内部复用已有的 `openApiClient.listProcessInstances(projectCode, pageNo, pageSize, null)`，翻页上限 3 页），用 `processDefinitionCode` 反查本平台工作流，映射不到的丢弃，合并后按 `startTime` 倒序取前 50 条。典型部署只有 1 个 Dolphin 配置，即每次翻页 1~3 次调用，与工作流数量无关。

配套：全局路径只读不写缓存（`replaceCache` 是 per-workflow 语义，由 `WorkflowExecutionSyncJob` 维护）；全局查询失败降级到 `WorkflowInstanceCacheService.listRecentAcrossWorkflows(50)` 一次索引查询；`loadLocalLogsByWorkflow` 加 limit 收口；V49 顺带为 `workflow_instance_cache.start_time` 建索引。

本地 `task_execution_log` 合并逻辑抽为 `mergeWithLocalLogs`，两条路径共用，保证「来源」「错误信息」「提交 Dolphin 前失败的行」在监控页仍然可见。

## Interfaces

`GET /v1/executions/workflow-instances`

- 移除 `startTime` / `endTime` 查询参数——监控页是「最近执行」视图，不再提供时间范围筛选。该端点只有 `ExecutionMonitor.vue` 一个调用方。
- 响应 `records[]` 新增两个字段：
  - `scheduleTime`：Dolphin 运行实例的调度日期，补数实例上表示补的是哪一个调度周期，可能为 `null`
  - `dolphinInstanceUrl`：实例详情深链，未配置 WebUI 地址或缺少 `projectCode` 时为 `null`

`POST /v1/workflows/{id}/backfill`

- 行为变更：范围与列表模式都会拒绝晚于今天的日期、非法格式与起止倒置，返回 400 并带中文原因。

数据库：`workflow_instance_cache` 新增 `schedule_time DATETIME NULL` 与 `idx_start_time`（V49）。

## Tradeoffs

- **最近 50 条 vs 全量分页。** 监控页语义收敛为「最近执行」：`total` 是窗口内条数，分页在窗口内进行，页大小选项收为 `[10, 20, 50]`，状态快捷筛选也在窗口内生效。代价是无法在监控页翻到很久以前的记录；补偿是筛选栏的工作流下拉走精确路径，详情页执行历史也可看单个工作流。换来的是调用次数与工作流数量解耦。
- **移除时间范围筛选。** 在最近 50 条里再按时间筛，结果要么是子集要么是空，控件本身失去意义。移除比下推 `startDate`/`endDate` 更简单，也不必依赖未验证的 DS 查询参数行为。
- **深链由后端下发 vs 前端拼装。** 后端下发多了一个响应字段和一次配置读取（已按配置记忆），但两个页面零重复，且与既有 `TaskExecutionStatus.dolphinTaskUrl` 的做法一致。
- **执行历史改用监控接口 vs 扩展 `recentInstances`。** 前者让两边行结构完全一致、组件真正可复用，且不改动详情接口的响应结构。代价是详情页每次加载仍会为 `recentInstances` 付一次 Dolphin 往返，而该字段前端已不再使用（见 Known Gaps）。

## Known Gaps

- `WorkflowDetailResponse.recentInstances` 前端已无消费方，但仍保留在响应中，详情页每次加载仍为它付一次 Dolphin 往返。移除属独立的 API 收敛动作，未包含在本次。
- `TaskExecutionService` 仍在拼 DS 2.x 的老形状 `/workflow/instance/{code}/{id}`，与本次统一的 3.x 形状不一致。
- `WorkflowList.vue` 保留着 `getInstanceStateType` / `getInstanceStateText` / `getTriggerText` 的局部副本（大写 DS 状态），供「最近实例」单元格使用，与共享模块的小写归一化状态并存。
