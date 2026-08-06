// DataStudioRightPanelFreshness 挂载与 API 接线测试。
// shallowMount 真正执行 script setup / computed / watch，验证：
// 1) 挂载即按当前表 id 拉取契约与历史；2) 切换表触发重新拉取；
// 3) 未配置时不抛错。DOM 细节交给运行时，这里锁住数据接线。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive, ref, nextTick } from 'vue'

const { getFreshness, freshnessHistory, checkFreshness, saveFreshness, deleteFreshness } = vi.hoisted(() => ({
  getFreshness: vi.fn(),
  freshnessHistory: vi.fn(),
  checkFreshness: vi.fn(),
  saveFreshness: vi.fn(),
  deleteFreshness: vi.fn(),
}))

vi.mock('@/api/table', () => ({
  tableApi: { getFreshness, freshnessHistory, checkFreshness, saveFreshness, deleteFreshness },
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  const msg = Object.assign(vi.fn(), { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() })
  return { ...actual, ElMessage: msg, ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) } }
})

import DataStudioRightPanelFreshness from '../components/DataStudioRightPanelFreshness.vue'

const buildCtx = (activeTab, tabStates) => ({
  activeTab,
  tabStates,
  formatDateTime: (v) => String(v ?? '-'),
})

const unconfigured = { tableId: 1, configured: false, config: null, effective: null, latestResult: null }
const configured = {
  tableId: 1,
  configured: true,
  config: { mode: 'column', loadedAtField: 'etl_time', warnAfterCount: 2, warnAfterPeriod: 'hour' },
  effective: {
    mode: 'column',
    loadedAtField: 'etl_time',
    warnAfter: { count: 2, period: 'hour' },
    errorAfter: { count: 4, period: 'hour' },
    fieldSources: { mode: 'table', warnAfter: 'rule_default' },
  },
  latestResult: { status: 'pass', maxLoadedAt: '2026-08-06 02:00:00', snapshottedAt: '2026-08-06 03:00:00', ageSeconds: 3600, mode: 'column' },
}

const mountPanel = (tabStates, activeTab) =>
  shallowMount(DataStudioRightPanelFreshness, {
    global: { provide: { dataStudioCtx: buildCtx(activeTab, tabStates) } },
  })

describe('DataStudioRightPanelFreshness', () => {
  beforeEach(() => {
    getFreshness.mockReset().mockResolvedValue(unconfigured)
    freshnessHistory.mockReset().mockResolvedValue([])
    checkFreshness.mockReset().mockResolvedValue({})
  })

  it('挂载即按当前表拉取契约与历史', async () => {
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: { table: { id: 1 } } })
    const wrapper = mountPanel(tabStates, activeTab)
    await flushPromises()

    expect(wrapper.exists()).toBe(true)
    expect(getFreshness).toHaveBeenCalledWith(1)
    expect(freshnessHistory).toHaveBeenCalledWith(1, 20)
  })

  it('切换表触发重新拉取', async () => {
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: { table: { id: 1 } }, t2: { table: { id: 2 } } })
    mountPanel(tabStates, activeTab)
    await flushPromises()
    expect(getFreshness).toHaveBeenLastCalledWith(1)

    activeTab.value = 't2'
    await nextTick()
    await flushPromises()
    expect(getFreshness).toHaveBeenLastCalledWith(2)
  })

  it('配置态挂载不抛错', async () => {
    getFreshness.mockResolvedValue(configured)
    freshnessHistory.mockResolvedValue([configured.latestResult])
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: { table: { id: 1 } } })
    const wrapper = mountPanel(tabStates, activeTab)
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
  })

  it('无当前表时不拉取', async () => {
    const activeTab = ref('t1')
    const tabStates = reactive({ t1: {} }) // 无 table
    mountPanel(tabStates, activeTab)
    await flushPromises()
    expect(getFreshness).not.toHaveBeenCalled()
  })
})
