import { defineStore } from 'pinia'
import { createNl2SqlApiClient, DATAAGENT_CLIENT_HEADERS } from '@/api/nl2sql'

const { authApi } = createNl2SqlApiClient({ defaultHeaders: DATAAGENT_CLIENT_HEADERS })

// 只接受同源应用内路径，防开放重定向（与后端 sanitize_redirect_path 同规则）。
export function sanitizeRedirectPath(raw, fallback = '/intelligent-query/chat') {
  const value = String(raw || '').trim()
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return fallback
  }
  return value
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // auth 未启用（后端 env 未设置或显式关闭）时一切保持现状：
    // 无登录页、无守卫、管理功能照旧可用。
    enabled: false,
    providerName: '',
    localLoginEnabled: false,
    oauthLoginEnabled: false,
    currentUser: null,
    bootstrapped: false
  }),

  getters: {
    isAuthenticated: (state) => !state.enabled || Boolean(state.currentUser),
    // auth 关闭时为 true：现有管理 UI（Skills/智能体/模型/Widget 接入）保持可用。
    isAdmin: (state) => !state.enabled || state.currentUser?.role === 'admin'
  },

  actions: {
    async bootstrap() {
      if (this.bootstrapped) return
      try {
        const config = await authApi.getAuthConfig()
        this.enabled = Boolean(config?.enabled)
        this.providerName = String(config?.provider_name || '')
        this.localLoginEnabled = Boolean(config?.local_login_enabled)
        this.oauthLoginEnabled = Boolean(config?.oauth_login_enabled)
      } catch (_error) {
        // 后端不可达时按关闭处理，避免把整个 SPA 锁在登录页外。
        this.enabled = false
      }
      if (this.enabled) {
        try {
          this.currentUser = await authApi.me()
        } catch (_error) {
          this.currentUser = null
        }
      }
      this.bootstrapped = true
    },

    async loginLocal(username, password) {
      const user = await authApi.login(username, password)
      this.currentUser = user
      return user
    },

    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.currentUser = null
      }
    },

    oauthAuthorizeUrl(redirect = '') {
      return authApi.oauthAuthorizeUrl(sanitizeRedirectPath(redirect, ''))
    }
  }
})
