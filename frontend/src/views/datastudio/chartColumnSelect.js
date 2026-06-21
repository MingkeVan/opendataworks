// Data Studio 图表选列评分：纯函数，无 Vue 依赖、无副作用，便于单测。
// 从 DataStudioNew.vue 的图表默认选列逻辑逐字抽出（P2-2 F2），行为保持不变。
// 渲染副作用（renderChart/disposeChart）与状态读写仍留在组件。

export const scoreColumnName = (name, keywords) => {
  if (!name) return 0
  const lower = String(name).toLowerCase()
  return keywords.reduce((score, keyword) => (lower.includes(keyword) ? score + 10 : score), 0)
}

export const scoreDimensionColumn = (column) => {
  const keywords = [
    'dt', 'date', 'day', 'week', 'month', 'year', 'hour', 'time',
    'category', 'type', 'name', 'region', 'province', 'city', 'status'
  ]
  const suffixBoost = /(_dt|_date|_day|_month|_year|_time)$/i.test(String(column)) ? 15 : 0
  return scoreColumnName(column, keywords) + suffixBoost
}

export const scoreMetricColumn = (column) => {
  const keywords = [
    'count', 'cnt', 'sum', 'avg', 'mean', 'max', 'min',
    'total', 'num', 'qty', 'amount', 'amt', 'value', 'rate', 'ratio', 'pct', 'percent',
    '数量', '金额', '总', '均', '最大', '最小', '比率', '比例'
  ]
  const suffixBoost = /(_cnt|_count|_sum|_avg|_max|_min|_total)$/i.test(String(column)) ? 15 : 0
  return scoreColumnName(column, keywords) + suffixBoost
}

// 在给定列与样本行下，判定哪些列是数值列（取前 sampleSize 行采样；空串/空值视为兼容数值）。
export const detectNumericColumns = (columns, rows, sampleSize = 10) => {
  const cols = Array.isArray(columns) ? columns : []
  const rws = Array.isArray(rows) ? rows : []
  if (!rws.length || !cols.length) return []
  const sample = rws.slice(0, sampleSize)
  return cols.filter((col) => {
    return sample.every((row) => {
      const val = row?.[col]
      return val === null || val === '' || !Number.isNaN(Number(val))
    })
  })
}
