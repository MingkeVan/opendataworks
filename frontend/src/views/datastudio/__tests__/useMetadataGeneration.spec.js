import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, ref } from 'vue'

const { nl2sqlApiMock, settingsApiMock, elMessageMock } = vi.hoisted(() => ({
  nl2sqlApiMock: {
    listAgents: vi.fn(),
    createTopic: vi.fn(),
    deliverMessage: vi.fn(),
    getTask: vi.fn(),
    getTaskMessage: vi.fn()
  },
  settingsApiMock: { getAgentSettings: vi.fn() },
  elMessageMock: { error: vi.fn(), success: vi.fn(), warning: vi.fn() }
}))

vi.mock('@/api/nl2sql', async () => {
  const actual = await vi.importActual('@/api/nl2sql')
  return { nl2sqlApi: nl2sqlApiMock, nl2sqlErrorMessage: actual.nl2sqlErrorMessage }
})
vi.mock('@/api/settings', () => ({ settingsApi: settingsApiMock }))
vi.mock('@/api/table', () => ({ tableApi: { updateComment: vi.fn(), updateField: vi.fn(), getFields: vi.fn() } }))
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

describe('useMetadataGeneration agent 解析', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    settingsApiMock.getAgentSettings.mockResolvedValue({ metadataAgentId: 'agent_biz' })
    nl2sqlApiMock.listAgents.mockResolvedValue([{ agent_id: 'agent_biz' }, { agent_id: 'agent_default' }])
    nl2sqlApiMock.createTopic.mockResolvedValue({ topic_id: 'tp1' })
    nl2sqlApiMock.deliverMessage.mockResolvedValue({ task_id: 'tk1' })
    nl2sqlApiMock.getTask.mockResolvedValue({ task_status: 'finished' })
    nl2sqlApiMock.getTaskMessage.mockResolvedValue(okMessage)
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
