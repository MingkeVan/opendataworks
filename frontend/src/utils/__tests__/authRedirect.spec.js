import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createUnauthorizedRedirect, handleUnauthorized, setUnauthorizedHandler } from '../authRedirect'

const createContext = (path = '/datastudio', fullPath = '/datastudio?tableId=7') => {
  let resolvePending
  const currentRoute = ref({ path, fullPath })
  const router = {
    currentRoute,
    replace: vi.fn(() => new Promise((resolve) => { resolvePending = resolve }))
  }
  const authStore = { markSessionExpired: vi.fn() }
  return { router, authStore, settle: () => resolvePending?.() }
}

describe('createUnauthorizedRedirect', () => {
  it('routes to the login page keeping the current location as redirect', () => {
    const { router, authStore } = createContext()

    createUnauthorizedRedirect({ router, authStore })()

    expect(authStore.markSessionExpired).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith({
      path: '/login',
      query: { redirect: '/datastudio?tableId=7' }
    })
  })

  it('does nothing when already on the login page', () => {
    const { router, authStore } = createContext('/login', '/login?redirect=/datastudio')

    createUnauthorizedRedirect({ router, authStore })()

    expect(router.replace).not.toHaveBeenCalled()
    expect(authStore.markSessionExpired).not.toHaveBeenCalled()
  })

  it('redirects once when concurrent requests all get 401', async () => {
    const { router, authStore, settle } = createContext()
    const redirect = createUnauthorizedRedirect({ router, authStore })

    // router.replace 是异步的，currentRoute 在导航完成前不会变成 /login
    redirect()
    redirect()
    redirect()

    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(authStore.markSessionExpired).toHaveBeenCalledTimes(1)

    settle()
    await Promise.resolve()
    await Promise.resolve()

    // 导航结束后（例如用户登录后会话再次过期）仍可再次跳转
    router.currentRoute.value = { path: '/datastudio', fullPath: '/datastudio' }
    redirect()
    expect(router.replace).toHaveBeenCalledTimes(2)
  })
})

describe('handleUnauthorized', () => {
  beforeEach(() => {
    setUnauthorizedHandler(null)
  })

  it('is a no-op until a handler is registered', () => {
    expect(() => handleUnauthorized()).not.toThrow()
  })

  it('calls the registered handler', () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)

    handleUnauthorized()

    expect(handler).toHaveBeenCalledTimes(1)
  })
})
