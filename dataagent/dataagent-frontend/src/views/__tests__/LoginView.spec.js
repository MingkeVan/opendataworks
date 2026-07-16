import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routerReplace = vi.hoisted(() => vi.fn())
const authState = vi.hoisted(() => ({
  enabled: true,
  currentUser: null,
  localLoginEnabled: false,
  oauthLoginEnabled: true,
  providerName: 'GitHub',
  providerIcon: 'fa-github',
  bootstrap: vi.fn(async () => {}),
  loginLocal: vi.fn(),
  oauthAuthorizeUrl: vi.fn(() => '/api/v1/nl2sql/auth/oauth/authorize')
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: routerReplace })
}))

vi.mock('@/stores/auth', () => ({
  sanitizeRedirectPath: () => '/',
  useAuthStore: () => authState
}))

import LoginView from '../LoginView.vue'

const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div />' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-divider': { template: '<div><slot /></div>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' }
}

describe('LoginView OAuth provider', () => {
  beforeEach(() => {
    routerReplace.mockReset()
    authState.bootstrap.mockClear()
  })

  it('renders a Superset-style Font Awesome icon class next to the provider name', async () => {
    const wrapper = shallowMount(LoginView, { global: { stubs } })
    await flushPromises()

    expect(wrapper.find('.oauth-button .fa.fa-github').exists()).toBe(true)
    expect(wrapper.find('.oauth-button').text()).toContain('GitHub')
  })
})
