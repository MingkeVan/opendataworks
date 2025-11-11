# 工作流自动编排技术实现设计（核心阶段）

> 基于《workflow-auto-orchestration.md》的业务目标，结合 `docs/reports/dolphinscheduler-api-guide.md` 中的 DolphinScheduler 网关/REST 接口，明确首期“可跑通流程”的技术方案。

## 1. 范围与里程碑
- **目标阶段**：实现多工作流定义、版本与任务关系的闭环管理，并能将平台定义发布到 DolphinScheduler（下称 Dolphin），同步获取最近执行历史，为工作流与任务列表提供实时数据支撑。
- **范围内能力**：
  1. 平台 `data_workflow`、`workflow_task_relation`、`workflow_version`、`workflow_publish_record` 等核心表实现与服务层 CRUD；
  2. 任务必须唯一归属一个工作流的校验、迁移流程；
  3. 手动触发的工作流定义生成与编辑；
  4. 平台态上线/下线与 Dolphin 发布操作解耦，并支持 `deploy` / `online` / `offline` 三种动作；
  5. 工作流/任务列表 UI 的基础视图、筛选、跳转 Dolphin、展示最近 10 次执行状态；
  6. 与 Dolphin 的 API 集成（Java Gateway 提交定义 + REST 发布、实例查询）。
- **范围外/后续增强**：自动拆分/合并算法、多租户安全策略、`external_dependencies` 字段的可视化管理、调度资源配额评估、智能巡检自动化等。

## 2. 系统架构概览
文本示意：
```
[Portal UI]
    | REST
[Workflow API Service]
    |-- WorkflowDefinitionService ----> data_workflow / workflow_version
    |-- TaskWorkflowRelationService --> workflow_task_relation
    |-- PublishOrchestrator ---------> workflow_publish_record + Dolphin Gateway/API
    |-- ExecutionHistorySync Job ----> Dolphin REST (instances) -> cache/report tables
    |-- TaskDependencyResolver ------> data_lineage / table_task_relation / cache
```
- **Portal UI**：提供工作流列表、详情、任务列表、发布操作等界面。
- **Workflow API Service**：新建 `workflow` 模块或扩展现有服务，封装数据访问、校验、版本、发布逻辑。
- **Dolphin Integration Layer**：
  - **Java Gateway**：通过 PyDolphin `GatewayEntryPoint.create_or_update_workflow` 将平台 JSON 定义写入 Dolphin 项目（来源：`docs/reports/dolphinscheduler-api-guide.md:1-170`）。
  - **REST API**：使用 `release`, `start-workflow-instance`, `workflow-instances`, `task-instances`, `log` 等接口完成上线/下线、实例拉取（参考 `docs/reports/dolphinscheduler-api-guide.md:200-330`）。
- **缓存/视图**：`task_dependency_view`（物化或 Redis）缓存上下游数量与列表，支撑任务列表查询。

## 3. 关键数据模型映射
| 用途 | 表/字段 | 说明 |
| --- | --- | --- |
| 工作流主数据 | `data_workflow(id, workflow_code, workflow_name, status, publish_status, current_version_id, last_published_version_id, definition_json, entry_task_ids, exit_task_ids, created_by, updated_by)` | `definition_json` 保存平台 DAG 结构；`workflow_code` 对齐 Dolphin `workflowDefinitionCode`；`status` 表示平台态，`publish_status` 表示最近一次发布结果。 |
| 任务归属 | `workflow_task_relation(id, workflow_id, task_id, node_attrs, upstream_task_count, downstream_task_count, version_id, workflow_inheritance_mode, created_at)` | `task_id` 唯一索引，确保一任务一工作流。`node_attrs` 存储在 Dolphin 发布时需要的 worker group、失败策略等。 |
| 版本快照 | `workflow_version(id, workflow_id, version_no, structure_snapshot, change_summary, trigger_source, created_by, created_at)` | `structure_snapshot` 为发布转换器的输入；`trigger_source` 区分任务变更、血缘变更、手工编辑。 |
| 发布记录 | `workflow_publish_record(id, workflow_id, version_id, target_engine, operation, status, engine_workflow_code, log, operator, created_at)` | `operation` 取 `deploy|online|offline`；`engine_workflow_code` 对齐 Dolphin 定义编码，便于 jump link。 |
| Dolphin 项目信息 | `workflow_integration_config`（新增表 or 配置文件） | 维护 `project_code`, `project_name`, `token`, `gateway_host`, `gateway_port`, `rest_base_url` 等。可按环境/租户维度配置。 |
| 执行历史缓存 | `workflow_instance_cache(workflow_id, instance_id, state, start_time, end_time)` | 由同步任务填充，限制为最近 10 条。 |

## 4. 服务与模块设计
### 4.1 WorkflowDefinitionService
- **职责**：
  - 解析并存储来自 UI 的工作流定义表单（节点、依赖、基础属性）；
  - 维护 `definition_json`、`entry_task_ids`、`exit_task_ids`；
  - 调用 `WorkflowVersionService` 生成版本；
  - 校验任务唯一归属，若任务属于其它工作流则组织迁移流程。
- **接口示例**：
  - `createWorkflow(dto)`：返回 `workflowId` 和 `versionNo`；
  - `updateWorkflow(id, dto)`：生成新版本，`status` 视审批结果变为 `ready`；
  - `getWorkflowDetail(id)`：聚合版本、任务、最近执行、发布记录。

### 4.2 TaskWorkflowRelationService
- 维护 `workflow_task_relation` 数据，提供：
  - `assignTasks(workflowId, taskIds, mode)`：任务批量归属；
  - `reassignTask(taskId, workflowId, cascadeMode)`：按“仅当前/级联下游/级联上下游”策略迁移，写入版本日志；
  - `updateDependencyCounts(workflowId)`：消费 `task_dependency_view` 数据回写上下游数量。
- 与 `TaskDependencyResolver` 协作，在血缘更新事件触发时重新计算计数。

### 4.3 WorkflowVersionService
- 根据 `definition_json` 生成 `structure_snapshot`：
  - 将任务节点信息（task_id、name、type、worker_group、参数）和依赖边序列化；
  - 计算 `diff`（新增/删除节点、依赖），写入 `change_summary`；
  - 支持 `rollback(versionId)`，回填 `data_workflow.definition_json` 与任务关系。

### 4.4 PublishOrchestrator
- **转换器**：将 `structure_snapshot` 转换为 Dolphin 需要的 `taskDefinitionJson`、`taskRelationJson`、`globalParams`、`schedule` 等字段。
- **Java Gateway 适配**：
  - 采用 PyDolphin `GatewayEntryPoint.create_or_update_workflow`（`docs/reports/dolphinscheduler-api-guide.md:90-170`）；
  - 需要配置 `PYDS_JAVA_GATEWAY_ADDRESS/PORT/AUTH_TOKEN` 环境变量；
  - 操作结果含 `workflowDefinitionCode`、`version`: 写回 `data_workflow.workflow_code` 与 `workflow_version.structure_snapshot.engineVersion`。
- **REST 发布**：
  - `deploy`：仅调用 Gateway upsert，不执行 release。
  - `online`/`offline`：调用 `POST /projects/{projectCode}/workflow-definition/{code}/release`，`releaseState=ONLINE|OFFLINE`（`docs/reports/dolphinscheduler-api-guide.md:210-250`）。
  - 记录 `workflow_publish_record`，并更新 `publish_status`、`last_published_version_id`。
- **实例启动（可选）**：在工作流详情页提供“立即执行”，调用 `POST /projects/{projectCode}/executors/start-workflow-instance`（`docs/reports/dolphinscheduler-api-guide.md:200-230`）。

### 4.5 ExecutionHistorySync Job
- 周期任务（每 5 min）遍历最近 7 天上线的工作流：
  1. 读取 `workflow_code`、`project_code`；
  2. 调用 `GET /projects/{projectCode}/workflow-instances?workflowDefinitionCode=...&pageSize=10` 拉取实例（`docs/reports/dolphinscheduler-api-guide.md:230-280`）；
  3. 缓存或写入 `workflow_instance_cache`，供 UI 使用；
  4. 同时根据最新 `state` 更新 `data_workflow.last_execution_state`（可选）与任务级指标；
  5. 若需要任务节点状态，调用 `GET /projects/{projectCode}/workflow-instances/{id}/tasks`。

### 4.6 TaskDependencyResolver
- 从 `data_lineage`、`table_task_relation` 构建任务依赖图，并写入 `task_dependency_view`。
- 当任务血缘变化时触发 `WorkflowDefinitionService` 生成新版本或提示人工处理。

### 4.7 Portal UI & API 对接
- **工作流列表**：调用 `/api/v1/workflows`，后端聚合 `data_workflow`、`workflow_instance_cache`、`workflow_publish_record`；点击“查看 Dolphin”跳转 `rest_base_url/projects/{projectCode}/workflow-definition/{workflowCode}`。
- **工作流详情**：展示 DAG（依据 `definition_json`）、版本历史、发布记录、最近 10 次执行。
- **任务列表**：`/api/v1/tasks` 接口增加 `workflowId`、`upstreamTaskId`、`downstreamTaskId` 参数，后端使用 `workflow_task_relation` + `task_dependency_view` 完成过滤与上下游计数。

## 5. Dolphin API/网关调用细节
| 功能 | 调用方式 | 关键参数 | 说明 |
| --- | --- | --- | --- |
| Upsert 工作流定义 | PyDolphin `GatewayEntryPoint.create_or_update_workflow` | `project_name`、`workflow_name`、`task_relation_json`、`task_definition_json`、`schedule` | 先在 Dolphin 控制台创建对应项目；平台保存 `project_name` 与 `project_code` 映射。成功返回 `workflowDefinitionCode`。 |
| 发布上线/下线 | `POST /projects/{projectCode}/workflow-definition/{code}/release` | `releaseState=ONLINE/ OFFLINE` | `online` 仅在平台审批通过且用户点击“发布上线”后调用。|
| 手动触发实例 | `POST /projects/{projectCode}/executors/start-workflow-instance` | `workflowDefinitionCode`、`scheduleTime`、`failureStrategy`、`warningType`、`workerGroup` | `workerGroup` 由 `node_attrs` 提供，`scheduleTime` 默认为当前时间窗口。|
| 批量触发 | `POST /projects/{projectCode}/executors/batch-start-workflow-instance` | `workflowDefinitionCodes` | 用于平台“多工作流补数”。|
| 实例列表（最近 10 次） | `GET /projects/{projectCode}/workflow-instances` | `workflowDefinitionCode`、`pageSize=10` | 同步任务保存 `state`、`startTime`、`endTime`。|
| 实例详情 + 任务列表 | `GET /projects/{projectCode}/workflow-instances/{id}`、`GET .../{id}/tasks` | `id` | 当用户在 UI 查看某次实例时调用。|
| 调度上线/下线 | `POST /projects/{projectCode}/schedules` + `/online` `/offline` | `schedule` JSON、`warningType`、`failureStrategy` | 若平台需要管理 Dolphin 的 Schedule。首期可仅保存 crontab 信息，发布时一起更新。|
| 任务日志 | `GET /log/detail` | `taskInstanceId`, `skipLineNum`, `limit` | UI 的“查看日志”按钮调用。|

## 6. 关键流程
### 6.1 创建/更新工作流（平台定义）
1. 用户在工作流详情/编辑界面调整 DAG；
2. 前端将节点列表、依赖、调度参数提交至 `/api/v1/workflows`（POST/PUT）；
3. `WorkflowDefinitionService` 校验：
   - 所含任务 `task_id` 是否存在且未归属其它工作流（或触发迁移流程）；
   - DAG 是否无环、入口出口是否明确；
4. 保存 `data_workflow`、`workflow_task_relation`（事务）并生成 `workflow_version`；
5. 状态置为 `draft`/`ready`，等待审批；
6. 审批通过后可执行平台上线（`status=online`），此时仍未影响 Dolphin。

### 6.2 发布到 Dolphin（deploy + online）
1. 用户点击“发布上线”，选择操作类型：`仅同步定义(deploy)` 或 `同步并上线(online)`；
2. `PublishOrchestrator` 查询 `current_version_id`、`structure_snapshot`，将其转换为 Dolphin JSON；
3. 调用 Gateway `create_or_update_workflow`，成功后拿到 `workflowDefinitionCode`；
4. 写入 `workflow_publish_record(operation=deploy)`, 更新 `publish_status`；
5. 若选择 `online`，继续调用 `POST .../release` `releaseState=ONLINE`；
6. 更新 `last_published_version_id`，将 Dolphin 返回的状态写入 `data_workflow`；
7. 失败时记录日志，`publish_status=failed`，允许在 UI 重试。

### 6.3 下线流程
- 平台下线：`status=offline`，禁止新实例，但 Dolphin 仍保持；
- 发布下线：在发布页面选择 `operation=offline`，调用 `POST .../release` `releaseState=OFFLINE`，成功后记录 `workflow_publish_record`。

### 6.4 最近执行历史同步
1. `ExecutionHistorySync` 每 5 分钟扫描 `status=online` 的工作流；
2. 对每个工作流调用 `GET /workflow-instances` 仅拉取 `pageSize=10`；
3. 将结果写入缓存表，并更新 UI 需要的字段（状态、耗时、触发类型）；
4. 若用户打开详情页且缓存超时，再次实时调用接口；
5. 若 `state=FAILURE`，同时向告警系统发送事件。

### 6.5 任务列表查询
1. 前端请求 `/api/v1/tasks?workflowId=...&upstreamTaskId=...`；
2. 后端根据参数：
   - 先在 `workflow_task_relation` 过滤 `workflow_id`；
   - 若有上下游参数，读取 `task_dependency_view`，返回匹配任务；
   - 附带 `upstream_task_count`、`downstream_task_count` 以及 `workflow_name`。
3. 提供跳转链接到工作流详情和 Dolphin 任务节点（根据 `engine_workflow_code` + 任务 code）。

## 7. 配置与运行要求
- **Dolphin 连接配置**：
  - `rest_base_url`、`project_code`、`project_name` 每个环境一份；
  - `token` 存储于 Vault/配置中心，通过服务启动参数注入；
  - `PYDS_JAVA_GATEWAY_*` 环境变量由 K8s Secret 注入。
- **安全**：所有对 Dolphin API 的调用都带 Token，敏感参数仅在 backend 内部可见。
- **幂等性**：
  - Gateway upsert 采用 `workflow_name` + `project_name`；重复调用会更新定义；
  - REST 发布接口需要 `workflowDefinitionCode`，若未改动可直接跳过；
  - 发布记录与版本号使用数据库事务保证一致。

## 8. 监控与告警（核心阶段）
- **接口调用监控**：统计 Gateway 与 REST 调用成功率、耗时，写入 Prometheus；
- **发布失败告警**：`workflow_publish_record.status=failed` 时推送到 IM；
- **实例失败告警**：同步任务检测到连续失败或失败率超过阈值后触发通知；
- **数据一致性**：每日巡检 `workflow_task_relation` 确保无孤儿任务；若发现任务缺少工作流，自动创建工单。

## 9. 交付检查清单
1. 数据库表结构与索引（含唯一约束、外键）上线；
2. 新增后端模块代码合并，包含单元测试与 API 文档；
3. 与 Dolphin 的连通性验证脚本（Ping Gateway、调用 `/workflow-instances`）完成；
4. Portal UI 工作流列表/详情/任务列表功能验收；
5. 执行一次端到端流程：创建工作流 → 保存版本 → 发布上线 → Dolphin 上查看 → 拉取最近 10 次实例；
6. 监控和日志仪表盘上线。

> 该技术方案确保首期即可管理多工作流定义并与 Dolphin 打通核心链路，为后续自动编排与高级治理能力奠定基础。
