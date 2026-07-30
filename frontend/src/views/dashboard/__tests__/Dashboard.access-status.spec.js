import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'

const { getDashboardStatisticsMock, listClustersMock } = vi.hoisted(() => ({
  getDashboardStatisticsMock: vi.fn(),
  listClustersMock: vi.fn(() => Promise.resolve([])),
}))

vi.mock('@/api/dashboard', () => ({
  getDashboardStatistics: getDashboardStatisticsMock,
}))
vi.mock('@/api/doris', () => ({
  dorisClusterApi: { list: listClustersMock },
}))

import Dashboard from '../Dashboard.vue'

const mountDashboard = () => shallowMount(Dashboard, {
  global: {
    stubs: {
      ElCard: { template: '<div><slot name="header" /><slot /></div>' },
      ElRow: { template: '<div><slot /></div>' },
      ElCol: { template: '<div><slot /></div>' },
      ElTag: { template: '<span><slot /></span>' },
      ElAlert: true,
      ElTable: true,
      ElTableColumn: true,
      ElButton: true,
      ElSelect: true,
      ElOption: true,
      ElEmpty: true,
      ElIcon: { template: '<span><slot /></span>' },
    },
    config: { warnHandler: () => {} },
  },
})

describe('Dashboard access statistics status', () => {
  beforeEach(() => {
    getDashboardStatisticsMock.mockReset()
    listClustersMock.mockClear()
  })

  it.each(['BACKFILLING', 'DEGRADED', 'DISABLED', 'UNAVAILABLE'])(
    '%s 状态不展示后端误传的冷表结论',
    async (status) => {
      getDashboardStatisticsMock.mockResolvedValue({
        tableAccessSyncStatus: status,
        tableAccessCoverageComplete: true,
        longUnusedTables: [{ tableId: 1, dbName: 'dw', tableName: 'orders' }],
      })

      const wrapper = mountDashboard()
      await flushPromises()

      expect(wrapper.text()).toContain('长期未用表 Top0')
      wrapper.unmount()
    }
  )

  it('READY 且覆盖完整时展示冷表，旧响应仍按原字段渲染', async () => {
    getDashboardStatisticsMock
      .mockResolvedValueOnce({
        tableAccessSyncStatus: 'READY',
        tableAccessCoverageComplete: true,
        longUnusedTables: [{ tableId: 1, dbName: 'dw', tableName: 'orders' }],
      })
      .mockResolvedValueOnce({
        longUnusedTables: [{ tableId: 2, dbName: 'dw', tableName: 'legacy_orders' }],
      })

    let wrapper = mountDashboard()
    await flushPromises()
    expect(wrapper.text()).toContain('长期未用表 Top1')
    wrapper.unmount()

    wrapper = mountDashboard()
    await flushPromises()
    expect(wrapper.text()).toContain('长期未用表 Top1')
    wrapper.unmount()
  })
})
