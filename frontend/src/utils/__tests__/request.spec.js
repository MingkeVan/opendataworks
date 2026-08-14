import { beforeEach, describe, expect, it, vi } from 'vitest'

const handleUnauthorized = vi.hoisted(() => vi.fn())

vi.mock('@/utils/authRedirect', () => ({ handleUnauthorized }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false }))
vi.mock('@/demo/mockServer', () => ({ demoAdapter: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))

import request from '../request'

const respondWith = (status) => {
  request.defaults.adapter = (config) => {
    const error = new Error(`Request failed with status code ${status}`)
    error.config = config
    error.response = { status, data: {}, config }
    return Promise.reject(error)
  }
}

describe('request 401 handling', () => {
  beforeEach(() => {
    handleUnauthorized.mockClear()
  })

  it('hands 401 to the session-expired redirect instead of reloading the page', async () => {
    respondWith(401)

    await expect(request({ url: '/v1/tables', method: 'get' })).rejects.toThrow()

    expect(handleUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('skips the redirect for probes that opt out', async () => {
    respondWith(401)

    await expect(
      request({ url: '/auth/me', method: 'get', skipAuthRedirect: true, skipErrorMessage: true })
    ).rejects.toThrow()

    expect(handleUnauthorized).not.toHaveBeenCalled()
  })

  it('leaves non-401 failures alone', async () => {
    respondWith(500)

    await expect(
      request({ url: '/v1/tables', method: 'get', skipErrorMessage: true })
    ).rejects.toThrow()

    expect(handleUnauthorized).not.toHaveBeenCalled()
  })
})
