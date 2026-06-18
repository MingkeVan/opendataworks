# 前端巨型组件拆分设计（DataStudioNew.vue 优先）

- 日期: 2026-06-18
- 关联报告: `docs/reports/2026-06-16-main-full-code-review.md`（前端 4.3「巨型组件」发现）
- 关联计划: `docs/plans/2026-06-16-code-review-remediation-plan.md`（P2-2）；配套执行计划 `docs/plans/2026-06-18-frontend-megacomponent-split-plan.md`（待产出）
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

## 8. 回退

- 每个切片独立提交，问题时按提交回退。
- 纯工具与 composable 抽取均保持组件对外行为与 `provide` 契约不变，回退不影响子组件与路由。

## 9. 后续（同类技术债 roadmap，另行设计）

- `WorkflowDetail.vue`(2792)：同法 composables 化。
- `TaskEditDrawer`：从 `views/` 迁入 `components/`，消除「视图导入视图」的跨视图耦合。
- `DataStudioRightPanel.vue`(1985)：随 `dataStudioCtx` 契约收敛一并瘦身。
