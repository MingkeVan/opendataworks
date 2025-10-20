import request from '@/utils/request'

export const tableApi = {
  // 获取表列表
  list(params) {
    return request.get('/v1/tables', { params })
  },

  // 获取所有表
  listAll() {
    return request.get('/v1/tables/all')
  },

  // 获取所有数据库列表
  listDatabases() {
    return request.get('/v1/tables/databases')
  },

  // 根据数据库获取表列表
  listByDatabase(database, sortField, sortOrder) {
    return request.get('/v1/tables/by-database', {
      params: { database, sortField, sortOrder }
    })
  },

  // 获取表详情
  getById(id) {
    return request.get(`/v1/tables/${id}`)
  },

  // 获取表字段
  getFields(id) {
    return request.get(`/v1/tables/${id}/fields`)
  },

  // 获取表关联任务
  getTasks(id) {
    return request.get(`/v1/tables/${id}/tasks`)
  },

  // 获取表的上下游
  getLineage(id) {
    return request.get(`/v1/tables/${id}/lineage`)
  },

  // 获取表统计信息
  getStatistics(id, clusterId = null, forceRefresh = false) {
    return request.get(`/v1/tables/${id}/statistics`, {
      params: { clusterId, forceRefresh }
    })
  },

  // 获取数据库所有表的统计信息
  getDatabaseStatistics(database, clusterId = null) {
    return request.get(`/v1/tables/statistics/database/${database}`, {
      params: { clusterId }
    })
  },

  // 获取表统计历史记录
  getStatisticsHistory(id, limit = 30) {
    return request.get(`/v1/tables/${id}/statistics/history`, {
      params: { limit }
    })
  },

  // 获取最近7天统计历史
  getLast7DaysHistory(id) {
    return request.get(`/v1/tables/${id}/statistics/history/last7days`)
  },

  // 获取最近30天统计历史
  getLast30DaysHistory(id) {
    return request.get(`/v1/tables/${id}/statistics/history/last30days`)
  },

  // 创建表
  create(data) {
    return request.post('/v1/tables', data)
  },

  // 更新表
  update(id, data) {
    return request.put(`/v1/tables/${id}`, data)
  },

  // 删除表
  delete(id) {
    return request.delete(`/v1/tables/${id}`)
  }
}
