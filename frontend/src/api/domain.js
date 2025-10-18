import request from '@/utils/request'

export const businessDomainApi = {
  list() {
    return request.get('/v1/business-domains')
  },

  create(data) {
    return request.post('/v1/business-domains', data)
  },

  update(id, data) {
    return request.put(`/v1/business-domains/${id}`, data)
  },

  remove(id) {
    return request.delete(`/v1/business-domains/${id}`)
  }
}

export const dataDomainApi = {
  list(params = {}) {
    return request.get('/v1/data-domains', { params })
  },

  create(data) {
    return request.post('/v1/data-domains', data)
  },

  update(id, data) {
    return request.put(`/v1/data-domains/${id}`, data)
  },

  remove(id) {
    return request.delete(`/v1/data-domains/${id}`)
  }
}
