// Data Studio 纯展示/判定工具：无 Vue 依赖、无副作用，纯输入输出，便于单测。
// 从 DataStudioNew.vue 的格式化簇逐字抽出（P2-2 F1），行为保持不变。

export const formatNumber = (num) => {
  if (num === null || num === undefined) return '-'
  const value = Number(num)
  if (Number.isNaN(value)) return num
  return value.toLocaleString('zh-CN')
}

export const formatRowCount = (rowCount) => {
  if (rowCount === null || rowCount === undefined) return '-'
  if (rowCount === 0) return '0'
  if (rowCount < 1000) return rowCount.toString()
  if (rowCount < 1000000) return (rowCount / 1000).toFixed(1) + 'K'
  if (rowCount < 1000000000) return (rowCount / 1000000).toFixed(1) + 'M'
  return (rowCount / 1000000000).toFixed(1) + 'B'
}

export const formatStorageSize = (size) => {
  if (size === null || size === undefined) return '-'
  if (size === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let value = size
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex++
  }
  return value >= 10 ? `${value.toFixed(0)} ${units[unitIndex]}` : `${value.toFixed(1)} ${units[unitIndex]}`
}

export const formatDuration = (ms) => {
  if (!ms) return '0ms'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
}

export const formatDateTime = (value) => {
  if (!value) return '-'
  return String(value).replace('T', ' ').split('.')[0]
}

export const abbreviateSql = (sqlText) => {
  const text = String(sqlText || '').replace(/\s+/g, ' ').trim()
  if (!text) return ''
  return text.length > 180 ? `${text.slice(0, 180)}...` : text
}

export const isAggregateTable = (table) => {
  if (!table?.tableModel) return false
  return String(table.tableModel).toUpperCase() === 'AGGREGATE'
}
