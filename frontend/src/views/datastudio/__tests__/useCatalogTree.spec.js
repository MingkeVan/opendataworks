import { describe, it, expect, vi } from 'vitest'
import { createApp, ref } from 'vue'

vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } }))
vi.mock('@/api/table', () => ({ tableApi: {} }))
vi.mock('@/api/doris', () => ({ dorisClusterApi: {} }))

import { useCatalogTree } from '../composables/useCatalogTree'

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
  const { result, unmount } = withSetup(() =>
    useCatalogTree({
      clusterId: ref(null),
      tabStates: {},
      openTabs: ref([]),
      activeTab: ref(''),
      tableObserver: ref(null),
      handleQuerySourceSelect: vi.fn(),
      handleQueryDatabaseSelect: vi.fn(),
      openTableTab: vi.fn(),
    })
  )
  return { api: result, unmount }
}

describe('useCatalogTree (getters / filter)', () => {
  it('getDatasourceById finds by stringified id from the owned dataSources ref', () => {
    const { api, unmount } = setup()
    api.dataSources.value = [{ id: 1, name: 'demo' }, { id: 2, name: 'prod' }]
    expect(api.getDatasourceById(1)).toMatchObject({ name: 'demo' })
    expect(api.getDatasourceById('2')).toMatchObject({ name: 'prod' })
    expect(api.getDatasourceById(99)).toBeNull()
    unmount()
  })

  it('filterCatalogNode matches table nodes by name/comment, empty value passes', () => {
    const { api, unmount } = setup()
    expect(api.filterCatalogNode('', { type: 'table', name: 'orders' })).toBe(true)
    expect(
      api.filterCatalogNode('ord', { type: 'table', table: { tableName: 'orders' } })
    ).toBe(true)
    expect(
      api.filterCatalogNode('订单', { type: 'table', table: { tableName: 'orders', tableComment: '订单表' } })
    ).toBe(true)
    expect(
      api.filterCatalogNode('xyz', { type: 'table', table: { tableName: 'orders', tableComment: '订单' } })
    ).toBe(false)
    unmount()
  })

  it('exposes the shared catalog caches as a single source of truth', () => {
    const { api, unmount } = setup()
    // schemaStore/tableStore/columnStore 由本 composable 拥有并返回（供补全/路由/查询共享同一引用）
    expect(api.schemaStore).toBeTypeOf('object')
    expect(api.tableStore).toBeTypeOf('object')
    expect(api.columnStore).toBeTypeOf('object')
    unmount()
  })
})
