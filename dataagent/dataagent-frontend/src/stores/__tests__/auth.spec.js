import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const authApiMock = vi.hoisted(() => ({
  getAuthConfig: vi.fn(),
  login: vi.fn(),
  me: vi.fn(),
  logout: vi.fn(),
  oauthAuthorizeUrl: vi.fn((redirect) => `/api/v1/nl2sql/auth/oauth/authorize?redirect=${encodeURIComponent(redirect || '')}`)
}))

vi.mock('@/api/nl2sql', () => ({
  createNl2SqlApiClient: () => ({ authApi: authApiMock }),
  DATAAGENT_CLIENT_HEADERS: Object.freeze({ 'X-ODW-Client': 'dataagent' })
}))

import { sanitizeRedirectPath, useAuthStore } from '../auth'

describe('sanitizeRedirectPath', () => {
  it.each([
    ['http://evil.com/x'],
    ['https://evil.com'],
    ['//evil.com'],
    ['/\\evil'],
    ['javascript:alert(1)'],
    [''],
    [null]
  ])('rejects unsafe value %s', (raw) => {
    expect(sanitizeRedirectPath(raw)).toBe('/intelligent-query/chat')
  })

  it('accepts same-origin app paths', () => {
    expect(sanitizeRedirectPath('/intelligent-query/chat?x=1')).toBe('/intelligent-query/chat?x=1')
  })
})

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('stays fully open when auth is disabled', async () => {
    authApiMock.getAuthConfig.mockResolvedValue({ enabled: false })
    const store = useAuthStore()
    await store.bootstrap()

    expect(store.enabled).toBe(false)
    expect(store.isAuthenticated).toBe(true)
    // 关闭态 isAdmin 为 true：既有管理 UI 保持可用（与后端 no-op 放行一致）。
    expect(store.isAdmin).toBe(true)
    expect(authApiMock.me).not.toHaveBeenCalled()
  })

  it('loads current user when auth is enabled', async () => {
    authApiMock.getAuthConfig.mockResolvedValue({
      enabled: true,
      provider_name: 'SSO',
      provider_icon: 'fa-github',
      local_login_enabled: true,
      oauth_login_enabled: true
    })
    authApiMock.me.mockResolvedValue({ user_id: 'SSO:42', username: 'alice', role: 'user' })
    const store = useAuthStore()
    await store.bootstrap()

    expect(store.enabled).toBe(true)
    expect(store.isAuthenticated).toBe(true)
    expect(store.isAdmin).toBe(false)
    expect(store.currentUser.username).toBe('alice')
    expect(store.providerName).toBe('SSO')
    expect(store.providerIcon).toBe('fa-github')
  })

  it('swallows the me() 401 and reports unauthenticated', async () => {
    authApiMock.getAuthConfig.mockResolvedValue({ enabled: true, local_login_enabled: true })
    authApiMock.me.mockRejectedValue(new Error('401'))
    const store = useAuthStore()
    await store.bootstrap()

    expect(store.isAuthenticated).toBe(false)
    expect(store.isAdmin).toBe(false)
  })

  it('treats a config fetch failure as disabled', async () => {
    authApiMock.getAuthConfig.mockRejectedValue(new Error('network'))
    const store = useAuthStore()
    await store.bootstrap()

    expect(store.enabled).toBe(false)
    expect(store.isAuthenticated).toBe(true)
  })

  it('sanitizes the oauth redirect before building the authorize url', () => {
    const store = useAuthStore()
    store.oauthAuthorizeUrl('//evil.com')
    expect(authApiMock.oauthAuthorizeUrl).toHaveBeenCalledWith('')
  })
})
