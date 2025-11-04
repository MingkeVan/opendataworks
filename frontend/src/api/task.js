import request from '@/utils/request'

export const taskApi = {
  // 获取任务列表
  list(params) {
    return request.get('/v1/tasks', { params })
  },

  // 获取任务详情
  getById(id) {
    return request.get(`/v1/tasks/${id}`)
  },

  // 创建任务
  create(data) {
    return request.post('/v1/tasks', data)
  },

  // 更新任务
  update(id, data) {
    return request.put(`/v1/tasks/${id}`, data)
  },

  // 发布任务
  publish(id) {
    return request.post(`/v1/tasks/${id}/publish`)
  },

  // 执行任务（单任务测试执行）
  execute(id) {
    return request.post(`/v1/tasks/${id}/execute`)
  },

  // 执行工作流（完整工作流执行）
  executeWorkflow(id) {
    return request.post(`/v1/tasks/${id}/execute-workflow`)
  },

  // 删除任务
  delete(id) {
    return request.delete(`/v1/tasks/${id}`)
  },

  // 获取任务执行状态
  getExecutionStatus(id) {
    return request.get(`/v1/tasks/${id}/execution-status`)
  },

  // 获取任务血缘关系
  getTaskLineage(id) {
    return request.get(`/v1/tasks/${id}/lineage`)
  },

  // 获取 Dolphin 数据源列表
  fetchDatasources(params = {}) {
    return request.get('/v1/dolphin/datasources', { params })
  }
}
