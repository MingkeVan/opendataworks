import request from '../utils/request'

/**
 * 查询任务执行历史 (分页)
 */
export function getExecutionHistory(params) {
  return request({
    url: '/v1/executions/history',
    method: 'get',
    params
  })
}

/**
 * 获取单个执行记录详情
 */
export function getExecutionDetail(id) {
  return request({
    url: `/v1/executions/${id}`,
    method: 'get'
  })
}

/**
 * 获取任务的最近执行记录
 */
export function getRecentExecutions(taskId, limit = 10) {
  return request({
    url: '/v1/executions/recent',
    method: 'get',
    params: { taskId, limit }
  })
}

/**
 * 同步执行状态 - 从 DolphinScheduler 获取最新状态
 */
export function syncExecutionStatus(id) {
  return request({
    url: `/v1/executions/${id}/sync`,
    method: 'post'
  })
}

/**
 * 获取任务执行统计信息
 */
export function getExecutionStatistics(params) {
  return request({
    url: '/v1/executions/statistics',
    method: 'get',
    params
  })
}

/**
 * 获取失败任务列表
 */
export function getFailedExecutions(limit = 50) {
  return request({
    url: '/v1/executions/failed',
    method: 'get',
    params: { limit }
  })
}

/**
 * 获取正在运行的任务
 */
export function getRunningExecutions() {
  return request({
    url: '/v1/executions/running',
    method: 'get'
  })
}

/**
 * 创建执行日志记录
 */
export function createExecutionLog(data) {
  return request({
    url: '/v1/executions',
    method: 'post',
    data
  })
}
