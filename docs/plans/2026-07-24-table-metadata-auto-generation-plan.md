# Table Metadata Auto-Generation Plan

**Date:** 2026-07-24
**Design:** docs/design/2026-07-24-table-metadata-auto-generation-design.md

## Tasks

1. 新增 `frontend/src/api/nl2sql.js`：智能问数任务链路客户端（独立 axios 实例，返回裸 JSON；`createTopic` / `deliverMessage` / `getTask` / `getTaskMessage`）。
2. 新增 `frontend/src/views/datastudio/metadataGeneration.js`：`buildMetadataPrompt`（内嵌 DDL/字段/血缘/任务 SQL，要求加工逻辑与单一 JSON 代码块）、`extractJsonBlock`、`parseMetadataResponse`、`formatFieldComment`、`isWeakDescription`、`computeMetadataCompleteness`。
3. 新增 `frontend/src/views/datastudio/composables/useMetadataGeneration.js`：
   - 生成：确保 DDL → 采集关联任务 SQL（上限 5 个）→ 建话题 → `deliver-message`（background）→ 轮询至终态 → 解析 → 写入 `metadataResult` 与 `state.metaSuggestion`
   - 采纳：`tableApi.updateComment` + 循环 `tableApi.updateField`（`buildFieldPayload` 仅改注释）→ 刷新字段
   - 支持 `generateMetadata(tabId, { force: true })` 重新生成
4. 新增 `frontend/src/views/datastudio/components/SmartMetadataDialog.vue`：两个 tab（字段描述 / 表名与表描述）、全选、搜索、弱描述过滤、可编辑推荐描述、已选计数、重新生成 / 取消 / 采纳。
5. 改 `frontend/src/views/datastudio/DataStudioNew.vue`：引入 `taskApi` 与 `useMetadataGeneration`，在 `useTableActions` 之后实例化（依赖 `loadDdl`），并把 6 个键并入 `provide('dataStudioCtx')`。
6. 改 `frontend/src/views/datastudio/components/DataStudioRightPanel.vue`：挂载 `<SmartMetadataDialog />`。
7. 改 `frontend/src/views/datastudio/components/DataStudioRightPanelColumns.vue`：新增「智能元数据 / 元数据生成中」按钮、「元数据完善度 N%」标签，以及内联「智能描述」列（来源 tooltip、「采纳描述」入口、「已采纳」态）。
8. 新增单测 `frontend/src/views/datastudio/__tests__/metadataGeneration.spec.js`。

`frontend/src/api/table.js` 无需改动：`updateComment` / `updateField` / `getFields` 均已存在。

### 明细信息子页与分区列表

后端（分区列表接口）：

9. 新增 DTO `backend/.../dto/TablePartitionInfo.java`。
10. `DorisConnectionService` 增加 `listPartitions(clusterId, database, tableName)`：执行 `SHOW PARTITIONS`，按结果集实际列名容错读取（缺失列与非数值计数归一为 null）。
11. `DataTableQueryService` 增加 `listPartitions(id, clusterId)`，沿用 `requireTableLocation` 解析库表。
12. `DataTableController` 增加 `GET /{id}/partitions`（`@RequireAuth`）。
13. 新增 `DorisConnectionServicePartitionsTest`：覆盖正常解析、缺列容错与非数值计数归一（独立测试类，避免与既有 `DorisConnectionServiceTest` 的严格 stub 冲突）。

前端：

14. `api/table.js` 增加 `listPartitions(id, clusterId, options)`，支持透传 `skipErrorMessage`。
15. 新增 `components/DataStudioRightPanelPartitions.vue`：分区/分桶配置 + 分区字段清单 + 分区列表（按需加载、可刷新、失败就地提示）。
16. 改 `DataStudioRightPanelColumns.vue`：顶部加「字段信息 / 分区信息」子页切换。
17. 改 `DataStudioRightPanel.vue`：「列详情」更名为「明细信息」；顶层「版本」tab 标签改为「变更」（内容不变）。
18. 改 `useStudioTabs.js`：`createTabState` 增加 `metaDetailTab: 'fields'`（与 `metaSuggestion: null` 一并声明）。
19. 补 `DataStudioRightPanel.smoke.spec.js`：补齐 ctx 中的智能元数据键；新增子页切换、分区字段拆分、分区列表渲染与请求失败就地提示四条用例。

分区体验修正：

20. 新增 `frontend/src/views/datastudio/partitionInfo.js`：`paginate` 纯函数（分页切片与越界回落）。
21. 改 `DataStudioRightPanelPartitions.vue`：移除与「分区列」重复的分区字段清单及计数，分区信息只保留 `partition_column` 一份来源；分区列表改为客户端分页（默认 5，可选 5/10/15）；结果缓存到 `state.partitionList`，仅「刷新」强制重取。
22. 改 `DataStudioRightPanelColumns.vue`：子页改用 `v-show` + 分区面板懒挂载，消除切换卡顿。
23. 改 `useStudioTabs.js`：`createTabState` 增加 `partitionList: null`。
24. 新增 `frontend/src/views/datastudio/__tests__/partitionInfo.spec.js`；smoke 测试补分区列表缓存不重复请求的用例。

修复 400 agent not found：

25. 改 `frontend/src/api/nl2sql.js`：`baseURL` 调整为 `/api/v1`，新增 `listAgents()`（`GET /dataagent/agents`）与 `nl2sqlErrorMessage()`（提取 FastAPI `detail`）。
26. 改 `useMetadataGeneration.js`：生成前先解析可见助手并把 `agent_id` 传给建话题与发消息；目录为空时给可操作提示；错误提示改用 `nl2sqlErrorMessage`；轮询改为先查一次再等待，任务立即失败时能马上反馈。
27. 新增 `__tests__/useMetadataGeneration.spec.js`、`__tests__/nl2sqlError.spec.js`。

助手改为设置项（部署级）：

28. 后端新增 `V47__create_sys_config.sql`（平台级通用键值表）、`SysConfig` 实体、`SysConfigMapper`、`SysConfigService`（按 key upsert）。
29. 后端新增 `AgentSettingsController`：`GET/PUT /v1/settings/agent`，键 `metadata.agent_id`，写接口带 `@RequireAuth`。
30. 前端 `api/settings.js` 增加 `getAgentSettings` / `updateAgentSettings`。
31. 新增 `views/settings/AgentSettings.vue`：助手下拉（选项取自 `/dataagent/agents`）、保存/刷新，并对「未配置」「已保存助手不在清单中」「目录获取失败」分别给出提示。
32. `ConfigurationManagement.vue` 新增「智能助手」tab（`name=agent`，并入 `availableTabs`）。
33. `useMetadataGeneration.js` 改为读取配置的助手，移除隐式挑选；相应更新单测。

枚举取值改为实测 + 修复采纳报错（2026-07-29）：

34. 后端新增 DTO `dto/ColumnValueProfile.java`（字段名、类型、去重取值数、`value/count` 列表）。
35. `DorisConnectionService` 增加 `profileColumnValues(clusterId, database, tableName, columns, maxDistinct)`：
    逐列 `GROUP BY` 取真实取值，多取一行判定是否超过枚举上限（超过整列丢弃），单列失败只跳过该列。
36. `DataTableQueryService` 增加 `profileEnumColumns(id, clusterId)` 与候选列判定 `isEnumCandidate`
    （可分组类型 + 非标识列命名，单表上限 20 列，取值上限 30）。
37. `DataTableController` 增加 `GET /{id}/column-values`（`@RequireAuth`）。
38. 改 `DorisTableEngineHandler#updateColumn`：只改注释走 `modifyColumnComment`，不重建列定义。
39. 改 `DorisConnectionService#buildColumnDefinition`：`isKey` 生效，key 列定义补 `KEY` 标记。
40. 改 `DorisTableEngineHandler` / `MysqlTableEngineHandler` 的 `normalize`：空串等价 `null`，消除假变更。
41. 改 `frontend/src/api/table.js`：新增 `profileColumnValues(id, clusterId)`（`skipErrorMessage`，120s 超时）。
42. 改 `metadataGeneration.js`：prompt 新增「# 字段实测取值」段并改写枚举要求；新增
    `normalizeColumnValueProfiles` / `buildObservedValueIndex` / `filterEnumValuesByObserved`。
43. 改 `useMetadataGeneration.js`：生成前拉取实测取值并入 prompt，`buildResult` 按实测取值过滤枚举；
    取值获取失败时按「无实测取值」处理，本次不产出枚举。
44. 补测：`DorisTableEngineHandlerTest` 增 6 例（注释轻量路径、AGGREGATE 注释、空串缺省值、key/value 列 KEY 标记、无变更不发 DDL）；
    新增 `DataTableQueryServiceEnumColumnsTest`（标识列与不可分组类型不参与、宽表候选列封顶、无候选列不发查询、类型回填）；
    `metadataGeneration.spec.js` 增 5 例；`useMetadataGeneration.spec.js` 增 2 例（编造取值被丢弃、取不到实测取值则不产枚举）。

## Verification

- `npm --prefix frontend run test`（全量 35 文件 / 185 用例）
- `npm --prefix frontend run build`
- `npx eslint`（新增文件 0 error）
- `mvn -pl backend -am test -Dtest='DorisConnectionServicePartitionsTest,DorisConnectionServiceTest'`
- `mvn -pl backend test -Dtest='DataTableQueryServiceEnumColumnsTest,DorisTableEngineHandlerTest,MysqlTableEngineHandlerTest'`（2026-07-29 修订）
- 2026-07-29 修订未覆盖：真实 Doris 集群上的采纳写回与取值统计（本地无 Doris/MySQL 数据源，后端集成测试因缺 DB 连接整体报错，与本次改动无关）
- 可选端到端 smoke：Docker MySQL `127.0.0.1:3316` + Redis `127.0.0.1:6379` + `.venv-py313` 启动 `dataagent-backend`，且 `da_agent_settings` 配置了可用 provider；对缺注释表执行 生成 → 弹窗复核 → 采纳 → 校验注释写回与完善度上升。

## Rollout / Backout

- Rollout：合并后无需迁移，对缺少注释的表即时可用；未配置模型服务时按错误提示降级，不影响表详情其余功能。
- Backout：删除新增前端文件与单测，撤销 `DataStudioNew.vue`、`DataStudioRightPanel.vue`、`DataStudioRightPanelColumns.vue` 三处改动即可完全回退；后端仅新增只读分区接口与只读取值统计接口，可一并移除；无 schema 变更。列变更修复（任务 38-40）改的是既有写回路径行为，回退会让 key 列采纳重新报 `Invalid column order`。
