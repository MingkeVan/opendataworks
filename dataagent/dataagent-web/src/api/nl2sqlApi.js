import axios from 'axios'

const DEFAULT_TIMEOUT = 120000

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

function unwrapResponse(response) {
  return response?.data
}

async function extractHttpError(response) {
  try {
    const data = await response.json()
    if (data?.detail) return String(data.detail)
  } catch (_e) {
    // ignore
  }
  try {
    const text = await response.text()
    if (text) return text
  } catch (_e) {
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
      if (!line) continue
      if (line.startsWith(':')) continue
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
        continue
      }
      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    }

    if (!dataLines.length) continue

    const jsonText = dataLines.join('\n')
    try {
      const evt = JSON.parse(jsonText)
      if (eventName && !evt.type) evt.type = eventName
      if (eventName) evt.sse_event = eventName
      events.push(evt)
      if (onEvent) onEvent(evt)
    } catch (_e) {
      // ignore invalid json event
    }
  }

  return { events, rest }
}

export function createNl2SqlApiClient(options = {}) {
  const baseURL = normalizeBaseUrl(options.baseURL)
  const timeout = options.timeout || DEFAULT_TIMEOUT

  const request = axios.create({
    baseURL,
    timeout
  })

  request.interceptors.response.use(
    (response) => unwrapResponse(response),
    (error) => {
      const responseMessage = error?.response?.data?.detail
      error.message = responseMessage || error.message || '网络错误'
      return Promise.reject(error)
    }
  )

  const isDoneEvent = (evt) => String(evt?.type || '') === 'done'
  const isMessageStopEvent = (evt) => String(evt?.type || '') === 'message_stop'

  return {
    createSession(title = '新会话') {
      return request.post('/api/v1/nl2sql/sessions', null, { params: { title } })
    },

    listSessions() {
      return request.get('/api/v1/nl2sql/sessions')
    },

    getSession(sessionId) {
      return request.get(`/api/v1/nl2sql/sessions/${sessionId}`)
    },

    deleteSession(sessionId) {
      return request.delete(`/api/v1/nl2sql/sessions/${sessionId}`)
    },

    sendMessage(sessionId, data) {
      return request.post(`/api/v1/nl2sql/sessions/${sessionId}/messages`, {
        ...data,
        stream: false
      })
    },

    async streamMessage(sessionId, data, options = {}) {
      const { onEvent, signal } = options
      const resp = await fetch(`${baseURL}/api/v1/nl2sql/sessions/${sessionId}/messages`, {
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

      if (!resp.ok) {
        const reason = await extractHttpError(resp)
        throw new Error(reason)
      }

      if (!resp.body) {
        throw new Error('SSE stream body is empty')
      }

      const decoder = new TextDecoder('utf-8')
      const reader = resp.body.getReader()
      let buffer = ''
      let doneEvent = null
      let sawMessageStop = null

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const parsed = parseSseChunk(buffer, onEvent)
        buffer = parsed.rest

        const maybeDone = parsed.events.find((evt) => isDoneEvent(evt))
        if (maybeDone) {
          doneEvent = maybeDone
          break
        }

        const maybeMessageStop = parsed.events.find((evt) => isMessageStopEvent(evt))
        if (maybeMessageStop) {
          sawMessageStop = maybeMessageStop
        }
      }

      if (!doneEvent && buffer.trim()) {
        const parsed = parseSseChunk(`${buffer}\n\n`, onEvent)
        const maybeDone = parsed.events.find((evt) => isDoneEvent(evt))
        if (maybeDone) {
          doneEvent = maybeDone
        }
        const maybeMessageStop = parsed.events.find((evt) => isMessageStopEvent(evt))
        if (maybeMessageStop) {
          sawMessageStop = maybeMessageStop
        }
      }

      if (!doneEvent) {
        if (sawMessageStop) {
          return sawMessageStop
        }
        throw new Error('SSE stream ended without done event')
      }
      return doneEvent
    },

    executeSql(data) {
      return request.post('/api/v1/nl2sql/execute', data)
    },

    getSettings() {
      return request.get('/api/v1/nl2sql/settings')
    },

    updateSettings(data) {
      return request.put('/api/v1/nl2sql/settings', data)
    },

    health() {
      return request.get('/api/v1/nl2sql/health')
    }
  }
}
