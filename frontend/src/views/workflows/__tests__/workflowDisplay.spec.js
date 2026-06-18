import { describe, it, expect } from 'vitest'
import {
  getWorkflowStatusType,
  getWorkflowStatusText,
  getInstanceStateType,
  getInstanceStateText,
  getTriggerText,
  getOperationText,
  getPublishRecordStatusType,
  getPublishRecordStatusText,
  formatDateTime,
  formatDuration,
  formatLog,
} from '../workflowDisplay'

describe('workflow status/text mappers', () => {
  it('map known keys and fall back gracefully', () => {
    expect(getWorkflowStatusType('online')).toBe('success')
    expect(getWorkflowStatusType('unknown')).toBe('info')
    expect(getWorkflowStatusText('draft')).toBe('草稿')
    expect(getWorkflowStatusText('weird')).toBe('weird')
    expect(getWorkflowStatusText(undefined)).toBe('-')

    expect(getInstanceStateType('FAILED')).toBe('danger')
    expect(getInstanceStateType('???')).toBe('info')
    expect(getInstanceStateText('RUNNING')).toBe('运行中')

    expect(getTriggerText('schedule')).toBe('调度')
    expect(getTriggerText('x')).toBe('x')
    expect(getOperationText('deploy')).toBe('部署')

    expect(getPublishRecordStatusType('pending_approval')).toBe('warning')
    expect(getPublishRecordStatusType('rejected')).toBe('danger')
    expect(getPublishRecordStatusText('pending')).toBe('进行中')
    expect(getPublishRecordStatusText(undefined)).toBe('-')
  })
})

describe('formatDateTime', () => {
  it('formats a timestamp, dash for empty', () => {
    expect(formatDateTime('2026-06-18T07:30:00')).toBe('2026-06-18 07:30:00')
    expect(formatDateTime(null)).toBe('-')
    expect(formatDateTime('')).toBe('-')
  })
})

describe('formatDuration', () => {
  it('uses durationMs, derives from start/end, and formats min/sec', () => {
    expect(formatDuration(0)).toBe('-')
    expect(formatDuration(5000)).toBe('5秒')
    expect(formatDuration(125000)).toBe('2分5秒')
    expect(formatDuration(null, '2026-06-18T07:00:00', '2026-06-18T07:01:30')).toBe('1分30秒')
  })
})

describe('formatLog', () => {
  it('flattens a JSON object, passes through plain text, dash for empty', () => {
    expect(formatLog('')).toBe('-')
    expect(formatLog('{"a":1,"b":2}')).toBe('a: 1, b: 2')
    expect(formatLog('plain message')).toBe('plain message')
    expect(formatLog('{bad json')).toBe('{bad json')
  })
})
