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

## Verification

- `npm --prefix frontend run test`（全量 32 文件 / 174 用例）
- `npm --prefix frontend run build`
- `npx eslint`（新增文件 0 error）
- `mvn -pl backend -am test -Dtest='DorisConnectionServicePartitionsTest,DorisConnectionServiceTest'`
- 可选端到端 smoke：Docker MySQL `127.0.0.1:3316` + Redis `127.0.0.1:6379` + `.venv-py313` 启动 `dataagent-backend`，且 `da_agent_settings` 配置了可用 provider；对缺注释表执行 生成 → 弹窗复核 → 采纳 → 校验注释写回与完善度上升。

## Rollout / Backout

- Rollout：合并后无需迁移，对缺少注释的表即时可用；未配置模型服务时按错误提示降级，不影响表详情其余功能。
- Backout：删除 4 个新增前端文件与单测，撤销 `DataStudioNew.vue`、`DataStudioRightPanel.vue`、`DataStudioRightPanelColumns.vue` 三处改动即可完全回退；无 schema 变更、无接口变更。
