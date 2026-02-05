import request from '@/utils/request'

export const dataQueryApi = {
  execute(data, config = {}) {
    return request.post('/v1/data-query/execute', data, { timeout: 300000, skipErrorMessage: true, ...config })
  },

  stop(data, config = {}) {
    return request.post('/v1/data-query/stop', data, { timeout: 10000, skipErrorMessage: true, ...config })
  },

  history(params) {
    return request.get('/v1/data-query/history', { params })
  }
}
