import { describe, expect, it } from 'vitest'
import {
  formatDurationSeconds,
  formatInstanceDateTime,
  getExecutionSourceText,
  getInstanceRowKey,
  getInstanceStatusText,
  getInstanceStatusType,
  getTriggerTypeText
} from '../workflowInstanceDisplay'

describe('workflow instance display', () => {
  it('creates stable keys for Dolphin and local pre-submit rows', () => {
    expect(getInstanceRowKey({ workflowId: 1, instanceId: 88 })).toBe('1-88')
    expect(getInstanceRowKey({ workflowId: 1, localExecutionLogId: 9 })).toBe('1-local-9')
  })

  it('maps trigger labels including backfill', () => {
    expect(getTriggerTypeText('schedule')).toBe('调度')
    expect(getTriggerTypeText('backfill')).toBe('补数')
    expect(getTriggerTypeText('manual')).toBe('手动')
    expect(getTriggerTypeText('weird')).toBe('weird')
    expect(getTriggerTypeText(undefined)).toBe('-')
  })

  it('maps execution source labels', () => {
    expect(getExecutionSourceText('platform')).toBe('平台')
    expect(getExecutionSourceText('dolphin')).toBe('Dolphin')
    expect(getExecutionSourceText(undefined)).toBe('-')
  })

  it('maps normalized lowercase statuses', () => {
    expect(getInstanceStatusType('success')).toBe('success')
    expect(getInstanceStatusType('failed')).toBe('danger')
    expect(getInstanceStatusType('running')).toBe('primary')
    expect(getInstanceStatusType('???')).toBe('info')
    expect(getInstanceStatusText('running')).toBe('运行中')
    expect(getInstanceStatusText('killed')).toBe('已终止')
    expect(getInstanceStatusText(undefined)).toBe('-')
  })

  it('formats durations in seconds and falls back to a dash', () => {
    expect(formatDurationSeconds(45)).toBe('45s')
    expect(formatDurationSeconds(192)).toBe('3m 12s')
    expect(formatDurationSeconds(0)).toBe('0s')
    expect(formatDurationSeconds(undefined)).toBe('-')
    expect(formatDurationSeconds('abc')).toBe('-')
    expect(formatDurationSeconds(-1)).toBe('-')
  })

  it('passes datetimes through and dashes empties', () => {
    expect(formatInstanceDateTime('2026-08-04 10:00:00')).toBe('2026-08-04 10:00:00')
    expect(formatInstanceDateTime(null)).toBe('-')
  })
})
