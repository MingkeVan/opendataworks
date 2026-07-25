# Table Metadata Auto-Generation Design

**Date:** 2026-07-24
**Goal:** 在表详情提供「智能元数据」：基于表 DDL、上下游血缘与关联任务代码，由 AI 推导字段描述（含加工逻辑与枚举值）与表描述，经弹窗复核后批量采纳写回。
**Tech Stack:** Vue 3 + Vite + Element Plus 前端；复用 DataAgent(FastAPI) 智能问数任务链路；Java 后端仅复用既有表/字段写回接口。

## Current State

- 表详情右侧面板打开 tab 时并行加载表信息、字段、血缘与关联任务（`useStudioTabs.js` `loadTabData`），DDL 由 `useTableActions.js` 的 `loadDdl` 懒加载至 `state.ddl`。
- 表级注释可经 `PUT /api/v1/tables/{id}/comment`（同步 Doris 与本地）修改；字段注释经 `PUT /api/v1/tables/{id}/fields/{fieldId}` 修改；前端 `tableApi.updateComment` / `tableApi.updateField` 已存在。
- 智能问数任务链路已具备：`POST /api/v1/nl2sql/topics`、`POST /api/v1/nl2sql/tasks/deliver-message`、`GET /api/v1/nl2sql/tasks/{id}`、`GET /api/v1/nl2sql/tasks/{id}/message`；助手消息以 `blocks`（`main_text`）或 `content` 承载文本。
- `data_field` 无枚举列；`data_table` 无「数据分类/密级」模型（仅 `table_comment`、`business_domain`、`data_domain`、`layer`）。
- 平台此前没有「元数据完善度」概念。
- 主应用没有智能问数的 HTTP 客户端；`@/utils/request` 按 Java `{code, data, message}` 信封解包，不适用于 FastAPI 裸 JSON。开发态 `vite.config.js` 已将 `/api/v1/nl2sql` 代理到 `:8900`。

## Problem

缺少业务注释的表语义缺失，影响检索、问数与血缘理解；人工逐字段补注释成本高。需要一个基于现有上下文的一键推导能力，并提供可复核、可批量采纳的交互。

## Scope

前端新增生成入口、生成编排、结果解析、「智能元数据」复核弹窗与采纳写回，以及字段表的内联「智能描述」列与元数据完善度指标。复用既有写回接口，不新增 DataAgent / Java 端点，不改数据库结构。

不在本次范围：参考产品中的「数据分类」tab（平台无对应存储模型）；服务端持久化生成建议；通过抽样真实数据推导枚举。

## Solution

1. **复用问数发送消息端点发起任务**：前端组装上下文 prompt（DDL + 字段 + 上下游血缘 + 关联任务 SQL）→ 创建话题 → `tasks/deliver-message` 以 `background` 模式发起 → 每 2s 轮询任务状态至终态（上限 300s）→ 取助手消息。
2. **前端解析格式化内容**：约定模型只输出一个 JSON 代码块；前端容错提取（优先最后一个 ``` 围栏，回退首尾花括号）后严格解析，结构非法即报错，不做静默降级。该思路对齐 `dataagent/dataagent-backend/core/followup_suggestions.py`。
3. **枚举并入描述**：平台无独立枚举列，`formatFieldComment` 将 `enum_values` 折叠进字段描述（如「订单状态。枚举：0=待支付；1=已支付」）。
4. **加工逻辑**：prompt 要求在可从关联任务 SQL 推断时追加「加工逻辑：……」，点明来源表、分组维度与计算方式。
5. **复核与采纳**：「智能元数据」弹窗含「字段描述」「表名与表描述」两个 tab；字段 tab 提供全选、关键字搜索、「仅看描述为空/描述与名称相同的字段」过滤、可编辑的推荐描述与「已选 N 项」计数；点「采纳」对所选项直接调用既有写回接口并刷新字段列表。
6. **结果可见性**：生成后字段表出现只读「智能描述」列（含来源说明 tooltip 与「采纳描述」入口），已采纳字段显示「已采纳」；「字段定义」标题旁显示「元数据完善度 N%」，采纳后随字段刷新自动上升。

## Interfaces

复用，不新增：

- `POST /api/v1/nl2sql/topics`
- `POST /api/v1/nl2sql/tasks/deliver-message`
- `GET /api/v1/nl2sql/tasks/{task_id}`
- `GET /api/v1/nl2sql/tasks/{task_id}/message`
- `PUT /api/v1/tables/{id}/comment`
- `PUT /api/v1/tables/{id}/fields/{fieldId}`

前端新增模块：

- `frontend/src/api/nl2sql.js`：独立 axios 实例（裸 JSON，不走 Java 信封拦截器；不带 `X-ODW-Client` 头即 portal 匿名来源）
- `frontend/src/views/datastudio/metadataGeneration.js`：prompt 组装、JSON 解析、枚举合并、弱描述判定、完善度计算（纯函数）
- `frontend/src/views/datastudio/composables/useMetadataGeneration.js`：生成编排与采纳写回
- `frontend/src/views/datastudio/components/SmartMetadataDialog.vue`：复核弹窗

## 明细信息子页（分区信息 / 变更记录）

参考产品把表详情的「明细信息」拆为「字段信息 / 分区信息 / 变更记录」三个子页。本次一并对齐：

- 原「列详情」tab 更名为「明细信息」，内部用 `el-radio-group` 提供三个子页，选择结果记在 tab state 的 `metaDetailTab`，按 tab 记忆。
- **字段信息**：原有字段表（含本次新增的「智能描述」列与元数据完善度）。
- **分区信息**：新增 `DataStudioRightPanelPartitions.vue`。展示分区列、分桶列、分桶数、副本数、表模型、Key 列，以及按 `data_field.is_partition` 过滤出的分区字段清单与「分区字段 N · 非分区字段 M」计数。数据全部来自表详情已加载的 state，不新增请求。
  - 说明：平台当前没有列举 Doris 实际分区实例（分区名、范围、行数）的接口，`DorisConnectionService` 仅统计 `information_schema.partitions` 的数量。因此本子页呈现的是分区与分桶**配置**及分区字段，而非分区实例列表；后者需要新增后端接口，留作后续。
- **变更记录**：复用既有 `TableVersionHistoryPanel.vue`（版本号、变更摘要、来源、操作人、时间、快照与版本对比），数据源为 `data_table_version`。
  - 该组件原挂在独立的顶层「版本」tab；迁入「变更记录」子页后，顶层「版本」tab 一并移除，避免同一面板在两处重复出现。功能与接口未变。

## Risks and Tradeoffs

- 依赖 `da_agent_settings` 已配置可用 provider/model；未配置时生成失败并提示，不影响表详情其余功能。
- 模型输出格式不稳定：靠容错提取 + 严格解析兜底；解析失败直接报错，避免把错误内容写库。
- 走完整 agent 任务链路比直连模型慢；采用后台任务 + 轮询（300s 上限），换取零后端改动与消息可审计持久化。
- 采纳对多个字段循环调用既有写回接口，非事务；失败即提示，已成功部分经字段刷新反映，用户可重试。
- 生成建议为内存态：`useTabPersistence` 只持久化 tab 骨架（id/kind/表名/sql/limit），刷新页面后需重新生成，弹窗内提供「重新生成」。服务端持久化需新增表，超出本次不改 schema 的约束。

## Verification

- 前端单测覆盖 prompt 组装、JSON 提取与解析、枚举合并、弱描述判定与完善度计算。
- 前端全量测试与生产构建。
- 具备本地环境时补一次端到端 smoke（生成 → 弹窗 → 采纳 → 写回刷新）；否则显式说明未覆盖层。
