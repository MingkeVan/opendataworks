import request from '../utils/request'

/**
 * 手动触发全量巡检
 */
export function runInspection(data) {
  return request({
    url: '/v1/inspections/run',
    method: 'post',
    data
  })
}

/**
 * 获取巡检记录列表
 */
export function getInspectionRecords(limit = 20) {
  return request({
    url: '/v1/inspections/records',
    method: 'get',
    params: { limit }
  })
}

/**
 * 获取巡检记录详情
 */
export function getInspectionDetail(recordId) {
  return request({
    url: `/v1/inspections/records/${recordId}`,
    method: 'get'
  })
}

/**
 * 获取巡检问题列表
 */
export function getInspectionIssues(params) {
  return request({
    url: '/v1/inspections/issues',
    method: 'get',
    params
  })
}

/**
 * 更新问题状态
 */
export function updateIssueStatus(issueId, data) {
  return request({
    url: `/v1/inspections/issues/${issueId}/status`,
    method: 'put',
    data
  })
}

/**
 * 获取巡检概览统计
 */
export function getInspectionOverview() {
  return request({
    url: '/v1/inspections/overview',
    method: 'get'
  })
}
