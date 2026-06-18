// WorkflowDetail 展示映射与格式化纯工具（同 DataStudio P2-2 套路）。
// 纯函数、无 Vue 依赖、无副作用，便于单测。从 WorkflowDetail.vue 逐字抽出，行为不变。
import dayjs from 'dayjs'

export const getWorkflowStatusType = (status) => {
  const map = {
    draft: 'info',
    online: 'success',
    offline: 'warning',
    failed: 'danger'
  }
  return map[status] || 'info'
}

export const getWorkflowStatusText = (status) => {
  const map = {
    draft: '草稿',
    online: '在线',
    offline: '下线',
    failed: '失败'
  }
  return map[status] || status || '-'
}

export const getInstanceStateType = (state) => {
  const map = {
    SUCCESS: 'success',
    FAILED: 'danger',
    RUNNING: 'warning',
    STOP: 'info',
    KILL: 'info'
  }
  return map[state] || 'info'
}

export const getInstanceStateText = (state) => {
  const map = {
    SUCCESS: '成功',
    FAILED: '失败',
    RUNNING: '运行中',
    STOP: '终止',
    KILL: '被终止'
  }
  return map[state] || state || '-'
}

export const getTriggerText = (type) => {
  const map = {
    manual: '手动',
    schedule: '调度',
    api: 'API'
  }
  return map[type] || type || '-'
}

export const getOperationText = (operation) => {
  const map = {
    deploy: '部署',
    online: '上线',
    offline: '下线'
  }
  return map[operation] || operation || '-'
}

export const getPublishRecordStatusType = (status) => {
  const map = {
    success: 'success',
    failed: 'danger',
    pending: 'info',
    pending_approval: 'warning',
    rejected: 'danger'
  }
  return map[status] || 'info'
}

export const getPublishRecordStatusText = (status) => {
  const map = {
    success: '成功',
    failed: '失败',
    pending: '进行中',
    pending_approval: '待审批',
    rejected: '已拒绝'
  }
  return map[status] || status || '-'
}

export const formatDateTime = (value) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

export const formatDuration = (durationMs, startTime, endTime) => {
  let duration = durationMs
  if (!duration && startTime && endTime) {
    duration = dayjs(endTime).diff(dayjs(startTime))
  }
  if (!duration) {
    return '-'
  }
  const seconds = Math.floor(duration / 1000)
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60
  return minutes ? `${minutes}分${remainSeconds}秒` : `${remainSeconds}秒`
}

export const formatLog = (log) => {
  if (!log) {
    return '-'
  }
  try {
    const parsed = JSON.parse(log)
    if (parsed && typeof parsed === 'object') {
      return Object.entries(parsed)
        .map(([key, value]) => `${key}: ${value}`)
        .join(', ')
    }
    return log
  } catch (error) {
    return log
  }
}
