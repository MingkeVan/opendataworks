# 前端巨型组件拆分执行计划（DataStudioNew.vue 优先）

- 日期: 2026-06-18
- 关联设计: `docs/design/2026-06-18-frontend-megacomponent-split-design.md`
- 关联报告: `docs/reports/2026-06-16-main-full-code-review.md`（前端 4.3）
- 影响栈: 前端（Vue 3 · Vite · Vitest）
- 原则: 行为保持、一次一簇、每步独立提交可回退、`provide('dataStudioCtx')` 契约不变

> 本文为执行计划，聚焦可执行任务、触及文件、验证、回滚。背景与方案见配套设计文档。

---

## 验证总约定

- 每步：`export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use` 后
  - `npm --prefix frontend run lint`（0 error）
  - `npm --prefix frontend run test`
  - `npm --prefix frontend run build`
- **纯工具切片**：必须配 Vitest 单测，作为回归网。
- **有状态切片**：合入前补关键路径组件测试或本地手动冒烟，并在提交说明如实标注验证范围（不谎称完整验证）。

## 任务分解（按风险递增顺序）

### F1 — 纯工具：表格格式化/判定（最低风险，先做）
- 抽出 `formatNumber`/`formatRowCount`/`formatStorageSize`/`formatDuration`/`formatDateTime`/`abbreviateSql`/`isDorisTable`/`isAggregateTable`/`isReplicaWarning` 等纯函数。
- 触及文件: 新增 `frontend/src/views/datastudio/tableFormat.js` + `__tests__/tableFormat.spec.js`；改 `DataStudioNew.vue` 引用。
- 验证: 单测覆盖边界（0/空/超大数值、单位换算、null 时间）；lint/test/build 全绿。
- 回退: 单提交回退。

### F2 — 纯工具：图表选列评分
- 抽出 `getNumericColumns`/`scoreColumnName`/`scoreDimensionColumn`/`scoreMetricColumn`/`applyDefaultChartSelection` 的**纯评分/选列**逻辑（渲染副作用 `renderChart`/`disposeChart` 暂留组件）。
- 触及文件: 新增 `frontend/src/views/datastudio/chartColumnSelect.js` + 测试；改 `DataStudioNew.vue`。
- 验证: 单测覆盖维度/指标列识别、默认选列；lint/test/build。
- 回退: 单提交回退。

### F3 — 纯工具：SQL 语句拆分与结果集判定
- 抽出 `splitSqlStatements`/`isResultSetType`/`getResultSetCountText`/`buildStatementInfosFromResultSets` 中的纯逻辑。
- 触及文件: 新增 `frontend/src/views/datastudio/sqlStatements.js` + 测试；改 `DataStudioNew.vue`。
- 验证: 单测覆盖多语句、注释、字符串内分号等边界；lint/test/build。
- 回退: 单提交回退。

### F4 — useTabPersistence（复用 utils/safeJson）
- 抽出 Tab 状态 localStorage 读写与序列化（`persistTabsNow`/`schedulePersistTabs`/`restoreTabsFromStorage`），复用已有 `utils/safeJson`。
- 触及文件: 新增 `frontend/src/views/datastudio/composables/useTabPersistence.js`（+ 可纯化的序列化部分配测试）；改 `DataStudioNew.vue`。
- 验证: 序列化往返单测 + 手动冒烟（刷新后 Tab 恢复、版本不符时丢弃）。
- 回退: 单提交回退。

### F5 — useResizablePanes
- 抽出布局/分栏尺寸计算与持久化（`getLayoutWidth`/`clamp*`/`normalizePaneRatios`/`getLeftPaneStyle`/`syncResultPaneLayout`/`handleResize`）。
- 验证: clamp/比例计算单测 + 手动冒烟（拖拽分栏、窗口 resize）。
- 回退: 单提交回退。

### F6 — useSqlCompletion
- 抽出补全表/列缓存（`loadCompletionTables`/`loadCompletionColumns`/`searchCompletionTables`/`getSqlCompletionContext`）。
- 验证: 手动冒烟（编辑器补全表名/列名）；lint/test/build。
- 回退: 单提交回退。

### F7 — useCatalogTree（最大一坨，已完成）
- 抽出数据源/schema/表 目录树加载、节点构建、刷新、过滤、懒加载（`load*`/`build*Node`/`refresh*InTree`/`loadCatalogNode`/`filterCatalogNode`）。
- 触及文件: 新增 `frontend/src/views/datastudio/composables/useCatalogTree.js`；改 `DataStudioNew.vue` 引用并保持 `schemaStore/tableStore/columnStore` 与 SQL 补全、路由同步共享。
- 验证: `DataStudioNew.smoke.spec.js` 挂载冒烟 + 真实前端页面手动/浏览器冒烟（展开数据源→schema→表、计数、刷新、过滤）+ lint/test/build。
- 回退: 单提交回退。

### F8 — useStudioTabs（Tab 生命周期 + 路由同步）
- 抽出 `openTableTab`/`syncRouteWithTab`/`syncFromRoute`/`loadTabData`/`handleTab*`。
- 验证: 手动冒烟（打开/关闭/切换 Tab、URL 同步、刷新恢复）。
- 回退: 单提交回退。

### F9 — useQueryExecution（已完成）
- 抽出 SQL 执行、结果集处理、历史、导出（`handleQuery*Select`/`buildDefaultSql`/`resetQuery`/`exportResult`/`fetchHistory`/`applyHistory`）。
- 触及文件: 新增 `frontend/src/views/datastudio/composables/useQueryExecution.js`；改 `DataStudioNew.vue` 接入 composable，保持模板、路由和 `provide('dataStudioCtx')` 契约不变。
- 验证: lint/test/build；真实项目浏览器冒烟（执行查询、导出、历史列表、历史回填）。
- F9 验证记录:
  - 本机 Podman 容器: MySQL 8.0 `127.0.0.1:3306`、Redis 7.2 `127.0.0.1:6379`、DolphinScheduler 3.2 `127.0.0.1:12345`。
  - 后端: `SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/opendataworks...`、`SPRING_DATASOURCE_USERNAME=opendataworks`、`SPRING_DATASOURCE_PASSWORD=opendataworks123`，Spring Boot 启动在 `127.0.0.1:18080/api`，Flyway 校验 46 个迁移且 schema 版本 45 无需迁移。
  - 前端: `nvm use` 后 `VITE_BACKEND_PROXY_TARGET=http://127.0.0.1:18080 npm --prefix frontend run dev -- --host 127.0.0.1 --port 3000`。
  - Playwright: 打开 `http://127.0.0.1:3000/datastudio`，新建查询，选择 `README 演示集群 / opendataworks`，执行 `SELECT 1 AS smoke_value;`，结果页显示 1 行、列 `smoke_value`、执行完成状态；点击导出生成 CSV，内容为 `smoke_value`/`1`；历史查询列表展示本次记录，点击「填入」无前端错误。
  - 清理: 删除本轮 `data_query_history` 中 `SELECT 1 AS smoke_value;` 且 `executed_at >= '2026-06-18 21:40:00'` 的 2 条烟测记录。
  - 已知非本切片问题: 浏览器 console 唯一 error 为 `/dataagent/widget/opendataworks-widget.bundle.js` 代理 500，与 DataStudio 查询执行抽取无关。
- 回退: 单提交回退。

### F10a — useResultChart（已完成）
- 抽出图表渲染（`renderChart`/`disposeChart`/`setChartRef`/`syncResultPaneLayout`/默认选列接线），保留 `chartColumnSelect.js` 作为纯评分工具。
- 触及文件: 新增 `frontend/src/views/datastudio/composables/useResultChart.js`；改 `DataStudioNew.vue` 接入 composable，保持模板与 `provide('dataStudioCtx')` 契约不变。
- 验证: lint/test/build；真实项目浏览器冒烟（查询返回结果、切换图表、默认选列、ECharts canvas 渲染、图表类型切换）。
- F10a 验证记录:
  - 静态验证: `nvm use` 后 `npm --prefix frontend run lint`（0 error，既有 259 warnings）、`npm --prefix frontend run test`（19 files / 87 tests passed，既有 malformed JSON/localStorage timeout stderr）、`npm --prefix frontend run build` 通过（既有 Sass legacy API 与 chunk size warning）。
  - 本机 Podman 容器: MySQL 8.0 `127.0.0.1:3306`、Redis 7.2 `127.0.0.1:6379`、DolphinScheduler 3.2 `127.0.0.1:12345`。
  - 后端: `SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/opendataworks...`、`SPRING_DATASOURCE_USERNAME=opendataworks`、`SPRING_DATASOURCE_PASSWORD=opendataworks123`，Spring Boot 启动在 `127.0.0.1:18080/api`，Flyway 校验 46 个迁移且 schema 版本 45 无需迁移。
  - 前端: `nvm use` 后 `VITE_BACKEND_PROXY_TARGET=http://127.0.0.1:18080 npm --prefix frontend run dev -- --host 127.0.0.1 --port 3000`。
  - Playwright: 打开 `http://127.0.0.1:3000/datastudio`，新建查询，选择 `README 演示集群 / opendataworks`，执行 `SELECT '2026-06-01' AS dt, 10 AS pv UNION ALL SELECT '2026-06-02' AS dt, 25 AS pv UNION ALL SELECT '2026-06-03' AS dt, 18 AS pv;`，结果页显示 3 行；切换「图表」后默认 X 轴为 `dt`、Y 轴为 `pv`，柱状图 canvas `423x230` 且采样 `127/651` 个非空像素；切换「折线图」后 canvas 仍存在且尺寸有效。
  - 清理: 删除本轮 `data_query_history` 中匹配 `SELECT '2026-06-01' AS dt, 10 AS pv%` 的 2 条烟测记录，复查剩余 0 条。
  - 已知非本切片问题: 浏览器 console error 为 `/dataagent/widget/opendataworks-widget.bundle.js` 代理 500；另有既有 Element Plus `small` deprecation 与 `TaskEditDrawer` 图标组件解析 warning，与图表抽取无关。
- 回退: 单提交回退。

### F10b — useTableMetaEditing（已完成）
- 抽出元数据编辑（`startMetaEdit`/`saveMetaEdit`）与字段编辑（`startFieldsEdit`/`addField`/`removeField`/`saveFieldsEdit`）。
- 触及文件: 新增 `frontend/src/views/datastudio/composables/useTableMetaEditing.js`；改 `DataStudioNew.vue` 接入 composable，保持右侧面板 `inject` 的 `dataStudioCtx` 契约不变。
- 验证: lint/test/build；真实项目浏览器冒烟（元数据编辑保存、字段编辑保存、右侧面板状态同步、MySQL 平台元数据与物理表字段注释校验）。
- F10b 验证记录:
  - 静态验证: `nvm use` 后 `npm --prefix frontend run lint`（0 error，既有 259 warnings）、`npm --prefix frontend run test`（19 files / 87 tests passed，既有 malformed JSON/localStorage timeout stderr）、`npm --prefix frontend run build` 通过（既有 Sass legacy API 与 chunk size warning）。
  - 本机 Podman 容器: MySQL 8.0 `127.0.0.1:3306`、Redis 7.2 `127.0.0.1:6379`、DolphinScheduler 3.2 `127.0.0.1:12345`。
  - 后端: `SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/opendataworks...`、`SPRING_DATASOURCE_USERNAME=opendataworks`、`SPRING_DATASOURCE_PASSWORD=opendataworks123`，Spring Boot 启动在 `127.0.0.1:18080/api`，Flyway 校验 46 个迁移且 schema 版本 45 无需迁移。
  - 前端: `nvm use` 后 `VITE_BACKEND_PROXY_TARGET=http://127.0.0.1:18080 npm --prefix frontend run dev -- --host 127.0.0.1 --port 3000`。
  - Playwright: 打开 `http://127.0.0.1:3000/datastudio?clusterId=1&database=smoke_metadata_guidance&tableName=smoke_f10b_meta_edit&tab=1::smoke_f10b_meta_edit&tableId=1653`；在「基本信息」编辑并保存表注释 `F10b smoke updated`、负责人 `codex-f10b`，MySQL `data_table` 校验通过；在「列详情」编辑 `smoke_value` 注释为 `value updated`，后端执行真实 `ALTER TABLE ... MODIFY COLUMN ... COMMENT`，MySQL `data_field` 和 `SHOW FULL COLUMNS` 均校验通过。
  - 环境处理: 字段路径首次因专用 smoke schema 缺少 `ALTER` 权限被 MySQL 拒绝；补充 `GRANT ALTER ON smoke_metadata_guidance.* TO 'opendataworks'@'%'/'localhost'` 后重试通过。
  - 清理: 删除本轮 `data_table_version`、`data_field`、`data_table` 中 `table_id=1653` 的 smoke 元数据并 `DROP TABLE smoke_metadata_guidance.smoke_f10b_meta_edit`，复查剩余 0 条；本地前端和后端进程已停止。
  - 已知非本切片问题: 浏览器 console error 为 `/dataagent/widget/opendataworks-widget.bundle.js` 代理 500；另有既有 Element Plus `small` deprecation 与 `TaskEditDrawer` 图标组件解析 warning，与表/字段编辑抽取无关。
- 回退: 单提交回退。

### F11 — 收尾（滚动推进）
- `DataStudioNew.vue` 收敛为「模板 + composables 编排」；清理残留未用函数/导入；评估是否进一步收敛 `provide('dataStudioCtx')` 契约（如收敛则单独切片 + 同步子组件）。
- 验证: lint/test/build；统计组件行数下降。
- 回退: 单提交回退。

## 回滚策略

- 每个 F 任务为独立提交，问题时按提交回退。
- `provide('dataStudioCtx')` 键集合全程不变，子组件 `inject` 零改动，回退不外溢。
- 纯工具切片（F1–F3）可凭单测自动验证；有状态切片（F4+）未补测试网前不得标注「完整验证」。

## 排期建议

- 先连做 **F1–F3 纯工具切片**（本环境可单测自动验证、可安全合入）。
- **F4+ 有状态切片**建议在能跑前端 E2E/组件测试或可本地手动冒烟的环境推进，逐簇小步合入。
- F7（目录树）、F9（查询执行）改动面最大，务必单独成片并补关键路径验证。
