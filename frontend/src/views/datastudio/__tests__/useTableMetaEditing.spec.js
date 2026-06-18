import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, ref } from 'vue'

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) },
}))
vi.mock('@/api/domain', () => ({ businessDomainApi: {}, dataDomainApi: {} }))
vi.mock('@/api/table', () => ({ tableApi: {} }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false, showDemoReadonlyMessage: vi.fn() }))

import { ElMessage } from 'element-plus'
import { useTableMetaEditing } from '../composables/useTableMetaEditing'

function setup(table = { tableModel: 'UNIQUE' }, fields = [{ id: 1, fieldName: 'a', fieldType: 'int' }]) {
  const tabStates = reactive({
    t1: { table, fields, fieldsDraft: [], fieldsRemoved: [], fieldsEditing: false },
  })
  const api = useTableMetaEditing({
    clusterId: ref(null),
    tabStates,
    openTabs: ref([]),
    activeTab: ref('t1'),
    selectedTableKey: ref(''),
    tableRefs: ref({}),
    tableStore: reactive({}),
    getTableKey: () => 't1',
    isDorisTable: () => false,
    isAggregateTable: (t) => String(t?.tableModel || '').toUpperCase() === 'AGGREGATE',
    warnPlatformMetadataMissing: () => false,
  })
  return { tabStates, api }
}

describe('useTableMetaEditing (field draft ops)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getFieldRows returns committed fields, or the draft while editing', () => {
    const { tabStates, api } = setup()
    expect(api.getFieldRows('t1')).toBe(tabStates.t1.fields)
    tabStates.t1.fieldsEditing = true
    expect(api.getFieldRows('t1')).toBe(tabStates.t1.fieldsDraft)
    expect(api.getFieldRows('missing')).toEqual([])
  })

  it('startFieldsEdit clones fields into a draft and resets removed', () => {
    const { tabStates, api } = setup()
    api.startFieldsEdit('t1')
    expect(tabStates.t1.fieldsEditing).toBe(true)
    expect(tabStates.t1.fieldsDraft).toEqual(tabStates.t1.fields)
    expect(tabStates.t1.fieldsDraft).not.toBe(tabStates.t1.fields) // deep clone
    expect(tabStates.t1.fieldsRemoved).toEqual([])
  })

  it('addField prepends a blank row; removeField drops it and records persisted id', () => {
    const { tabStates, api } = setup()
    api.startFieldsEdit('t1')
    api.addField('t1')
    expect(tabStates.t1.fieldsDraft[0]).toMatchObject({ id: null, fieldName: '', fieldType: '' })
    const persisted = tabStates.t1.fieldsDraft.find((r) => r.id === 1)
    api.removeField('t1', persisted)
    expect(tabStates.t1.fieldsDraft.includes(persisted)).toBe(false)
    expect(tabStates.t1.fieldsRemoved).toContain(1)
  })

  it('aggregate tables block add/remove with a warning', () => {
    const { tabStates, api } = setup({ tableModel: 'AGGREGATE' })
    api.startFieldsEdit('t1')
    const before = tabStates.t1.fieldsDraft.length
    api.addField('t1')
    expect(tabStates.t1.fieldsDraft.length).toBe(before)
    expect(ElMessage.warning).toHaveBeenCalled()
  })

  it('cancelFieldsEdit clears editing state and drafts', () => {
    const { tabStates, api } = setup()
    api.startFieldsEdit('t1')
    api.addField('t1')
    api.cancelFieldsEdit('t1')
    expect(tabStates.t1.fieldsEditing).toBe(false)
    expect(tabStates.t1.fieldsDraft).toEqual([])
    expect(tabStates.t1.fieldsRemoved).toEqual([])
  })
})
