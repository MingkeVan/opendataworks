# Agent Task Lineage and Workflow Definition Consistency Design

**Date:** 2026-07-31
**Goal:** 修复 Agent 更新任务时血缘被静默清空，导致 `table_task_relation` 与 `definitionJson` 同时少边的问题；并让血缘写入、发布预检、导出三处对同一份一致性结论有统一口径。
**Tech Stack:** Spring Boot 2.7 + MyBatis-Plus（`backend`、`backend-agent-api`）；FastMCP + Pydantic（`dataagent/portal-mcp`）；Vue 3 + Element Plus（`frontend`）。

## Current State

### 血缘丢失链路

Agent 更新任务时，血缘会被静默清空，链路上有三个独立缺陷：

1. `dataagent/portal-mcp/portal_mcp/app.py` 的 `UpdateTaskInput.input_table_ids` / `output_table_ids` 使用 `Field(default_factory=list)`。模型省略该字段时，MCP 仍然向后端发送 `[]`，"未提供"与"清空"在协议层就已经无法区分。
2. `BackendAgentTaskService.nullSafe()` 把 `null` 转成 `Collections.emptyList()`，把仅存的 null 语义再抹掉一次。
3. `DataTaskService.update()` 无条件 `delete` 该任务的全部 `data_lineage` 与 `table_task_relation`，再按入参重建。入参为空即等于清空。

`DataTaskService.validateTask()` 里的 `enforceLineage = task.getId() == null || inputTableIds != null || outputTableIds != null` 表明"null 表示保持原值"本来就是既定约定，但 `update()` 从未遵守。

实际故障形态是：Agent 传了输出表、输入表传空或只传子集时，输入侧血缘被清空。两侧同时传 `[]` 时会命中"任务必须至少配置一个输出表"而报错，不会静默丢数据。

### 定义漂移

`DataTaskService.update()` 末尾经 `normalizeTaskMetadataOnPersist()` 调用 `WorkflowService.normalizeAndPersistMetadata()`，后者按 `table_task_relation` 重建并持久化 `definitionJson`。因此血缘一旦少边，定义同步少边。

`SqlTableMatcherService.bindTaskRelations()` 同样替换 `data_lineage` 与 `table_task_relation`，但完全不刷新工作流拓扑与定义，是既有的旁路缺口。

`DataTableService.purgeTableMetadata()` 删表时清掉该表全部关系与血缘，也不刷新任何工作流定义。

### 发布与导出现状

- `WorkflowPublishService.buildPlatformDefinition()` 从 `table_task_relation` 构建平台快照，preview 的 diff 本来就是关系驱动的。
- `publish("deploy")` 第一步就调用 `WorkflowService.syncCurrentVersion()`，按当前关系重新生成并持久化 `definitionJson`。因此 deploy 阶段比对"定义 vs 关系"没有意义。
- `ensureBlockingRepairIssuesResolved()` 目前只被 `repairPublishMetadata()` 调用，`publish()` 没有任何服务端复检，preview 可被绕过。
- `WorkflowService.buildDefinitionJsonForExport()` 在 `definitionJson` 非空时直接清理后导出；为空时构建并持久化后导出。
- 前端 `WorkflowDetail.vue` 与 `WorkflowList.vue` 的发布门禁均以 `repairIssues.filter(issue => issue?.repairable !== false)` 判定，`repairable=false` 的问题目前完全不参与门禁。`publishPreviewHelper.js` 的 `buildPublishRepairHtml()` 不过滤，因此这类问题只在弹窗被其他问题触发时才会被顺带看到。

## Scope

本次包含：

- 固化任务更新的血缘合并语义，并在协议层、Agent 层、业务层三处保持一致。
- 新增两个无循环依赖的组件，统一血缘写入与只读一致性比对。
- 补齐 `SqlTableMatcherService.bindTaskRelations()` 与 `DataTableService.purgeTableMetadata()` 的定义刷新。
- 发布预检与实际 deploy 复检接入一致性结论，并提供 warn / block-missing 两档开关。
- 导出附带一致性问题但不阻断。
- 新增只读一致性查询接口，用于上线前扫描存量。
- 同步 Portal MCP 参数契约、数据开发 Skill 文档与前端发布提示。

本次不包含：

- 不新增数据库字段或迁移。
- 不提供批量自动修复接口，不静默修改历史血缘。
- 不引入"工作流未保存"提示。系统实际已经保存，只是保存内容不完整。
- 不改变 `WorkflowCommandService` 的级联删除行为。
- 不改变导入路径既有的 unmatched / ambiguous 硬校验。

## Solution

### 1. 任务更新的血缘合并语义

更新任务时按侧独立合并，最终语义固定为：

| 原始参数 | 输入侧 | 输出侧 |
| --- | --- | --- |
| 字段省略 / `null` | 保留原输入 | 保留原输出 |
| `[]` | 清空输入，允许 | 清空输出，拒绝 |
| 非空数组 | 全量替换输入 | 全量替换输出 |

创建任务要求两个字段都出现；输入允许 `[]`，输出必须至少一个。

`DataTaskService.update()` 执行顺序调整为：

1. 读取已有血缘。
2. 按侧合并出最终输入 / 输出列表。
3. 用**最终列表**校验（输出非空；STRICT 模式追加 SQL 高可信校验）。
4. 校验通过后才更新任务与血缘。

校验失败前不删除任何记录。两侧都省略时完全跳过血缘写入，不做无谓的删除重建。

### 2. 校验强度分级

引入 `LineageValidationMode`：

- `LENIENT`：只校验最终输出非空。平台 UI、运行态同步、版本回滚使用。
- `STRICT`：追加"SQL 高可信缺失"校验。Agent 写入路径使用。

合并与校验都留在 `DataTaskService.update()` 内部，由调用方传模式，避免 Agent 层重复实现一份合并逻辑。调用方对应关系：

| 调用方 | 模式 |
| --- | --- |
| `BackendAgentTaskService` | `STRICT` |
| `DataTaskController` | `LENIENT` |
| `WorkflowRuntimeSyncService` | `LENIENT` |
| `WorkflowVersionOperationService` | `LENIENT` |

**SQL 高可信缺失**定义为：任务 `dolphinNodeType` 为 `SQL`，且 `SqlTableMatcherService.analyze()` 中 `matchStatus=matched` 的输入 / 输出表 ID 不在最终血缘里。多余血缘、`unmatched`、`ambiguous` 一律不阻断保存，避免形成"发不了也存不进"的死循环。非 SQL 节点完全跳过 SQL 一致性校验。

### 3. 组件拆分与依赖方向

新增两个组件，均不依赖 `DataTaskService`：

**`TaskLineageWriteService`**

- 统一替换 `data_lineage` 与 `table_task_relation`。
- 写入后按 `workflowId` 去重刷新受影响工作流拓扑并持久化 `definitionJson`。
- 供 `DataTaskService`、`SqlTableMatcherService.bindTaskRelations()`、`DataTableService.purgeTableMetadata()` 共同调用。
- 依赖：`DataLineageMapper`、`TableTaskRelationMapper`、`WorkflowTaskRelationMapper`、`WorkflowService`。

**`TaskLineageConsistencyChecker`**

- 只读比对 SQL 推断、`table_task_relation`、`definitionJson` 三方。
- 依赖：`SqlTableMatcherService`、`DataTaskMapper`、`TableTaskRelationMapper`、`WorkflowTaskRelationMapper`、`DataWorkflowMapper`、`ObjectMapper`。

依赖方向已核实无环。`WorkflowService` 及其传递依赖（`WorkflowCommandService`、`WorkflowTaskRelationService`、`WorkflowTopologyService`、`WorkflowDefinitionAssembler`、`WorkflowVersionService`）全部只依赖 mapper 与 Dolphin 服务，不反向依赖 `DataTaskService` / `DataTableService` / `SqlTableMatcherService`。因此下列链路成立且无环：

```text
DataTaskService -> TaskLineageConsistencyChecker -> SqlTableMatcherService -> TaskLineageWriteService -> WorkflowService
DataTaskService -> TaskLineageWriteService -> WorkflowService
DataTableService -> TaskLineageWriteService -> WorkflowService
```

**红线：`TaskLineageWriteService` 不得依赖 `DataTaskService`。** `WorkflowRuntimeSyncService` 同时持有 `SqlTableMatcherService` 与 `DataTaskService`，一旦反接即形成环。

### 4. 一致性问题分类

复用既有的 `WorkflowPublishRepairIssue`，新增四类：

| code | repairable | 含义 | 阻断 |
| --- | --- | --- | --- |
| `LINEAGE_SQL_RELATION_MISSING` | false | SQL 已匹配但关系表缺失 | block-missing 模式下阻断 |
| `LINEAGE_RELATION_EXTRA` | false | 关系表存在但 SQL 未推断 | 始终告警 |
| `LINEAGE_SQL_UNRESOLVED` | false | SQL 存在 unmatched 或 ambiguous | 始终告警 |
| `LINEAGE_DEFINITION_DRIFT` | true | `definitionJson` 边与关系表推导边不一致 | 不阻断，可走既有元数据修复 |

### 5. 发布时序

- **Preview**：同时展示 SQL / 关系差异与定义漂移。block-missing 模式下若存在 `LINEAGE_SQL_RELATION_MISSING`，除写入 `repairIssues` 外同时写入 `errors` 并置 `canPublish=false`，使两个前端入口既有的 `if (!preview?.canPublish)` 门禁直接生效。warn 模式下 `canPublish` 保持不变。
- **Deploy**：仍先执行 `syncCurrentVersion()`。同步后 `definitionJson` 必然与关系表一致，因此复检**只检查 SQL 与关系表**，不检查定义漂移。

`ensureBlockingRepairIssuesResolved()` 扩展为按场景筛选：

| 场景 | 检查内容 |
| --- | --- |
| `METADATA_REPAIR` | 仅该修复动作能解决的必填元数据（保持现状） |
| `PUBLISH_DEPLOY` | 必填元数据 + block-missing 模式下的 SQL 高可信缺失 |

平台与 Agent 即使绕过 preview，实际 deploy 仍会复检。

### 6. 上线策略

新增配置 `workflow.lineage-consistency.enforcement-mode`：

- `warn`（默认）：只提示，不改变既有工作流的发布能力。
- `block-missing`：仅阻断 SQL 已明确匹配但关系表缺失的情况。

新增只读接口 `GET /v1/workflows/{id}/lineage-consistency`，返回问题列表与分类计数，用于切换开关前扫描真实存量。

多余关系、`unmatched`、`ambiguous` 在任何模式下都只告警。

### 7. 导出行为

- `definitionJson` 非空：保持现状，直接清理后导出，不重建。
- `definitionJson` 为空：保留既有"构建并持久化后导出"的兜底分支。
- 不阻断导出。`WorkflowExportJsonResponse` 新增可选 `consistencyIssues`。

导出是只读诊断与备份手段，阻断会剥夺用户排查坏定义的能力。

### 8. 表清理

`DataTableService.purgeTableMetadata()` 在删除关系**之前**收集受影响任务与工作流，删除后按 `workflowId` 去重刷新定义。已删除的表若仍出现在 SQL 中，只会在后续一致性检查里产生 `LINEAGE_SQL_UNRESOLVED` 告警，不阻断。

`DataTableAutoPurgeTask` 每天批量 purge，刷新按 `workflowId` 去重后再执行，避免一次定时任务重复重写同一份 `definitionJson`。

`WorkflowCommandService` 的级联删除保持现状：其后紧接 `hardDeleteByWorkflowId()` 与工作流删除，任务与工作流一起消失，刷新无意义。**明确列为无须刷新的例外。**

## Interfaces

### Portal MCP

- `portal_create_task`：`input_table_ids`、`output_table_ids` 必须显式出现。
- `portal_update_task`：两字段改为可选；省略表示保留，数组表示全量替换。使用 `exclude_unset` 保证省略后 JSON 中确实没有对应键。

### Backend

- 新增 `GET /v1/workflows/{id}/lineage-consistency`。
- `WorkflowExportJsonResponse` 新增可选 `consistencyIssues`，`fileName` / `content` 不变。
- 发布预览响应结构不变，一致性问题进入既有 `repairIssues`；block-missing 模式下额外进入 `errors`。

### Frontend

- 发布门禁提取到 `publishPreviewHelper.js`，`WorkflowDetail.vue` 与 `WorkflowList.vue` 共用，消除两份近似重复实现。
- `repairable=false` 的问题在 warn 模式下以只读告警呈现，不再进入"修复元数据并重试"流程（该流程修不了这类问题）。
- block-missing 模式由 `canPublish=false` 拦截，无"继续发布"出口。

## Tradeoffs

- **默认 warn 而非直接阻断**：存量数据里 `bindTaskRelations` 旁路与删表都可能留下不一致，直接阻断会让一批今天能发布的工作流突然发不了。先只读扫描，审计后再切换。
- **unmatched / ambiguous 只告警**：解析基于 JSQLParser AST 加正则兜底，对方言 SQL 存在误判空间；且未登记的外部表、临时表天然 unmatched。若阻断保存，用户会陷入"存不进、发不了、导不出"的死循环。
- **与导入路径的故意不一致**：`WorkflowDefinitionLifecycleService` 导入时把 unmatched / ambiguous 当作硬 error。导入是新建对象，拦截无存量代价；发布面对的是存量，拦截有回归风险。该不对称是有意为之，不应以"统一行为"为由改成一致。
- **校验留在 `DataTaskService` 而非 Agent 层**：合并逻辑只有一份实现，代价是 `DataTaskService` 多一个 `TaskLineageConsistencyChecker` 依赖。依赖方向已验证无环。
- **deploy 复检增加 SQL 解析开销**：`publish()` 处于事务内，大工作流会明显变重。当前接受该代价，换取"绕过 preview 也拦得住"。若成为瓶颈，后续可按 `previewToken` 缓存 preview 结论复用。
