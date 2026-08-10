# Workflow Import Runtime Binding Design

**Date:** 2026-08-04
**Goal:** 让工作流 JSON 导入不再无条件继承来源平台的 Dolphin 运行态编码，改由导入表单显式确定目标 Dolphin 环境与运行态关联关系。
**Tech Stack:** Java 8 + Spring Boot 2.7 backend, MyBatis-Plus + MySQL, Vue 3 + Element Plus frontend

## Scope

- 工作流 JSON 导入与 Dolphin 导入的预检、提交链路
- 导入弹窗表单结构与必填校验
- 导入后 `data_workflow` 运行态字段（`dolphin_config_id` / `project_code` / `workflow_code` / `dolphin_schedule_id`）的写入规则

不在本次范围：

- 工作流导出格式（保持现状）
- 工作流详情页的运行态改绑入口
- Dolphin 项目选择（Dolphin 配置侧已固定 `projectName`，不引入 per-workflow 项目）

## Current State

- `WorkflowDefinitionAssembler.sanitizeDefinitionJsonForExport` 只移除 `processDefinition.releaseState` 与 `status`。`code` / `workflowCode` / `projectCode`、`schedule.id`、`taskGroupId`、`xPlatformWorkflowMeta` 均随导出文件带出。
- `DolphinRuntimeDefinitionService.parseRuntimeDefinitionFromJson` 把这些编码原样解析回 `RuntimeWorkflowDefinition`。
- `WorkflowDefinitionLifecycleService.applyImportedWorkflowFields` 对 `sourceType=json` 无条件继承 `projectCode` / `workflowCode` / `dolphinScheduleId`，并置 `publishStatus="published"`。
- `WorkflowDefinitionLifecycleService.ensureWorkflowConflictAbsent` 在 `commit` 阶段按 `(project_code, workflow_code)` 查 `data_workflow`，命中即抛 `IllegalStateException("工作流已存在（…）")`。该检查不在 `preview` 阶段执行。
- `validateWorkflowNameConflict` 只对 `sourceType=dolphin` 生效，JSON 导入没有平台侧同名校验。
- 导入弹窗 `WorkflowImportDialog.vue` 中，「新工作流名称」输入只在 Dolphin 模式出现；两种模式都无法选择目标 Dolphin 环境。
- `data_workflow` 已有 `dolphin_config_id`、`project_code`、`workflow_code`、`dolphin_schedule_id` 字段，但没有任何接口允许用户直接设置它们。

## Problem

导出携带来源平台运行态身份，导入端无条件继承，导致三种失败模式：

- 目标平台已有工作流占用同一 `(projectCode, workflowCode)`：`commit` 抛 `工作流已存在`。预检显示"可导入"，错误只在点「确认导入」时出现，且没有任何出路。
- 目标平台指向另一个 Dolphin：导入成功但绑着来源集群的 `workflowCode`，`isFirstDeploy` 判为 `false`，发布走更新分支，DolphinScheduler 以同名定义拒绝，错误以 `API Error …` 透出。
- 来源与目标指向同一 Dolphin 集群同一项目且平台侧未占用：`WorkflowDeployService` 的 `checkWorkflowExists` 返回 `true`，发布时先把来源平台的生产工作流 `OFFLINE` 再覆盖，全程无提示。

共同根因是运行态归属在导入时没有被显式决定，而是由文件内容隐式决定。

## Design

把运行态归属变成导入表单上的显式输入，并且必填项在前端校验完成后才允许提交，不再依赖后端后置报错。

### 表单契约

两种导入模式共用：

- **目标 Dolphin 环境**：必填。选项取自 `GET /v1/settings/dolphin/configs`，未启用的配置禁选。默认选中 `isDefault` 的配置；只有一个启用配置时直接选中。选择框右侧常驻一个跳转 Dolphin 管理页的按钮；一条可用配置都没有时在该行以行内提示说明，不额外加横幅。
- **新工作流名称**：必填。默认取解析出的定义名称。
- **关联运行态工作流**：JSON 模式为可搜索、可清空的下拉；Dolphin 模式沿用现有表格选择。

JSON 模式下，前端从待导入 JSON 中解析 `processDefinition.code`，用单条探测接口在目标环境中查找并预选。清空该项即表示"不关联，按全新工作流导入"。

### 运行态归属规则

`linkedWorkflowCode` 是唯一决策输入，后端在事务内复核：

- **ADOPT**（选中了运行态工作流）：`projectCode` 取目标环境解析出的项目编码，`workflowCode` 取所选编码，`publishStatus="published"`，`dolphinConfigId` 取所选环境。`dolphinScheduleId` 与 `scheduleState` 从**被关联的目标运行态**解析，解析不到就留空 —— 导入文件里的调度 id 属于来源环境，沿用会让后续上下线、更新调度打到目标环境里另一条无关的 schedule 上。调度 cron、时区等属于定义内容，两种归属都保留。
- **RESET**（未选中）：`projectCode` 取目标环境项目编码，`workflowCode` / `dolphinScheduleId` / `scheduleState` 置空，`status="draft"`，`publishStatus="never"`，`dolphinConfigId` 取所选环境。调度 cron、时区、告警等属于定义内容，保留。

RESET 时定义 JSON 走 `WorkflowDefinitionAssembler.refreshRuntimeBindings`，复用既有的 `resetDefinitionRuntimeBinding` 与 `enrichMetadataFromCatalog`，清除运行态编码并按目标 Dolphin 目录重新解析 datasource 与 task group 编码。该原语此前只有 `WorkflowExecutionService.switchSchedulerEngine` 使用。

复核失败直接报错，不静默改判：

- 所选编码在目标项目中不存在
- 所选编码已被其它平台工作流关联（错误信息带出冲突工作流名称与 id，并给出"改为不关联导入"的出路）

### 项目编码解析

目标项目编码由所选 Dolphin 配置的 `projectName` 反查得到。所有只读路径都使用只读解析，项目不存在时返回失败，不自动创建项目 —— 现有 `DolphinSchedulerService.getProjectCode` 在项目缺失时会调用 `createProject`，仅仅打开导入弹窗不该在目标 Dolphin 里凭空建出一个项目。`DolphinRuntimeDefinitionService` 整体是运行态只读服务，其 `resolveProjectCode` 一并改为只读解析。

### 运行态占用判定的隔离与并发

运行态编码只在单个 Dolphin 环境内唯一，跨环境可能重号，因此占用判定按 `(project_code, workflow_code)` 查询后还要按 `dolphin_config_id` 过滤。`dolphin_config_id` 为空的存量行从宽判为占用：宁可多报一次冲突，也不能漏判后在发布时覆盖它的运行态。

提交阶段（ADOPT）的顺序是：

1. **在 `analyze()` 之前**取全局运行态绑定锁：`sys_config` 中 `workflow.runtime_binding.lock` 一行的排他行锁。
2. 在锁内跑完整个 `analyze()`：解析目标项目、查目标运行态、判定归属。
3. 复核目标 Dolphin 环境仍存在且启用。
4. 用加锁读查占用。

取锁必须早于第一次读库。事务的 `REPEATABLE READ` 快照由第一次读确立，锁取晚了，之后无论怎么复核，读到的都可能是取锁之前的旧快照 —— 加锁读能解决单条查询的新鲜度，却解决不了"复核逻辑整体基于旧快照"。锁先于 `analyze` 之后，归属判定所依据的环境配置与运行态都是当前数据；管理员若在预检之后改掉了环境身份（URL / 项目名），`analyze` 会按新配置重新解析项目与运行态，对不上就直接报错，因此不需要额外记录身份指纹。

第四步单独用不够。目标运行态还没被占用时，占用查询命中的是空结果，InnoDB 只能给出间隙锁；而间隙锁是"纯抑制性"的，可以被多个事务同时持有，两个并发导入会双双读到空。随后两边把 `workflow_code` 从 `NULL` 改成同一个值时，在 `REPEATABLE READ` 下互相等待对方的间隙锁而死锁（由 InnoDB 回滚其中一个，而不是让后者读到"已占用"），在 `READ COMMITTED` 下则因为检索通常不加间隙锁而双双绑定成功。锁一行真实存在的记录才是真正互斥，且不依赖隔离级别。

互斥点取全局一行而不是按 Dolphin 环境分别加锁：占用判定落在 `(project_code, workflow_code)` 上，不同环境完全可能出现相同的 project/workflow 编码，按环境加锁的两个事务会锁住不同的配置行，却对同一个索引间隙执行 `FOR UPDATE`，跨环境并发仍会死锁。绑定运行态是低频人工操作，全局串行更简单也更可靠。

该锁同时被 Dolphin 环境的修改与删除路径持有：两者都是"先统计绑定数量、再写"的读改写，不共用同一把锁的话，管理员可以在导入提交前读到"尚未绑定"，随后删除或改掉环境，留下指向已消失环境的工作流。同样出于快照的理由，这些路径也必须在第一次读库之前取锁 —— 其中 `updateConfig()` 尤其容易看漏：它先 `getDefaultConfig()` 再类内自调用 `update()`，自调用不会另起事务，等 `update()` 取到锁时快照早已定死。

第四步保留 `FOR UPDATE` 仍有必要：加锁读总是读最新已提交版本，绕开 `REPEATABLE READ` 的快照，否则同一事务里先前的一致性读会让复核看到过期数据。为此新增非唯一索引 `idx_data_workflow_runtime (project_code, workflow_code)`，否则该查询会退化成全表扫描并锁住整张表。

刻意不加唯一约束：`data_workflow` 是逻辑删除，被软删的行仍持有 `workflow_code`，唯一约束会让"删除工作流后重新关联同一运行态"直接失败；且存量数据是否已存在重复绑定无法在改动内验证，迁移失败会阻断部署。

不关联既有运行态（RESET）时没有可争抢的目标，不取该锁，避免无谓串行化。

### 初始版本快照

`createWorkflow` 在建出工作流的同时就生成初始版本快照，而运行态归属要等工作流有了 id 才能写入，快照因此会停留在"未绑定"状态。不修正的话，回滚到这一版会把发布状态和调度恢复错，甚至让下一次发布被误判为首次部署、在目标 Dolphin 里新建一条重复定义。

处理方式是两步：把 `dolphinConfigId` 提前放进 `WorkflowDefinitionRequest`（`createWorkflow` 用它归一化定义、解析 datasource 与 task group 编码，不传就会按默认环境的目录解析）；导入全部完成后，用最终定义就地重写这一版快照，而不是再追加一个版本，避免留下一个内容错误的初始版本。

### 名称冲突

`validateWorkflowNameConflict` 扩展到 JSON 导入。DolphinScheduler 本身会在发布时拒绝同名定义，把这个失败提前到预检更有价值，且表单已提供名称输入供当场修改。

## Interfaces / Data Model

复用 `data_workflow` 既有字段，仅新增一个非唯一索引 `V51__add_data_workflow_runtime_index.sql`：`idx_data_workflow_runtime (project_code, workflow_code)`，用于支撑提交阶段的加锁复核。

### 请求/响应

`WorkflowImportPreviewRequest` 与 `WorkflowImportCommitRequest` 新增：

- `Long dolphinConfigId`：目标 Dolphin 环境，必填
- `Long linkedWorkflowCode`：关联的运行态工作流编码，空表示不关联

`WorkflowImportPreviewResponse` 新增 `WorkflowImportRuntimeBinding runtimeBinding`：

```
decision             ADOPT | RESET
dolphinConfigId      目标环境 id
projectCode          目标项目编码
workflowCode         关联的运行态编码（RESET 时为空）
runtimeWorkflowName  运行态工作流名称
releaseState         运行态发布状态
scheduleId           目标运行态的调度 id（解析不到为空）
scheduleReleaseState 目标运行态的调度状态
conflictWorkflowId   占用该运行态的平台工作流 id
conflictWorkflowName 占用该运行态的平台工作流名称
message              面向用户的说明文案
```

`WorkflowImportCommitResponse` 新增 `String appliedRuntimeBinding`。

### HTTP

- `GET /v1/workflows/import/dolphin` 新增可选 `dolphinConfigId`
- `GET /v1/workflows/import/dolphin/{workflowCode}` 新增，参数 `dolphinConfigId`，返回单条 `DolphinRuntimeWorkflowOption` 或空

### 服务层

- `DolphinSchedulerService.findProjectCode(Long dolphinConfigId)`：只读解析项目编码，不创建项目
- `DolphinRuntimeDefinitionService.findRuntimeWorkflow(Long dolphinConfigId, Long projectCode, Long workflowCode)`：单条运行态查询，带平台占用信息
- Dolphin 来源导入读取定义时必须传入所选环境，走 `loadRuntimeDefinitionFromExport(dolphinConfigId, projectCode, workflowCode)` 重载，否则会从默认环境读到同编码的另一条工作流

## Risks / Alternatives

- JSON 导入新增同名校验会让此前可通过的重名导入变为预检失败。这是有意收紧，缓解措施是同一改动内为 JSON 模式提供名称输入。
- 打开弹窗即访问目标 Dolphin（解析项目、列出定义），Dolphin 不可达时下拉加载失败。`DolphinOpenApiClient` 已有 10s 超时，失败路径明确提示连接问题。
- 备选方案：导出时彻底剥离运行态编码。该方案最简单，但会永久失去"同集群导出再导入以保留运行态"的能力，且对已经导出的历史文件无效，因此不采纳。
- 备选方案：完全由后端自动判定归属，不给用户选择。自动判定在同集群多项目、同名不同 code 等场景下容易判错且无法纠偏，因此改为"自动预选 + 用户可覆盖"。
- 备选方案：新增 Dolphin 项目选择器。Dolphin 配置侧已固定 `projectName`，再引入 per-workflow 项目会与 `getProjectCode` 的既有解析路径冲突，复杂度不划算，因此不采纳。
- Dolphin 导入模式的绑定语义保持不变（`applyImportedWorkflowFields` 对该来源刻意不写 `workflowCode`），本轮只为其补充环境选择与表单校验。

## Verification

- 后端针对预检决策、复核失败、提交落库分支补充单测，其中 `ensureWorkflowConflictAbsent` 此前无任何用例覆盖
- 前端把 JSON 解析、payload 构建、运行态提示文案抽为纯函数并单测
- 环境可用时用两个 Dolphin 配置做手工端到端：关联导入、占用阻断、不关联导入、跨环境导入、无配置提示
- 前端预检是异步的，返回时表单可能已变。可提交状态绑定在预检时的表单指纹上（`buildPreviewSignature`），指纹不一致就要求重新预检，避免"以 RESET 预检、却按 ADOPT 提交"这类错配
- 并发绑定的互斥依赖真实数据库行为，单测只能钉住"先取全局锁、再解析归属与占用"的调用顺序。真实 MySQL 双事务并发验证仍需在有数据库的环境补做：两个会话同时提交关联同一运行态的导入，预期一方成功、另一方收到"已被平台工作流关联"，而不是死锁错误或双双成功
