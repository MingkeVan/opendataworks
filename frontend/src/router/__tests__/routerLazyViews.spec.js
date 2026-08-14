import { describe, expect, it, vi } from 'vitest'

vi.mock('@/demo/runtime', () => ({ isDemoMode: false }))

import router from '../index'

const collectRouteComponents = (records) =>
  records.flatMap((record) => {
    const own = record.components
      ? Object.values(record.components)
      : record.component
        ? [record.component]
        : []
    return [...own, ...collectRouteComponents(record.children || [])]
  })

describe('router route components', () => {
  it('wraps every route component with lazyView so navigation never awaits a chunk', () => {
    const components = collectRouteComponents(router.options.routes)

    expect(components.length).toBeGreaterThan(0)
    components.forEach((component) => {
      expect(typeof component).not.toBe('function')
      expect(typeof component.__routeLoader).toBe('function')
    })
  })

  it('keeps /datastudio and its layout parent lazily wrapped', () => {
    const matched = router.resolve('/datastudio').matched

    expect(matched.length).toBeGreaterThanOrEqual(2)
    matched.forEach((record) => {
      Object.values(record.components || {}).forEach((component) => {
        expect(typeof component.__routeLoader).toBe('function')
      })
    })
  })
})
