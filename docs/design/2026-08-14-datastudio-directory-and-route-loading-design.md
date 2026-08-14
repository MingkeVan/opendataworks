# Data Studio 目录宽度与路由加载响应 Design

**Date:** 2026-08-14
**Goal:** 修两个 Data Studio 可用性问题——(1) 左侧目录拉宽后长表名仍然省略号；(2) 切换到 `/datastudio`（以及 401 回登录页后登录成功）时页面几秒无反应。目标是：导航立即生效，未就绪的区域用 loading 占位。
**Tech Stack:** `frontend/`（Vue 3 + Vue Router 4 + Element Plus）。不涉及后端与 DataAgent。

## Current State

### 1. 目录树表名宽度

`frontend/src/views/datastudio/components/DataStudioCatalogNode.vue` 的表节点结构是：

```
.catalog-node (width: 100%)
  └ .catalog-node-row (flex)
      ├ .node-icon        (flex-shrink: 0)
      ├ .table-main       (flex: 1; min-width: 0)
      │   ├ .table-title → .table-name  (flex: 1; min-width: 0; max-width: 200px)
      │   └ .table-comment
      └ .table-meta-tags  (flex-shrink: 0)
```

侧栏宽度由 `useResizablePanes.js` 控制，可拖到 `MAX_SIDEBAR_WIDTH = 840px`。但 `.table-name` 上有一条硬编码 `max-width: 200px`，无论侧栏多宽，表名可用宽度都被钉死在 200px。

### 2. 路由切换无响应

`frontend/src/router/index.js` 的路由组件全部写成裸的动态 import：

```js
component: () => import('@/views/datastudio/DataStudioNew.vue')
```

Vue Router 对「函数形式的组件」会在导航过程中 `await` 这个 loader。也就是说 chunk 下载 + 求值完成之前，导航根本不会 resolve：URL 不变、菜单高亮不变、页面停在原地，用户看到的就是"点了没反应"。

`/datastudio` 这条链是全站最重的：`DataStudioNew.vue` → `DataStudioQueryPanel` → `SqlEditor.vue` 静态 import 了整套 `@codemirror/*`（打包进 `vendor-codemirror`），加上 `vendor-element-plus`。首次进入要拉这几个 chunk，几秒空白是常态。

`routeWarmup.js` 已经在 Layout 挂载后用 `requestIdleCallback` 预热菜单路由，能缓解但盖不住：
- 预热是串行链，用户点得比预热快时仍然要等；
- 预热只在 **Layout 已挂载** 时发生。

而 401 场景恰好绕开了预热：`utils/request.js` 拿到 401 后走的是 `window.location.href = '/login?redirect=...'`，**整页刷新**，把整个已加载已求值的模块图丢光。登录页只加载了 Login chunk，Layout 从没挂载过，`scheduleRouteWarmup` 一次都没跑。登录成功后 `router.replace(redirect)` 才第一次去拉 `Layout` + `DataStudioNew` + `vendor-codemirror`，期间登录页原地不动——就是用户描述的"登录成功后没反应，要过一会才打开 datastudio"。

`LoginView.vue` 的 `finally { loading.value = false }` 在 `router.replace` 之前就跑完了，连按钮 loading 都收掉了，反馈为零。

### 3. 已进过 Data Studio 后再切回来，仍然卡几秒

chunk 已经在内存里了，卡顿却还在：从 Data Studio 切到任务调度是秒切，切回来要等几秒。这是**挂载开销**，与 chunk 无关。

`PersistentTabs.vue` 把每个 Tab 渲染成 `el-tab-pane`，而 `el-tab-pane` 的渲染条件是 `shouldBeRender = !lazy || loaded || active`——`lazy` 默认 `false`，于是**所有** pane 的内容都会渲染，只是非激活的用 `v-show` 藏起来。每个 pane 里有一个 `DataStudioQueryPanel`，它的 `SqlEditor.vue` 在 `onMounted` 里 `new EditorView({...})` 建一个 CodeMirror 实例。

恢复十几个查询 Tab 时，就是十几个 CodeMirror 实例在同一个 tick 里同步构造完，主线程一卡就是几秒。期间浏览器无法绘制，所以连菜单高亮和 URL 变化都看不到——观感依旧是"点了没反应，几秒后整页一起出来"。

`SqlEditor` 虽然是 `defineAsyncComponent`，但那只推迟 chunk 加载；chunk 命中缓存后，挂载照样是同步的。

## Problem

1. 侧栏拉宽对长表名无效，硬编码 `max-width: 200px` 是唯一原因。
2. "点了没反应"有三条彼此独立的成因，各自解决其中一段：
   - 路由组件是裸 loader → 导航被 chunk 下载阻塞（冷加载）。
   - 401 整页刷新 → 模块图全丢，登录后要重新下载（会话过期链路）。
   - 所有 Tab pane 一次性挂载 → 十几个 CodeMirror 同步构造（热状态下切回来）。

## Scope

**做：**

- 去掉 `.table-name` 的 `max-width`，让表名吃满侧栏实际宽度。
- 路由组件统一用 `defineAsyncComponent` 包装（`lazyView`），使导航不再 await chunk：URL 与菜单高亮立即切换，chunk 未就绪的区域渲染骨架屏。
- chunk 加载失败时给出可重载的错误占位（原来是导航静默失败）。
- `routeWarmup` 兼容新的包装组件，预热能力不回退。
- 401 改成 SPA 内 `router.replace`，不再整页刷新。
- `PersistentTabs` 支持 `lazy`，Data Studio 工作区打开，把 pane 内容的首次渲染推迟到该 Tab 首次激活。

**不做（本次）：**

- 不拆 `SqlEditor` 的 CodeMirror 静态依赖。
- 不改 Data Studio 的 Tab 持久化格式与 `localStorage` key。

## Solution

### 1. `.table-name` 去掉宽度上限

删掉 `max-width: 200px`。`.table-main` 已经是 `flex: 1; min-width: 0`，`.table-title`/`.table-name` 也都有 `min-width: 0`，去掉上限后表名自然吃满「侧栏宽度 − 图标 − 右侧指标标签」的剩余空间，只有真放不下时才省略号。

顺带把 `.catalog-node` 的 `width: 100%` 换成 `flex: 1; min-width: 0`。它是 `.el-tree-node__content`（flex 容器）的直接子元素，`flex: 1` 表达的是"占满展开图标之后的剩余宽度"，比依赖 `width: 100%` + flex 收缩更直白，也避免嵌套层里再出现意外的宽度基准。

### 2. `lazyView`：路由组件不再阻塞导航

新增 `frontend/src/router/lazyView.js`：

```js
export const lazyView = (loader) => {
  const component = defineAsyncComponent({
    loader,
    loadingComponent: RouteLoadingView,
    errorComponent: RouteLoadErrorView,
    delay: ROUTE_LOADING_DELAY, // 150ms
    suspensible: false
  })
  component.__routeLoader = loader
  return component
}
```

关键点：`defineAsyncComponent` 返回的是**对象**而不是函数，Vue Router 的 `typeof component === 'function'` 判定不成立，于是导航不再 await chunk，立刻 resolve。真正的加载被推到渲染阶段，由 `<router-view>` 渲染出的异步组件自己处理，未就绪时渲染 `loadingComponent`。

`delay: 150` 是刻意留的：已预热/已缓存的 chunk 在一个 microtask 内就 resolve，150ms 内不会闪骨架屏；只有真正慢的加载才会露出骨架屏。

`errorComponent` 补上原来缺失的失败反馈——以前 chunk 加载失败只会让导航 reject，用户停在原页面没有任何提示。

`__routeLoader` 是给 `routeWarmup` 用的原始 loader 引用，不依赖 Vue 内部字段。

站内所有路由组件统一走 `lazyView`，包括父级 `Layout`。父子都是异步组件时，登录后先出 Layout 骨架、再出 Layout 外壳 + Data Studio 骨架，逐层显形。

前提确认：全仓没有任何组件内路由守卫（`beforeRouteEnter` / `beforeRouteUpdate` / `beforeRouteLeave`），所以「异步组件的选项式守卫不会被 router 提取」这个已知限制在本仓不适用。

### 3. 占位组件

- `RouteLoadingView.vue`：骨架屏，粗略模仿"工具条 + 左窄右宽"的主流页面骨架，撑满容器高度。
- `RouteLoadErrorView.vue`：`el-result` + 「重新加载」按钮（`window.location.reload()`）。`defineAsyncComponent` 只向 errorComponent 传 `error` prop，不传 `retry`，所以用整页重载而不是自造重试链路。

### 4. `routeWarmup` 兼容

`getAsyncLoaders` 原来只收 `typeof component === 'function'`。改成先取 `component.__routeLoader`，再回落到函数本身：

```js
const toRouteLoader = (component) => {
  if (typeof component?.__routeLoader === 'function') return component.__routeLoader
  if (typeof component === 'function') return component
  return null
}
```

预热调的是原始 loader，模块进 ESM registry 后，`defineAsyncComponent` 挂载时再调 `loader()` 直接命中缓存，在一个 microtask 内 resolve——落在 150ms 的 delay 之内，所以预热过的路由不会闪骨架屏。

`preloadRouteComponents` 顺带加 try/catch 兜 `router.resolve` 抛错（登录页 `redirect` 参数来自 URL，可能是任意字符串）。

### 5. 401 改成 SPA 内跳转

根因是整页刷新本身。用户在 Data Studio 上遇到 401 时，Data Studio 与 Layout 的 chunk 本来就在内存里已加载已求值；只要不刷新页面，登录成功后 `router.replace(redirect)` 是**零网络的重新渲染**。

`request.js` 不能直接 `import router`——`utils/request.js` ← `api/auth.js` ← `stores/auth.js` ← `router/index.js` 已经是一条依赖链，反向 import 会成环。做法是加一个零依赖叶子模块 `utils/authRedirect.js` 承载回调注册，在组合根 `main.js` 注入 router 与 store：

```js
setUnauthorizedHandler(createUnauthorizedRedirect({ router, authStore: useAuthStore(pinia) }))
```

两个必要细节：

- **必须重置 auth store。** 整页刷新时 store 天然是空的；留在 SPA 里则 `currentUser` 还是旧值，路由守卫会认为用户仍然登录着。为此 store 加 `markSessionExpired()`（清 `currentUser`，`initialized` 保持 `true`，省掉一次注定 401 的 `/auth/me` 探测）。
- **必须挡并发。** 会话过期时 Data Studio 的多个并行请求会一起 401，而 `router.replace` 是异步的，`currentRoute` 在导航完成前不会变成 `/login`，只靠路径判断挡不住。用一个 `redirecting` 标志，在导航 settle 后复位。

顺带修好一个隐性问题：`window.location.href` 不触发 Vue 的 `onBeforeUnmount`，所以原来 401 会截断 Data Studio 标签页持久化的 250ms 去抖写入（`flushPersistTabs`）；改成 SPA 跳转后组件正常卸载，标签页状态能落盘。

### 6. Tab pane 首次渲染推迟

`PersistentTabs` 加一个 `lazy` prop（默认 `false`，保持既有语义），透传给每个 `el-tab-pane`；Data Studio 的工作区打开它。

`el-tab-pane` 的 `lazy` 语义正好合用：`loaded` 一旦置位就不再回落，所以 `lazy` 只推迟**首次**渲染，激活过的 pane 依然常驻——"Persistent"的语义一点没变。恢复十几个 Tab 时只挂载当前激活的那一个 CodeMirror，其余等用户真正切过去再建。

对既有逻辑的影响已核对过：`leftPaneRefs` 的两个消费方都有空值保护（`startLeftResize` 里 `if (!container) return`，`disposeTabResources` 里 `if (leftPaneRefs.value?.[id])`），且 `startLeftResize` 只可能由可见 pane 的分隔条触发。持久化快照读的是 `tabStates`，与 pane 是否渲染无关，所以从未激活过的 Tab 的 SQL 不会丢。

## Interfaces

| 文件 | 变更 |
| --- | --- |
| `frontend/src/router/lazyView.js` | 新增。`lazyView(loader)`，返回带 `__routeLoader` 的异步组件 |
| `frontend/src/components/RouteLoadingView.vue` | 新增。路由 chunk 加载中的骨架屏 |
| `frontend/src/components/RouteLoadErrorView.vue` | 新增。路由 chunk 加载失败占位 |
| `frontend/src/router/index.js` | 所有 `component: () => import(...)` → `component: lazyView(() => import(...))` |
| `frontend/src/router/routeWarmup.js` | `getAsyncLoaders` 认 `__routeLoader` |
| `frontend/src/utils/authRedirect.js` | 新增。零依赖的 401 回调注册点 + `createUnauthorizedRedirect` |
| `frontend/src/utils/request.js` | 401 分支改调 `handleUnauthorized()`，不再碰 `window.location` |
| `frontend/src/stores/auth.js` | 新增 `markSessionExpired()` action |
| `frontend/src/main.js` | 组合根注入 router / store 到 401 处理器 |
| `frontend/src/components/PersistentTabs.vue` | 新增 `lazy` prop（默认 `false`），透传给 `el-tab-pane` |
| `frontend/src/views/datastudio/DataStudioNew.vue` | 工作区 `<PersistentTabs lazy>` |
| `frontend/src/views/datastudio/components/DataStudioCatalogNode.vue` | `.table-name` 去 `max-width`；`.catalog-node` 用 `flex: 1; min-width: 0` |

对外行为契约不变：路由路径、`meta`、守卫、预热 API 签名、Tab 持久化格式都没动。401 后落到 `/login?redirect=...` 的 URL 形态也与原来一致。

## Tradeoffs

- **异步组件 vs 裸 loader**：换来导航即时生效和 loading 态，代价是失去 router 对组件内选项式守卫的提取能力。本仓没有这类守卫，代价为 0；后续若要加，用组合式 `onBeforeRouteLeave` / `onBeforeRouteUpdate` 即可（在 setup 里注册，不受影响）。
- **`delay: 150`**：0 会让已缓存路由闪一下骨架屏，Vue 默认 200 又让慢路由多空白 50ms。150 取中间，且保证预热过的路由不闪。
- **失败用整页重载而非 retry**：`defineAsyncComponent` 不给 errorComponent 传 `retry`，自建重试要么依赖内部字段要么另铺一层状态。chunk 加载失败通常伴随发版换 hash，整页重载反而是对的处理。
- **401 不再整页刷新**：整页刷新是一把很钝但很安全的锤子——状态全清。换成 SPA 跳转后必须显式清 auth store，否则守卫会放行已过期的会话。代价是多了一处必须维护的状态重置；收益是会话过期后的返回从"几秒"变成"瞬间"，并且组件能正常卸载。仓库里只有 `stores/auth.js` 一个 store，重置面很小，这笔交换划算。
- **`lazy` 用 prop 而非直接写死**：`PersistentTabs` 目前只有 Data Studio 一个消费方，本可以直接开。但 pane 的渲染时机是这个组件的对外语义，用 prop（默认 `false`）表达出来，未来的消费方不会被动继承一个隐式行为。
- **`lazy` 的代价**：首次切到某个 Tab 时才建它的 CodeMirror，会有一次很短的构建开销。相比进入页面时一次性构建十几个，这是明显更好的分摊。
