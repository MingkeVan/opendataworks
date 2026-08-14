import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('routeWarmup', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.restoreAllMocks()
    window.requestIdleCallback = undefined
  })

  it('preloads matched async route components only once', async () => {
    const { preloadRouteComponents } = await import('../routeWarmup')
    const layoutLoader = vi.fn().mockResolvedValue({})
    const pageLoader = vi.fn().mockResolvedValue({})
    const router = {
      resolve: vi.fn().mockReturnValue({
        matched: [
          { components: { default: layoutLoader } },
          { components: { default: pageLoader } }
        ]
      })
    }

    await preloadRouteComponents(router, '/datastudio')
    await preloadRouteComponents(router, '/datastudio')

    expect(router.resolve).toHaveBeenCalledTimes(2)
    expect(layoutLoader).toHaveBeenCalledTimes(1)
    expect(pageLoader).toHaveBeenCalledTimes(1)
  })

  it('preloads lazyView-wrapped components through __routeLoader', async () => {
    const { preloadRouteComponents } = await import('../routeWarmup')
    const layoutLoader = vi.fn().mockResolvedValue({})
    const pageLoader = vi.fn().mockResolvedValue({})
    const router = {
      resolve: vi.fn().mockReturnValue({
        matched: [
          { components: { default: { __routeLoader: layoutLoader } } },
          { components: { default: { __routeLoader: pageLoader } } }
        ]
      })
    }

    await preloadRouteComponents(router, '/datastudio')

    expect(layoutLoader).toHaveBeenCalledTimes(1)
    expect(pageLoader).toHaveBeenCalledTimes(1)
  })

  it('schedules a single idle warmup run', async () => {
    const { scheduleRouteWarmup } = await import('../routeWarmup')
    const dashboardLoader = vi.fn().mockResolvedValue({})
    const lineageLoader = vi.fn().mockResolvedValue({})
    const requestIdleCallback = vi.fn((callback) => {
      callback()
      return 1
    })
    const router = {
      resolve: vi.fn((path) => ({
        matched: [
          {
            components: {
              default: path === '/dashboard' ? dashboardLoader : lineageLoader
            }
          }
        ]
      }))
    }

    window.requestIdleCallback = requestIdleCallback

    scheduleRouteWarmup(router, ['/dashboard', '/lineage'])
    scheduleRouteWarmup(router, ['/dashboard', '/lineage'])
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(requestIdleCallback).toHaveBeenCalledTimes(1)
    expect(dashboardLoader).toHaveBeenCalledTimes(1)
    expect(lineageLoader).toHaveBeenCalledTimes(1)
  })
})
