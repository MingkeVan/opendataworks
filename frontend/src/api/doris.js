import request from '@/utils/request'

export const dorisClusterApi = {
  list() {
    return request.get('/v1/doris-clusters')
  },

  getById(id) {
    return request.get(`/v1/doris-clusters/${id}`)
  },

  create(data) {
    return request.post('/v1/doris-clusters', data)
  },

  update(id, data) {
    return request.put(`/v1/doris-clusters/${id}`, data)
  },

  remove(id) {
    return request.delete(`/v1/doris-clusters/${id}`)
  },

  setDefault(id) {
    return request.post(`/v1/doris-clusters/${id}/default`)
  },

  testConnection(id) {
    return request.post(`/v1/doris-clusters/${id}/test`)
  },

  getDatabases(id) {
    return request.get(`/v1/doris-clusters/${id}/databases`)
  },

  getTables(id, dbName) {
    return request.get(`/v1/doris-clusters/${id}/databases/${dbName}/tables`)
  }
}
