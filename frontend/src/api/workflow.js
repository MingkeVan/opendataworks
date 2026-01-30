import request from '@/utils/request'

export const workflowApi = {
  list(params = {}) {
    return request.get('/v1/workflows', { params })
  },

  detail(id) {
    return request.get(`/v1/workflows/${id}`)
  },

  create(data) {
    return request.post('/v1/workflows', data)
  },

  update(id, data) {
    return request.put(`/v1/workflows/${id}`, data)
  },

  publish(id, payload) {
    return request.post(`/v1/workflows/${id}/publish`, payload)
  },

  approve(id, recordId, payload) {
    return request.post(`/v1/workflows/${id}/publish/${recordId}/approve`, payload)
  },

  execute(id) {
    return request.post(`/v1/workflows/${id}/execute`)
  },

  backfill(id, payload) {
    return request.post(`/v1/workflows/${id}/backfill`, payload)
  },

  updateSchedule(id, payload) {
    return request.put(`/v1/workflows/${id}/schedule`, payload)
  },

  onlineSchedule(id) {
    return request.post(`/v1/workflows/${id}/schedule/online`)
  },

  offlineSchedule(id) {
    return request.post(`/v1/workflows/${id}/schedule/offline`)
  },

  delete(id) {
    return request.delete(`/v1/workflows/${id}`)
  }
}
