// DataStudioRightPanel 运行时挂载冒烟（P2-2 F17 的安全网）。
// DataStudioNew.smoke 把 RightPanel stub 掉了，所以这里单独挂载:
// 用假 dataStudioCtx provide + shallowMount 真正执行 script setup / composables /
// computed 与模板渲染。若 F17 抽取破坏了引用或响应式,挂载/渲染会抛错。
import { describe, it, expect, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { reactive, ref } from 'vue'

const { apiStub } = vi.hoisted(() => {
  const apiStub = () =>
    new Proxy(
      {},
      {
        get: () => () =>
          Promise.resolve({ data: [], records: [], total: 0, code: 0, success: true }),
      }
    )
  return { apiStub }
})

vi.mock('@/api/table', () => ({ tableApi: apiStub() }))
vi.mock('@/utils/loadEcharts', () => ({ loadEcharts: vi.fn(() => Promise.resolve({})) }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: () => false, showDemoReadonlyMessage: vi.fn() }))

import DataStudioRightPanel from '../components/DataStudioRightPanel.vue'
import DataStudioRightPanelBasic from '../components/DataStudioRightPanelBasic.vue'
import DataStudioRightPanelColumns from '../components/DataStudioRightPanelColumns.vue'
import DataStudioRightPanelAccess from '../components/DataStudioRightPanelAccess.vue'

const buildCtx = () => {
  const activeTab = ref('t1')
  const tabStates = reactive({
    t1: {
      table: { id: 1, tableName: 'demo_t', dbName: 'db', sourceId: 's1', sourceType: 'DORIS' },
      metaTab: 'basic',
      metaEditing: false,
      metaSaving: false,
      metaForm: {},
      metaDataDomainOptions: [],
      metadataSyncing: false,
      fieldSubmitting: false,
      fieldsEditing: false,
      fieldsDraft: [],
      fieldsRemoved: [],
      fields: [],
      ddl: '',
      ddlLoading: false,
      accessLoading: false,
      accessStats: null,
      accessError: '',
      lineage: { upstreamTables: [], downstreamTables: [], edges: [] },
      tasks: { writeTasks: [], readTasks: [] },
    },
  })
  const fn = () => vi.fn()
  return {
    clusterId: ref('s1'),
    openTabs: ref([{ id: 't1', kind: 'table', tableName: 'demo_t' }]),
    activeTab,
    tabStates,
    layerOptions: [],
    businessDomainOptions: ref([]),
    getMetaDataDomainOptions: () => [],
    handleMetaBusinessDomainChange: fn(),
    isDorisTable: () => true,
    isPlatformMetadataMissing: () => false,
    isAggregateTable: () => false,
    isReplicaWarning: () => false,
    getFieldRows: () => [],
    startMetaEdit: fn(),
    cancelMetaEdit: fn(),
    saveMetaEdit: fn(),
    handleDeleteTable: fn(),
    syncMissingTableMetadata: fn(),
    startFieldsEdit: fn(),
    cancelFieldsEdit: fn(),
    saveFieldsEdit: fn(),
    addField: fn(),
    removeField: fn(),
    copyDdl: fn(),
    loadAccessStats: fn(),
    formatDuration: (v) => String(v ?? '-'),
    formatDateTime: (v) => String(v ?? '-'),
    goLineage: fn(),
    goCreateRelatedTask: fn(),
    openTask: fn(),
    openTableTab: fn(),
  }
}

describe('DataStudioRightPanel mount smoke', () => {
  it('mounts with a table tab and renders the panel shell', async () => {
    const wrapper = shallowMount(DataStudioRightPanel, {
      global: {
        provide: { dataStudioCtx: buildCtx() },
        stubs: {
          DataStudioRightPanelLineage: true,
          TableVersionHistoryPanel: true,
          TableTrendDialog: true,
          ElScrollbar: { template: '<div><slot /></div>' },
        },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('.panel-shell').exists()).toBe(true)
    expect(wrapper.find('.panel-resizer').exists()).toBe(true)
    wrapper.unmount()
  })

  it('mounts each pane child with the ctx (P2-2 F17d)', () => {
    const panes = [
      [DataStudioRightPanelBasic, '.basic-grid'],
      [DataStudioRightPanelColumns, '.section-block'],
      [DataStudioRightPanelAccess, '.section-block'],
    ]
    for (const [Pane, marker] of panes) {
      const wrapper = shallowMount(Pane, {
        global: {
          provide: { dataStudioCtx: buildCtx() },
          stubs: { TableTrendDialog: true, ElScrollbar: { template: '<div><slot /></div>' } },
          config: { warnHandler: () => {} },
          directives: { loading: {} },
        },
      })
      expect(wrapper.exists()).toBe(true)
      expect(wrapper.find(marker).exists()).toBe(true)
      wrapper.unmount()
    }
  })

  it('renders the empty state for query tabs', () => {
    const ctx = buildCtx()
    ctx.openTabs.value = [{ id: 'q1', kind: 'query', tableName: '查询' }]
    ctx.activeTab.value = 'q1'
    const wrapper = shallowMount(DataStudioRightPanel, {
      global: {
        provide: { dataStudioCtx: ctx },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    expect(wrapper.find('.panel-shell').exists()).toBe(false)
    expect(wrapper.find('.right-empty').exists()).toBe(true)
    wrapper.unmount()
  })
})
