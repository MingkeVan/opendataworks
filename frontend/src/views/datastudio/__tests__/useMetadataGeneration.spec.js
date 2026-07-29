import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, ref } from 'vue'

const { nl2sqlApiMock, settingsApiMock, elMessageMock, tableApiMock } = vi.hoisted(() => ({
  nl2sqlApiMock: {
    listAgents: vi.fn(),
    createTopic: vi.fn(),
    deliverMessage: vi.fn(),
    getTask: vi.fn(),
    getTaskMessage: vi.fn()
  },
  settingsApiMock: { getAgentSettings: vi.fn() },
  elMessageMock: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  tableApiMock: {
    updateComment: vi.fn(),
    updateField: vi.fn(),
    getFields: vi.fn(),
    profileColumnValues: vi.fn()
  }
}))

vi.mock('@/api/nl2sql', async () => {
  const actual = await vi.importActual('@/api/nl2sql')
  return { nl2sqlApi: nl2sqlApiMock, nl2sqlErrorMessage: actual.nl2sqlErrorMessage }
})
vi.mock('@/api/settings', () => ({ settingsApi: settingsApiMock }))
vi.mock('@/api/table', () => ({ tableApi: tableApiMock }))
vi.mock('element-plus', () => ({ ElMessage: elMessageMock }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false, showDemoReadonlyMessage: vi.fn() }))

import { useMetadataGeneration } from '../composables/useMetadataGeneration'

const buildDeps = () => {
  const tabStates = reactive({
    t1: {
      table: { id: 1, tableName: 'orders', dbName: 'ods' },
      fields: [{ id: 11, fieldName: 'status', fieldType: 'INT', fieldComment: '' }],
      lineage: { upstreamTables: [], downstreamTables: [] },
      tasks: { writeTasks: [], readTasks: [] },
      ddl: 'CREATE TABLE orders(status INT)'
    }
  })
  return {
    clusterId: ref('c1'),
    tabStates,
    taskApi: { getById: vi.fn() },
    loadDdl: vi.fn(),
    warnPlatformMetadataMissing: () => false
  }
}

const okMessage = {
  blocks: [
    {
      type: 'main_text',
      text: '```json\n{"table_comment":"订单表","fields":[{"field_name":"status","comment":"订单状态","enum_values":[]}]}\n```'
    }
  ]
}

const enumMessage = (enumValues) => ({
  blocks: [
    {
      type: 'main_text',
      text:
        '```json\n' +
        JSON.stringify({
          table_comment: '订单表',
          fields: [{ field_name: 'status', comment: '订单状态', enum_values: enumValues }]
        }) +
        '\n```'
    }
  ]
})

describe('useMetadataGeneration agent 解析', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    settingsApiMock.getAgentSettings.mockResolvedValue({ metadataAgentId: 'agent_biz' })
    nl2sqlApiMock.listAgents.mockResolvedValue([{ agent_id: 'agent_biz' }, { agent_id: 'agent_default' }])
    nl2sqlApiMock.createTopic.mockResolvedValue({ topic_id: 'tp1' })
    nl2sqlApiMock.deliverMessage.mockResolvedValue({ task_id: 'tk1' })
    nl2sqlApiMock.getTask.mockResolvedValue({ task_status: 'finished' })
    nl2sqlApiMock.getTaskMessage.mockResolvedValue(okMessage)
    tableApiMock.profileColumnValues.mockResolvedValue([])
  })

  it('使用设置中配置的助手，而不是隐式挑选默认助手', async () => {
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).toHaveBeenCalledWith(expect.objectContaining({ agent_id: 'agent_biz' }))
    expect(nl2sqlApiMock.deliverMessage).toHaveBeenCalledWith(
      expect.objectContaining({ agent_id: 'agent_biz', topic_id: 'tp1' })
    )
    expect(metadataResult.value.table.suggestedComment).toBe('订单表')
  })

  it('未配置助手时提示去设置，且不建话题', async () => {
    settingsApiMock.getAgentSettings.mockResolvedValue({ metadataAgentId: '' })
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).not.toHaveBeenCalled()
    expect(elMessageMock.error).toHaveBeenCalledWith(expect.stringContaining('尚未配置'))
  })

  it('配置的助手已不在可用清单时提示重新选择', async () => {
    settingsApiMock.getAgentSettings.mockResolvedValue({ metadataAgentId: 'agent_removed' })
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).not.toHaveBeenCalled()
    expect(elMessageMock.error).toHaveBeenCalledWith(expect.stringContaining('agent_removed'))
  })

  it('助手目录暂时取不到时不阻断，仍用已配置的助手', async () => {
    nl2sqlApiMock.listAgents.mockRejectedValue(new Error('boom'))
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).toHaveBeenCalledWith(expect.objectContaining({ agent_id: 'agent_biz' }))
  })

  it('枚举取值只保留实测数据中出现过的值', async () => {
    tableApiMock.profileColumnValues.mockResolvedValue([
      { fieldName: 'status', values: [{ value: '0', count: 12 }, { value: '1', count: 8 }] }
    ])
    nl2sqlApiMock.getTaskMessage.mockResolvedValue(
      enumMessage([
        { value: '0', label: '待支付' },
        { value: '1', label: '已支付' },
        { value: '2', label: '已退款' }
      ])
    )
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(tableApiMock.profileColumnValues).toHaveBeenCalledWith(1, 'c1')
    expect(metadataResult.value.fields[0].suggestedComment).toBe('订单状态。枚举：0=待支付；1=已支付')
    // prompt 里带上实测取值，模型才有据可依
    expect(nl2sqlApiMock.deliverMessage.mock.calls[0][0].content).toContain('- status: 0(12)、1(8)')
  })

  it('取不到实测取值时不输出任何枚举，而不是采信模型编造的取值', async () => {
    tableApiMock.profileColumnValues.mockRejectedValue(new Error('permission denied'))
    nl2sqlApiMock.getTaskMessage.mockResolvedValue(
      enumMessage([
        { value: '0', label: '待支付' },
        { value: '1', label: '已支付' }
      ])
    )
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(metadataResult.value.fields[0].suggestedComment).toBe('订单状态')
  })

  it('后端 400 时透出 detail 而不是 axios 通用文案', async () => {
    nl2sqlApiMock.createTopic.mockRejectedValue({
      message: 'Request failed with status code 400',
      response: { data: { detail: 'agent not found' } }
    })
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(elMessageMock.error).toHaveBeenCalledWith('agent not found')
  })
})
