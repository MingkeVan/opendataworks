# DolphinScheduler 发布快照与版本治理设计

## 背景

- `create` 仅向 `data_lineage` 记录上下游血缘；`publish` 在内存中临时拼装 DolphinScheduler 所需的任务定义与依赖关系，并直接同步至 Python 网关（参见 `backend/src/main/java/com/onedata/portal/service/DataTaskService.java:184`）。
- 发布后缺乏持久化的工作流定义快照，无法追溯差异、生成审计记录或执行版本回滚。

## 问题与目标

- **不可追踪**：每次发布后的 definitions/relations 全部丢失，排查问题时必须重跑发布流程。
- **无法比较**：没有渠道判断本次发布与上次的节点或依赖变化。
- **缺乏治理**：无版本列表、无回滚能力、无法做留痕审计。

目标：发布时生成结构化快照存储到数据库，为版本对比、回滚和后续扩展提供基础数据，同时保持现有与 DolphinScheduler 的发布链路。

## 需求范围

必选能力：
1. 发布流程生成统一的工作流快照（包含工作流标识、任务定义、依赖关系、涉及任务集合等），以 JSON 形式落库。
2. 数据库存储版本信息，可快速比对最近一次发布是否发生变化。
3. 发布成功后记录快照的元数据，便于后续检索和审计。

建议能力：
- 保存结构化 diff 摘要（节点新增/删除、依赖变动），作为留痕数据。
- 记录发布人、触发来源、参与的任务 ID 以及血缘摘要。

后续可扩展能力：
- 版本列表、版本详情、差异对比 API/前端页面。
- 基于快照内容的版本回滚。

## 主要用例

- **首次发布**：生成版本 v1 快照 → 写库 → 上传至 DolphinScheduler → 标记为当前版本。
- **增量发布**：生成 v2 快照 → 与 v1 做 diff → 上传 → 标记 v2 为当前版本。
- **回滚（扩展）**：选定历史版本 v1 → 直接复用 v1 JSON 调用发布 → 新增 v3 快照并标记 `rollback_of = v1`。

## 数据模型设计

新增表 `workflow_definition_snapshot`（示例字段）：

| 字段 | 说明 |
| ---- | ---- |
| `id` | 主键 |
| `workflow_key` | 工作流标识（如项目名 + 引擎） |
| `workflow_code` | DolphinScheduler workflow code |
| `version` | 在 `workflow_key` 下递增的版本号 |
| `publish_task_ids` | JSON 数组，记录参与发布的 `data_task.id` |
| `definitions_json` | 任务定义 JSON |
| `relations_json` | 任务依赖 JSON |
| `task_metadata_json` | 可选：与业务相关的任务元数据（如参与任务 ID、编码映射） |
| `lineage_digest` | 可选：血缘摘要哈希，便于检测血缘变化 |
| `content_hash` | SHA256（或类似）哈希，用于快速判断是否有变更 |
| `change_summary` | 可选：结构化 diff 摘要 |
| `status` | `draft` / `published` / `failed` |
| `created_by` | 发布人 |
| `created_at` | 创建时间 |
| `rollback_of` | 可选：回滚来源版本号 |

索引建议：
- `(workflow_key, version)`：获取最新版本。
- `(workflow_code)`：按 Dolphin workflow 查询。
- `(content_hash)`：判断重复发布。

字段类型建议 `MEDIUMTEXT` 或 `JSON`（取决于 MySQL 版本）。若 JSON 体积过大，可考虑压缩或拆分表。

## 服务改造方案

### 快照生成

1. 在 `DataTaskService.publish` 中新增 `buildWorkflowSnapshot()`，将当前 workflow 的 definitions、relations 及必要的任务元数据按固定顺序组装。
2. 统一使用 `ObjectMapper` 或 `Jackson` 序列化成规范化 JSON（字段排序一致）。
3. 计算 `content_hash` 与 `lineage_digest`（血缘可通过 `data_lineage` 查询结果排序后哈希）。

### 快照入库

- `publish` 调用 `WorkflowVersionService.createSnapshot(...)`，写入 `workflow_definition_snapshot`。
- 保持与 `data_task` 状态更新在同一事务；写入初始状态为 `draft`。
- 若 DolphinScheduler 同步成功，将快照状态更新为 `published`，并记录 `workflow_code`、`version` 等信息；同步失败则标记为 `failed`，记录错误信息（可扩展字段）。

### 版本对比（建议实现）

- 读取上一条 `status = 'published'` 的快照，通过 `content_hash` 先做快速比对。
- 若哈希不同，可在后台执行 JSON diff，生成 `change_summary`（节点新增/删除/修改、关系变动等）。
- 在发布页面展示 diff 结果，帮助用户确认变动。

### 回滚流程（扩展）

1. 选择目标快照。
2. 读取 `definitions_json` 与 `relations_json`，直接调用现有 `dolphinSchedulerService.syncWorkflow()` 发布。
3. 新建一个快照记录，`rollback_of` 指向目标版本，`change_summary` 标记为回滚操作。
4. 更新 `data_task` 状态及 `dolphin_process_code`。

## API 与前端建议

后端新增接口（可分阶段落地）：

1. `GET /workflows/{key}/versions`：分页列出快照版本。
2. `GET /workflows/{key}/versions/{version}`：查看快照详情及 diff 内容。
3. `POST /workflows/{key}/versions/{version}/rollback`：触发回滚（可带审批逻辑）。

前端页面增强：
- 在任务发布页面展示最新 diff 概要。
- 提供版本列表页面，可筛选 workflow、版本、状态。
- 版本详情展示 JSON 片段、参与任务、血缘摘要。
- 回滚按钮（必要时结合权限控制）。

## 发布流程变更对比

| 流程阶段 | 当前实现 | 新方案 |
| -------- | -------- | ------ |
| 构建数据 | 临时 Map | 标准化 JSON（definitions + relations）+ 元数据 |
| 持久化 | 无 | `workflow_definition_snapshot` |
| 与 DS 同步 | 直接调用 `syncWorkflow` | 先写快照再同步，成功后更新状态 |
| 审计 | 不可用 | 可追踪版本、发布人、变更内容 |
| 回滚 | 不支持 | 可复用快照实现 |


## 迁移策略

- 上线前对现有 Dolphin 任务进行一次 baseline 快照：读取当前数据库任务与血缘，生成快照并写入，但不触发 `syncWorkflow`，并在 `change_summary` 标记为 `legacy baseline`。
- 若历史信息无法完整还原，可将 baseline 版本作为 v1，后续版本从 v2 开始。

## 风险与缓解

- **JSON 体积**：工作流节点多时 JSON 较大。需确认数据库字段长度，必要时对 `definitions_json` 做 gzip 压缩或拆表。
- **并发发布**：多个发布同时写同一 workflow 时可能出现版本冲突。可在 `WorkflowVersionService` 内对 `(workflow_key)` 加行锁或使用乐观锁。
- **同步失败补偿**：快照已写库但 `syncWorkflow` 失败，需要前端提示并允许复用该快照重试。
- **血缘一致性**：回滚时需确保 `data_lineage` 与快照一致；回滚前提示业务复核或自动校验。
- **维护成本**：diff 逻辑复杂，可分阶段实现；第一阶段仅依赖 hash 判变更。

## 验证与测试

- 新增集成测试覆盖：快照生成、重复发布去重、发布失败状态更新、回滚流程等。
- 更新 `WorkflowLifecycleFullTest`，断言每次发布后生成一条快照记录并携带正确的 `data_task` ID 列表。
- 设计回滚测试：创建三个任务→发布→修改→发布→回滚→确认 Dolphin workflow 与快照一致。

## 实施建议

1. 评审数据库建表及 JSON 规范，确认字段类型和索引策略。
2. 实现 `WorkflowVersionService` 与快照写入逻辑，并调整 `publish` 流程。
3. 逐步补充 diff、版本 API 与前端展示。
4. 准备历史数据 baseline 脚本与回滚验证脚本。
