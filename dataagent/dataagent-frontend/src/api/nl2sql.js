import axios from 'axios'
import { demoAdapter } from '@/demo/mockServer'
import { isDemoMode } from '@/demo/runtime'

const DEFAULT_TIMEOUT = 120000
const RUNTIME_PREFIX = '/api/v1/nl2sql'
const ADMIN_PREFIX = '/api/v1/nl2sql-admin'
const DATAAGENT_PREFIX = '/api/v1/dataagent'

function getDefaultBaseUrl() {
  if (typeof window === 'undefined') {
    return 'http://localhost:8900'
  }
  return ''
}

function normalizeBaseUrl(baseURL) {
  if (baseURL === undefined || baseURL === null) {
    return getDefaultBaseUrl()
  }
  return String(baseURL).replace(/\/+$/, '')
}

function buildUrl(baseURL, path) {
  return `${normalizeBaseUrl(baseURL)}${path}`
}

function unwrapResponse(response) {
  const payload = response?.data
  if (payload && typeof payload === 'object' && payload.code === 200 && Object.prototype.hasOwnProperty.call(payload, 'data')) {
    return payload.data
  }
  return payload
}

async function extractHttpError(response) {
  try {
    const data = await response.clone().json()
    if (data?.detail) return String(data.detail)
  } catch (_error) {
    // ignore
  }

  try {
    const text = await response.text()
    if (text) return text
  } catch (_error) {
    // ignore
  }

  return `${response.status} ${response.statusText || 'Request failed'}`
}

function parseSseChunk(buffer, onEvent) {
  let rest = buffer

  while (true) {
    const splitAt = rest.indexOf('\n\n')
    if (splitAt < 0) break

    const rawEvent = rest.slice(0, splitAt)
    rest = rest.slice(splitAt + 2)

    let eventName = ''
    const dataLines = []
    const lines = rawEvent.split('\n').map((line) => line.trimEnd())

    for (const line of lines) {
      if (!line || line.startsWith(':')) continue
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
        continue
      }
      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    }

    if (!dataLines.length) continue

    try {
      const payload = JSON.parse(dataLines.join('\n'))
      if (eventName && !payload.type) payload.type = eventName
      if (eventName) payload.sse_event = eventName
      onEvent?.(payload)
    } catch (_error) {
      // ignore malformed chunks
    }
  }

  return rest
}

function createAxiosClient(baseURL, prefix, timeout, defaultHeaders = {}, onUnauthorized = null) {
  const request = axios.create({
    baseURL: buildUrl(baseURL, prefix),
    timeout,
    headers: { ...defaultHeaders },
    ...(isDemoMode ? { adapter: demoAdapter } : {})
  })

  request.interceptors.response.use(
    (response) => unwrapResponse(response),
    (error) => {
      const responseDetail = error?.response?.data?.detail
      const responseMessage = typeof responseDetail === 'object'
        ? responseDetail?.message
        : (responseDetail || error?.response?.data?.message)
      const responseCode = typeof responseDetail === 'object'
        ? responseDetail?.code
        : error?.response?.data?.code
      if (responseCode) error.code = String(responseCode)
      error.message = String(responseMessage || error.message || '网络错误')

      // 认证 401 交给调用方：SPA 可跳转登录页，Widget 切换到未登录状态。
      if (error?.response?.status === 401 && typeof onUnauthorized === 'function') {
        onUnauthorized(error)
      }
      return Promise.reject(error)
    }
  )

  return request
}

export function createNl2SqlApiClient(options = {}) {
  const baseURL = normalizeBaseUrl(options.baseURL)
  const timeout = options.timeout || DEFAULT_TIMEOUT
  const defaultHeaders = options.defaultHeaders || options.headers || {}
  const onUnauthorized = typeof options.onUnauthorized === 'function' ? options.onUnauthorized : null
  const runtimeRequest = createAxiosClient(baseURL, RUNTIME_PREFIX, timeout, defaultHeaders, onUnauthorized)
  const adminRequest = createAxiosClient(baseURL, ADMIN_PREFIX, timeout, defaultHeaders, onUnauthorized)
  const dataagentRequest = createAxiosClient(baseURL, DATAAGENT_PREFIX, timeout, defaultHeaders, onUnauthorized)

  const runtimeApi = {
    getConfig() {
      return runtimeRequest.get('/runtime-config')
    }
  }

  const topicApi = {
    createTopic(title = '新话题', data = {}) {
      return runtimeRequest.post('/topics', { title, ...data })
    },
    listTopics(params = {}) {
      return runtimeRequest.get('/topics', { params })
    },
    getTopic(topicId) {
      return runtimeRequest.get(`/topics/${topicId}`)
    },
    updateTopic(topicId, data) {
      return runtimeRequest.put(`/topics/${topicId}`, data)
    },
    deleteTopic(topicId) {
      return runtimeRequest.delete(`/topics/${topicId}`)
    },
    getTopicMessages(topicId, params = {}) {
      return runtimeRequest.get(`/topics/${topicId}/messages`, { params })
    },
    updateMessageFeedback(topicId, messageId, feedback = '') {
      return runtimeRequest.put(`/topics/${topicId}/messages/${messageId}/feedback`, { feedback })
    },
    generateFollowupSuggestions(topicId, messageId) {
      return runtimeRequest.post(`/topics/${topicId}/messages/${messageId}/followup-suggestions`, {})
    },
    uploadFile(topicId, file, { onUploadProgress } = {}) {
      const form = new FormData()
      form.append('file', file)
      return runtimeRequest.post(`/topics/${topicId}/files`, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress
      })
    },
    listFiles(topicId) {
      return runtimeRequest.get(`/topics/${topicId}/files`)
    },
    fileUrl(topicId, relPath, { download = false } = {}) {
      const encoded = String(relPath || '')
        .split('/')
        .map((seg) => encodeURIComponent(seg))
        .join('/')
      const query = download ? '?download=1' : ''
      return buildUrl(baseURL, `${RUNTIME_PREFIX}/topics/${topicId}/files/${encoded}${query}`)
    },
    async fetchFileText(topicId, relPath) {
      const response = await fetch(this.fileUrl(topicId, relPath), {
        credentials: 'include',
        headers: { ...defaultHeaders }
      })
      if (!response.ok) {
        throw new Error(await extractHttpError(response))
      }
      return response.text()
    },
    // 浏览器裸链接导航带不上 SPA/dataagent/widget 的自定义上下文头，文件
    // 下载统一改走 fetch -> Blob，由各聊天界面触发浏览器保存。
    async fetchFileBlob(topicId, relPath) {
      const response = await fetch(this.fileUrl(topicId, relPath), {
        credentials: 'include',
        headers: { ...defaultHeaders }
      })
      if (!response.ok) {
        throw new Error(await extractHttpError(response))
      }
      return response.blob()
    }
  }

  const taskApi = {
    deliverMessage(data) {
      return runtimeRequest.post('/tasks/deliver-message', data)
    },
    createTask(data) {
      return runtimeRequest.post('/tasks', data)
    },
    getTask(taskId) {
      return runtimeRequest.get(`/tasks/${taskId}`)
    },
    getTaskMessage(taskId) {
      return runtimeRequest.get(`/tasks/${taskId}/message`)
    },
    cancelTask(taskId) {
      return runtimeRequest.post(`/tasks/${taskId}/cancel`)
    },
    submitPermissionDecision(taskId, requestId, decision, note = '') {
      return runtimeRequest.post(`/tasks/${taskId}/permission-decision`, {
        request_id: requestId,
        decision,
        note,
      })
    },
    submitQuestionAnswer(taskId, requestId, answers = []) {
      return runtimeRequest.post(`/tasks/${taskId}/question-answer`, {
        request_id: requestId,
        answers,
      })
    },
    async streamSdkEvents(taskId, options = {}) {
      const { onRecord, signal, afterId = 0 } = options
      const response = await fetch(
        buildUrl(baseURL, `${RUNTIME_PREFIX}/tasks/${taskId}/sdk-events/stream?after_id=${encodeURIComponent(afterId)}`),
        {
          method: 'GET',
          headers: { Accept: 'text/event-stream', ...defaultHeaders },
          credentials: 'include',
          signal
        }
      )
      if (!response.ok) {
        throw new Error(await extractHttpError(response))
      }
      if (!response.body) {
        throw new Error('SSE stream body is empty')
      }
      const decoder = new TextDecoder('utf-8')
      const reader = response.body.getReader()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        buffer = parseSseChunk(buffer, onRecord)
      }
      if (buffer.trim()) {
        parseSseChunk(`${buffer}\n\n`, onRecord)
      }
    }
  }

  const queryApi = {
    executeSql({ sql, database, engine, limit, timeoutSeconds, topicId } = {}) {
      const payload = { sql, database }
      if (engine) payload.engine = engine
      if (limit) payload.limit = limit
      if (timeoutSeconds) payload.timeout_seconds = timeoutSeconds
      if (topicId) payload.topic_id = topicId
      return runtimeRequest.post('/query/execute', payload)
    }
  }

  const messageQueueApi = {
    query(data) {
      return runtimeRequest.post('/message-queue/queries', data)
    },
    create(data) {
      return runtimeRequest.post('/message-queue', data)
    },
    update(queueId, data) {
      return runtimeRequest.put(`/message-queue/${queueId}`, data)
    },
    remove(queueId) {
      return runtimeRequest.delete(`/message-queue/${queueId}`)
    },
    consume(queueId) {
      return runtimeRequest.post(`/message-queue/${queueId}/consume`)
    }
  }

  const scheduleApi = {
    query(data) {
      return runtimeRequest.post('/message-schedule/queries', data)
    },
    create(data) {
      return runtimeRequest.post('/message-schedule', data)
    },
    update(scheduleId, data) {
      return runtimeRequest.put(`/message-schedule/${scheduleId}`, data)
    },
    remove(scheduleId) {
      return runtimeRequest.delete(`/message-schedule/${scheduleId}`)
    },
    get(scheduleId) {
      return runtimeRequest.get(`/message-schedule/${scheduleId}`)
    },
    logs(scheduleId, data) {
      return runtimeRequest.post(`/message-schedule/${scheduleId}/logs`, data)
    }
  }

  const eventApi = {
    async recordEvents(events, { keepalive = false } = {}) {
      return fetch(
        buildUrl(baseURL, `${RUNTIME_PREFIX}/widget-events`),
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...defaultHeaders },
          body: JSON.stringify({ events }),
          keepalive
        }
      )
    }
  }

  const adminApi = {
    getSettings() {
      return adminRequest.get('/settings')
    },
    updateSettings(data) {
      return adminRequest.put('/settings', data)
    },
    listAdminTopics(params = {}) {
      return adminRequest.get('/topics', { params })
    }
  }

  const authApi = {
    getAuthConfig() {
      return runtimeRequest.get('/auth/config')
    },
    login(username, password) {
      return runtimeRequest.post('/auth/login', { username, password })
    },
    me() {
      return runtimeRequest.get('/auth/me')
    },
    logout() {
      return runtimeRequest.post('/auth/logout')
    },
    oauthAuthorizeUrl(redirect = '') {
      const query = redirect ? `?redirect=${encodeURIComponent(redirect)}` : ''
      return buildUrl(baseURL, `${RUNTIME_PREFIX}/auth/oauth/authorize${query}`)
    }
  }

  const agentApi = {
    listAgents() {
      return dataagentRequest.get('/agents')
    },
    getAgent(agentId) {
      return dataagentRequest.get(`/agents/${encodeURIComponent(agentId)}`)
    },
    getAgentSlashCommands(agentId) {
      return dataagentRequest.get(`/agents/${encodeURIComponent(agentId)}/slash-commands`)
    },
    createAgent(data) {
      return dataagentRequest.post('/agents', data)
    },
    updateAgent(agentId, data) {
      return dataagentRequest.put(`/agents/${encodeURIComponent(agentId)}`, data)
    },
    deleteAgent(agentId) {
      return dataagentRequest.delete(`/agents/${encodeURIComponent(agentId)}`)
    },
    getCapabilities() {
      return dataagentRequest.get('/agents/capabilities')
    }
  }

  return {
    runtimeApi,
    topicApi,
    taskApi,
    queryApi,
    messageQueueApi,
    scheduleApi,
    adminApi,
    authApi,
    agentApi,
    eventApi,
    health() {
      return runtimeRequest.get('/health')
    }
  }
}

// SPA 专用客户端标记：让后端在 auth 启用时对独立站点消费会话 Cookie。
// 只允许在 SPA 调用点使用，绝不能作为 createNl2SqlApiClient 的工厂默认值 ——
// widget（含门户嵌入页）共用该工厂，加了默认标记会破坏匿名语义。
export const DATAAGENT_CLIENT_HEADERS = Object.freeze({ 'X-ODW-Client': 'dataagent' })
