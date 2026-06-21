import { describe, it, expect } from 'vitest'
import {
  cloneGlobalParamCore,
  createGlobalParamRow,
  normalizeGlobalParams,
  isGlobalParamEmpty,
  formatGlobalParamDisplay,
} from '../globalParams'

describe('cloneGlobalParamCore', () => {
  it('trims prop and applies direct/type/value defaults', () => {
    expect(cloneGlobalParamCore({ prop: '  x ' })).toEqual({
      prop: 'x',
      direct: 'IN',
      type: 'VARCHAR',
      value: '',
    })
    expect(cloneGlobalParamCore({ prop: 'p', direct: 'OUT', type: 'INT', value: 5 })).toEqual({
      prop: 'p',
      direct: 'OUT',
      type: 'INT',
      value: 5,
    })
    expect(cloneGlobalParamCore()).toMatchObject({ prop: '', direct: 'IN', type: 'VARCHAR', value: '' })
  })
})

describe('createGlobalParamRow', () => {
  it('wraps core fields with editing/new/backup ui flags', () => {
    const row = createGlobalParamRow({ prop: 'a', value: 1 }, { editing: true, isNew: true })
    expect(row).toMatchObject({ prop: 'a', value: 1, __editing: true, __isNew: true, __backup: null })
  })
})

describe('normalizeGlobalParams', () => {
  it('maps an array to rows, and returns [] for non-arrays', () => {
    const rows = normalizeGlobalParams([{ prop: 'a' }, { prop: 'b' }])
    expect(rows).toHaveLength(2)
    expect(rows[0]).toMatchObject({ prop: 'a', __editing: false, __isNew: false })
    expect(normalizeGlobalParams(null)).toEqual([])
    expect(normalizeGlobalParams(undefined)).toEqual([])
  })
})

describe('isGlobalParamEmpty / formatGlobalParamDisplay', () => {
  it('treats null/undefined/empty-string as empty', () => {
    expect(isGlobalParamEmpty(null)).toBe(true)
    expect(isGlobalParamEmpty(undefined)).toBe(true)
    expect(isGlobalParamEmpty('')).toBe(true)
    expect(isGlobalParamEmpty(0)).toBe(false)
    expect(isGlobalParamEmpty('x')).toBe(false)
  })
  it('formats empty as dash, otherwise stringifies', () => {
    expect(formatGlobalParamDisplay('')).toBe('-')
    expect(formatGlobalParamDisplay(null)).toBe('-')
    expect(formatGlobalParamDisplay(0)).toBe('0')
    expect(formatGlobalParamDisplay('abc')).toBe('abc')
  })
})
