# Task-level Recent Execution and Workflow Execution Monitor Plan

**Date:** 2026-07-31
**Design:** [2026-07-31-task-list-execution-monitoring-design.md](../design/2026-07-31-task-list-execution-monitoring-design.md)

## Implementation Tasks

### 1. Dolphin API and DTO

- [x] 新增 Dolphin 任务实例 DTO，兼容 3.2 `processInstance*` 与 3.4 `workflowInstance*` 字段。
- [x] 新增 `/projects/{projectCode}/task-instances` 分页查询，同时发送两套实例 ID 参数。
- [x] 工作流实例 DTO 兼容 3.4 `workflowDefinition*` 字段。
- [x] 保留 Dolphin 查询异常，让上层区分“空数据”和“数据源失败”。

### 2. Task-level recent execution

- [x] 当前页任务按工作流分组。
- [x] 每个工作流读取最新实例及该实例的全部任务实例。
- [x] 以 `dolphinTaskCode` 映射平台任务，避免逐任务查询。
- [x] 实现 `waiting`、`not_run`、`unavailable` 与“未执行”语义。
- [x] 独立或未部署任务保留本地日志回退。
- [x] 单任务最近执行接口与列表复用相同解析流程。

### 3. Unified execution monitor backend

- [x] 新增工作流实例分页与同快照统计接口。
- [x] 复用 `workflow_instance_cache` 作为工作流实例降级数据源。
- [x] Dolphin 实例与平台日志按工作流和外部实例 ID 去重。
- [x] 保留提交 Dolphin 前失败的本地平台记录。
- [x] 新增按工作流实例懒加载任务实例接口。
- [x] 平台手动、Dolphin 调度与补数触发类型统一映射。
- [x] 保持旧执行历史接口不变。

### 4. Frontend

- [x] 任务列表移除任务编码、调度配置、负责人列。
- [x] 上下游数量点击填写反向搜索条件、清空互斥条件并自动查询。
- [x] 增加“等待执行”“本次未运行”“状态不可用”展示。
- [x] 执行监控切换为工作流实例顶层列表。
- [x] 工作流筛选替代任务 ID 筛选，保留状态和时间范围。
- [x] 列表与统计读取同一个响应。
- [x] 展开行懒加载任务实例，错误局部展示并支持重试。

### 5. Task soft-delete unique keys

- [x] 逻辑删除前以任务 ID 生成唯一的归档任务名称和编码。
- [x] 创建任务时按需释放修复前删除记录仍占用的任务编码。
- [x] 归档值按字段上限截断，不新增数据库迁移。
- [x] 覆盖同名任务再次删除和历史编码复用测试。

### 6. Tests and verification

- [x] Dolphin 3.2/3.4 请求与响应兼容测试。
- [x] 同工作流不同任务状态、缺失任务状态测试。
- [x] 定时实例、平台去重、提交前失败和缓存降级测试。
- [x] 任务实例展开与平台任务 ID 映射测试。
- [x] 控制器筛选参数透传测试。
- [x] 前端筛选与监控模型 Vitest。
- [x] 后端编译与前端生产构建。
- [ ] 真实 Dolphin 平台手动 + 定时 smoke（依赖本地可访问的 Dolphin 运行时和有效配置）。

## Verification Commands

```bash
mvn -pl backend -am \
  -Dtest='DolphinTaskInstanceCompatibilityTest,DolphinSchedulerServiceTest,WorkflowExecutionMonitorServiceTest,WorkflowExecutionServiceTest,DataTaskServiceWorkflowMetadataTest,TaskExecutionControllerTest,TaskExecutionServiceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```bash
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use
npm --prefix frontend run test -- \
  src/views/tasks/__tests__/taskListFilters.spec.js \
  src/views/executions/__tests__/executionMonitorModel.spec.js \
  src/views/executions/__tests__/ExecutionMonitor.demo.spec.js
npm --prefix frontend run build
```

## Verification Results

- 后端目标测试：32 项通过，0 失败，0 跳过。
- 任务重复删除修复目标测试：14 项通过，0 失败，0 错误，0 跳过。
- 前端目标测试：3 个测试文件、9 项测试通过。
- 后端编译和前端生产构建通过。
- `git diff --check` 通过。
- 真实 Dolphin smoke 未完成：MySQL `3306`、Redis `6379` 与 Dolphin `12345` 曾通过连通性预检，但 Dolphin standalone 随后因 Podman VM 内存不足退出（退出码 137、`OOMKilled=true`）。
- 环境恢复记录：尝试将 Podman VM 从 2 GB 调整为 4 GB、3 GB，VM 均无法稳定启动，随后已恢复原 2 GB 配置；未修改或清理数据库卷。
- 未覆盖风险：平台手动触发、Dolphin 定时触发以及真实任务展开状态尚未完成端到端核对。

## Local Smoke

1. 预检主后端配置的 Dolphin URL、Token、项目和工作流是否可访问。
2. 平台手动运行一个包含多个任务的已部署工作流。
3. 确认执行监控只显示一个父工作流实例，来源为“平台”，展开后各任务状态与 Dolphin 一致。
4. 等待或触发一次 Dolphin 定时实例。
5. 确认无需平台日志也能显示该实例，来源为“Dolphin”，触发方式为“调度”。
6. 临时断开 Dolphin 后刷新，确认父实例使用缓存；任务列表显示“状态不可用”，展开行局部报错。
7. 恢复连接并重试展开，确认任务实例恢复。

## Rollout and Backout

- 无数据库迁移；先发布后端，再发布前端。
- 回退前端页面与 API helper 可恢复旧监控。
- 回退新增后端控制器方法、聚合服务与 Dolphin 任务实例客户端即可；旧接口和缓存表保持兼容。
