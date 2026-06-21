import { describe, it, expect, vi } from 'vitest'
import { createApp, reactive, ref } from 'vue'

vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) } }))
vi.mock('@/api/query', () => ({ dataQueryApi: {} }))

import { useQueryExecution } from '../composables/useQueryExecution'

// 在真实组件实例内运行 composable，使 onBeforeUnmount/watch 合法
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

function setup() {
  const tabStates = reactive({
    t1: {
      table: { tableName: 'orders', dbName: 'db', sourceId: '1' },
      query: { sql: '', selectionText: 'x', hasSelection: true },
      queryResult: { resultSets: [{ columns: ['a'], rows: [{ a: 1 }] }] },
    },
  })
  const { result, unmount } = withSetup(() =>
    useQueryExecution({
      clusterId: ref(null),
      activeSource: ref(''),
      activeSchema: reactive({}),
      schemaStore: reactive({}),
      tabStates,
      openTabs: ref([]),
      activeTab: ref('t1'),
      loadSchemas: vi.fn(),
      loadTables: vi.fn(),
      syncRouteWithTab: vi.fn(),
      disposeChart: vi.fn(),
      applyDefaultChartSelection: vi.fn(),
      syncResultPaneLayout: vi.fn(),
    })
  )
  return { tabStates, api: result, unmount }
}

describe('useQueryExecution (pure helpers + state)', () => {
  it('parseResultTabIndex parses result-N or returns null', () => {
    const { api, unmount } = setup()
    expect(api.parseResultTabIndex('result-2')).toBe(2)
    expect(api.parseResultTabIndex('result-0')).toBe(0)
    expect(api.parseResultTabIndex('info')).toBeNull()
    expect(api.parseResultTabIndex(null)).toBeNull()
    unmount()
  })

  it('isResultSetType treats default/RESULT_SET as a result set, UPDATE_COUNT as not', () => {
    const { api, unmount } = setup()
    expect(api.isResultSetType({})).toBe(true)
    expect(api.isResultSetType({ resultType: 'RESULT_SET' })).toBe(true)
    expect(api.isResultSetType({ resultType: 'UPDATE_COUNT' })).toBe(false)
    unmount()
  })

  it('getResultSetCountText reports row counts / affected rows', () => {
    const { api, unmount } = setup()
    expect(api.getResultSetCountText({ rows: [1, 2, 3] })).toBe('3 行')
    expect(api.getResultSetCountText({ resultType: 'UPDATE_COUNT', affectedRows: 5 })).toBe('影响 5 行')
    expect(api.getResultSetCountText({ resultType: 'UPDATE_COUNT' })).toBe('影响行数未知')
    unmount()
  })

  it('getResultSetByIndex returns normalized columns/rows or empty defaults', () => {
    const { api, unmount } = setup()
    expect(api.getResultSetByIndex('t1', 0)).toMatchObject({ columns: ['a'], rows: [{ a: 1 }] })
    expect(api.getResultSetByIndex('t1', 9)).toMatchObject({ columns: [], rows: [] })
    expect(api.getResultSetByIndex('missing', 0)).toMatchObject({ columns: [], rows: [] })
    unmount()
  })

  it('resetQuery rebuilds default sql and clears selection', () => {
    const { tabStates, api, unmount } = setup()
    api.resetQuery('t1')
    expect(typeof tabStates.t1.query.sql).toBe('string')
    expect(tabStates.t1.query.sql.length).toBeGreaterThan(0)
    expect(tabStates.t1.query.selectionText).toBe('')
    expect(tabStates.t1.query.hasSelection).toBe(false)
    unmount()
  })
})
