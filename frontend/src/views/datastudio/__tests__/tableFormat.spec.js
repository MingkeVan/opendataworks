import { describe, it, expect } from 'vitest'
import {
  formatNumber,
  formatRowCount,
  formatStorageSize,
  formatDuration,
  formatDateTime,
  abbreviateSql,
  isAggregateTable,
  getLayerType,
  isReplicaWarning,
} from '../tableFormat'

describe('formatNumber', () => {
  it('returns dash for null/undefined and passes through NaN-like input', () => {
    expect(formatNumber(null)).toBe('-')
    expect(formatNumber(undefined)).toBe('-')
    expect(formatNumber('abc')).toBe('abc')
  })
  it('groups numbers with zh-CN locale', () => {
    expect(formatNumber(1234567)).toBe('1,234,567')
    expect(formatNumber(0)).toBe('0')
  })
})

describe('formatRowCount', () => {
  it('handles null/zero/small', () => {
    expect(formatRowCount(null)).toBe('-')
    expect(formatRowCount(0)).toBe('0')
    expect(formatRowCount(999)).toBe('999')
  })
  it('abbreviates with K/M/B', () => {
    expect(formatRowCount(1500)).toBe('1.5K')
    expect(formatRowCount(2_500_000)).toBe('2.5M')
    expect(formatRowCount(3_200_000_000)).toBe('3.2B')
  })
})

describe('formatStorageSize', () => {
  it('handles null/zero', () => {
    expect(formatStorageSize(null)).toBe('-')
    expect(formatStorageSize(0)).toBe('0 B')
  })
  it('scales units and switches precision at >=10', () => {
    expect(formatStorageSize(5)).toBe('5.0 B')
    expect(formatStorageSize(512)).toBe('512 B')
    expect(formatStorageSize(2048)).toBe('2.0 KB')
    expect(formatStorageSize(15 * 1024 * 1024)).toBe('15 MB')
  })
})

describe('formatDuration', () => {
  it('formats ms and seconds, 0 for falsy', () => {
    expect(formatDuration(0)).toBe('0ms')
    expect(formatDuration(null)).toBe('0ms')
    expect(formatDuration(250)).toBe('250ms')
    expect(formatDuration(1500)).toBe('1.50s')
  })
})

describe('formatDateTime', () => {
  it('normalizes ISO timestamps and drops fractional seconds', () => {
    expect(formatDateTime(null)).toBe('-')
    expect(formatDateTime('2026-06-18T07:30:00.123')).toBe('2026-06-18 07:30:00')
    expect(formatDateTime('2026-06-18 07:30:00')).toBe('2026-06-18 07:30:00')
  })
})

describe('abbreviateSql', () => {
  it('collapses whitespace and truncates beyond 180 chars', () => {
    expect(abbreviateSql('  select \n  1 ')).toBe('select 1')
    expect(abbreviateSql(null)).toBe('')
    const long = 'a'.repeat(200)
    const out = abbreviateSql(long)
    expect(out.endsWith('...')).toBe(true)
    expect(out.length).toBe(183)
  })
})

describe('isAggregateTable', () => {
  it('detects AGGREGATE model case-insensitively', () => {
    expect(isAggregateTable({ tableModel: 'aggregate' })).toBe(true)
    expect(isAggregateTable({ tableModel: 'UNIQUE' })).toBe(false)
    expect(isAggregateTable({})).toBe(false)
    expect(isAggregateTable(null)).toBe(false)
  })
})

describe('getLayerType', () => {
  it('maps known data layers to Element Plus tag types', () => {
    expect(getLayerType('ODS')).toBe('info')
    expect(getLayerType('DWD')).toBe('success')
    expect(getLayerType('DIM')).toBe('warning')
    expect(getLayerType('DWS')).toBe('primary')
    expect(getLayerType('ADS')).toBe('danger')
  })

  it('uses info by default and allows callers to preserve a custom fallback', () => {
    expect(getLayerType('UNKNOWN')).toBe('info')
    expect(getLayerType('UNKNOWN', '')).toBe('')
  })
})

describe('isReplicaWarning', () => {
  it('flags positive replica counts below 3', () => {
    expect(isReplicaWarning(1)).toBe(true)
    expect(isReplicaWarning('2')).toBe(true)
  })

  it('does not flag empty, non-numeric, zero, or healthy replica counts', () => {
    expect(isReplicaWarning(null)).toBe(false)
    expect(isReplicaWarning(undefined)).toBe(false)
    expect(isReplicaWarning('')).toBe(false)
    expect(isReplicaWarning('abc')).toBe(false)
    expect(isReplicaWarning(0)).toBe(false)
    expect(isReplicaWarning(3)).toBe(false)
  })
})
