# Task-level Recent Execution and Workflow Execution Monitor Design

**Date:** 2026-07-31
**Goal:** 精简任务列表，按 Dolphin 任务实例展示任务级最近执行，并让执行监控统一覆盖平台触发与 Dolphin 定时触发的工作流实例。
**Tech Stack:** Vue 3 + Element Plus；Spring Boot + MyBatis-Plus；DolphinScheduler 3.2/3.4 OpenAPI；既有 `workflow_instance_cache`。

## Current State

- 任务列表展示任务编码、调度配置、负责人等内部字段，上下游数量不可操作。
- “最近执行”只读取本地 `task_execution_log`；平台运行工作流时该表只记录一个代表任务，因此同工作流其他任务长期显示“未执行”。
- 执行监控只查询 `task_execution_log`，Dolphin 定时创建的工作流实例不会进入该表，因而不可见。
- 平台已通过 `workflow_instance_cache` 缓存 Dolphin 工作流实例，但尚未接入 Dolphin 任务实例接口。
- 任务使用逻辑删除；删除记录继续占用任务编码，并且同名任务再次删除时会与历史记录的 `(task_name, deleted=1)` 唯一键冲突。

## Scope

本次包含：

- 任务列表移除任务编码、调度配置、负责人列，保留任务 ID 与任务名称。
- 上下游数量点击后填写已有的反向关系筛选框并立即查询。
- 任务“最近执行”以最新工作流实例为边界，用 `dolphinTaskCode` 匹配该实例下的 Dolphin 任务实例。
- 执行监控改为工作流实例顶层列表，展开行实时读取该次运行的任务实例。
- 平台触发、Dolphin 定时触发和提交 Dolphin 前失败的本地记录统一展示。
- 逻辑删除前归档任务名称和编码，允许同名、同编码任务再次创建和删除。
- 保留旧执行历史 API，新增接口供新监控页使用。

本次不包含：

- 不删除任务编码、调度配置、负责人后端字段，不修改任务编辑表单。
- 不新增数据库迁移或任务实例缓存表。
- 不向前查找任务在更早工作流实例中的状态。
- 不实现 Dolphin 日志正文读取。

## Solution

### 1. Dolphin 任务实例兼容

新增调用：

```text
GET /projects/{projectCode}/task-instances
```

Dolphin 3.2 使用 `processInstanceId`，3.4 使用 `workflowInstanceId`。客户端在同一请求中发送两套参数且值相同；DTO 兼容两套工作流实例字段。任务实例按页读取，每个工作流实例只发起一组分页请求。

工作流实例 DTO 同时兼容 `processDefinitionCode` 与 `workflowDefinitionCode`，保证监控在 3.2/3.4 都能按工作流编码过滤。

### 2. 任务级最近执行

任务列表加载一页数据后：

1. 按平台工作流分组已部署任务。
2. 每个工作流读取最新一个 Dolphin 工作流实例。
3. 按工作流实例批量读取所有任务实例。
4. 以 `dolphinTaskCode` 映射平台任务，每个任务展示自己的任务实例状态。

同一工作流只进行一次最新实例查询和一次任务实例分页查询，避免逐任务 N+1。

任务未出现在最新工作流实例时：

- 工作流仍在运行：`waiting` / “等待执行”。
- 工作流已终止：`not_run` / “本次未运行”。
- 工作流从未运行：无执行状态，页面显示“未执行”。
- Dolphin 查询失败：`unavailable` / “状态不可用”，不以工作流状态冒充任务状态。
- 未关联、未部署或没有 Dolphin 任务编码的任务：继续读取本地最近任务日志。

### 3. 统一工作流实例监控

新增 `WorkflowExecutionMonitorService`，以工作流为单位聚合：

- Dolphin 实时工作流实例。
- Dolphin 不可用时的 `workflow_instance_cache`。
- 平台写入的本地 `task_execution_log`。

Dolphin 实例与本地日志按“平台工作流 ID + 外部实例 ID”去重：

- 匹配到本地日志的 Dolphin 实例标记来源为 `platform`。
- 未匹配的实例标记来源为 `dolphin`，其中调度命令映射为 `schedule`。
- 没有外部实例 ID 的本地失败记录代表提交 Dolphin 前失败，保留为不可展开的平台记录。

列表筛选、分页前的统计均基于同一个聚合快照，避免历史列表与统计口径不一致。

### 4. 展开任务实例

顶层工作流实例按需调用任务实例接口，不预加载所有任务：

- 以 Dolphin `taskCode` 匹配平台任务 ID 和名称。
- 展示任务状态、主机、重试次数、执行人、开始/结束时间和时长。
- 单行加载失败由该展开行展示错误及重试按钮，不影响顶层列表和其他展开行。
- 提交 Dolphin 前失败的本地记录不可展开。

### 5. 状态与触发映射

状态：

- `SUCCESS` / `FORCED_SUCCESS` → `success`
- `FAILURE` / `FAILED` → `failed`
- `RUNNING_EXECUTION` / `RUNNING` / `SUBMITTED_SUCCESS` / `DELAY_EXECUTION` → `running`
- `STOP` / `KILL` / `KILLED` → `killed`
- `READY_PAUSE` / `PAUSE` → `paused`
- 其他等待态 → `pending`

触发方式：

- Dolphin 调度命令 → `schedule`
- `START_PROCESS` 或平台手动日志 → `manual`
- `COMPLEMENT_DATA` 或平台补数日志 → `backfill`
- 其他 → `api`

### 6. 任务列表交互

- 点击“上游任务数”：设置 `downstreamTaskId=row.id`，清空 `upstreamTaskId`。
- 点击“下游任务数”：设置 `upstreamTaskId=row.id`，清空 `downstreamTaskId`。
- 两种点击均重置页码并立即请求。
- 仅服务端列表、工具栏可见、非新增行且数量大于 0 时可点击。

### 7. 任务逻辑删除唯一键

- 删除前将 `task_name`、`task_code` 改写为以任务 ID 结尾的归档值，再执行 MyBatis-Plus 逻辑删除。
- 归档值限制在原字段的 100 字符范围内，不改变数据库结构。
- 任务 ID 保证每次删除生成不同归档值，因此重复创建、删除同名任务不会产生 duplicate key。
- 创建任务时若发现修复前的已删除记录仍占用目标编码，先按相同规则归档该历史记录，再插入新任务。

## Interfaces

新增：

```text
GET /v1/executions/workflow-instances
  ?workflowId=&status=&startTime=&endTime=&refresh=&pageNum=&pageSize=

GET /v1/executions/workflows/{workflowId}/instances/{instanceId}/tasks
```

第一个接口返回：

- `records`：工作流实例分页。
- `statistics`：同一筛选快照的总数、成功、失败、运行中、成功率、失败率和平均时长。
- `total/pageNum/pageSize`。

旧 `/v1/executions/history`、`/statistics`、`/running`、`/failed` 和详情/同步接口保持不变。

## Failure and Fallback

- Dolphin 工作流实例刷新失败：读取对应工作流已有缓存。
- Dolphin 任务实例查询失败：任务列表显示“状态不可用”；展开行显示局部错误。
- Dolphin 成功返回空实例：清空对应工作流缓存，表示确实未执行。
- 单个工作流失败不阻断其他工作流聚合。
- 任务实例实时读取，不使用工作流级状态替代任务状态。

## Tradeoffs

- 监控每次加载会按已部署工作流读取最近最多 100 个实例，工作流较多时 OpenAPI 请求数随工作流数增长。
- 任务实例不落库，Dolphin 不可用时无法提供历史任务级详情；页面明确显示不可用或展开错误。
- 当前统计是可见的近期实例口径，不是 Dolphin 全量历史报表。

## Verification

- 后端契约测试覆盖 3.2/3.4 参数及 DTO 字段。
- 服务测试覆盖任务独立状态、缺失任务语义、定时实例、去重、提交前失败、缓存降级和任务展开映射。
- 服务测试覆盖删除任务归档唯一键、重复创建后再次删除，以及旧删除记录编码释放。
- 前端测试覆盖上下游筛选方向、统一查询参数、触发/来源文案和稳定行键。
- 运行目标 Maven 测试、前端 Vitest 与生产构建。
- 环境可用时真实执行一次平台手动运行和一次 Dolphin 定时运行，核对顶层与展开任务状态。
