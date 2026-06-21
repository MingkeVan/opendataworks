import { describe, it, expect, vi } from 'vitest'
import { createApp, reactive, ref } from 'vue'

vi.mock('@/utils/loadEcharts', () => ({ loadEcharts: vi.fn(() => Promise.resolve({})) }))

import { useResultChart } from '../composables/useResultChart'

function withSetup(composable) {
  let result
  const app = createApp({
    setup() {
      result = composable()
      return () => null
    },
  })
  app.mount(document.createElement('div'))
  return { result, unmount: () => app.unmount() }
}

function setup(resultSet, charts) {
  const tabStates = reactive({
    t1: { charts: charts || [], queryResult: { resultSets: resultSet ? [resultSet] : [] } },
  })
  // 注入一个确定性的 getResultSetByIndex，避免依赖真实结果集读取
  const getResultSetByIndex = (tabId, idx = 0) => {
    const set = tabStates[tabId]?.queryResult?.resultSets?.[idx]
    return { columns: set?.columns || [], rows: set?.rows || [], hasMore: false }
  }
  const { result, unmount } = withSetup(() =>
    useResultChart({
      activeTab: ref('t1'),
      tabStates,
      parseResultTabIndex: (v) => {
        const m = String(v || '').match(/^result-(\d+)$/)
        return m ? Number(m[1]) : null
      },
      getResultSetByIndex,
    })
  )
  return { tabStates, api: result, unmount }
}

describe('useResultChart (selection / gating)', () => {
  it('getNumericColumns delegates to numeric detection on the result set', () => {
    const { api, unmount } = setup({
      columns: ['id', 'name', 'amt'],
      rows: [
        { id: 1, name: 'a', amt: '10' },
        { id: 2, name: 'b', amt: '' },
      ],
    })
    expect(api.getNumericColumns('t1', 0)).toEqual(['id', 'amt'])
    unmount()
  })

  it('applyDefaultChartSelection fills x/y axes for a fresh numeric+dimension set', () => {
    const set = {
      columns: ['dt', 'pv'],
      rows: [
        { dt: '2026-06-01', pv: 10 },
        { dt: '2026-06-02', pv: 25 },
      ],
    }
    const { tabStates, api, unmount } = setup(set, [{ xAxis: '', yAxis: [] }])
    api.applyDefaultChartSelection('t1')
    expect(tabStates.t1.charts[0].xAxis).toBe('dt')
    expect(tabStates.t1.charts[0].yAxis).toEqual(['pv'])
    unmount()
  })

  it('canRenderChart requires rows and a configured x/y axis', () => {
    const set = { columns: ['dt', 'pv'], rows: [{ dt: 'x', pv: 1 }] }
    const { api: noAxis, unmount: u1 } = setup(set, [{ xAxis: '', yAxis: [] }])
    expect(noAxis.canRenderChart('t1', 0)).toBe(false)
    u1()
    const { api: withAxis, unmount: u2 } = setup(set, [{ xAxis: 'dt', yAxis: ['pv'] }])
    expect(withAxis.canRenderChart('t1', 0)).toBe(true)
    u2()
  })
})
