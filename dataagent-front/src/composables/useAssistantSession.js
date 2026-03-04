import { computed, ref } from 'vue'

const defaultContext = () => ({
  sourceId: null,
  database: '',
  limitProfile: 'text_answer',
  manualLimit: null,
  mode: 'need-confirm'
})

const normalizeArtifact = (artifact) => {
  if (!artifact) return null
  let parsed = null
  try {
    parsed = artifact.contentJson ? JSON.parse(artifact.contentJson) : null
  } catch (error) {
    parsed = artifact.contentJson
  }
  return {
    ...artifact,
    parsed
  }
}

const sortByCreated = (items) => {
  return [...items].sort((a, b) => {
    const at = new Date(a.createdAt || 0).getTime()
    const bt = new Date(b.createdAt || 0).getTime()
    return at - bt
  })
}

export function useAssistantSession(assistantApi) {
  if (!assistantApi) {
    throw new Error('assistantApi client is required for useAssistantSession')
  }
  const loading = ref(false)
  const sessions = ref([])
  const activeSessionId = ref('')

  const activeSession = computed(() => {
    return sessions.value.find(item => item.session.sessionId === activeSessionId.value) || null
  })

  const toSessionState = (detail) => {
    const artifacts = (detail.artifacts || []).map(normalizeArtifact)
    const draftArtifact = [...artifacts].reverse().find(a => a.artifactType === 'sql')
    const chartArtifact = [...artifacts].reverse().find(a => a.artifactType === 'chart')
    const taskArtifact = [...artifacts].reverse().find(a => a.artifactType === 'task_draft')

    return {
      session: {
        ...detail.session,
        context: detail.session?.context || defaultContext()
      },
      messages: sortByCreated(detail.messages || []),
      runs: sortByCreated(detail.runs || []),
      artifacts,
      steps: [],
      pendingApproval: null,
      draftSql: draftArtifact?.parsed?.sql || '',
      chart: chartArtifact?.parsed || null,
      taskDraft: taskArtifact?.parsed || null,
      lastRunId: detail.runs?.length ? detail.runs[detail.runs.length - 1].runId : ''
    }
  }

  const upsertSessionState = (state) => {
    const index = sessions.value.findIndex(item => item.session.sessionId === state.session.sessionId)
    if (index >= 0) {
      sessions.value.splice(index, 1, state)
      return
    }
    sessions.value.unshift(state)
  }

  const loadSessionDetail = async (sessionId) => {
    const detail = await assistantApi.getSessionDetail(sessionId)
    const state = toSessionState(detail)
    upsertSessionState(state)
    return state
  }

  const createBlankSession = async () => {
    const created = await assistantApi.createSession({
      title: '新会话',
      context: defaultContext()
    })
    const detail = await assistantApi.getSessionDetail(created.sessionId)
    const state = toSessionState(detail)
    upsertSessionState(state)
    activeSessionId.value = created.sessionId
    return state
  }

  const loadSessions = async () => {
    loading.value = true
    try {
      const list = await assistantApi.listSessions()
      if (!list.length) {
        await createBlankSession()
        return
      }

      const detailPromises = list.slice(0, 10).map(item => assistantApi.getSessionDetail(item.sessionId))
      const details = await Promise.all(detailPromises)
      sessions.value = details.map(toSessionState)
      if (!activeSessionId.value || !sessions.value.some(item => item.session.sessionId === activeSessionId.value)) {
        activeSessionId.value = sessions.value[0]?.session.sessionId || ''
      }
    } finally {
      loading.value = false
    }
  }

  const selectSession = async (sessionId) => {
    activeSessionId.value = sessionId
    const exists = sessions.value.some(item => item.session.sessionId === sessionId)
    if (!exists) {
      await loadSessionDetail(sessionId)
    }
  }

  const closeSession = async (sessionId) => {
    const index = sessions.value.findIndex(item => item.session.sessionId === sessionId)
    if (index < 0) return
    sessions.value.splice(index, 1)

    if (activeSessionId.value === sessionId) {
      if (sessions.value.length) {
        activeSessionId.value = sessions.value[0].session.sessionId
      } else {
        await createBlankSession()
      }
    }
  }

  const updateSessionContext = (sessionId, contextPatch) => {
    const target = sessions.value.find(item => item.session.sessionId === sessionId)
    if (!target) return
    target.session.context = {
      ...defaultContext(),
      ...(target.session.context || {}),
      ...(contextPatch || {})
    }
    if (contextPatch?.mode) {
      target.session.mode = contextPatch.mode
    }
  }

  const updateDraftSql = (sessionId, sql) => {
    const target = sessions.value.find(item => item.session.sessionId === sessionId)
    if (!target) return
    target.draftSql = sql || ''
  }

  const sendMessage = async (sessionId, content) => {
    const target = sessions.value.find(item => item.session.sessionId === sessionId)
    if (!target) return null

    const text = (content || '').trim()
    if (!text) return null

    const tempMessage = {
      id: `tmp-${Date.now()}`,
      runId: null,
      role: 'user',
      content: text,
      createdAt: new Date().toISOString()
    }
    target.messages.push(tempMessage)

    const response = await assistantApi.sendMessage(sessionId, {
      content: text,
      context: target.session.context
    })

    target.lastRunId = response.runId
    target.runs.push({
      runId: response.runId,
      sessionId,
      status: response.status,
      intent: null,
      policyMode: target.session.mode,
      startedAt: new Date().toISOString()
    })

    return response
  }

  const upsertRun = (state, runId, patch) => {
    const index = state.runs.findIndex(item => item.runId === runId)
    if (index >= 0) {
      state.runs[index] = { ...state.runs[index], ...patch }
      return state.runs[index]
    }
    const next = {
      runId,
      sessionId: state.session.sessionId,
      status: patch.status || 'running',
      ...patch
    }
    state.runs.push(next)
    return next
  }

  const upsertArtifact = (state, artifactPayload) => {
    const parsed = normalizeArtifact(artifactPayload)
    const index = state.artifacts.findIndex(item => item.id === parsed.id)
    if (index >= 0) {
      state.artifacts[index] = parsed
    } else {
      state.artifacts.push(parsed)
    }

    if (parsed.artifactType === 'sql') {
      state.draftSql = parsed.parsed?.sql || state.draftSql
    }
    if (parsed.artifactType === 'chart') {
      state.chart = parsed.parsed
    }
    if (parsed.artifactType === 'task_draft') {
      state.taskDraft = parsed.parsed
    }
  }

  const applyStreamEvent = (sessionId, payload) => {
    const state = sessions.value.find(item => item.session.sessionId === sessionId)
    if (!state || !payload) return

    const runId = payload.runId || state.lastRunId
    const event = payload.event
    const data = payload.data || {}

    if (event === 'run_started') {
      upsertRun(state, runId, { status: 'running', startedAt: payload.at })
      return
    }

    if (event === 'assistant_message') {
      state.messages.push({
        id: data.id || `m-${Date.now()}`,
        runId,
        role: 'assistant',
        content: data.content,
        intent: data.intent,
        metadataJson: data.metadataJson,
        createdAt: payload.at || new Date().toISOString()
      })
      return
    }

    if (event === 'artifact') {
      upsertArtifact(state, {
        id: data.id,
        runId,
        artifactType: data.artifactType,
        title: data.title,
        contentJson: data.contentJson,
        createdAt: payload.at
      })
      return
    }

    if (event === 'step') {
      state.steps.push({
        runId,
        ...data,
        at: payload.at
      })
      return
    }

    if (event === 'need_approval') {
      state.pendingApproval = {
        runId,
        ...data
      }
      upsertRun(state, runId, { status: 'waiting_approval' })
      return
    }

    if (event === 'approval_passed') {
      state.pendingApproval = null
      upsertRun(state, runId, { status: 'running' })
      return
    }

    if (event === 'run_cancel_requested') {
      upsertRun(state, runId, { status: 'cancel_requested' })
      return
    }

    if (event === 'run_cancelled') {
      state.pendingApproval = null
      upsertRun(state, runId, { status: 'cancelled', completedAt: payload.at })
      return
    }

    if (event === 'run_failed') {
      state.pendingApproval = null
      upsertRun(state, runId, { status: 'failed', completedAt: payload.at, errorMessage: data.error })
      return
    }

    if (event === 'run_completed') {
      state.pendingApproval = null
      upsertRun(state, runId, { status: 'completed', completedAt: payload.at })
    }
  }

  return {
    loading,
    sessions,
    activeSessionId,
    activeSession,
    loadSessions,
    loadSessionDetail,
    createBlankSession,
    selectSession,
    closeSession,
    updateSessionContext,
    updateDraftSql,
    sendMessage,
    applyStreamEvent
  }
}
