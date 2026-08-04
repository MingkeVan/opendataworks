import { describe, it, expect } from 'vitest'
import {
  getWorkflowStatusType,
  getWorkflowStatusText,
  getOperationText,
  getPublishRecordStatusType,
  getPublishRecordStatusText,
  formatDateTime,
  formatLog,
  getErrorMessage,
} from '../workflowDisplay'

describe('getErrorMessage', () => {
  it('prefers response message, then error message, then a default', () => {
    expect(getErrorMessage({ response: { data: { message: 'server says no' } } })).toBe('server says no')
    expect(getErrorMessage({ message: 'boom' })).toBe('boom')
    expect(getErrorMessage({})).toBe('操作失败，请稍后重试')
    expect(getErrorMessage(null)).toBe('操作失败，请稍后重试')
  })
})

describe('workflow status/text mappers', () => {
  it('map known keys and fall back gracefully', () => {
    expect(getWorkflowStatusType('online')).toBe('success')
    expect(getWorkflowStatusType('unknown')).toBe('info')
    expect(getWorkflowStatusText('draft')).toBe('草稿')
    expect(getWorkflowStatusText('weird')).toBe('weird')
    expect(getWorkflowStatusText(undefined)).toBe('-')

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

describe('formatLog', () => {
  it('flattens a JSON object, passes through plain text, dash for empty', () => {
    expect(formatLog('')).toBe('-')
    expect(formatLog('{"a":1,"b":2}')).toBe('a: 1, b: 2')
    expect(formatLog('plain message')).toBe('plain message')
    expect(formatLog('{bad json')).toBe('{bad json')
  })
})
