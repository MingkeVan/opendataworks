import { describe, it, expect } from 'vitest'
import { splitSqlStatements } from '../sqlStatements'

describe('splitSqlStatements', () => {
  it('splits on top-level semicolons and trims, dropping empties', () => {
    expect(splitSqlStatements('select 1; select 2 ;')).toEqual(['select 1', 'select 2'])
    expect(splitSqlStatements('  select 1  ')).toEqual(['select 1'])
    expect(splitSqlStatements(';;')).toEqual([])
    expect(splitSqlStatements('')).toEqual([])
    expect(splitSqlStatements(null)).toEqual([])
  })

  it('ignores semicolons inside single/double quoted strings', () => {
    expect(splitSqlStatements("select ';' as a; select 2")).toEqual(["select ';' as a", 'select 2'])
    expect(splitSqlStatements('select ";" as a')).toEqual(['select ";" as a'])
  })

  it('handles doubled-quote escapes inside strings', () => {
    expect(splitSqlStatements("select 'a''b;c'")).toEqual(["select 'a''b;c'"])
  })

  it('ignores semicolons inside line (--, #) and block comments', () => {
    expect(splitSqlStatements('select 1 -- a;b\n; select 2')).toEqual(['select 1 -- a;b', 'select 2'])
    expect(splitSqlStatements('select 1 # a;b\n; select 2')).toEqual(['select 1 # a;b', 'select 2'])
    expect(splitSqlStatements('select 1 /* a;b */; select 2')).toEqual(['select 1 /* a;b */', 'select 2'])
  })

  it('keeps the trailing statement without a closing semicolon', () => {
    expect(splitSqlStatements('select 1; select 2')).toEqual(['select 1', 'select 2'])
  })
})
