import { describe, expect, it } from 'vitest'
import { buildWorkflowExecutionParams } from '../executionMonitorModel'

describe('execution monitor model', () => {
  it('builds list and statistics parameters from one filter snapshot', () => {
    expect(buildWorkflowExecutionParams({
      workflowId: '12',
      pageNum: 2,
      pageSize: 20,
      status: 'failed',
      refresh: true
    })).toEqual({
      workflowId: 12,
      pageNum: 2,
      pageSize: 20,
      status: 'failed',
      refresh: true
    })
  })

  it('omits empty workflow and status filters', () => {
    expect(buildWorkflowExecutionParams({ pageNum: 1, pageSize: 10 })).toEqual({
      pageNum: 1,
      pageSize: 10,
      refresh: false
    })
  })

  it('carries no time-range parameters — the monitor is a recent-executions view', () => {
    const params = buildWorkflowExecutionParams({
      pageNum: 1,
      pageSize: 10,
      dateRange: ['2026-07-01 00:00:00', '2026-07-31 23:59:59']
    })
    expect(params).not.toHaveProperty('startTime')
    expect(params).not.toHaveProperty('endTime')
  })
})
