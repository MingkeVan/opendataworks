import { describe, expect, it } from 'vitest'
import {
  buildWorkflowExecutionParams,
  getExecutionRowKey,
  getExecutionSourceText,
  getTriggerTypeText
} from '../executionMonitorModel'

describe('execution monitor model', () => {
  it('builds list and statistics parameters from one filter snapshot', () => {
    expect(buildWorkflowExecutionParams({
      workflowId: '12',
      pageNum: 2,
      pageSize: 20,
      status: 'failed',
      dateRange: ['2026-07-01 00:00:00', '2026-07-31 23:59:59'],
      refresh: true
    })).toEqual({
      workflowId: 12,
      pageNum: 2,
      pageSize: 20,
      status: 'failed',
      startTime: '2026-07-01 00:00:00',
      endTime: '2026-07-31 23:59:59',
      refresh: true
    })
  })

  it('creates stable keys for Dolphin and local pre-submit rows', () => {
    expect(getExecutionRowKey({ workflowId: 1, instanceId: 88 })).toBe('1-88')
    expect(getExecutionRowKey({ workflowId: 1, localExecutionLogId: 9 })).toBe('1-local-9')
  })

  it('maps trigger and source labels', () => {
    expect(getTriggerTypeText('schedule')).toBe('调度')
    expect(getTriggerTypeText('backfill')).toBe('补数')
    expect(getExecutionSourceText('platform')).toBe('平台')
    expect(getExecutionSourceText('dolphin')).toBe('Dolphin')
  })
})
