import request from '@/utils/request'

export const tableDesignerApi = {
  generateTableName(data) {
    return request.post('/v1/table-designer/table-name', data)
  },

  preview(data) {
    return request.post('/v1/table-designer/preview', data)
  },

  create(data) {
    return request.post('/v1/table-designer', data)
  }
}
