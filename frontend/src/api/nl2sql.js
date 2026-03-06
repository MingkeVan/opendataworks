import axios from 'axios'

const DEFAULT_TIMEOUT = 120000
const API_PREFIX = '/api/v1/nl2sql'

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
  return response?.data
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
  const events = []
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
      events.push(payload)
      onEvent?.(payload)
    } catch (_error) {
      // ignore malformed chunks
    }
  }

  return { events, rest }
}

export function createNl2SqlApiClient(options = {}) {
  const baseURL = normalizeBaseUrl(options.baseURL)
  const timeout = options.timeout || DEFAULT_TIMEOUT

  const request = axios.create({
    baseURL: buildUrl(baseURL, API_PREFIX),
    timeout
  })

  request.interceptors.response.use(
    (response) => unwrapResponse(response),
    (error) => {
      const responseMessage = error?.response?.data?.detail || error?.response?.data?.message
      error.message = responseMessage || error.message || '网络错误'
      return Promise.reject(error)
    }
  )

  const isDoneEvent = (event) => String(event?.type || '') === 'done'
  const isMessageStopEvent = (event) => String(event?.type || '') === 'message_stop'

  return {
    createSession(title = '新会话') {
      return request.post('/sessions', null, { params: { title } })
    },

    listSessions() {
      return request.get('/sessions')
    },

    getSession(sessionId) {
      return request.get(`/sessions/${sessionId}`)
    },

    deleteSession(sessionId) {
      return request.delete(`/sessions/${sessionId}`)
    },

    sendMessage(sessionId, data) {
      return request.post(`/sessions/${sessionId}/messages`, {
        ...data,
        stream: false
      })
    },

    async streamMessage(sessionId, data, options = {}) {
      const { onEvent, signal } = options
      const response = await fetch(buildUrl(baseURL, `${API_PREFIX}/sessions/${sessionId}/messages`), {
        method: 'POST',
        headers: {
          Accept: 'text/event-stream',
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          ...data,
          stream: true
        }),
        signal
      })

      if (!response.ok) {
        throw new Error(await extractHttpError(response))
      }
      if (!response.body) {
        throw new Error('SSE stream body is empty')
      }

      const decoder = new TextDecoder('utf-8')
      const reader = response.body.getReader()
      let buffer = ''
      let doneEvent = null
      let messageStopEvent = null

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const parsed = parseSseChunk(buffer, onEvent)
        buffer = parsed.rest

        doneEvent = parsed.events.find(isDoneEvent) || doneEvent
        messageStopEvent = parsed.events.find(isMessageStopEvent) || messageStopEvent

        if (doneEvent) break
      }

      if (!doneEvent && buffer.trim()) {
        const parsed = parseSseChunk(`${buffer}\n\n`, onEvent)
        doneEvent = parsed.events.find(isDoneEvent) || doneEvent
        messageStopEvent = parsed.events.find(isMessageStopEvent) || messageStopEvent
      }

      if (doneEvent) return doneEvent
      if (messageStopEvent) return messageStopEvent

      throw new Error('SSE stream ended without done event')
    },
    getSettings() {
      return request.get('/settings')
    },

    updateSettings(data) {
      return request.put('/settings', data)
    },

    health() {
      return request.get('/health')
    }
  }
}
