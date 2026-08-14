# Data Studio 目录宽度与路由加载响应 Plan

**Date:** 2026-08-14
**Design:** [`docs/design/2026-08-14-datastudio-directory-and-route-loading-design.md`](../design/2026-08-14-datastudio-directory-and-route-loading-design.md)

受影响栈：仅 `frontend/`（路由装配、登录页、Data Studio 目录树节点）。后端、DataAgent、`deploy/` 不涉及。

## Tasks

### T1 — 目录树表名吃满侧栏宽度

- `frontend/src/views/datastudio/components/DataStudioCatalogNode.vue`
  - `.table-name` 删除 `max-width: 200px`。
  - `.catalog-node` 的 `width: 100%` 换成 `flex: 1; min-width: 0`（它是 `.el-tree-node__content` 这个 flex 容器的直接子元素）。

### T2 — 路由加载占位组件

- 新增 `frontend/src/components/RouteLoadingView.vue`：`el-skeleton animated` 自定义 template，工具条 + 左窄右宽内容区，撑满容器高度。
- 新增 `frontend/src/components/RouteLoadErrorView.vue`：接 `error` prop，`el-result` + 「重新加载」按钮调 `window.location.reload()`。

### T3 — `lazyView` 包装 + 路由表接线

- 新增 `frontend/src/router/lazyView.js`：`defineAsyncComponent({ loader, loadingComponent, errorComponent, delay: 150, suspensible: false })`，并在返回的组件上挂 `__routeLoader = loader`。
- `frontend/src/router/index.js`：所有 `component: () => import(...)`（含父级 `Layout`）改成 `component: lazyView(() => import(...))`。`redirect` 型路由不动。

### T4 — 预热兼容

- `frontend/src/router/routeWarmup.js`：抽 `toRouteLoader(component)`，优先 `component.__routeLoader`，回落 `typeof component === 'function'`。`preloadRouteComponents` 本身不动。

### T5 — 401 改成 SPA 内跳转

- 新增 `frontend/src/utils/authRedirect.js`（零依赖叶子模块，规避 `request.js ← api/auth.js ← stores/auth.js ← router/index.js` 的反向成环）：
  - `setUnauthorizedHandler` / `handleUnauthorized`
  - `createUnauthorizedRedirect({ router, authStore })`：`redirecting` 标志挡并发 401，已在 `/login` 时短路，跳转前调 `authStore.markSessionExpired()`。
- `frontend/src/stores/auth.js`：新增 `markSessionExpired()`（清 `currentUser`，`initialized` 保持 `true`）。
- `frontend/src/utils/request.js`：401 分支改成 `handleUnauthorized()`，删掉 `window.location.href` 一段。
- `frontend/src/main.js`：`app.use(router)` 之后、`app.mount` 之前接线。

### T6 — Tab pane 首次渲染推迟

- `frontend/src/components/PersistentTabs.vue`：新增 `lazy` prop（默认 `false`），`:lazy="lazy"` 透传给 `el-tab-pane`。
- `frontend/src/views/datastudio/DataStudioNew.vue`：工作区 `<PersistentTabs ... lazy>`。

### T7 — 回归测试

- `frontend/src/router/__tests__/lazyView.spec.js`（新增）
  - 用 memory history 真路由 + 一个**永不 resolve** 的 loader：`await router.push('/lazy')` 能完成且 `currentRoute.path === '/lazy'` —— 锁住"导航不再被 chunk 阻塞"。
  - `lazyView(loader).__routeLoader === loader`。
- `frontend/src/router/__tests__/routeWarmup.spec.js`（更新）
  - 新增用例：matched 里是 `__routeLoader` 形态的组件时也能预热。
- `frontend/src/utils/__tests__/authRedirect.spec.js`（新增）
  - 跳 `/login` 且带 `redirect=fullPath`；已在 `/login` 时不跳；并发 401 只跳一次且导航结束后可再次跳；`handleUnauthorized` 未注册时是 no-op。
- `frontend/src/utils/__tests__/request.spec.js`（新增）
  - 塞 401 的 axios adapter：401 触发 `handleUnauthorized`；`skipAuthRedirect: true` 与非 401 都不触发。
- `frontend/src/components/__tests__/persistentTabsLazy.spec.js`（新增）
  - 默认不推迟；`lazy` 透传到每个 pane；Data Studio 用法里带 `lazy`。
- `frontend/src/views/datastudio/__tests__/catalogNodeWidth.spec.js`（新增）
  - 读 `DataStudioCatalogNode.vue` 源码，断言 `.table-name` 规则块内不含 `max-width`（scoped 样式在 jsdom 里不生效，用源码守卫，对齐 `taskListFilters.spec.js` 的既有做法）。
- `frontend/src/router/__tests__/routerLazyViews.spec.js`（新增）
  - 断言路由表里 `/datastudio` 及父级 Layout 的 `component` 不是函数且带 `__routeLoader` —— 防止后续新增路由时退回裸 loader。

## Verification

- `nvm use` 后在 `frontend/`（本环境无 nvm，Node v22.22.2 满足 `engines: >=20.19.0`）：
  - `npx vitest run`（全量）
  - `npm run lint`
  - `npm run build`（确认 chunk 切分与新组件都能正常打包）
- 手工（有本地环境时）：
  - 拖宽左侧目录 → 长表名不再省略号。
  - 冷缓存首次点「Data Studio」菜单 → URL/菜单立即切换，内容区出骨架屏。
  - 开十几个查询 Tab → 切到任务调度 → 切回 Data Studio → 立即响应，不再卡几秒；切到某个未激活过的 Tab 时其 SQL 内容完整。
  - Data Studio 页触发 401（后端清 `odw_session` 或等会话超时）→ **不整页刷新**直接落到登录页 → 登录成功 → 立即回到 Data Studio（Network 面板看不到重新下载 JS chunk），且原有 Tab 仍在。

## Rollout

- 纯前端改动，随前端构建发布，无 schema / API / 部署变更。
- 路由路径、`meta`、守卫、`routeWarmup` 对外签名、Tab 持久化格式都不变，无需协调后端。
- 401 后的落地 URL（`/login?redirect=...`）与原来一致，登录页无需改动。

## Backout

- 单 commit revert 即可。三块改动彼此独立，也可以单独回退：
  - 路由部分：把 `router/index.js` 的 `lazyView(...)` 还原成裸 `() => import(...)`，`routeWarmup` 的 `toRouteLoader` 对函数形态仍然兼容，无需同步回退。
  - 401 部分：`request.js` 的 401 分支改回 `window.location.href`，`main.js` 与 `authRedirect.js` 的接线留着也不会被触发。
  - Tab 部分：去掉 `DataStudioNew.vue` 上的 `lazy`，`PersistentTabs` 的 prop 默认 `false`，行为即回到原状。
