import request from '@/utils/request'

const withCluster = (clusterId) =>
  clusterId === null || clusterId === undefined ? {} : { params: { clusterId } }

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
  listDatabases(clusterId = null) {
    return request.get('/v1/tables/databases', {
      params: clusterId === null || clusterId === undefined ? {} : { clusterId }
    })
  },

  // 根据数据库获取表列表
  listByDatabase(database, sortField, sortOrder, clusterId = null) {
    return request.get('/v1/tables/by-database', {
      params: {
        database,
        sortField,
        sortOrder,
        ...(clusterId === null || clusterId === undefined ? {} : { clusterId })
      }
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

  // 创建字段
  createField(tableId, data, clusterId = null) {
    return request.post(`/v1/tables/${tableId}/fields`, data, withCluster(clusterId))
  },

  // 更新字段
  updateField(tableId, fieldId, data, clusterId = null) {
    return request.put(`/v1/tables/${tableId}/fields/${fieldId}`, data, withCluster(clusterId))
  },

  // 删除字段
  deleteField(tableId, fieldId, clusterId = null) {
    return request.delete(`/v1/tables/${tableId}/fields/${fieldId}`, withCluster(clusterId))
  },

  // 获取表关联任务
  getTasks(id) {
    return request.get(`/v1/tables/${id}/tasks`)
  },

  // 获取表的上下游
  getLineage(id) {
    return request.get(`/v1/tables/${id}/lineage`)
  },

  // 搜索表下拉选项
  searchOptions(params) {
    return request.get('/v1/tables/options', { params })
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

  // 获取表的DDL（建表语句）
  getTableDdl(id, clusterId = null) {
    return request.get(`/v1/tables/${id}/ddl`, {
      params: { clusterId }
    })
  },

  // 根据表名获取表的DDL（建表语句）
  getTableDdlByName(clusterId, database, tableName) {
    return request.get('/v1/tables/ddl/by-name', {
      params: { clusterId, database, tableName }
    })
  },

  // 预览表数据
  previewTableData(id, clusterId = null, limit = 100) {
    return request.get(`/v1/tables/${id}/preview`, {
      params: { clusterId, limit }
    })
  },

  // 创建表
  create(data) {
    return request.post('/v1/tables', data)
  },

  // 更新表
  update(id, data, clusterId = null) {
    return request.put(`/v1/tables/${id}`, data, withCluster(clusterId))
  },

  // 删除表
  delete(id) {
    return request.delete(`/v1/tables/${id}`)
  },

  // 修改表注释（同时更新Doris）
  updateComment(id, comment, clusterId = null) {
    return request.put(`/v1/tables/${id}/comment`, { comment }, {
      params: { clusterId }
    })
  },

  // 软删除表（重命名为 deprecated）
  softDelete(id, clusterId = null) {
    return request.post(`/v1/tables/${id}/soft-delete`, null, {
      params: { clusterId }
    })
  },

  // 稽核/比对 Doris 元数据（只检查差异，不同步）
  auditMetadata(clusterId = null) {
    return request.post('/v1/tables/audit-metadata', null, {
      params: { clusterId }
    })
  },

  // 同步 Doris 元数据（全量同步）
  syncMetadata(clusterId = null) {
    return request.post('/v1/tables/sync-metadata', null, {
      params: { clusterId }
    })
  },

  // 同步指定数据库的元数据
  syncDatabaseMetadata(database, clusterId = null) {
    return request.post(`/v1/tables/sync-metadata/database/${database}`, null, {
      params: { clusterId }
    })
  },

  // 同步指定表的元数据
  syncTableMetadata(id, clusterId = null) {
    return request.post(`/v1/tables/${id}/sync-metadata`, null, {
      params: { clusterId }
    })
  }
}
