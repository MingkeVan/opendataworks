import request from '../utils/request'

/**
 * 获取控制台统计数据
 */
export function getDashboardStatistics() {
  return request({
    url: '/v1/dashboard/statistics',
    method: 'get'
  })
}
