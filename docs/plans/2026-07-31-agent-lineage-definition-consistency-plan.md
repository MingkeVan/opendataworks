# Agent Task Lineage and Workflow Definition Consistency Plan

**Date:** 2026-07-31
**Design:** [2026-07-31-agent-lineage-definition-consistency-design.md](../design/2026-07-31-agent-lineage-definition-consistency-design.md)

## Implementation Tasks

### 1. Portal MCP 参数契约

- [x] `UpdateTaskInput` 的 `input_table_ids` / `output_table_ids` 改为 `list[int] | None = None`。
- [x] `CreateTaskInput` 两个字段改为必填。
- [x] `portal_update_task` 按 `model_fields_set` 组装 payload，省略字段不出现在 JSON 中。
- [x] 更新工具 docstring，说明省略与全量替换语义。

### 2. Agent 写入层

- [x] 删除 `BackendAgentTaskService.nullSafe()`，`null` 原样下传。
- [x] `updateTask` 传 `LineageValidationMode.STRICT`，`createTask` 保持既有校验。
- [x] 保留既有 `workflowId` 防丢处理。

### 3. 血缘合并与校验

- [x] 新增 `LineageValidationMode` 枚举。
- [x] `DataTaskService.update()` 调整顺序：读取已有血缘 → 按侧合并 → 校验最终列表 → 写入。
- [x] `validateTask()` 改为校验合并后的最终列表，修正"只替换一侧"被误拒的问题。
- [x] 两侧均省略时跳过血缘写入。
- [x] 删除无调用方的 `@Deprecated update(DataTask)` 单参重载。
- [x] 现有三参 `update()` 保留为 `LENIENT` 重载，`DataTaskController` / `WorkflowRuntimeSyncService` / `WorkflowVersionOperationService` 无需改动。

### 4. TaskLineageWriteService

- [x] 新增组件，统一替换 `data_lineage` 与 `table_task_relation`。
- [x] 写入后按 `workflowId` 去重刷新拓扑并持久化 `definitionJson`。
- [x] `DataTaskService.create/update/delete` 改走统一入口。
- [x] `SqlTableMatcherService.bindTaskRelations()` 改走统一入口，补上缺失的定义刷新。
- [x] `DataTableService.purgeTableMetadata()` 删除前收集受影响工作流，删除后去重刷新。
- [x] 确认该组件不依赖 `DataTaskService`。

### 5. TaskLineageConsistencyChecker

- [x] 新增只读比对组件，输出四类问题与分类计数。
- [x] 非 SQL 节点跳过 SQL 比对。
- [x] 提供任务级高可信缺失检查，供 STRICT 保存调用。
- [x] 提供工作流级完整报告，供 preview / deploy / export / 只读接口调用。

### 6. 配置与只读接口

- [x] 新增 `LineageConsistencyProperties`，绑定 `workflow.lineage-consistency.enforcement-mode`，默认 `warn`。
- [x] `application.yml` 增加默认配置。
- [x] 新增 `GET /v1/workflows/{id}/lineage-consistency`。

### 7. 发布链路

- [x] `buildPublishPreview()` 注入四类一致性问题。
- [x] block-missing 模式下高可信缺失同时写入 `errors` 并置 `canPublish=false`。
- [x] `ensureBlockingRepairIssuesResolved()` 增加场景参数。
- [x] `repairPublishMetadata()` 传 `METADATA_REPAIR`，保持现有行为。
- [x] `publish()` 在 `syncCurrentVersion()` 之后传 `PUBLISH_DEPLOY` 复检，不检查定义漂移。

### 8. 导出链路

- [x] `WorkflowExportJsonResponse` 增加 `consistencyIssues`。
- [x] `exportJson()` 附带一致性问题，不阻断。
- [x] 保持 `definitionJson` 非空不重建、为空走既有兜底。

### 9. 前端

- [x] 发布门禁逻辑提取到 `publishPreviewHelper.js`。
- [x] `WorkflowDetail.vue` 与 `WorkflowList.vue` 改用共享实现，消除四处重复过滤。
- [x] `repairable=false` 问题在 warn 模式下以只读告警呈现，不进入"修复元数据并重试"。
- [x] 沿用 `MAX_RENDER_COUNT` 与"另有 N 项"。

### 10. Skill 文档

- [x] `SKILL.md`、`reference/10-task-fields.md`、`reference/30-tool-recipes.md` 说明省略与全量替换语义。
- [x] `assets/task-template.json` 移除会被拒绝的空 `output_table_ids`。

### 11. Tests and verification

- [x] Portal MCP：省略字段序列化后键不存在；显式 `[]` 保留为空数组；创建缺字段被拒。
- [x] Agent 服务：`null` 原样传入；`workflowId` 继续保留；显式部分输入触发高可信校验失败且事务后原输入仍在。
- [x] `DataTaskService`：覆盖真值表全部组合；输入清空允许、输出清空拒绝、只替换一侧正确保留另一侧。
- [x] 写服务：任务保存、SQL bind、表 purge 后关系表与 `definitionJson` 同步；purge 多表时按 `workflowId` 去重只刷新一次。
- [x] 一致性检查：非 SQL 跳过；四类问题分别得到固定分类。
- [x] 发布：warn 模式 `canPublish` 保持 true 可继续；block-missing 模式 preview 与实际 deploy 均阻断；定义漂移在 sync 后不误阻断。
- [x] 导出：坏定义仍能下载并带问题；非空定义不重建；空定义走既有兜底。
- [x] 前端：两个发布入口行为一致；`repairable=false` 可见。

## Verification

已执行：

- 后端：`mvn -B -pl backend -am test` → 430 tests，0 failures，18 errors。18 个 error 全部是无 MySQL 导致的 Spring context 加载失败，与改动前基线（368 tests / 18 errors）逐条比对完全一致，未新增任何失败。
- Portal MCP：`pytest dataagent/portal-mcp/tests` → 27 passed。
- 前端：`npx vitest run` → 39 files / 237 tests 全部通过。

数字覆盖 #437 与其后两轮 review 补丁的累计结果。

未执行：

- 本地端到端冒烟。当前环境没有 MySQL，无法启动后端与 Portal MCP 走真实 HTTP 流程。已验证的层次是单元与契约级，尚未验证的是"Agent 更新任务 → 发布预检 → 导出"的真实全链路。
- 本地冒烟：Agent 省略输入、显式传输入子集、正常完整更新、发布预检、导出各跑一次，记录 MySQL、后端、Portal MCP 与前端验证环境。

## Rollout

1. 默认 `warn` 上线，不改变既有发布能力。
2. 用 `GET /v1/workflows/{id}/lineage-consistency` 扫描存量，统计高可信缺失分布。
3. 修复存量后切换 `block-missing`。

步骤 2 依赖定义漂移检查的准确性：定义为空、或含无法识别节点的工作流必须能被扫出来，
否则扫描结论不可信。相关修复见 review 后续补丁。

## Backout

- 配置回退到 `warn` 即可解除所有发布阻断。
- 代码回退需同时回滚 Portal MCP 与后端，否则省略语义与 `nullSafe` 删除会不匹配。
