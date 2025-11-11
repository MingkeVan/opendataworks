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
  }
}
