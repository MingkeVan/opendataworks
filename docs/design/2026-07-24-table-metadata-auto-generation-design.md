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

不在本次范围：参考产品中的「数据分类」tab（平台无对应存储模型）；服务端持久化生成建议。

> 2026-07-29 修订：枚举取值改为从真实数据查询（原先「不推导真实枚举」的范围限制已移除），并修复采纳写回时 Doris 报
> `Invalid column order. value should be after key.` 的问题。见文末「枚举取值必须来自真实数据」与「采纳写回的 Doris 列变更」。

## Solution

1. **复用问数发送消息端点发起任务**：前端组装上下文 prompt（DDL + 字段 + 上下游血缘 + 关联任务 SQL）→ 创建话题 → `tasks/deliver-message` 以 `background` 模式发起 → 每 2s 轮询任务状态至终态（上限 300s）→ 取助手消息。
2. **前端解析格式化内容**：约定模型只输出一个 JSON 代码块；前端容错提取（优先最后一个 ``` 围栏，回退首尾花括号）后严格解析，结构非法即报错，不做静默降级。该思路对齐 `dataagent/dataagent-backend/core/followup_suggestions.py`。
3. **枚举并入描述**：平台无独立枚举列，`formatFieldComment` 将 `enum_values` 折叠进字段描述（如「订单状态。枚举：0=待支付；1=已支付」）。取值本身来自真实数据实测，见「枚举取值必须来自真实数据」。
4. **加工逻辑**：prompt 要求在可从关联任务 SQL 推断时追加「加工逻辑：……」，点明来源表、分组维度与计算方式。
5. **复核与采纳**：「智能元数据」弹窗含「字段描述」「表名与表描述」两个 tab；字段 tab 提供全选、关键字搜索、「仅看描述为空/描述与名称相同的字段」过滤、可编辑的推荐描述与「已选 N 项」计数；点「采纳」对所选项直接调用既有写回接口并刷新字段列表。
6. **结果可见性**：生成后字段表出现只读「智能描述」列（含来源说明 tooltip 与「采纳描述」入口），已采纳字段显示「已采纳」；「字段定义」标题旁显示「元数据完善度 N%」，采纳后随字段刷新自动上升。

## Interfaces

复用，不新增：

- `GET /api/v1/dataagent/agents`（助手选项来源）
- `POST /api/v1/nl2sql/topics`
- `POST /api/v1/nl2sql/tasks/deliver-message`
- `GET /api/v1/nl2sql/tasks/{task_id}`
- `GET /api/v1/nl2sql/tasks/{task_id}/message`
- `PUT /api/v1/tables/{id}/comment`
- `PUT /api/v1/tables/{id}/fields/{fieldId}`

本次新增（Java 后端）：

- `GET /api/v1/tables/{id}/column-values?clusterId=` → `List<ColumnValueProfile>`（`@RequireAuth`，字段实测取值分布）
- `GET /api/v1/settings/agent` → `{ metadataAgentId }`
- `PUT /api/v1/settings/agent`（`@RequireAuth`），空值表示清除配置
- 新表 `sys_config`（`V47__create_sys_config.sql`）：平台级通用键值配置，后续其他全局设置可复用

#### agent_id 由设置显式配置，不能依赖默认助手

建话题时省略 `agent_id`，后端会回落到 `DEFAULT_AGENT_ID`（`agent_default`，由
`bootstrap_default_agent_profile` 保证存在）。但 `api_create_topic` 还会过 `_require_agent_profile`
→ `agent_visible_to`：门户来源不带 `X-ODW-Client: dataagent`，解析出的身份恒为 `None`；
一旦启用 auth 且该助手可见范围是 `authenticated` 或 `selected`，判定失败并返回
`400 agent not found`（该文案对"不存在"与"不可见"故意一致，防助手存在性探测）。

使用哪个助手由「配置管理 / 智能助手」显式配置，不在代码里隐式挑选（早期实现按
`agent_default` → 第一个 的顺序自动选取，行为不可预期且不可控）：

- 存储：新增平台级通用键值表 `sys_config`，键 `metadata.agent_id`。
  `GET/PUT /api/v1/settings/agent` 读写，部署级生效。
- 选项来源：`GET /api/v1/dataagent/agents`。其 `_catalog_identity` 对非 `dataagent`
  客户端同样返回 `None`，与建话题共用同一套可见性过滤，因此目录返回的即"建话题会被接受的助手"。
- 生成前校验：未配置时提示前往设置；已配置但不在当前目录中（助手被删除或可见范围收紧）时
  提示重新选择；目录接口本身失败则不阻断，仍用已配置的助手，把最终判定交给后端。

另外 `api/nl2sql.js` 导出 `nl2sqlErrorMessage`，从 FastAPI 的 `{"detail": ...}` 中取真实错误文案
——否则 axios 只会给出 `Request failed with status code 400`，掩盖真正原因。

前端新增模块：

- `frontend/src/api/nl2sql.js`：独立 axios 实例（裸 JSON，不走 Java 信封拦截器；不带 `X-ODW-Client` 头即 portal 匿名来源）
- `frontend/src/views/datastudio/metadataGeneration.js`：prompt 组装、JSON 解析、枚举合并、弱描述判定、完善度计算（纯函数）
- `frontend/src/views/datastudio/composables/useMetadataGeneration.js`：生成编排与采纳写回
- `frontend/src/views/datastudio/components/SmartMetadataDialog.vue`：复核弹窗

## 明细信息子页与分区列表

参考产品把表详情的「明细信息」拆为字段信息 / 分区信息 / 变更记录。本次落地形态：

- 原「列详情」tab 更名为「明细信息」，内部用 `el-radio-group` 提供「字段信息 / 分区信息」两个子页，选择结果记在 tab state 的 `metaDetailTab`，按 tab 记忆。
- **字段信息**：原有字段表（含「智能描述」列与元数据完善度）。
- **分区信息**：新增 `DataStudioRightPanelPartitions.vue`，含两块内容：
  - 分区与分桶配置（分区列、分桶列、分桶数、副本数、表模型、Key 列），取自已加载的 state
  - **分区列表**：异步按需请求新增接口，展示分区名、范围、大小、行数、分桶、副本与状态，客户端分页（默认每页 5，可选 5/10/15），并可手动刷新

#### 为什么不展示「分区字段」清单

分区信息只保留一份来源：`data_table.partition_column`（由 DDL 的 `PARTITION BY (...)` 经
`DorisCreateTableUtils.parsePartitionColumn` 解析并同步落库），直接作为「分区列」原样展示，
与同一块里的「分桶列」「Key 列」呈现方式一致。

不再按字段维度再列一遍分区字段：那既与「分区列」重复，也会引入不必要的列名解析与多源合并。
需要说明的是，`data_field.is_partition` 并不适合作为判定依据——它只有平台建表路径
（`TableCreateService`，且值本身就是从 `partitionColumn` 推导）会写入，`DorisMetadataSyncService`
同步时不回填，因此同步来的表恒为 0。

#### 子页切换性能

字段表行数较多时，用 `v-if` 在子页间切换会反复销毁/重建表格造成卡顿。因此改为：字段区常驻并用 `v-show`
切换；分区面板首次进入时才挂载，之后同样常驻 `v-show`。分区列表结果缓存在 tab state 的 `partitionList`
上（`null` 表示未加载过），子页来回切换不重复请求，仅「刷新」强制重取。
- **变更记录**：保留在顶层 tab，标签由「版本」改为「变更」，内容仍是既有 `TableVersionHistoryPanel.vue`（版本号、变更摘要、来源、操作人、时间、快照与版本对比），数据源 `data_table_version`。

### 分区列表接口（本次唯一的后端新增）

`GET /api/v1/tables/{id}/partitions?clusterId=`，返回 `List<TablePartitionInfo>`。

实现基于 Doris `SHOW PARTITIONS FROM \`db\`.\`table\``，沿用 `getTableDdl` 的同一条连接与库表解析路径
（`DataTableService#requireTableLocation` → `DorisConnectionService`）。

不同 Doris 版本 `SHOW PARTITIONS` 的返回列不一致，因此按结果集实际存在的列名读取（`ResultSetMetaData` 取列标签集合），
缺失列以 `null` 表达，非数值计数列也归一为 `null`，避免因版本差异整体失败。非分区表通常也会返回一行默认分区。

前端以 `skipErrorMessage` 静默调用，失败时在分区列表区域就地展示错误文案，不弹全局提示——
非 Doris 数据源等场景下打开该子页不会造成打扰。

## 枚举取值必须来自真实数据（2026-07-29）

初版 prompt 让模型「结合 DDL 与任务代码中的取值推导 `enum_values`」。DDL 与任务 SQL 里通常
根本没有取值线索，模型于是按字段名编出一套看似合理的枚举（`0=待支付；1=已支付；2=已完成`），
写进注释后比没有注释更有害——它看上去是权威元数据。

改为「先查数据，再让模型贴标签」，分三层：

1. **后端只读取值统计**：新增 `GET /api/v1/tables/{id}/column-values?clusterId=`，返回
   `List<ColumnValueProfile>`。候选列由字段类型与命名先筛
   （`DataTableQueryService#isEnumCandidate`：可分组类型，且命名不是 `*_id` / `*_no` 这类标识列，
   单表上限 20 列），逐列执行
   `SELECT col, COUNT(*) FROM db.tbl WHERE col IS NOT NULL GROUP BY col ORDER BY 2 DESC LIMIT n+1`。
   多取一行用于判定是否超过枚举上限（30）：超过即认为不是枚举列并整列丢弃，**不返回截断结果**，
   避免下游把不完整的取值集合当成完整枚举。取值统一 `getString` 读出，不做 `CAST`，因此 Doris 与
   MySQL 数据源同样适用；单列失败（权限、类型不可分组）只跳过该列。
2. **prompt 只给实测取值**：新增「# 字段实测取值」段，要求 `value` 逐字复制该清单，清单外字段一律返回空数组，
   模型只负责补 `label` 中文含义。
3. **写回前硬过滤**：`filterEnumValuesByObserved` 按实测取值集合过滤模型返回的 `enum_values`，
   清单里没有的取值直接丢弃；该字段没有实测取值（非候选列、取值发散、统计失败）时枚举整体清空。
   prompt 是要求，这一层才是约束——第 2 层失效时第 3 层仍然保证注释里不会出现编造的取值。

取值统计失败不阻断生成：前端 `skipErrorMessage` 静默调用，失败时按「无实测取值」处理，
本次生成不含枚举，字段业务含义照常产出（fail-closed，宁缺毋滥）。

## 采纳写回的 Doris 列变更（2026-07-29）

采纳字段描述会走 `PUT /v1/tables/{id}/fields/{fieldId}` → `DorisTableEngineHandler#updateColumn`。
原实现对非 AGGREGATE 表一律重建整列定义并执行 `ALTER TABLE ... MODIFY COLUMN <完整定义>`，
在 key 列上必然失败：

- `DorisConnectionService#buildColumnDefinition(field, isKey)` 声明了 `isKey` 却从未使用，
  生成的定义永远不带 `KEY` 标记。Doris 的 `MODIFY COLUMN` 按定义重建该列，缺少标记就把它当成 value 列，
  而 value 列排在 key 列之前触发 FE 校验 `Invalid column order. value should be after key. index[...]`。
- 前端 `buildFieldPayload` 把未设置的缺省值回填成空串，`mergeField` 后与库里的 `null` 不相等，
  于是「只改了注释」被判定成定义变更，本可以避免的 `MODIFY COLUMN` 也被触发
  （AGGREGATE 表上则表现为「AGGREGATE 表字段变更需指定聚合方式，暂不支持同步」）。

三处修正：

1. 只改注释时走轻量 `ALTER TABLE ... MODIFY COLUMN col COMMENT '...'`（与 AGGREGATE 分支一致），
   不重建列定义，也就不依赖平台侧 key 列元数据是否准确——采纳走的正是这条路径。
2. `buildColumnDefinition` 真正使用 `isKey`，在类型后补 `KEY`，让确需改类型/缺省值的 key 列也能改。
3. 两个引擎 handler 的 `normalize` 统一把空串视作 `null`，消除「空串 vs null」造成的假变更。

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
