import request from '@/utils/request'

export const dataQueryApi = {
  execute(data) {
    return request.post('/v1/data-query/execute', data)
  },

  history(params) {
    return request.get('/v1/data-query/history', { params })
  }
}
