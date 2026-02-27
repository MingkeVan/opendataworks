# Workflow Dolphin 字段与平台 V3 字段对比及映射方案

## 1. 结论摘要

- 你判断的方向是对的：`taskGroupName` 不一致很可能由 Dolphin 导出里缺少 `taskGroupName` 引起。
- 现有发布预检把 `task.taskGroupName` 作为有效差异字段，但把 `task.taskGroupId` 视为噪声字段。
- 当运行态只有 `taskGroupId`、平台侧有 `taskGroupName` 时，即使语义一致也会报可修复漂移。
- 根因不是导出失败，而是当前比较规则对 `name/id` 的语义对齐不足。

## 2. 字段对比矩阵（Dolphin vs 平台 V3）

说明：
- Dolphin 侧字段来自导出 JSON 与运行态读取逻辑。
- 平台 V3 指 `workflow_version.structure_snapshot` 的 V3 规范化结构（`snapshotSchemaVersion=3`）。

### 2.1 Workflow 级字段

| 语义 | Dolphin 字段 | 平台 V3 字段 | 当前映射 |
| --- | --- | --- | --- |
| 工作流编码 | `processDefinition.code` / `workflowDefinition.code` | `workflow.workflowCode` | 直接映射 |
| 项目编码 | `projectCode` | `workflow.projectCode` | 直接映射 |
| 名称 | `name` / `workflowName` | `workflow.workflowName` | 直接映射 |
| 描述 | `description` / `desc` | `workflow.description` | 直接映射 |
| 全局参数 | `globalParams` | `workflow.globalParams` | 归一化 JSON 字符串 |
| 默认任务组名 | `processDefinition.taskGroupName` | `workflow.taskGroupName` | 直接映射（可能为空） |
| 发布状态 | `releaseState` / `publishStatus` | `workflow.status` / `workflow.publishStatus` | 状态值兼容映射 |

### 2.2 Schedule 级字段

| 语义 | Dolphin 字段 | 平台 V3 字段 | 当前映射 |
| --- | --- | --- | --- |
| scheduleId | `schedule.id` / `scheduleId` | `schedule.dolphinScheduleId` | 直接映射 |
| 调度状态 | `schedule.releaseState` | `schedule.scheduleState` | 直接映射 |
| cron | `schedule.crontab` / `cron` | `schedule.scheduleCron` | 直接映射 |
| 时区 | `schedule.timezoneId` / `timezone` | `schedule.scheduleTimezone` | 直接映射 |
| 开始时间 | `schedule.startTime` | `schedule.scheduleStartTime` | 文本到时间格式兼容 |
| 结束时间 | `schedule.endTime` | `schedule.scheduleEndTime` | 文本到时间格式兼容 |
| 失败策略 | `schedule.failureStrategy` | `schedule.scheduleFailureStrategy` | 直接映射 |
| 告警类型 | `schedule.warningType` | `schedule.scheduleWarningType` | 直接映射 |
| 告警组 | `schedule.warningGroupId` | `schedule.scheduleWarningGroupId` | 直接映射 |
| 实例优先级 | `schedule.processInstancePriority` | `schedule.scheduleProcessInstancePriority` | 直接映射 |
| workerGroup | `schedule.workerGroup` | `schedule.scheduleWorkerGroup` | 直接映射 |
| tenantCode | `schedule.tenantCode` | `schedule.scheduleTenantCode` | 直接映射 |
| environment | `schedule.environmentCode` | `schedule.scheduleEnvironmentCode` | 直接映射 |
| 自动上线 | `schedule.autoOnline`(部分场景) | `schedule.scheduleAutoOnline` | 兼容读取 |

### 2.3 Task 级字段（重点）

| 语义 | Dolphin 字段 | 平台 V3 字段 | 当前映射 |
| --- | --- | --- | --- |
| 任务编码 | `task.code` / `taskCode` | `tasks[].taskCode` | 直接映射 |
| 版本 | `version` / `taskVersion` | `tasks[].dolphinTaskVersion` | 直接映射 |
| 名称 | `name` / `taskName` | `tasks[].taskName` | 直接映射 |
| 类型 | `taskType` / `nodeType` | `tasks[].dolphinNodeType` | 直接映射 |
| SQL | `taskParams.sql` / `rawScript` | `tasks[].taskSql` | 直接映射 |
| 数据源 ID | `taskParams.datasource` / `datasourceId` | `taskParams.datasourceId` (definition 元数据) | 发布依赖 ID |
| 数据源名 | `taskParams.datasourceName` | `tasks[].datasourceName` | 直接映射 |
| 数据源类型 | `taskParams.type` / `datasourceType` | `tasks[].datasourceType` | 直接映射 |
| 任务组 ID | `task.taskGroupId` | `task.taskGroupId` (definition 元数据) | 发布依赖 ID |
| 任务组名 | `task.taskGroupName` | `tasks[].taskGroupName` | 用于展示和部分比对 |
| 输入/输出表 | `inputTableIds` / `outputTableIds` | `tasks[].inputTableIds` / `tasks[].outputTableIds` | 直接映射 |

注意：
- 平台 `data_task` 实体没有 `taskGroupId` 持久列，只有 `taskGroupName`。
- `taskGroupId` 主要通过 `definitionJson.taskDefinitionList[].taskGroupId` 保存并参与发布。

### 2.4 Relation 级字段

| 语义 | Dolphin 字段 | 平台 V3 字段 | 当前映射 |
| --- | --- | --- | --- |
| 上游 | `preTaskCode` / `upstreamTaskCode` | `edges[].upstreamTaskCode` | 直接映射 |
| 下游 | `postTaskCode` / `downstreamTaskCode` | `edges[].downstreamTaskCode` | 直接映射 |
| 入口边 | `preTaskCode=0` | `entryEdge` 语义 | 发布预检做了入口边归一化 |

## 3. `taskGroupName` 不一致的成因链路

1. Dolphin 导入解析时，`taskGroupName` 读取是可选：
- 读取 `taskGroupId`：`task.setTaskGroupId(readInt(item, "taskGroupId"))`
- 读取 `taskGroupName`：`task.setTaskGroupName(readText(item, "taskGroupName"))`

2. 发布预检平台侧快照来自 `data_task.taskGroupName`（或 workflow 默认组名兜底），但通常没有 taskGroupId。

3. 发布预检运行态快照可能只有 `taskGroupId`，`taskGroupName` 为空。

4. 差异过滤里：
- `task.taskGroupId` 被视为噪声字段（忽略）
- `task.taskGroupName` 不在噪声列表（保留）

5. 结果：即使运行态 `taskGroupId` 与平台语义一致，也可能因为 `taskGroupName` 缺失被报漂移。

## 4. 当前行为是否“导出有问题”

- 更准确地说：不是导出坏了，而是不同版本/接口返回字段集合不同，`taskGroupName` 不保证总是出现在 task 节点。
- 现有平台逻辑没有在预检前做“`taskGroupId -> taskGroupName` 补齐”，也没有在比对时做“同义字段折叠”，所以出现误报感知。

## 5. 映射转换方案（待你确认）

## 方案 A（推荐）：`taskGroupName` 回填 + 双字段比对

目标：在保留 `taskGroupName` 严格比对的前提下，减少因 Dolphin 导出缺字段导致的误报。

规则：
1. 运行态侧：
- 若 `taskGroupName` 为空且 `taskGroupId` 有值，调用任务组目录（`listTaskGroups`）补齐 name。
2. 平台侧：
- 在预检构造快照时，为每个 task 追加 definition 元数据中的 `taskGroupId`（按 `taskCode` 关联）。
3. 比对侧：
- 保持现有行为：`taskGroupId` 和 `taskGroupName` 都参与比较。
- 即使 `taskGroupId` 一致，`taskGroupName` 不一致也视为差异。

优点：
- 能覆盖 Dolphin 只回 `taskGroupId` 的场景（通过回填减少空值差异）。
- 保留名称差异的可观测性。

成本：
- 需额外查询一次任务组目录（仅在存在缺失 name 的任务时触发）。

## 方案 B（最小改动）：把 `task.taskGroupName` 也加入噪声字段

规则：预检直接忽略 `task.taskGroupName` 差异。

优点：
- 改动最小，立即消除这类提示。

风险：
- 真实的任务组名称漂移也被忽略（可能降低可观测性）。

## 方案 C（保持现状）

优点：
- 不改逻辑。

风险：
- 继续出现“ID 一致但名称缺失导致漂移”的提示，体验与信噪比较差。

## 6. 推荐落地顺序

1. 先做方案 A：补齐运行态 `taskGroupName`，并在平台快照写入 `taskGroupId`。  
2. 保持双字段比较策略，验证“`taskGroupId` 一致时仍比较 `taskGroupName`”。  
3. 增加 3 个用例：
- 仅 `taskGroupId`、缺 `taskGroupName`（可回填）-> 回填后不报虚假差异。
- `taskGroupId` 一致、`taskGroupName` 不一致 -> 报差异。
- `taskGroupId` 不一致 -> 报差异。

## 7. 代码定位（用于评审）

- Dolphin 任务字段解析：`DolphinRuntimeDefinitionService.parseTaskDefinitionsFromNode`
- 发布预检平台快照构造：`WorkflowPublishService.buildPlatformDefinition`
- 运行态/平台差异比较：`WorkflowRuntimeDiffService.buildTaskSnapshot + compareTasks`
- 发布噪声字段过滤：`WorkflowPublishService.PUBLISH_NOISE_TASK_FIELDS`
- 发布依赖 taskGroupId 元数据：`WorkflowDeployService.loadTaskDeployMetadata`
- V3 规范化字段：`WorkflowVersionOperationService.normalizeSnapshotFromDefinitionJson`
