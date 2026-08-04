// 工作流实例行的展示映射与格式化纯工具，供执行监控与工作流详情「执行历史」共用。
// 纯函数、无 Vue 依赖、无副作用，便于单测。

export const getInstanceRowKey = (row) => {
  const instanceKey = row.instanceId ?? `local-${row.localExecutionLogId}`
  return `${row.workflowId}-${instanceKey}`
}

export const getInstanceStatusType = (status) => {
  const types = {
    success: 'success',
    failed: 'danger',
    running: 'primary',
    pending: 'info',
    waiting: 'warning',
    not_run: 'info',
    unavailable: 'danger',
    killed: 'warning',
    paused: 'warning'
  }
  return types[status] || 'info'
}

export const getInstanceStatusText = (status) => {
  const labels = {
    success: '成功',
    failed: '失败',
    running: '运行中',
    pending: '待执行',
    waiting: '等待执行',
    not_run: '本次未运行',
    unavailable: '状态不可用',
    killed: '已终止',
    paused: '已暂停'
  }
  return labels[status] || status || '-'
}

export const getTriggerTypeText = (type) => {
  const labels = {
    manual: '手动',
    schedule: '调度',
    backfill: '补数',
    api: 'API'
  }
  return labels[type] || type || '-'
}

export const getExecutionSourceText = (source) => {
  const labels = {
    platform: '平台',
    dolphin: 'Dolphin'
  }
  return labels[source] || source || '-'
}

export const formatInstanceDateTime = (datetime) => datetime || '-'

export const formatDurationSeconds = (seconds) => {
  const value = Number(seconds)
  if (!Number.isFinite(value) || value < 0) return '-'
  if (value < 60) return `${Math.round(value)}s`
  const minutes = Math.floor(value / 60)
  const remainingSeconds = Math.round(value % 60)
  return `${minutes}m ${remainingSeconds}s`
}
