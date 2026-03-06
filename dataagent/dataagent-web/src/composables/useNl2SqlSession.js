import { computed, ref } from 'vue'

function normalizeMessage(message) {
  if (!message || typeof message !== 'object') return null
  return {
    message_id: String(message.message_id || ''),
    role: String(message.role || 'assistant'),
    content: String(message.content || ''),
    status: String(message.status || 'success'),
    stop_reason: message.stop_reason ? String(message.stop_reason) : '',
    stop_sequence: message.stop_sequence ? String(message.stop_sequence) : '',
    run_id: message.run_id ? String(message.run_id) : '',
    blocks: Array.isArray(message.blocks) ? message.blocks.map(normalizeBlock).filter(Boolean) : [],
    sql: String(message.sql || ''),
    execution: message.execution && typeof message.execution === 'object' ? message.execution : null,
    error: message.error && typeof message.error === 'object' ? message.error : null,
    resolved_database: message.resolved_database ? String(message.resolved_database) : null,
    provider_id: message.provider_id ? String(message.provider_id) : null,
    model: message.model ? String(message.model) : null,
    created_at: String(message.created_at || new Date().toISOString())
  }
}

function normalizeBlock(block, fallback = {}) {
  if (!block || typeof block !== 'object') return null
  const merged = { ...fallback, ...block }
  return {
    block_id: String(merged.block_id || `b_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`),
    type: String(merged.type || 'unknown'),
    status: String(merged.status || 'success'),
    text: typeof merged.text === 'string' ? merged.text : '',
    tool_name: merged.tool_name ? String(merged.tool_name) : '',
    tool_id: merged.tool_id ? String(merged.tool_id) : '',
    input: merged.input ?? null,
    output: merged.output ?? null,
    payload: merged.payload && typeof merged.payload === 'object' ? merged.payload : {}
  }
}

function applyDoneBlocks(targetMessage, blocks) {
  if (!Array.isArray(blocks) || !targetMessage) return
  for (const raw of blocks) {
    const normalized = normalizeBlock(raw)
    if (!normalized) continue

    if (normalized.type === 'error') {
      const duplicateError = targetMessage.blocks.find((item) => {
        if (!item || item.type !== 'error') return false
        const left = String(item.text || '').trim()
        const right = String(normalized.text || '').trim()
        return Boolean(left) && left === right
      })
      if (duplicateError) {
        continue
      }
    }

    const idx = targetMessage.blocks.findIndex((item) => item.block_id === normalized.block_id)
    if (idx >= 0) {
      targetMessage.blocks[idx] = {
        ...targetMessage.blocks[idx],
        ...normalized
      }
      continue
    }
    targetMessage.blocks.push(normalized)
  }
}

function ensureBlock(message, blockId, blockType, defaults = {}) {
  let idx = message.blocks.findIndex((item) => item.block_id === blockId)
  if (idx < 0) {
    const block = normalizeBlock(
      {
        block_id: blockId,
        type: blockType,
        status: 'streaming',
        ...defaults
      },
      defaults
    )
    message.blocks.push(block)
    idx = message.blocks.length - 1
  }
  return message.blocks[idx]
}

function appendText(target, delta) {
  const incoming = String(delta || '')
  if (!incoming) return target
  const current = String(target || '')
  if (!current) return incoming
  if (incoming === current) return current
  if (incoming.startsWith(current)) return incoming
  return `${current}${incoming}`
}

function normalizeErrorPayload(payload) {
  if (!payload || typeof payload !== 'object') return { code: 'unknown_error', message: '请求失败' }
  return {
    code: String(payload.code || 'model_call_failed'),
    message: String(payload.message || '请求失败'),
    detail: payload.detail || ''
  }
}

function parseMaybeJson(text) {
  const source = String(text || '').trim()
  if (!source) return null
  try {
    return JSON.parse(source)
  } catch (_error) {
    return null
  }
}

function buildMessageLimitText(limitPayload) {
  const windows = limitPayload && typeof limitPayload === 'object' ? limitPayload.windows || {} : {}
  const win5h = windows?.['5h'] || windows?.five_hour || null
  const utilization = Number(win5h?.utilization ?? 0)
  const status = String(limitPayload?.type || limitPayload?.status || 'within_limit')
  if (Number.isFinite(utilization) && utilization > 0) {
    return `配额状态: ${status} · 5h 使用率 ${(utilization * 100).toFixed(0)}%`
  }
  return `配额状态: ${status}`
}

function mapClaudeBlockType(type) {
  const lower = String(type || '').toLowerCase()
  if (lower === 'text') return 'main_text'
  if (lower === 'thinking') return 'thinking'
  if (lower === 'tool_use' || lower.includes('tooluse')) return 'tool_use'
  if (lower === 'tool_result' || lower.includes('toolresult')) return 'tool_result'
  return 'raw'
}

export function useNl2SqlSession(nl2sqlApi) {
  if (!nl2sqlApi) {
    throw new Error('nl2sqlApi client is required')
  }

  const loading = ref(false)
  const generating = ref(false)
  const streamTick = ref(0)
  const sessions = ref([])
  const activeSessionId = ref('')
  const hydratedSessionIds = new Set()
  const seenErrorKeys = new Set()

  const activeSession = computed(() => sessions.value.find((item) => item.session_id === activeSessionId.value) || null)
  const activeMessages = computed(() => activeSession.value?.messages || [])

  const findSession = (sessionId) => sessions.value.find((item) => item.session_id === sessionId) || null

  const sortSessions = () => {
    sessions.value.sort((a, b) => {
      const ta = new Date(a.updated_at || a.created_at || 0).getTime()
      const tb = new Date(b.updated_at || b.created_at || 0).getTime()
      return tb - ta
    })
  }

  const normalizeSession = (session) => ({
    session_id: String(session?.session_id || ''),
    title: String(session?.title || '新会话'),
    message_count: Number(session?.message_count || 0),
    last_message_preview: String(session?.last_message_preview || ''),
    created_at: String(session?.created_at || new Date().toISOString()),
    updated_at: String(session?.updated_at || new Date().toISOString()),
    messages: Array.isArray(session?.messages) ? session.messages.map(normalizeMessage).filter(Boolean) : []
  })

  const hydrateSessionMessages = async (sessionId, force = false) => {
    if (!sessionId) return null
    if (!force && hydratedSessionIds.has(sessionId)) {
      return findSession(sessionId)
    }

    const detail = await nl2sqlApi.getSession(sessionId)
    const target = findSession(sessionId)
    if (target) {
      const normalized = normalizeSession(detail)
      target.title = normalized.title
      target.created_at = normalized.created_at
      target.updated_at = normalized.updated_at
      target.message_count = normalized.messages.length
      target.last_message_preview = normalized.last_message_preview
      target.messages = normalized.messages
    } else if (detail) {
      sessions.value.unshift(normalizeSession(detail))
    }
    hydratedSessionIds.add(sessionId)
    sortSessions()
    return findSession(sessionId)
  }

  const loadSessions = async () => {
    loading.value = true
    try {
      const list = await nl2sqlApi.listSessions()
      sessions.value = (Array.isArray(list) ? list : []).map(normalizeSession)
      sortSessions()
      if (!activeSessionId.value && sessions.value.length) {
        activeSessionId.value = sessions.value[0].session_id
      }
      if (activeSessionId.value) {
        await hydrateSessionMessages(activeSessionId.value)
      }
    } finally {
      loading.value = false
    }
  }

  const createSession = async (title = '新会话') => {
    const session = normalizeSession(await nl2sqlApi.createSession(title))
    sessions.value.unshift(session)
    hydratedSessionIds.add(session.session_id)
    activeSessionId.value = session.session_id
    sortSessions()
    return session
  }

  const selectSession = async (sessionId) => {
    activeSessionId.value = sessionId
    await hydrateSessionMessages(sessionId)
  }

  const deleteSession = async (sessionId) => {
    await nl2sqlApi.deleteSession(sessionId)
    const idx = sessions.value.findIndex((item) => item.session_id === sessionId)
    if (idx >= 0) sessions.value.splice(idx, 1)
    hydratedSessionIds.delete(sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = sessions.value[0]?.session_id || ''
      if (activeSessionId.value) {
        await hydrateSessionMessages(activeSessionId.value)
      }
    }
  }

  const applyStreamEvent = (assistantMessage, evt) => {
    if (!assistantMessage || !evt || typeof evt !== 'object') return
    streamTick.value += 1

    const payload = evt.payload && typeof evt.payload === 'object' ? evt.payload : {}
    const evtType = String(evt.type || '')

    if (evt.run_id) assistantMessage.run_id = String(evt.run_id)
    if (evt.message_id) assistantMessage.message_id = String(evt.message_id)
    if (payload.provider_id) assistantMessage.provider_id = String(payload.provider_id)
    if (payload.model) assistantMessage.model = String(payload.model)
    if (payload.resolved_database) assistantMessage.resolved_database = String(payload.resolved_database)

    const ensureClaudeBlock = (index, claudeType, defaults = {}) => {
      const safeIndex = Number.isInteger(Number(index)) ? Number(index) : -1
      const blockId = safeIndex >= 0 ? `cb-${safeIndex}` : `cb-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`
      const mappedType = mapClaudeBlockType(claudeType)
      const block = ensureBlock(assistantMessage, blockId, mappedType, defaults)
      block.type = mappedType
      block.payload = {
        ...(block.payload && typeof block.payload === 'object' ? block.payload : {}),
        claude_index: safeIndex,
        claude_type: String(claudeType || block.payload?.claude_type || ''),
        ...(defaults.payload && typeof defaults.payload === 'object' ? defaults.payload : {})
      }
      return block
    }

    // Claude SSE compatible protocol
    if (
      evtType === 'message_start' ||
      evtType === 'content_block_start' ||
      evtType === 'content_block_delta' ||
      evtType === 'content_block_stop' ||
      evtType === 'message_delta' ||
      evtType === 'message_limit' ||
      evtType === 'message_stop' ||
      evtType === 'ping'
    ) {
      if (evtType === 'ping') return

      if (evtType === 'message_start') {
        const messagePayload = (evt.message && typeof evt.message === 'object')
          ? evt.message
          : (payload.message && typeof payload.message === 'object' ? payload.message : {})
        if (messagePayload.id) assistantMessage.message_id = String(messagePayload.id)
        if (messagePayload.model) assistantMessage.model = String(messagePayload.model)
        if (typeof messagePayload.stop_reason === 'string') assistantMessage.stop_reason = messagePayload.stop_reason
        if (typeof messagePayload.stop_sequence === 'string') assistantMessage.stop_sequence = messagePayload.stop_sequence
        assistantMessage.status = 'streaming'
        return
      }

      if (evtType === 'content_block_start') {
        const index = evt.index ?? payload.index
        const blockPayload = (evt.content_block && typeof evt.content_block === 'object')
          ? evt.content_block
          : (payload.content_block && typeof payload.content_block === 'object' ? payload.content_block : {})
        const claudeType = String(blockPayload.type || 'unknown')
        const block = ensureClaudeBlock(index, claudeType, { payload: blockPayload })
        block.status = 'streaming'

        if (claudeType === 'text' && typeof blockPayload.text === 'string' && blockPayload.text) {
          block.text = blockPayload.text
          assistantMessage.content = appendText(assistantMessage.content, blockPayload.text)
        }
        if (claudeType === 'thinking' && typeof blockPayload.thinking === 'string' && blockPayload.thinking) {
          block.text = blockPayload.thinking
        }
        if (claudeType === 'tool_use') {
          if (blockPayload.id) block.tool_id = String(blockPayload.id)
          if (blockPayload.name) block.tool_name = String(blockPayload.name)
          if ('input' in blockPayload) block.input = blockPayload.input
        }
        if (claudeType === 'tool_result') {
          if (blockPayload.tool_use_id) block.tool_id = String(blockPayload.tool_use_id)
          if (blockPayload.name) block.tool_name = String(blockPayload.name)
          if ('content' in blockPayload) block.output = blockPayload.content
        }
        return
      }

      if (evtType === 'content_block_delta') {
        const index = evt.index ?? payload.index
        const delta = evt.delta && typeof evt.delta === 'object' ? evt.delta : (payload.delta && typeof payload.delta === 'object' ? payload.delta : {})
        const deltaType = String(delta.type || '')
        const block = ensureClaudeBlock(index, 'unknown')
        block.status = 'streaming'

        if (deltaType === 'text_delta') {
          block.type = 'main_text'
          block.text = appendText(block.text, delta.text)
          assistantMessage.content = appendText(assistantMessage.content, delta.text)
          return
        }

        if (deltaType === 'thinking_delta') {
          block.type = 'thinking'
          block.text = appendText(block.text, delta.thinking)
          return
        }

        if (deltaType === 'thinking_summary_delta') {
          block.type = 'thinking'
          const summaryText = String(delta.summary?.summary || '')
          if (summaryText) {
            const summaries = Array.isArray(block.payload?.summaries) ? [...block.payload.summaries] : []
            if (!summaries.includes(summaryText)) summaries.push(summaryText)
            block.payload = { ...(block.payload || {}), summaries }
          }
          return
        }

        if (deltaType === 'input_json_delta') {
          const partial = String(delta.partial_json || '')
          if (!partial) return
          const partialJson = appendText(block.payload?.partial_json, partial)
          block.payload = { ...(block.payload || {}), partial_json: partialJson }
          const parsed = parseMaybeJson(partialJson)
          const claudeType = String(block.payload?.claude_type || '')
          if (parsed !== null) {
            if (claudeType === 'tool_use') {
              block.type = 'tool_use'
              block.input = parsed
            } else if (claudeType === 'tool_result') {
              block.type = 'tool_result'
              block.output = parsed
            }
          } else {
            if (claudeType === 'tool_use') {
              block.type = 'tool_use'
              block.input = partialJson
            } else if (claudeType === 'tool_result') {
              block.type = 'tool_result'
              block.output = partialJson
            }
          }
          return
        }

        if (deltaType === 'citation_start_delta') {
          block.type = 'main_text'
          const citation = delta.citation && typeof delta.citation === 'object' ? delta.citation : null
          if (!citation) return
          const citations = Array.isArray(block.payload?.citations) ? [...block.payload.citations] : []
          citations.push(citation)
          block.payload = { ...(block.payload || {}), citations }
          block.text = appendText(block.text, `[^${citations.length}]`)
          return
        }

        if (deltaType === 'citation_end_delta') {
          return
        }

        return
      }

      if (evtType === 'content_block_stop') {
        const index = evt.index ?? payload.index
        const block = ensureClaudeBlock(index, 'unknown')
        if (block.status !== 'failed') block.status = 'success'
        const partialJson = String(block.payload?.partial_json || '')
        const parsed = parseMaybeJson(partialJson)
        if (parsed !== null) {
          const claudeType = String(block.payload?.claude_type || '')
          if (claudeType === 'tool_use') block.input = parsed
          if (claudeType === 'tool_result') block.output = parsed
        }
        return
      }

      if (evtType === 'message_delta') {
        const delta = evt.delta && typeof evt.delta === 'object' ? evt.delta : (payload.delta && typeof payload.delta === 'object' ? payload.delta : {})
        if (delta.stop_reason !== undefined && delta.stop_reason !== null) {
          assistantMessage.stop_reason = String(delta.stop_reason)
        }
        if (delta.stop_sequence !== undefined && delta.stop_sequence !== null) {
          assistantMessage.stop_sequence = String(delta.stop_sequence)
        }
        return
      }

      if (evtType === 'message_limit') {
        const limitPayload = (evt.message_limit && typeof evt.message_limit === 'object')
          ? evt.message_limit
          : (payload.message_limit && typeof payload.message_limit === 'object' ? payload.message_limit : {})
        const block = ensureBlock(assistantMessage, 'message-limit', 'message_limit')
        block.status = 'success'
        block.text = buildMessageLimitText(limitPayload)
        block.payload = limitPayload
        return
      }

      if (evtType === 'message_stop') {
        assistantMessage.status = assistantMessage.status === 'failed' ? 'failed' : 'success'
        const textBlock = assistantMessage.blocks.find((block) => block.type === 'main_text')
        if (!assistantMessage.content && textBlock?.text) {
          assistantMessage.content = String(textBlock.text)
        }
        assistantMessage.blocks.forEach((block) => {
          if (block.status === 'streaming' || block.status === 'pending') {
            block.status = 'success'
          }
        })
        return
      }
    }

    // Current DataAgent custom stream protocol
    if (evtType === 'text.start') {
      ensureBlock(assistantMessage, String(payload.block_id || 'main-text'), 'main_text')
      return
    }

    if (evtType === 'text.delta') {
      const block = ensureBlock(assistantMessage, String(payload.block_id || 'main-text'), 'main_text')
      block.text = appendText(block.text, payload.text)
      block.status = 'streaming'
      assistantMessage.content = appendText(assistantMessage.content, payload.text)
      return
    }

    if (evtType === 'text.complete') {
      const block = ensureBlock(assistantMessage, String(payload.block_id || 'main-text'), 'main_text')
      block.status = 'success'
      if (typeof payload.text === 'string') {
        block.text = payload.text
        assistantMessage.content = payload.text
      }
      return
    }

    if (evtType === 'thinking.start') {
      ensureBlock(assistantMessage, String(payload.block_id || 'thinking-main'), 'thinking')
      return
    }

    if (evtType === 'thinking.delta') {
      const block = ensureBlock(assistantMessage, String(payload.block_id || 'thinking-main'), 'thinking')
      block.text = appendText(block.text, payload.text)
      block.status = 'streaming'
      return
    }

    if (evtType === 'thinking.complete') {
      const block = ensureBlock(assistantMessage, String(payload.block_id || 'thinking-main'), 'thinking')
      block.status = 'success'
      if (typeof payload.text === 'string') {
        block.text = payload.text
      }
      return
    }

    if (evtType.startsWith('tool.')) {
      const blockId = String(payload.block_id || `tool-${payload.tool_id || Date.now()}`)
      const blockType = evtType === 'tool.complete' ? 'tool_result' : 'tool_use'
      const block = ensureBlock(assistantMessage, blockId, blockType)
      block.type = blockType
      block.tool_id = payload.tool_id ? String(payload.tool_id) : block.tool_id
      block.tool_name = payload.tool_name ? String(payload.tool_name) : block.tool_name
      if ('input' in payload) block.input = payload.input
      if ('output' in payload) block.output = payload.output
      block.payload = { ...(block.payload || {}), ...payload }
      if (evtType === 'tool.pending') block.status = 'pending'
      if (evtType === 'tool.in_progress' || evtType === 'tool.streaming') block.status = 'streaming'
      if (evtType === 'tool.complete') block.status = 'success'
      return
    }

    if (evtType === 'raw') {
      const rawBlockId = `raw-${evt.seq || Date.now()}`
      const rawText = (() => {
        if (typeof payload.preview === 'string') return payload.preview
        if (typeof payload.message_type === 'string') {
          return `${payload.message_type}${payload.subtype ? ` (${payload.subtype})` : ''}`
        }
        try {
          return JSON.stringify(payload, null, 2)
        } catch (_e) {
          return String(payload)
        }
      })()
      assistantMessage.blocks.push(
        normalizeBlock({
          block_id: rawBlockId,
          type: 'raw',
          status: 'success',
          text: rawText,
          payload
        })
      )
      return
    }

    if (evtType === 'error') {
      const err = normalizeErrorPayload(payload)
      const runId = String(evt.run_id || assistantMessage.run_id || '')
      const dedupKey = `${runId}:${err.code}`
      if (seenErrorKeys.has(dedupKey)) return
      seenErrorKeys.add(dedupKey)

      assistantMessage.status = 'failed'
      assistantMessage.error = err
      assistantMessage.blocks.push(
        normalizeBlock({
          block_id: `error-${err.code}-${evt.seq || Date.now()}`,
          type: 'error',
          status: 'failed',
          text: err.message,
          payload: err
        })
      )
      return
    }

    if (evtType === 'done') {
      assistantMessage.status = String(payload.status || assistantMessage.status || 'success')
      assistantMessage.content = String(payload.content || assistantMessage.content || '')
      assistantMessage.sql = String(payload.sql || '')
      assistantMessage.execution = payload.execution && typeof payload.execution === 'object' ? payload.execution : null
      assistantMessage.error = payload.error && typeof payload.error === 'object' ? payload.error : assistantMessage.error
      assistantMessage.resolved_database = payload.resolved_database ? String(payload.resolved_database) : assistantMessage.resolved_database
      assistantMessage.provider_id = payload.provider_id ? String(payload.provider_id) : assistantMessage.provider_id
      assistantMessage.model = payload.model ? String(payload.model) : assistantMessage.model
      applyDoneBlocks(assistantMessage, payload.blocks)
    }
  }

  const sendMessage = async (
    content,
    {
      providerId = null,
      model = null,
      debug = false,
      stream = true
    } = {}
  ) => {
    const text = String(content || '').trim()
    if (!text || generating.value) return null

    if (!activeSessionId.value) {
      await createSession(text.length > 20 ? `${text.slice(0, 20)}...` : text)
    }
    await hydrateSessionMessages(activeSessionId.value)

    const session = findSession(activeSessionId.value)
    if (!session) return null
    if (!Array.isArray(session.messages)) session.messages = []

    const now = new Date().toISOString()
    const userMessage = normalizeMessage({
      message_id: `local_u_${Date.now()}`,
      role: 'user',
      content: text,
      status: 'success',
      created_at: now
    })
    session.messages.push(userMessage)

    const assistantMessage = normalizeMessage({
      message_id: `local_a_${Date.now()}`,
      role: 'assistant',
      status: 'streaming',
      stop_reason: '',
      stop_sequence: '',
      content: '',
      blocks: [],
      sql: '',
      execution: null,
      error: null,
      provider_id: providerId || null,
      model: model || null,
      created_at: now
    })
    session.messages.push(assistantMessage)

    generating.value = true
    try {
      if (!stream) {
        const message = normalizeMessage(
          await nl2sqlApi.sendMessage(activeSessionId.value, {
            content: text,
            provider_id: providerId,
            model,
            debug,
            stream: false
          })
        )
        if (message) {
          const idx = session.messages.findIndex((item) => item.message_id === assistantMessage.message_id)
          if (idx >= 0) session.messages[idx] = message
          return message
        }
      }

      await nl2sqlApi.streamMessage(
        activeSessionId.value,
        {
          content: text,
          provider_id: providerId,
          model,
          debug,
          stream: true
        },
        {
          onEvent: (evt) => applyStreamEvent(assistantMessage, evt)
        }
      )

      assistantMessage.status = assistantMessage.status || 'success'
      session.updated_at = new Date().toISOString()
      session.message_count = (session.message_count || 0) + 2
      session.last_message_preview = assistantMessage.content || text
      if (session.title === '新会话') {
        session.title = text.length > 30 ? `${text.slice(0, 30)}...` : text
      }
      sortSessions()
      return assistantMessage
    } catch (error) {
      const reason = String(error?.message || '请求失败')
      applyStreamEvent(assistantMessage, {
        type: 'error',
        seq: Date.now(),
        run_id: assistantMessage.run_id || `run_local_${Date.now()}`,
        payload: { code: 'request_failed', message: reason }
      })
      assistantMessage.status = 'failed'
      sortSessions()
      throw error
    } finally {
      generating.value = false
      hydratedSessionIds.add(activeSessionId.value)
    }
  }

  const executeSql = async (sql, database = null) => nl2sqlApi.executeSql({ sql, database })

  return {
    loading,
    streamTick,
    sessions,
    activeSessionId,
    activeSession,
    activeMessages,
    generating,
    loadSessions,
    createSession,
    selectSession,
    deleteSession,
    sendMessage,
    executeSql
  }
}
