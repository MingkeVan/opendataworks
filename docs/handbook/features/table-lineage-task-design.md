# 表管理血缘任务入口与任务创建功能改造需求设计

## 1. 背景与目标
- 表管理血缘区域现仅展示上下游表，缺少与相关任务的联动入口，跨页面操作成本高。
- 任务创建表单需手动输入数据源、任务编码、关联表等信息，易出错且难以在大规模表场景下使用。
- 调度配置需与 Dolphin 工作流统一维护，前端重复配置意义不大。
- 本次改造目标：在血缘区补充任务入口，优化任务创建表单体验（支持数据源下拉选择、任务编码、表搜索），移除无效字段，确保新建完成后可返回来源页。

## 2. 范围
- 前端页面：`表管理`（血缘区卡片、创建任务入口）、`任务创建/编辑` 表单。
- 后端服务：表任务关联接口扩展、任务创建接口兼容、Dolphin 数据源查询接口、新表查询接口。
- 不在范围：Dolphin 调度工作流改造、任务执行/发布逻辑变更、数据源管理后台。

## 3. 现状评估
- 血缘卡片仅有上下游表列表，缺少与写入/读取任务的双向导航。
- 表详情页已能展示关联任务，可复用数据结构（`writeTasks`/`readTasks`）。
- 任务表单需输入任务编码，且数据源字段是自由文本；输入/输出表使用一次性加载的多选下拉，不支持搜索。
- 后端若未提供任务编码会自动生成，但前端校验仍要求填写。
- `scheduleCron` 字段用于 Dolphin 定时，目前计划迁移到 Dolphin 工作流统一配置。

## 4. 新需求说明
### 4.1 血缘区任务入口
- 上游卡片展示**写入当前表的任务**，下游卡片展示**读取当前表的任务**。
- 每个任务显示名称、状态、最近执行信息，点击跳转任务详情/编辑。
- 新增“新增写入任务”“新增读取任务”操作按钮，进入任务创建页并带回来源 URL。
- 新建任务成功后返回原页面并刷新血缘 & 任务数据。

### 4.2 任务创建表单
- **任务编码**：改为后端自动生成。前端移除必填校验并只读展示。
- **数据源**：改为通过 Dolphin API 拉取可选项，支持过滤 DORIS 数据源，禁止手动输入。
- **输入/输出表**：下拉支持远程搜索（filterable + remote），默认不加载全量；新建入口可自动勾选来源表。
- **直达入口**：根据来源参数预设输入/输出表，支持用户手动调整。
- **调度配置**：移除“调度配置（CRON）”表单项及校验。
- **提交后跳转**：优先跳转回来源 URL（如来源于表管理血缘区），否则返回任务列表。

## 5. 功能设计
### 5.1 页面流程
1. 用户在表管理血缘区域查看上下游表及其关联任务。
2. 点击“新增写入任务”进入任务创建页，URL 携带 `?relation=write&tableId={tableId}&redirect={encodeURIComponent(currentUrl)}`。
3. 任务表单根据 `relation` 自动将当前表填入对应多选框并锁定默认提示（用户可取消）。
4. 用户配置任务名称、SQL 等信息，选择数据源、输入输出表（支持搜索）。
5. 提交后调用 `/v1/tasks` 接口；成功后跳回 `redirect`，刷新数据；取消时亦回跳。
6. 表管理页刷新 `getTasks`、`getLineage`，展示新任务。

### 5.2 数据结构与路由参数
- 新增前端常量用于记录 `redirect`、`relation`、`tableId`。
- 任务表单维护 `datasourceOptions`、`tableOptions` 状态；`tableOptions` 由 remote 查询组成。
- 前端在 `beforeRouteLeave` 不做额外处理，依赖 `redirect` 参数保证回跳。

## 6. 接口与后端改动
### 6.1 新增接口
1. `GET /v1/dolphin/datasources`
   - 功能：代理 DolphinScheduler API，返回可用数据源列表。
   - 参数：`type`（可选，默认过滤 DORIS）。
   - 响应字段：`[{id, name, type, dbName, description}]`。
   - 需增加调用 Dolphin REST 或 Python 服务逻辑，建议增加缓存（5-10 分钟）。

2. `GET /v1/tables/options`
   - 功能：用于远程搜索表；可复用现有 `tableApi.list` 的轻量版。
   - 参数：`keyword`（必填）、`limit`、`layer`、`dbName`。
   - 响应字段：`[{id, tableName, layer, dbName, tableComment}]`。
   - 需要限制最大返回数（默认 50），避免压力。

### 6.2 现有接口调整
- `/v1/tasks` 创建接口：允许 `taskCode` 为空，保持自动生成逻辑；若提供则校验唯一。
- `/v1/tables/{id}/tasks`：已包含写入/读取任务，前端直接复用，完善空数据处理。
- `/v1/tasks/{id}` 更新接口：去掉对 `taskCode` 的必填依赖，保持更新兼容。
- `DataTask` 实体无需立即移除 `scheduleCron` 字段，前端不再传值即可。

## 7. 前端实现要点
- 血缘卡片新增任务列表组件（复用表详情卡片样式），处理空态、加载态。
- 任务表单：
  - `datasourceName` 渲染为可搜索下拉（`el-select filterable remote`），触发 `taskApi.fetchDatasources`。
  - 输入/输出表多选在聚焦或输入时触发 API；增加防抖（建议 300ms）。
  - 校验规则更新：移除 `taskCode` 必填；数据源必选但需考虑接口异常时的报错提示。
  - 成功提交后调用 `router.push(redirect || '/tasks')`；取消同理。
- 处理来自血缘入口的预设值：加载表列表前，若 `relation=write` 则默认放入 `outputTableIds`，`relation=read` 则放入 `inputTableIds`。
- 更新任务列表展示（可复用 `taskStatusTag` 逻辑），确保状态、最近执行信息直观。

## 8. 数据库与模型影响
- 数据库结构保持不变；`data_task.task_code` 唯一约束仍有效。
- 若后续需要保存 `datasourceId`，需新增字段，目前暂不实施。
- `scheduleCron` 字段可为空，前端不再写入；后端逻辑保持兼容。

## 9. 测试计划
- **单页测试**
  - 血缘区任务列表显示、跳转、刷新是否正确。
  - 新建任务入口参数传递正确，上下游表预设符合预期。
  - 数据源下拉加载成功；接口失败提示友好。
  - 输入/输出表搜索：不同关键字、大小写、特殊字符、无结果场景。
  - 任务提交后是否返回来源页面。
- **集成测试**
  - 创建任务后调用 `/v1/tables/{id}/tasks` 确认写入/读取关系正确。
  - Dolphin 工作流发布流程确保 `scheduleCron` 为空不阻塞。
  - 回归任务列表、任务编辑场景（数据源列表、关联表回显）。
- **异常测试**
  - Dolphin 数据源 API 返回错误/超时的处理。
  - 远程表搜索延迟或无响应时的 loading/禁用状态。
  - 用户删除预设输入/输出表后的提交行为。


## 12. 附录
- 现有接口参考：`/v1/tables/{id}/lineage`、`/v1/tables/{id}/tasks`、`/v1/tasks`。
- 现有代码位置：
  - 前端：`frontend/src/views/tables/TableManagement.vue`、`frontend/src/views/tasks/TaskForm.vue`
  - 后端：`backend/src/main/java/com/onedata/portal/service/DataTaskService.java`、`DataTableService.java`
