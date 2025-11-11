# 工作流自动编排需求设计（多工作流与平台定义）

## 1. 背景与目标
- **背景**：历史上所有调度任务被聚合在单个 Dolphin 工作流中，平台侧缺少对工作流定义、版本与任务关系的管理，导致跨域耦合、下线风险、不可追溯等问题。
- **目标**：
  1. 平台侧支持多工作流治理，工作流定义持久化保存并具备全生命周期管理能力。
  2. 建立任务与工作流的强约束关系（任务必须且只能属于一个工作流），并可追踪上下游依赖变化对工作流的影响。
  3. 工作流上线、下线、发布与回滚流程可控，只有在“发布到 Dolphin”时才会影响 Dolphin/Dinky 侧对应工作流的状态。
  4. 提供面向工作流与任务的统一管理界面，支持列表、过滤、血缘查询、执行历史与跳转调度引擎。
  5. 兼容现有 `data_task`、`data_lineage`、`table_task_relation` 等元数据，最大化复用当前资产。

## 2. 能力范围与角色划分
| 角色 | 职责 | 说明 |
| --- | --- | --- |
| 平台（Portal + Backend） | 定义、存储、审批和版本化工作流；维护任务归属与依赖；触发发布 | 平台数据即真实来源，所有工作流变更必须先在平台生效。 |
| DolphinScheduler/Dinky | 负责实际调度执行 | 只接受平台发布的版本；平台下线但未发布不会影响 Dolphin 现状。 |
| 数据开发/运维 | 提交需求、审核变更、查看运行情况 | 通过平台界面完成所有日常操作。 |

## 3. 术语与数据模型映射
### 3.1 核心概念
| 概念 | 表/视图 | 说明 |
| --- | --- | --- |
| 工作流（Workflow） | `data_workflow` | 描述一个可调度的 DAG 定义及其平台状态。 |
| 工作流版本 | `workflow_version` | 保存每次结构快照、差异与来源，支持回滚与发布。 |
| 发布记录 | `workflow_publish_record`（新增） | 记录平台版本何时发布到 Dolphin，包含发布状态、目标环境、Operator。 |
| 工作流-任务关系 | `workflow_task_relation` | 任务归属以及节点属性；`task_id` 在表内唯一，强制一个任务只能在一个工作流中出现一次。 |
| 任务（Task） | `data_task` | 复用现有任务定义，扩展字段用于关联 `workflow_id` 与上下游统计。 |
| 依赖/血缘 | `data_lineage`、`table_task_relation` | 作为自动编排、差异检测与上下游统计的数据来源。 |

### 3.2 表结构（关键字段补充）
1. **`data_workflow`（新增）**
   | 字段 | 类型 | 说明 |
   | --- | --- | --- |
   | `id` | BIGINT PK | 工作流主键 |
   | `workflow_code` | VARCHAR(100) UNIQUE | 对外编码，对接 Dolphin/Dinky 定义 |
   | `workflow_name` | VARCHAR(200) | 展示名称 |
   | `status` | ENUM(`draft`,`ready`,`online`,`offline`,`archived`) | 平台状态；`online` 表示平台可调度，`offline` 表示停用但保留定义 |
   | `publish_status` | ENUM(`never`,`publishing`,`published`,`failed`) | 最近一次向 Dolphin 的发布状态 |
   | `current_version_id` | BIGINT | 指向最新 `workflow_version` |
   | `last_published_version_id` | BIGINT | 指向最近一次成功发布的版本 |
   | `definition_json` | JSON | 当前 DAG 的去结构化描述（节点、依赖、参数、资源） |
   | `entry_task_ids` / `exit_task_ids` | JSON | 入口/出口节点缓存，便于 UI 展示 |
   | `created_by`/`updated_by`/`timestamps` | | | 

   > **注**：跨工作流依赖字段 `external_dependencies` 暂未启用，相关信息通过版本快照、操作日志与 UI 提示记录，待后续需求落地再补充。

2. **`workflow_task_relation`（新增）**
   | 字段 | 类型 | 说明 |
   | --- | --- | --- |
   | `id` | BIGINT PK | |
   | `workflow_id` | BIGINT | 归属工作流 |
   | `task_id` | BIGINT UNIQUE | 任务 ID，唯一约束保证“一任务一工作流” |
   | `node_attrs` | JSON | 节点元信息（层级、负责人、运行参数、标签等） |
   | `upstream_task_count` / `downstream_task_count` | INT | 冗余存储，用于任务列表快速展示 |
   | `version_id` | BIGINT | 对应版本（方便回滚） |
   | `workflow_inheritance_mode` | ENUM | `auto`,`inherit_upstream`,`manual_select` |
   | `created_at` / `updated_at` | | |

3. **`workflow_version`（新增）**
   | 字段 | 类型 | 说明 |
   | --- | --- | --- |
   | `id` | BIGINT PK | |
   | `workflow_id` | BIGINT | |
   | `version_no` | INT | 自增版本号 |
   | `structure_snapshot` | JSON | 节点列表、依赖边、参数快照 |
   | `change_summary` | VARCHAR(1000) | 变更描述（来源任务/血缘/人工操作） |
   | `trigger_source` | ENUM(`task_change`,`lineage_update`,`manual`,`api`) | 触发来源 |
   | `created_by` / `created_at` | | |

4. **`workflow_publish_record`（新增）**
   | 字段 | 类型 | 说明 |
   | --- | --- | --- |
   | `id` | BIGINT PK | |
   | `workflow_id` | BIGINT | |
   | `version_id` | BIGINT | 发布的版本 |
   | `target_engine` | ENUM(`dolphin`,`dinky`) | 发布目标 |
   | `operation` | ENUM(`deploy`,`online`,`offline`) | 发布动作 |
   | `status` | ENUM(`pending`,`success`,`failed`) | 执行结果 |
   | `engine_workflow_code` | VARCHAR(100) | Dolphin 内部 workflow code |
   | `log` | JSON | 详细返回 |
   | `operator` / `created_at` | | |

5. **`task_dependency_view`（物化视图/缓存，推荐）**
   - 依据 `data_lineage`、`table_task_relation` 计算任务的上下游任务集合、数量及层级，用于 UI 检索与触发工作流更新。

## 4. 工作流全生命周期
### 4.1 定义与保存
1. 平台端根据事件（任务新增/修改、血缘更新）或人工操作构建候选 DAG。
2. 生成/更新 `data_workflow`，状态设为 `draft` 或 `ready`，并写入最新 `definition_json`。
3. 同步更新 `workflow_task_relation`：新增节点时要求选择/继承工作流；系统阻止一个任务被加入多个工作流。
4. 保存动作写入 `workflow_version`，记录触发来源与差异。

### 4.2 版本管理
- 每次 DAG 结构、任务参数或依赖发生变化，都生成新版本 `version_no = last + 1`。
- `structure_snapshot` 保存完整节点与边，确保离线比较、回滚与发布复现。
- 提供版本 diff（新增/删除节点、依赖）与审批记录，支撑发布可追溯。

### 4.3 上线与下线（平台态）
- **上线**：审批通过、状态从 `ready` 变为 `online`，平台允许触发工作流实例；此时 Dolphin 仍保持旧版本，直至执行“发布”。
- **下线**：将 `status` 置为 `offline`，禁止新实例生成，但 `definition_json` 与版本保留；若未发布到 Dolphin，则调度侧不受影响。
- **删除/归档**：当所有任务迁移或删除后，可将工作流标记为 `archived`，保留历史版本与发布记录。

### 4.4 发布到 Dolphin/Dinky
1. 仅在用户执行“发布”动作时，读取 `current_version_id` 并向 Dolphin 创建/更新对应工作流。
2. 发布过程包含：生成 Dolphin 定义 → 调用 Dolphin API 更新 → 同步上线/下线状态。
3. 成功后更新 `last_published_version_id`、`publish_status='published'`，并写入 `workflow_publish_record`。
4. 若平台侧下线但未执行发布，则 Dolphin 保持原有线上/下线状态；只有“发布下线”才会在 Dolphin 上真正下线。

## 5. 任务-工作流关系与依赖维护
### 5.1 任务唯一归属
- 在任务创建/编辑页必须选择一个工作流；若来源于自动编排，则默认继承推荐值。
- `workflow_task_relation` 对 `task_id` 添加唯一索引，数据库层面保证唯一性。
- 当任务被移动至其他工作流时，旧工作流自动生成新版本，并在版本 Diff / 操作日志中标记跨工作流影响（当前不维护专用字段）。

### 5.2 上下游依赖变更 → 工作流定义更新
1. 监听 `data_task`、`data_lineage`、`table_task_relation` 的变更，构建任务上下游影响范围。
2. 若依赖变更涉及两个不同工作流：
   - 在 `workflow_version` / `workflow_operation_log` 中记录 "inter-workflow edge"，并在 UI 与审批流程中提示（暂未启用 `external_dependencies` 字段）；
   - 通知相关责任人处置（如合并、声明依赖或调整触发策略），变更完成后生成新版本。
3. 若依赖变更在同一工作流内：直接更新 DAG，生成新版本并标记 `trigger_source='lineage_update'`。
4. 上游/下游数量缓存写回 `workflow_task_relation`，供 UI 快速展示。

### 5.3 任务迁移流程
1. 用户在工作流详情或任务详情发起“调整工作流”。
2. 系统提供三种策略：仅当前任务迁移、级联下游迁移、级联上下游迁移。
3. 迁移过程中校验：
   - 目标工作流是否允许新增节点（容量、领域、责任人）；
   - 跨工作流依赖是否会形成循环；
   - 是否需要同步迁移调度参数（资源、定时、失败策略）。
4. 迁移完成后新老工作流各写入一个版本，生成待发布事件。

### 5.4 校验与巡检
- 定期巡检 `workflow_task_relation` 中是否存在任务缺失、重复或孤立依赖，触发告警。
- 对 `manual_select` 任务进行专项巡检，确认跨工作流依赖描述是否补充完整。

## 6. 平台界面调整
### 6.1 工作流列表页
- 展示字段：工作流名称、编码、所属域、状态、最近一次发布版本、最近发布时间、入口/出口任务数。
- 支持按状态、责任组、领域、关键任务标签过滤，支持关键字模糊搜索。
- 在每行提供“跳转 Dolphin”链接，使用 `engine_workflow_code` 打开 Dolphin 对应工作流。
- 展示 Dolphin 历史执行状态：默认查询并缓存最近 10 次实例（含开始/结束时间、状态、触发方式）。
- 批量操作：上线、下线、发布、导出定义。

### 6.2 工作流详情页
- 包含 DAG 预览、版本列表、发布记录、任务清单（可批量打开任务详情）。
- 显示“平台状态 vs Dolphin 状态”比对，提示是否存在未发布的变更。
- 展示上下游跨工作流依赖拓扑，支持跳转到目标工作流详情。

### 6.3 任务列表页
- 支持按工作流、责任人、层级、标签过滤；默认展示所属工作流信息。
- 提供“根据上游任务查下游”、“根据下游任务查上游”两种查询模式，返回任务集合并可追加筛选条件。
- 每个任务展示 `upstream_task_count`、`downstream_task_count`、是否存在跨工作流依赖等指标。
- 列表支持一键跳转到任务详情、工作流详情、Dolphin 任务节点。
- 批量功能：变更工作流、刷新依赖、触发模拟运行。

## 7. 调度引擎交互与发布策略
1. 平台调用 Dolphin API 完成创建、更新、上线、下线，所有调用在 `workflow_publish_record` 中落库。
2. 发布策略：
   - `deploy`：首次发布，若 Dolphin 无对应 workflow，则创建后保持 `offline` 状态，待用户一并发布上线。
   - `online`：将 `last_published_version_id` 标记的定义上线；若 Dolphin 当前在线版本不同，则先下线再上线。
   - `offline`：仅在平台明确执行“发布下线”时调用 Dolphin 的 offline API。
3. 平台对比 `current_version_id` 与 `last_published_version_id`，提示“是否存在未发布变更”。
4. 失败重试：发布失败后 `publish_status='failed'`，保留错误日志，可在界面点击“重试发布”。

## 8. 系统接口与服务流程
### 8.1 API（示例）
| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/v1/workflows` | GET | 列表、过滤、包含执行历史统计 |
| `/api/v1/workflows` | POST | 新建/导入工作流定义 |
| `/api/v1/workflows/{id}` | PUT | 更新定义、上下游信息 |
| `/api/v1/workflows/{id}/publish` | POST | 发布到 Dolphin，body 指定 `operation=deploy/online/offline` |
| `/api/v1/workflows/{id}/versions` | GET | 查询版本列表与 diff |
| `/api/v1/workflows/{id}/tasks` | GET | 获取任务列表与上下游计数 |
| `/api/v1/tasks` | GET | 任务列表查询，支持 `workflowId`、`upstreamTaskId`、`downstreamTaskId` 参数 |
| `/api/v1/tasks/{id}/reassign` | POST | 调整任务所属工作流，含级联策略 |

### 8.2 编排服务流程
1. **事件监听器**：订阅 `data_task`、`data_lineage`、`table_task_relation` 的变更，形成待处理任务集合。
2. **拓扑构建器**：根据最新血缘生成 DAG；当任务跨工作流时，拆分为多个子图。
3. **规则引擎**：依据领域、责任人、任务数、执行窗口等规则决定是否拆分/合并工作流。
4. **定义管理器**：比对旧版 DAG，更新 `data_workflow`、`workflow_version`、`workflow_task_relation`。
5. **发布管理器**：根据用户/自动触发动作，执行 Dolphin 发布流程并写日志。
6. **UI/接口层**：为列表、详情、查询功能提供统一接口与缓存。

## 9. 权限与审批
- 平台沿用现有审批流，根据工作流责任团队、任务级别、变更类型决定审批链。
- 工作流上线、下线、发布、任务迁移等操作全部进入审批，并生成 `workflow_operation_log` 便于审计。
- 仅具备相应权限的用户才能执行跨工作流迁移或发布。

## 10. 监控、巡检与告警
- **巡检规则**：
  - 任务缺少工作流或出现重复归属；
  - 跨工作流依赖未在平台标记/确认；
  - 工作流长时间未发布但存在线上调度实例；
  - Dolphin/Dinky 中的工作流状态与平台不一致。
- **告警渠道**：IM/邮件/告警平台，告警内容附带工作流、任务、版本信息。
- **执行历史拉取**：定时任务同步 Dolphin 最近 10 次实例到平台缓存，供 UI 展示并触发异常告警。

## 11. 实施路径
1. **Phase 1 - 数据模型与 UI 基础**：落地 `data_workflow`、`workflow_task_relation`、列表/详情页、任务唯一归属校验。
2. **Phase 2 - 自动编排与版本管理**：实现事件监听、DAG 比对、版本生成、上下游计数、依赖触发更新。
3. **Phase 3 - 发布与执行历史**：打通 Dolphin 发布/上线/下线 API，展示最近 10 次执行记录，支持跳转。
4. **Phase 4 - 高级治理**：跨工作流依赖可视化、容量规则、自动合并/拆分建议、巡检闭环。

## 12. 风险与缓解
- **血缘不完整**：提供手工补录、巡检告警、通过任务编辑页补充上下游信息。
- **任务唯一归属导致迁移压力**：提供批量迁移和级联模拟，减少手工操作时间。
- **发布失败或与 Dolphin 不一致**：发布前做差异比对，失败可重试并支持回滚到最近已发布版本。
- **性能瓶颈**：拓扑构建采用增量计算与缓存，列表查询依赖物化视图/Redis，提高 UI 响应。

## 13. 方案补充（任务驱动模式）
- 当自动编排未完全覆盖或业务需要精细控制时，任务表单提供“继承上游工作流”“指定工作流”“新建工作流”选项。
- 任务上游/下游调整会立即触发工作流版本更新，并在审批通过后写入 Dolphin。
- 平台巡检每周扫描 `manual_select` 任务，确保跨工作流依赖被正确声明并提示责任人确认。
