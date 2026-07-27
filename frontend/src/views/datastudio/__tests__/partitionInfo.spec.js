import { describe, it, expect } from 'vitest'
import { paginate } from '../partitionInfo'

describe('partitionInfo', () => {
  it('paginate 按页切片', () => {
    const rows = Array.from({ length: 12 }, (_, i) => ({ n: i + 1 }))
    expect(paginate(rows, 1, 5).map((r) => r.n)).toEqual([1, 2, 3, 4, 5])
    expect(paginate(rows, 2, 5).map((r) => r.n)).toEqual([6, 7, 8, 9, 10])
    expect(paginate(rows, 3, 5).map((r) => r.n)).toEqual([11, 12])
  })

  it('paginate 页码越界时回落到最后一页', () => {
    const rows = Array.from({ length: 6 }, (_, i) => ({ n: i + 1 }))
    expect(paginate(rows, 99, 5).map((r) => r.n)).toEqual([6])
  })

  it('paginate 处理空列表与非法入参', () => {
    expect(paginate([], 3, 5)).toEqual([])
    expect(paginate(null, 1, 5)).toEqual([])
    expect(paginate([{ n: 1 }], 1, 0).map((r) => r.n)).toEqual([1])
  })
})
