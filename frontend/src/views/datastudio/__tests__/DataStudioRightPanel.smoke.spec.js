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
import DataStudioRightPanelPartitions from '../components/DataStudioRightPanelPartitions.vue'
import TableVersionHistoryPanel from '../components/TableVersionHistoryPanel.vue'

const buildCtx = () => {
  const activeTab = ref('t1')
  const tabStates = reactive({
    t1: {
      table: { id: 1, tableName: 'demo_t', dbName: 'db', sourceId: 's1', sourceType: 'DORIS' },
      metaTab: 'basic',
      metaDetailTab: 'fields',
      metaEditing: false,
      metaSaving: false,
      metaForm: {},
      metaDataDomainOptions: [],
      metaSuggestion: null,
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
    generateMetadata: fn(),
    adoptMetadata: fn(),
    metadataGenerating: ref(false),
    metadataAdopting: ref(false),
    metadataDialogVisible: ref(false),
    metadataResult: ref(null),
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

  it('明细信息子页在 分区信息 / 变更记录 之间切换', async () => {
    const ctx = buildCtx()
    ctx.tabStates.t1.fields = [
      { id: 1, fieldName: 'dt', fieldType: 'date', isPartition: 1, fieldComment: '业务日期' },
      { id: 2, fieldName: 'amount', fieldType: 'decimal', isPartition: 0, fieldComment: '' },
    ]
    ctx.tabStates.t1.table.partitionColumn = 'dt'

    const mountPane = () =>
      shallowMount(DataStudioRightPanelColumns, {
        global: {
          provide: { dataStudioCtx: ctx },
          stubs: { ElScrollbar: { template: '<div><slot /></div>' } },
          config: { warnHandler: () => {} },
          directives: { loading: {} },
        },
      })

    // 默认字段信息
    let wrapper = mountPane()
    expect(wrapper.findComponent(DataStudioRightPanelPartitions).exists()).toBe(false)
    wrapper.unmount()

    // 分区信息：渲染分区面板
    ctx.tabStates.t1.metaDetailTab = 'partitions'
    wrapper = mountPane()
    expect(wrapper.findComponent(DataStudioRightPanelPartitions).exists()).toBe(true)
    wrapper.unmount()

    // 变更记录：把 tableId 透传给版本历史面板
    ctx.tabStates.t1.metaDetailTab = 'changes'
    wrapper = mountPane()
    const history = wrapper.findComponent(TableVersionHistoryPanel)
    expect(history.exists()).toBe(true)
    expect(history.props('tableId')).toBe(1)
    expect(history.props('active')).toBe(true)
    wrapper.unmount()
  })

  it('分区信息按 isPartition 拆分字段', () => {
    const ctx = buildCtx()
    ctx.tabStates.t1.fields = [
      { id: 1, fieldName: 'dt', fieldType: 'date', isPartition: 1 },
      { id: 2, fieldName: 'amount', fieldType: 'decimal', isPartition: 0 },
      { id: 3, fieldName: 'shop_id', fieldType: 'string', isPartition: 0 },
    ]
    const wrapper = shallowMount(DataStudioRightPanelPartitions, {
      global: {
        provide: { dataStudioCtx: ctx },
        stubs: { ElScrollbar: { template: '<div><slot /></div>' } },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    expect(wrapper.text()).toContain('分区字段 1')
    expect(wrapper.text()).toContain('非分区字段 2')
    wrapper.unmount()
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
