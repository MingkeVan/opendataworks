export const buildWorkflowExecutionParams = ({
  workflowId,
  pageNum,
  pageSize,
  status,
  dateRange,
  refresh = false
}) => {
  const params = {
    pageNum,
    pageSize,
    refresh
  }
  if (workflowId) params.workflowId = Number(workflowId)
  if (status) params.status = status
  if (Array.isArray(dateRange) && dateRange.length === 2) {
    params.startTime = dateRange[0]
    params.endTime = dateRange[1]
  }
  return params
}

export const getExecutionRowKey = (row) => {
  const instanceKey = row.instanceId ?? `local-${row.localExecutionLogId}`
  return `${row.workflowId}-${instanceKey}`
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
