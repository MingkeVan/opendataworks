import axios from 'axios'

const unwrapResponse = (response) => {
  const payload = response?.data
  if (payload && typeof payload === 'object' && Object.prototype.hasOwnProperty.call(payload, 'code')) {
    if (payload.code !== 200) {
      throw new Error(payload.message || '请求失败')
    }
    return payload.data
  }
  return payload
}

export function createAssistantApiClient(options = {}) {
  const apiBase = options.apiBase || '/api'
  const streamBase = options.streamBase || apiBase
  const timeout = options.timeout || 60000

  const request = axios.create({
    baseURL: apiBase,
    timeout
  })

  request.interceptors.response.use(
    (response) => unwrapResponse(response),
    (error) => {
      const responseMessage = error?.response?.data?.message
      const message = responseMessage || error.message || '网络错误'
      error.message = message
      return Promise.reject(error)
    }
  )

  const normalizedStreamBase = streamBase.endsWith('/') ? streamBase.slice(0, -1) : streamBase

  return {
    createSession(data = {}) {
      return request.post('/v1/assistant/sessions', data)
    },

    listSessions() {
      return request.get('/v1/assistant/sessions')
    },

    getSessionDetail(sessionId) {
      return request.get(`/v1/assistant/sessions/${sessionId}`)
    },

    sendMessage(sessionId, data) {
      return request.post(`/v1/assistant/sessions/${sessionId}/messages`, data)
    },

    approveRun(runId, data) {
      return request.post(`/v1/assistant/runs/${runId}/approve`, data)
    },

    cancelRun(runId) {
      return request.post(`/v1/assistant/runs/${runId}/cancel`)
    },

    getPolicy() {
      return request.get('/v1/assistant/policy')
    },

    updatePolicy(data) {
      return request.put('/v1/assistant/policy', data)
    },

    listSkills() {
      return request.get('/v1/assistant/skills')
    },

    updateSkill(skillKey, data) {
      return request.put(`/v1/assistant/skills/${skillKey}`, data)
    },

    listQueryHistory(params) {
      return request.get('/v1/data-query/history', { params })
    },

    openRunStream(runId) {
      return new EventSource(`${normalizedStreamBase}/v1/assistant/runs/${runId}/stream`, { withCredentials: true })
    }
  }
}
