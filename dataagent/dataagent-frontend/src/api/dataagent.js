import axios from 'axios'
import { ElMessage } from 'element-plus'
import { demoAdapter } from '@/demo/mockServer'
import { isDemoMode } from '@/demo/runtime'

const dataagentRequest = axios.create({
  baseURL: '/api',
  timeout: 120000,
  // 本模块只被独立 SPA 使用（src/widget 不引用），标记头让后端在 auth 启用时
  // 消费会话 Cookie。不要把该头扩散到 widget 共用的 client 工厂里。
  headers: { 'X-ODW-Client': 'dataagent' },
  ...(isDemoMode ? { adapter: demoAdapter } : {})
})

// 认证 401 处理（跳登录页）由 SPA 入口注入，避免 api 模块反向依赖 router/store。
let unauthorizedHandler = null
export function setDataagentUnauthorizedHandler(handler) {
  unauthorizedHandler = typeof handler === 'function' ? handler : null
}

dataagentRequest.interceptors.response.use(
  (response) => {
    const payload = response.data
    if (payload && typeof payload === 'object' && payload.code === 200 && Object.prototype.hasOwnProperty.call(payload, 'data')) {
      return payload.data
    }
    return payload
  },
  (error) => {
    if (error?.response?.status === 401 && unauthorizedHandler) {
      unauthorizedHandler(error)
      error.__odwNotified = true
      return Promise.reject(error)
    }
    const message = error?.response?.data?.detail || error?.response?.data?.message || error.message || '请求失败'
    ElMessage.error(message)
    error.__odwNotified = true
    return Promise.reject(error)
  }
)

export const dataagentApi = {
  getSettings() {
    return dataagentRequest.get('/v1/nl2sql-admin/settings')
  },

  updateSettings(data) {
    return dataagentRequest.put('/v1/nl2sql-admin/settings', data)
  },

  detectModel(data) {
    return dataagentRequest.post('/v1/nl2sql-admin/model-detections', data)
  },

  listSkillDocuments() {
    return dataagentRequest.get('/v1/dataagent/skills/documents')
  },

  getSkillDocument(documentId) {
    return dataagentRequest.get(`/v1/dataagent/skills/documents/${documentId}`)
  },

  updateSkillDocument(documentId, data) {
    return dataagentRequest.put(`/v1/dataagent/skills/documents/${documentId}`, data)
  },

  updateSkillRuntime(folder, data) {
    return dataagentRequest.put(`/v1/dataagent/skills/runtime/${encodeURIComponent(folder)}`, data)
  },

  importSkill(file) {
    const formData = new FormData()
    formData.append('file', file)
    return dataagentRequest.post('/v1/dataagent/skills/imports', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  exportSkill(folder) {
    return dataagentRequest.get(`/v1/dataagent/skills/${encodeURIComponent(folder)}/export`, {
      responseType: 'blob'
    })
  },

  uninstallSkill(folder) {
    return dataagentRequest.delete(`/v1/dataagent/skills/${encodeURIComponent(folder)}`)
  },

  compareSkillDocument(documentId, data) {
    return dataagentRequest.post(`/v1/dataagent/skills/documents/${documentId}/compare`, data)
  },

  rollbackSkillDocument(documentId, versionId) {
    return dataagentRequest.post(`/v1/dataagent/skills/documents/${documentId}/versions/${versionId}/rollback`)
  },

  listAgentProfiles() {
    return dataagentRequest.get('/v1/dataagent/agents/profiles')
  },

  getAgentProfile(agentId) {
    return dataagentRequest.get(`/v1/dataagent/agents/${encodeURIComponent(agentId)}/profile`)
  },

  getAgentConfiguration(agentId) {
    return dataagentRequest.get(`/v1/dataagent/agents/${encodeURIComponent(agentId)}/configuration`)
  },

  createAgent(data) {
    return dataagentRequest.post('/v1/dataagent/agents', data)
  },

  updateAgent(agentId, data) {
    return dataagentRequest.put(`/v1/dataagent/agents/${encodeURIComponent(agentId)}`, data)
  },

  deleteAgent(agentId) {
    return dataagentRequest.delete(`/v1/dataagent/agents/${encodeURIComponent(agentId)}`)
  },

  getAgentCapabilities() {
    return dataagentRequest.get('/v1/dataagent/agents/capabilities')
  },

  listDataScopeOptions() {
    return dataagentRequest.get('/v1/dataagent/data-scope/options')
  },

  listAdminTopics(params = {}) {
    return dataagentRequest.get('/v1/nl2sql-admin/topics', { params })
  },

  listWidgetTopics(params = {}) {
    return dataagentRequest.get('/v1/nl2sql-admin/widget-topics', { params })
  },

  listWidgetUsers(params = {}) {
    return dataagentRequest.get('/v1/nl2sql-admin/widget-users', { params })
  },

  listAuthUsers(params = {}) {
    return dataagentRequest.get('/v1/nl2sql-admin/auth-users', { params })
  },

  getWidgetTopicMessages(topicId, params = {}) {
    return dataagentRequest.get(`/v1/nl2sql-admin/widget-topics/${encodeURIComponent(topicId)}/messages`, { params })
  }
}
