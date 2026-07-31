import { describe, expect, it } from 'vitest'
import { demoAdapter } from '@/demo/mockServer'

const request = async (url, params = {}) => {
  const response = await demoAdapter({
    method: 'get',
    url,
    baseURL: '',
    params
  })
  return response.data.data
}

describe('execution monitor demo endpoints', () => {
  it('returns workflow rows and same-response statistics', async () => {
    const result = await request('/v1/executions/workflow-instances', {
      status: 'failed',
      pageNum: 1,
      pageSize: 10
    })

    expect(result.records).toEqual(expect.arrayContaining([
      expect.objectContaining({
        workflowId: 2,
        status: 'failed',
        expandable: true
      })
    ]))
    expect(result.statistics.totalExecutions).toBe(result.total)
    expect(result.statistics.failedCount).toBe(result.total)
  })

  it('returns task rows for an expanded workflow instance', async () => {
    const rows = await request('/v1/executions/workflows/1/instances/900006/tasks')

    expect(rows).toEqual(expect.arrayContaining([
      expect.objectContaining({
        platformTaskId: 1,
        taskName: expect.any(String)
      })
    ]))
  })
})
