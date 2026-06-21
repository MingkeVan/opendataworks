import { describe, it, expect } from 'vitest'
import { buildFieldPayload, isFieldChanged, isOnlyCommentChanged } from '../fieldEdit'

describe('buildFieldPayload', () => {
  it('trims name/type and applies defaults', () => {
    expect(buildFieldPayload({ fieldName: '  a ', fieldType: ' int ' })).toEqual({
      fieldName: 'a',
      fieldType: 'int',
      fieldComment: '',
      isNullable: 1,
      isPrimary: 0,
      defaultValue: '',
      fieldOrder: 0,
    })
  })
  it('preserves provided nullable/primary/order', () => {
    const p = buildFieldPayload({ fieldName: 'x', fieldType: 'int', isNullable: 0, isPrimary: 1, fieldOrder: 5 })
    expect(p).toMatchObject({ isNullable: 0, isPrimary: 1, fieldOrder: 5 })
  })
})

describe('isFieldChanged', () => {
  const base = { fieldName: 'a', fieldType: 'int', fieldComment: 'c', isNullable: 1, isPrimary: 0, defaultValue: '', fieldOrder: 1 }
  it('treats a missing original as changed (new row)', () => {
    expect(isFieldChanged({ fieldName: 'a', fieldType: 'int' }, null)).toBe(true)
  })
  it('returns false when nothing changed', () => {
    expect(isFieldChanged({ ...base }, base)).toBe(false)
  })
  it('detects type/name/comment/flag/order changes', () => {
    expect(isFieldChanged({ ...base, fieldType: 'bigint' }, base)).toBe(true)
    expect(isFieldChanged({ ...base, fieldComment: 'd' }, base)).toBe(true)
    expect(isFieldChanged({ ...base, isPrimary: 1 }, base)).toBe(true)
    expect(isFieldChanged({ ...base, fieldOrder: 2 }, base)).toBe(true)
  })
})

describe('isOnlyCommentChanged', () => {
  const base = { fieldName: 'a', fieldType: 'int', fieldComment: 'c', isNullable: 1, isPrimary: 0, defaultValue: '', fieldOrder: 1 }
  it('is true when only the comment differs', () => {
    expect(isOnlyCommentChanged({ ...base, fieldComment: 'new' }, base)).toBe(true)
  })
  it('is false when a non-comment attribute also differs', () => {
    expect(isOnlyCommentChanged({ ...base, fieldComment: 'new', fieldType: 'bigint' }, base)).toBe(false)
  })
  it('is false when nothing (incl. comment) differs, and false for missing original', () => {
    expect(isOnlyCommentChanged({ ...base }, base)).toBe(false)
    expect(isOnlyCommentChanged({ ...base }, null)).toBe(false)
  })
})
