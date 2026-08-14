import { defineAsyncComponent } from 'vue'
import RouteLoadingView from '@/components/RouteLoadingView.vue'
import RouteLoadErrorView from '@/components/RouteLoadErrorView.vue'

// 已预热/已缓存的 chunk 在一个 microtask 内就能 resolve，150ms 内不会闪骨架屏；
// 只有真正慢的加载才会露出 loadingComponent。
const ROUTE_LOADING_DELAY = 150

/**
 * 把路由的动态 import 包成异步组件。
 *
 * Vue Router 只会对「函数形式的组件」在导航过程中 await loader，chunk 下载完成前
 * 导航不会 resolve —— 表现就是点了菜单没反应。defineAsyncComponent 返回的是对象，
 * 导航立即完成，加载推到渲染阶段，未就绪时由 loadingComponent 占位。
 *
 * 返回值上挂 __routeLoader，供 routeWarmup 预热原始 loader，不依赖 Vue 内部字段。
 */
export const lazyView = (loader) => {
  const component = defineAsyncComponent({
    loader,
    loadingComponent: RouteLoadingView,
    errorComponent: RouteLoadErrorView,
    delay: ROUTE_LOADING_DELAY,
    suspensible: false
  })
  component.__routeLoader = loader
  // vue-router 的 dev 警告会劝你写回裸 () => import()，正是这里要刻意避开的行为
  // （见 docs/design/2026-08-14-datastudio-directory-and-route-loading-design.md）。
  // __warnedDefineAsync 是 vue-router 自己的「已提示过」标记，预置它避免每次导航刷屏。
  component.__warnedDefineAsync = true
  return component
}

export { ROUTE_LOADING_DELAY }
