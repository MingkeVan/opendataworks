import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    currentUser: null,
    // 是否已尝试过恢复会话（避免路由守卫重复请求 /auth/me）
    initialized: false
  }),

  getters: {
    isLoggedIn: (state) => !!state.currentUser,
    isAdmin: (state) => state.currentUser?.role === 'admin'
  },

  actions: {
    async fetchMe() {
      try {
        this.currentUser = await authApi.me()
      } catch (error) {
        this.currentUser = null
      } finally {
        this.initialized = true
      }
      return this.currentUser
    },

    async login(username, password) {
      this.currentUser = await authApi.login({ username, password })
      this.initialized = true
      return this.currentUser
    },

    // 会话过期（401）：已确定未登录，initialized 保持 true 避免再探一次 /auth/me
    markSessionExpired() {
      this.currentUser = null
      this.initialized = true
    },

    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.currentUser = null
      }
    }
  }
})
