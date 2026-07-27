import { describe, it, expect } from 'vitest'
import { paginate, parsePartitionColumnNames, resolvePartitionFields } from '../partitionInfo'

describe('partitionInfo', () => {
  it('parsePartitionColumnNames 处理反引号与多列', () => {
    expect(parsePartitionColumnNames('dt')).toEqual(['dt'])
    expect(parsePartitionColumnNames('`dt`')).toEqual(['dt'])
    expect(parsePartitionColumnNames('`dt`, `region`')).toEqual(['dt', 'region'])
    expect(parsePartitionColumnNames(' dt , region ')).toEqual(['dt', 'region'])
    expect(parsePartitionColumnNames('')).toEqual([])
    expect(parsePartitionColumnNames(null)).toEqual([])
  })

  it('resolvePartitionFields 在 isPartition 未回填时按 partitionColumn 判定', () => {
    // 同步来的表 is_partition 恒为 0，只能靠 partition_column 识别
    const fields = [
      { fieldName: 'shop_id', isPartition: 0 },
      { fieldName: 'dt', isPartition: 0 },
      { fieldName: 'amount', isPartition: 0 },
    ]
    expect(resolvePartitionFields(fields, '`dt`').map((f) => f.fieldName)).toEqual(['dt'])
  })

  it('resolvePartitionFields 兼容 isPartition 标记与多列，并保持原始顺序', () => {
    const fields = [
      { fieldName: 'region', isPartition: 0 },
      { fieldName: 'shop_id', isPartition: 1 },
      { fieldName: 'dt', isPartition: 0 },
    ]
    const names = resolvePartitionFields(fields, '`dt`, `region`').map((f) => f.fieldName)
    expect(names).toEqual(['region', 'shop_id', 'dt'])
  })

  it('resolvePartitionFields 可回退到 SHOW PARTITIONS 的 PartitionKey', () => {
    const fields = [{ fieldName: 'dt', isPartition: 0 }, { fieldName: 'amount', isPartition: 0 }]
    expect(resolvePartitionFields(fields, '', 'dt').map((f) => f.fieldName)).toEqual(['dt'])
  })

  it('resolvePartitionFields 字段名大小写不敏感', () => {
    const fields = [{ fieldName: 'DT', isPartition: 0 }]
    expect(resolvePartitionFields(fields, 'dt')).toHaveLength(1)
  })

  it('resolvePartitionFields 无分区信息时返回空', () => {
    const fields = [{ fieldName: 'a', isPartition: 0 }, { fieldName: 'b', isPartition: 0 }]
    expect(resolvePartitionFields(fields, null, null)).toEqual([])
  })

  it('paginate 按页切片', () => {
    const rows = Array.from({ length: 12 }, (_, i) => ({ n: i + 1 }))
    expect(paginate(rows, 1, 5).map((r) => r.n)).toEqual([1, 2, 3, 4, 5])
    expect(paginate(rows, 3, 5).map((r) => r.n)).toEqual([11, 12])
  })

  it('paginate 页码越界时回落到最后一页', () => {
    const rows = Array.from({ length: 6 }, (_, i) => ({ n: i + 1 }))
    expect(paginate(rows, 99, 5).map((r) => r.n)).toEqual([6])
    expect(paginate([], 3, 5)).toEqual([])
  })
})
