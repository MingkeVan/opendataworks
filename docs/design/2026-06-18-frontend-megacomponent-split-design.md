# 前端巨型组件拆分设计（DataStudioNew.vue 优先）

- 日期: 2026-06-18
- 关联报告: `docs/reports/2026-06-16-main-full-code-review.md`（前端 4.3「巨型组件」发现）
- 关联计划: `docs/plans/2026-06-16-code-review-remediation-plan.md`（P2-2）；配套执行计划 `docs/plans/2026-06-18-frontend-megacomponent-split-plan.md`
- 影响栈: 前端（Vue 3 · Vite · Pinia · Element Plus）。不涉及后端、DataAgent、部署。
- 性质: 行为保持型重构（无功能变更、无路由/接口变更）

> 本文为设计文档，聚焦现状/问题/范围/方案/接口/权衡。可执行任务、文件清单、回滚见配套计划。

---

## 1. 现状

`frontend/src/views/datastudio/DataStudioNew.vue` 是前端最大的单文件组件：

| 指标 | 现值 |
|------|------|
| 总行数 | 6108 |
| template | 1146 行 |
| **script setup** | **3797 行** |
| style | 1160 行 |
| 响应式状态（ref/reactive/computed） | 47 |
| 顶层函数 | ~150 |
| `onMounted/watch/onBeforeUnmount` | 12 |
| 向子组件 `provide('dataStudioCtx', {...})` | ~30 个键 |

此前已拆出若干子组件：`DataStudioRightPanel`(1985)、`DataStudioRightPanelLineage`(876)、`CreateTableDrawer`(664)、`DataStudioResultGrid`(218)、`TableVersionHistoryPanel/CompareDialog` 等。但核心入口仍是巨无霸，主要复杂度在 script。

script 的函数按职责已天然聚成约 9 个簇：

| 簇 | 代表函数（行号区间） | 性质 |
|----|--------------------|------|
| 布局/分栏 | `getLayoutWidth`/`clampWidth`/`normalizePaneRatios`/`getLeftPaneStyle`/`syncResultPaneLayout`/`handleResize`（1213–1232, 1849, 4088, 4206） | 含 DOM 副作用 |
| Tab 持久化 | `persistTabsNow`/`schedulePersistTabs`/`restoreTabsFromStorage`（1337–1503） | localStorage + 可纯化序列化 |
| 目录树 | 数据源/schema/表 的 `load*`/`build*Node`/`refresh*InTree`/`filterCatalogNode`/`loadCatalogNode`（1277–2177, 2770–2834） | 最大一坨，含异步加载 |
| 格式化/判定 | `formatNumber`/`formatRowCount`/`formatStorageSize`/`formatDuration`/`formatDateTime`/`abbreviateSql`/`isDorisTable`/`isAggregateTable`（2204–2330） | **纯函数** |
| SQL 补全 | `loadCompletionTables`/`loadCompletionColumns`/`searchCompletionTables`/`getSqlCompletionContext`（3188–3268） | 异步 + 缓存 |
| 查询执行 | `splitSqlStatements`/`buildDefaultSql`/`parseAutoSelectSql`/`resetQuery`/`exportResult`/`fetchHistory`/结果集助手（2330–2500, 3279–3772） | 异步 + 部分纯逻辑 |
| 图表渲染 | `getNumericColumns`/`scoreColumnName`/`scoreDimensionColumn`/`scoreMetricColumn`/`applyDefaultChartSelection`/`renderChart`/`disposeChart`（3936–4206） | 评分/选列**纯**，渲染含 ECharts 副作用 |
| Tab/路由 | `openTableTab`/`syncRouteWithTab`/`syncFromRoute`/`loadTabData`/`handleTab*`（2663–3188） | 异步 + 路由副作用 |
| 元数据编辑 | `startMetaEdit`/`saveMetaEdit`/`startFieldsEdit`/`addField`/`removeField`（4213–4409+） | 异步写 |

## 2. 问题

- **单一职责严重破坏**：一个组件同时管理布局、目录树、Tab/路由、查询执行、图表、元数据编辑。
- **状态难追踪**：47 个响应式状态散落，跨簇共享；`provide('dataStudioCtx', {...})` 把 ~30 个键（含可变状态与方法）作为隐式契约暴露给子组件，子组件 `inject` 后强耦合，改动难评估。
- **不可测**：该组件无任何组件测试，前端整体覆盖极低；当前没有重构所需的回归保护。
- **认知与协作成本高**：6108 行单文件，定位与并行开发困难。

## 3. 范围

**本设计聚焦 `DataStudioNew.vue` 的内部职责拆分**，把逻辑按职责抽成 **composables（`useXxx()`）** 与 **纯工具**，组件本身收敛为「编排 + 模板」。

**明确不做（本设计之外）：**

- 不改变路由、对外行为、后端接口调用契约。
- 不为强行引入 Tailwind 而重写既有 Element Plus / Sass 模板（遵循仓库前端规则）。
- 不在本轮重写 `provide('dataStudioCtx')` 的对外形状（先稳定契约，后续单独切片收敛）。
- `WorkflowDetail.vue`(2792)、`TaskEditDrawer`（跨视图导入问题）作为同类技术债的后续设计，不在本文展开。

## 4. 方案

### 4.1 目标结构

```
src/views/datastudio/
  DataStudioNew.vue            （收敛为：模板 + 组合 composables + 顶层编排）
  composables/
    useResizablePanes.js       布局/分栏尺寸与持久化
    useTabPersistence.js       Tab 状态 localStorage 读写（复用 utils/safeJson）
    useCatalogTree.js          数据源/schema/表 目录树加载与刷新
    useStudioTabs.js           Tab 生命周期 + 路由同步
    useSqlCompletion.js        SQL 补全表/列缓存
    useQueryExecution.js       SQL 执行、结果集、历史、导出
    useResultChart.js          图表渲染（选列评分用纯工具）
    useTableMetaEditing.js     表/字段元数据编辑
  utils/ (或 datastudio 本地)
    tableFormat.js             纯格式化/判定（formatRowCount/StorageSize/...）
    chartColumnSelect.js       纯图表选列评分（score*/getNumericColumns/applyDefaultChartSelection）
    sqlStatements.js           纯 splitSqlStatements / 结果集判定
```

- **Vue 3 composables 而非再切子组件**：优先把逻辑抽成 `useXxx()`，避免大量 props/emit/`provide` 重接线带来的回归面；模板结构基本不动。
- **纯逻辑先行抽成可单测的工具**：`tableFormat`/`chartColumnSelect`/`sqlStatements` 是纯函数簇，抽出后配单测，作为该区域的回归网。

### 4.2 迁移策略（行为保持、增量、可回退）

1. **可验证切片优先**（复刻后端 T1 套路）：先抽**纯函数工具**（格式化、图表选列评分、SQL 拆分、Tab 序列化），逐个搬迁 + 配 Vitest 单测；组件改为引用，模板与响应式状态不动。
2. **再抽有状态 composables**：按簇逐个抽 `useXxx()`，每次只迁一簇，组件内以 `const { ... } = useXxx(deps)` 接回；保持 `provide('dataStudioCtx')` 暴露的键不变。
3. **每步独立提交、独立验证**，可单独回退。
4. **优先顺序（风险递增）**：纯工具（formatters/chart-select/sql-split/tab-serialize）→ `useResizablePanes` → `useTabPersistence` → `useSqlCompletion` → `useCatalogTree` → `useStudioTabs` → `useQueryExecution` → `useResultChart` → `useTableMetaEditing`。

### 4.3 验证约束（关键，决定推进节奏）

- 该组件**无组件测试**，前端覆盖极低 → 纯靠 `build`/`lint` 只能保证「能编过」，**catch 不到行为回归**。
- 因此：**纯工具切片**可凭单测安全推进（本轮主交付）；**有状态 composables** 切片在合入前需要至少一项行为级保证 —— 优先为关键路径补 `@vue/test-utils` 组件测试或一次本地手动冒烟（查询执行、目录树加载、Tab 切换、图表渲染）。
- 若某有状态切片暂无法补测试网，则在该切片说明里显式标注「仅 build/lint + 手动冒烟」，不谎称完整验证。

## 5. 接口

- **对外**：路由、页面行为、对后端 API 的调用契约**不变**。
- **对子组件**：`provide('dataStudioCtx', {...})` 暴露的键集合**本轮保持不变**（值改由 composable 提供，但键名/语义不变），子组件 `inject` 零改动。
- **composable 契约**：`useXxx(deps)` 接收所需依赖（如 ref、api、cfg），返回该簇的状态与方法；composable 之间不互相反向依赖，避免环。
- **纯工具**：无 Vue 依赖、无副作用，纯输入输出，便于单测。

### 5.0 契约变更记录（2026-07-17 起）

- `dataStudioCtx`（F13）：移除死键 `getLayerType`（其模板引用全部位于 F12 删除的死代码块内；`DataStudioRightPanelLineage` 一直直接 `import { getLayerType } from '../tableFormat'`）。契约从 32 键收敛为 31 键，其余键名/语义不变。
- 新增 `provide('dataStudioQueryCtx')`（F16a/F16b）：消费者为 `DataStudioResultPanel` 与 `DataStudioQueryPanel`，键集合 = `tabStates, dataSources, getSourceName, getSchemaOptions, handleQuerySourceSelect, handleQueryDatabaseSelect, executeQuery, stopQuery, resetQuery, saveAsTask, getSqlCompletionContext, handleSqlSelectionChange, getLiveDurationMs, getStatementStatusTagType, getDisplayResultSets, isResultSetType, getResultSetCountText, getResultSetAlertType, exportResult, getResultRowKeyPrefix, applyHistory, historyData, historyPager, historyLoading, getNumericColumns, setChartRef, canRenderChart`（由 `useQueryExecution`/`useResultChart`/`useSqlCompletion`/`useTableActions` 返回值与共享状态装配）。
- 新增 `provide('dataStudioCatalogCtx')`（F16c）：消费者为 `DataStudioCatalogNode`,键集合 = `dbLoading, schemaLoading, schemaCountLoading, tableLoading, setTableRef, getProgressWidth, getDatasourceIconUrl, isDatasourceIconInactive, isViewTable, getTableCount, getTableCountByType, getTableRowCount, getTableStorageSize, refreshDatasourceNode, refreshSchemaNode, isPlatformMetadataMissing, getUpstreamCount, getDownstreamCount`。
- CSS 作用域约束（F16c）：锚定在 `.catalog-tree` 上的悬停/选中态规则保留在 `DataStudioNew.vue` 的 `:deep()` 中（必须从 el-tree 所有者穿透）；节点内部元素样式随 `DataStudioCatalogNode` 迁移。

### 5.1 当前落地状态（2026-06-18）

- 已完成 F1–F10b。
- 已完成 F7 `useCatalogTree`：目录树状态、schema/table/column 缓存、懒加载、过滤、刷新和侧栏聚焦已从 `DataStudioNew.vue` 移入 `composables/useCatalogTree.js`；`schemaStore/tableStore/columnStore` 仍作为同一引用共享给 SQL 补全与路由同步。
- 已完成 F9 `useQueryExecution`：查询执行、停止、查询源/数据库选择、默认 SQL、结果集标准化、计时、历史分页/回填、CSV 导出已从 `DataStudioNew.vue` 移入 `composables/useQueryExecution.js`；查询执行后的默认图表选择和结果区布局同步通过回调接入。
- 已完成 F10a `useResultChart`：ECharts 实例、图表 DOM ref、默认选列、渲染、resize 同步与销毁已从 `DataStudioNew.vue` 移入 `composables/useResultChart.js`；纯选列评分继续复用 `chartColumnSelect.js`，`provide('dataStudioCtx')` 键集合保持不变。
- 已完成 F10b `useTableMetaEditing`：业务域/数据域加载、表元数据编辑、字段编辑草稿、取消回滚、字段增删改和保存刷新已从 `DataStudioNew.vue` 移入 `composables/useTableMetaEditing.js`；右侧面板 `inject` 的 `dataStudioCtx` 键集合保持不变。
- 当前 `DataStudioNew.vue` 已从 6108 行降至 3568 行；新增 `useQueryExecution.js` 729 行、`useResultChart.js` 252 行、`useTableMetaEditing.js` 382 行。

### 5.2 续篇落地状态（2026-07-17，F12–F17c）

- F12：删除主组件死模板块（旧右侧 meta 面板 HTML 注释，~499 行）与 46 个无法再匹配的孤儿样式规则块（逐选择器核对；动态类 `catalog-node--${type}` 与 `:deep()` 运行时类保留）。3553 → 2759 行。
- F13：`dataStudioCtx` 移除死键 `getLayerType`（见 §5.0）。
- F14 `useStudioTabs`：完成 F8 残留，Tab 生命周期（`createTabState/openTableTab/loadTabData/hydrateRestoredTableTabs/disposeTabResources/handleTab*` 等,~413 行）移入 `composables/useStudioTabs.js`,共享 ref 所有权仍在组件；配 13 个单测。
- F15 `useTableActions`：建表/删表/元数据同步、DDL/访问统计加载、任务与血缘跳转（~288 行）移入 `composables/useTableActions.js`；配 12 个单测。
- F16a `DataStudioResultPanel.vue`（533 行）：结果面板模板+样式随迁,经 `dataStudioQueryCtx` 接线,props 仅 `tab`。
- F16b `DataStudioQueryPanel.vue`（240 行）：查询工具栏+异步 SqlEditor 随迁,复用同一 `dataStudioQueryCtx`。
- F16c `DataStudioCatalogNode.vue`（403 行）：目录树节点模板+样式随迁,经 `dataStudioCatalogCtx` 接线,props 仅 `data`。
- F17a：右面板 7 个纯解析/格式化函数逐字迁入 `tableFormat.js`,补 6 个单测。
- F17b `usePanelVerticalResize`：右面板上下分栏拖拽与按 tab 记忆迁入 composable,配 5 个单测。
- F17c `TableTrendDialog.vue`（202 行）：趋势弹窗自包含化（ECharts 生命周期内聚,父组件 `ref.open(metric)` 触发）；同时补 `DataStudioRightPanel.smoke.spec.js` 挂载冒烟（此前该组件被 DataStudioNew 冒烟 stub,无任何运行时覆盖）。
- 行数结果：`DataStudioNew.vue` 3553 → **1217** 行；`DataStudioRightPanel.vue` 1985 → **1678** 行（余量主要是 basic/columns/access 三个 tab pane 与 ~800 行样式,见 §9 F17d）。
- 验证口径：每片 `nvm use` 后 lint（0 error）/test/build 全绿；有状态切片另做 **demo 模式 Playwright 浏览器冒烟**（`npm run dev:demo` + 预装 Chromium,无真实后端/MySQL,与 F9/F10 的真实后端冒烟不同级）：目录树懒加载展开、打开表 Tab、右面板 DDL/访问 tab、SqlEditor 输入并执行、Tab 关闭与刷新恢复、右面板分栏拖拽、趋势弹窗 canvas 渲染均通过；唯一 console error 为既有 `/dataagent/widget` 资源代理 500。

## 6. 权衡

- **收益**：组件职责清晰、状态可追踪、纯逻辑可测、降低并行开发冲突；为 `WorkflowDetail` 等后续大组件提供范式。
- **成本/风险**：纯结构重构无新功能；最大风险是响应式依赖/闭包捕获在抽 composable 时漂移。通过「纯工具先行 + 一次一簇 + 保持 provide 契约 + 关键路径冒烟」控制。
- **暂不做的更激进选择**：不引入 Pinia store 重建本页状态（当前为局部组件状态，迁 store 会扩大改动面与回归风险）；不重写 `dataStudioCtx` 形状；不强推 Tailwind。
- **替代方案对比**：
  - 「只切子组件」——需大量 props/emit/provide 重接线，回归面大，否决为首选。
  - 「一次性大重构」——不可控，否决。
  - 「composables 增量 + 纯工具先行可测」（本方案）——风险可控、可回退、部分可自动验证，采纳。

## 7. 验证

- 纯工具切片：Vitest 单测覆盖（格式化边界、图表选列评分、SQL 拆分、Tab 序列化往返）。
- 每步：`nvm use` 后 `npm run lint`（0 error）+ `npm run test` + `npm run build` 全绿。
- 有状态切片：补关键路径组件测试或本地手动冒烟（查询执行/目录树/Tab/图表），并在切片记录中如实标注验证范围。
- F9 本轮真实项目验证：Podman MySQL 8.0(`127.0.0.1:3306`)、Redis 7.2(`127.0.0.1:6379`) 与 DolphinScheduler 3.2(`127.0.0.1:12345`) 已运行；Spring Boot 后端以 `SPRING_DATASOURCE_*` 指向真实 MySQL 8 启动在 `127.0.0.1:18080/api`；Vite 前端以 `VITE_BACKEND_PROXY_TARGET=http://127.0.0.1:18080` 启动在 `127.0.0.1:3000`。Playwright 通过 DataStudio 新建查询，选择 `README 演示集群 / opendataworks`，执行 `SELECT 1 AS smoke_value;` 成功返回 1 行，CSV 导出文件内容为 `smoke_value=1`，历史查询出现记录并可回填。浏览器唯一 error 为既有 DataAgent widget 资源代理 500，与本切片无关。
- F10a 本轮真实项目验证：复用同一 Podman MySQL 8.0、Redis 7.2、DolphinScheduler 3.2；Spring Boot 后端启动在 `127.0.0.1:18080/api`，Vite 前端启动在 `127.0.0.1:3000`。Playwright 通过 DataStudio 新建查询，选择 `README 演示集群 / opendataworks`，执行 `SELECT '2026-06-01' AS dt, 10 AS pv UNION ALL SELECT '2026-06-02' AS dt, 25 AS pv UNION ALL SELECT '2026-06-03' AS dt, 18 AS pv;` 成功返回 3 行；切换到「图表」后默认 X 轴为 `dt`、Y 轴为 `pv`，柱状图 canvas 为 `423x230` 且采样 `127/651` 个非空像素，切换「折线图」后 canvas 仍保持有效尺寸。浏览器 error 仍为既有 DataAgent widget 资源代理 500；本轮 smoke 历史记录已从 `data_query_history` 清理。
- F10b 本轮真实项目验证：复用 Podman MySQL 8.0(`127.0.0.1:3306`)、Redis 7.2(`127.0.0.1:6379`) 与 DolphinScheduler 3.2(`127.0.0.1:12345`)；Spring Boot 后端启动在 `127.0.0.1:18080/api`，Vite 前端启动在 `127.0.0.1:3000`。Playwright 打开 `smoke_metadata_guidance.smoke_f10b_meta_edit`（平台 `data_table.id=1653`）后，在右侧「基本信息」编辑并保存表注释 `F10b smoke updated`、负责人 `codex-f10b`，MySQL `data_table` 校验通过；再切到「列详情」编辑 `smoke_value` 字段注释为 `value updated`，后端执行真实 `ALTER TABLE ... MODIFY COLUMN ... COMMENT`，MySQL `data_field` 与 `SHOW FULL COLUMNS` 均校验通过。字段路径首次暴露 smoke schema 缺少 ALTER 权限，补充专用 smoke schema `ALTER` 权限后重试通过；浏览器 error 仍为既有 DataAgent widget 资源代理 500。验证后删除本轮 smoke 平台元数据和物理表，复查剩余 0 条。

## 8. 回退

- 每个切片独立提交，问题时按提交回退。
- 纯工具与 composable 抽取均保持组件对外行为与 `provide` 契约不变，回退不影响子组件与路由。

## 9. 后续（同类技术债 roadmap，另行设计）

- `WorkflowDetail.vue`(2792)：同法 composables 化。
- ~~`TaskEditDrawer`：从 `views/` 迁入 `components/`~~（已完成）。
- ~~`DataStudioRightPanel.vue`(1985)：瘦身~~（F17a–c 已降至 1678,余量见下）。
- F17d（未做）：`DataStudioRightPanel.vue` 的 basic/columns/access 三个 tab pane 子组件化 + 样式随迁,是把该组件压到 <800 行的必经一步。
- `DataStudioNew.vue` 若要继续逼近 <800：候选是 PersistentTabs 内层 `tab-grid` 整体抽片与剩余 ~600 行布局样式的下沉,收益/回归比一般,暂不强推。
- `dataStudioCtx`（31 键、单消费者）可评估整体收敛为 props 或按域拆分（meta/fields/nav）,需与 F17d 一并设计。
