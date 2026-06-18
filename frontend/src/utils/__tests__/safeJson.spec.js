import { describe, it, expect } from 'vitest'
import { safeJsonParse } from '../safeJson'

describe('safeJsonParse', () => {
  it('parses valid JSON', () => {
    expect(safeJsonParse('{"a":1}')).toEqual({ a: 1 })
    expect(safeJsonParse('[1,2,3]')).toEqual([1, 2, 3])
  })

  it('returns the fallback on invalid JSON', () => {
    expect(safeJsonParse('{bad json', { ok: false })).toEqual({ ok: false })
    expect(safeJsonParse('not json')).toBeNull()
  })

  it('returns the fallback for non-string or empty input', () => {
    expect(safeJsonParse(null, [])).toEqual([])
    expect(safeJsonParse(undefined, [])).toEqual([])
    expect(safeJsonParse('', 'fallback')).toBe('fallback')
    expect(safeJsonParse('   ', 'fallback')).toBe('fallback')
    expect(safeJsonParse(42, 'fallback')).toBe('fallback')
  })

  it('defaults the fallback to null', () => {
    expect(safeJsonParse('oops')).toBeNull()
  })
})
