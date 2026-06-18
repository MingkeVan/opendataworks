import { describe, it, expect } from 'vitest'
import {
  scoreColumnName,
  scoreDimensionColumn,
  scoreMetricColumn,
  detectNumericColumns,
} from '../chartColumnSelect'

describe('scoreColumnName', () => {
  it('adds 10 per matched keyword, case-insensitive', () => {
    expect(scoreColumnName('OrderDate', ['date'])).toBe(10)
    expect(scoreColumnName('order_date_time', ['date', 'time'])).toBe(20)
    expect(scoreColumnName('id', ['date'])).toBe(0)
    expect(scoreColumnName('', ['date'])).toBe(0)
  })
})

describe('scoreDimensionColumn / scoreMetricColumn', () => {
  it('boosts dimension-like names and suffixes', () => {
    // 'dt' keyword (10) + suffix _dt (15)
    expect(scoreDimensionColumn('order_dt')).toBe(25)
    expect(scoreDimensionColumn('region')).toBe(10)
    expect(scoreDimensionColumn('xyz')).toBe(0)
  })
  it('boosts metric-like names and suffixes', () => {
    // 'count' keyword (10) + suffix _count (15)
    expect(scoreMetricColumn('order_count')).toBe(25)
    expect(scoreMetricColumn('amount')).toBe(10)
    expect(scoreMetricColumn('label')).toBe(0)
  })
  it('ranks a metric column above a plain dimension on metric scoring', () => {
    expect(scoreMetricColumn('total_sum')).toBeGreaterThan(scoreMetricColumn('name'))
  })
})

describe('detectNumericColumns', () => {
  it('returns columns whose sampled values are all numeric (or null/empty)', () => {
    const columns = ['id', 'name', 'amount']
    const rows = [
      { id: 1, name: 'a', amount: '10' },
      { id: 2, name: 'b', amount: '' },
      { id: 3, name: 'c', amount: null },
    ]
    expect(detectNumericColumns(columns, rows)).toEqual(['id', 'amount'])
  })
  it('only samples the first sampleSize rows', () => {
    const columns = ['v']
    const rows = [{ v: '1' }, { v: '2' }, { v: 'not-a-number' }]
    expect(detectNumericColumns(columns, rows, 2)).toEqual(['v'])
    expect(detectNumericColumns(columns, rows)).toEqual([])
  })
  it('guards empty/invalid input', () => {
    expect(detectNumericColumns([], [{ a: 1 }])).toEqual([])
    expect(detectNumericColumns(['a'], [])).toEqual([])
    expect(detectNumericColumns(null, null)).toEqual([])
  })
})
