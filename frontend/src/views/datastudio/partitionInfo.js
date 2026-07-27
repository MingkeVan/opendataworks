// 分区信息的纯函数工具（无 Vue 依赖、无副作用，便于单测）。
//
// 背景：`data_field.is_partition` 只有平台建表路径（TableCreateService）会写入，
// 从 Doris 同步元数据时不会回填，因此同步来的表该字段恒为 0。
// 但 `data_table.partition_column` 是同步的（由 DDL 的 PARTITION BY(...) 解析而来），
// SHOW PARTITIONS 也会返回 PartitionKey。所以分区字段需要从多个来源合并判定。

/**
 * 解析分区列表达式为字段名数组。
 *
 * 入参可能是 dt、反引号包裹的 dt、以及多列形式 dt, region（各列可能带反引号）。
 * 函数表达式（如 date_trunc(dt, 'day')）无法可靠拆出列名，原样返回该片段由调用方兜底。
 */
export function parsePartitionColumnNames(partitionColumn) {
  const raw = String(partitionColumn || '').trim()
  if (!raw) return []
  return raw
    .split(',')
    .map((part) => part.trim().replace(/^`+|`+$/g, '').trim())
    .filter(Boolean)
}

/**
 * 合并多个来源判定分区字段，保持字段原始顺序。
 *
 * @param {Array} fields 表字段列表
 * @param {String} partitionColumn data_table.partition_column
 * @param {String} partitionKey SHOW PARTITIONS 返回的 PartitionKey（可选）
 */
export function resolvePartitionFields(fields, partitionColumn, partitionKey) {
  const list = Array.isArray(fields) ? fields : []
  const names = new Set(
    [...parsePartitionColumnNames(partitionColumn), ...parsePartitionColumnNames(partitionKey)].map((name) =>
      name.toLowerCase()
    )
  )
  return list.filter((field) => {
    if (Number(field?.isPartition ?? 0) === 1) return true
    const name = String(field?.fieldName || '').trim().toLowerCase()
    return !!name && names.has(name)
  })
}

/**
 * 客户端分页切片；页码越界时回落到最后一页，避免出现空白页。
 */
export function paginate(rows, currentPage, pageSize) {
  const list = Array.isArray(rows) ? rows : []
  const size = Math.max(1, Number(pageSize) || 1)
  const totalPages = Math.max(1, Math.ceil(list.length / size))
  const page = Math.min(Math.max(1, Number(currentPage) || 1), totalPages)
  const start = (page - 1) * size
  return list.slice(start, start + size)
}
