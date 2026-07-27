import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, ref } from 'vue'

const { nl2sqlApiMock, elMessageMock } = vi.hoisted(() => ({
  nl2sqlApiMock: {
    listAgents: vi.fn(),
    createTopic: vi.fn(),
    deliverMessage: vi.fn(),
    getTask: vi.fn(),
    getTaskMessage: vi.fn()
  },
  elMessageMock: { error: vi.fn(), success: vi.fn(), warning: vi.fn() }
}))

vi.mock('@/api/nl2sql', async () => {
  const actual = await vi.importActual('@/api/nl2sql')
  return { nl2sqlApi: nl2sqlApiMock, nl2sqlErrorMessage: actual.nl2sqlErrorMessage }
})
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
    nl2sqlApiMock.createTopic.mockResolvedValue({ topic_id: 'tp1' })
    nl2sqlApiMock.deliverMessage.mockResolvedValue({ task_id: 'tk1' })
    nl2sqlApiMock.getTask.mockResolvedValue({ task_status: 'finished' })
    nl2sqlApiMock.getTaskMessage.mockResolvedValue(okMessage)
  })

  it('从助手目录解析 agent_id 并透传给建话题与发消息', async () => {
    nl2sqlApiMock.listAgents.mockResolvedValue([{ agent_id: 'agent_biz' }, { agent_id: 'agent_default' }])
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    // 目录里存在默认助手时优先用它
    expect(nl2sqlApiMock.createTopic).toHaveBeenCalledWith(
      expect.objectContaining({ agent_id: 'agent_default' })
    )
    expect(nl2sqlApiMock.deliverMessage).toHaveBeenCalledWith(
      expect.objectContaining({ agent_id: 'agent_default', topic_id: 'tp1' })
    )
    expect(metadataResult.value.table.suggestedComment).toBe('订单表')
  })

  it('目录中没有默认助手时用第一个可见助手', async () => {
    nl2sqlApiMock.listAgents.mockResolvedValue([{ agent_id: 'agent_biz' }])
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).toHaveBeenCalledWith(expect.objectContaining({ agent_id: 'agent_biz' }))
  })

  it('没有可见助手时给出可操作提示，且不再建话题', async () => {
    nl2sqlApiMock.listAgents.mockResolvedValue([])
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).not.toHaveBeenCalled()
    expect(elMessageMock.error).toHaveBeenCalledWith(expect.stringContaining('没有可用的智能助手'))
  })

  it('后端 400 时透出 detail 而不是 axios 通用文案', async () => {
    nl2sqlApiMock.listAgents.mockResolvedValue([{ agent_id: 'agent_default' }])
    nl2sqlApiMock.createTopic.mockRejectedValue({
      message: 'Request failed with status code 400',
      response: { data: { detail: 'agent not found' } }
    })
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(elMessageMock.error).toHaveBeenCalledWith('agent not found')
  })
})
