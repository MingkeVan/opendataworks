import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import { lazyView } from '../lazyView'

describe('lazyView', () => {
  it('exposes the raw loader for route warmup', () => {
    const loader = () => Promise.resolve({ template: '<div />' })
    const component = lazyView(loader)

    expect(typeof component).not.toBe('function')
    expect(component.__routeLoader).toBe(loader)
  })

  it('does not block navigation on the route chunk', async () => {
    // 永不 resolve 的 loader：裸 () => import() 形态下导航会一直挂着，
    // 包成异步组件后导航必须立即完成。
    let started = false
    const neverResolves = () => {
      started = true
      return new Promise(() => {})
    }

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/lazy', component: lazyView(neverResolves) }
      ]
    })

    await router.push('/')
    await router.push('/lazy')

    expect(router.currentRoute.value.path).toBe('/lazy')
    // 导航期间 router 不应该去碰 loader，加载留给渲染阶段
    expect(started).toBe(false)
  })
})
