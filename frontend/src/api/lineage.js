import request from '@/utils/request'

export const lineageApi = {
  // 获取血缘图数据
  getLineageGraph(params = {}) {
    return request.get('/v1/lineage', { params })
  }
}
