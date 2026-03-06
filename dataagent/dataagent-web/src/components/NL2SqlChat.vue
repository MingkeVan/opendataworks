<template>
  <div class="v2-layout">
    <aside class="v2-sidebar">
      <div class="v2-sidebar-head">
        <span class="v2-brand">DataAgent</span>
        <button class="v2-btn-new" @click="handleNewSession">+ 新对话</button>
      </div>
      <div class="v2-sidebar-search">
        <input v-model="searchKeyword" class="v2-search-input" type="text" placeholder="搜索会话..." />
      </div>
      <div class="v2-session-list">
        <button
          v-for="s in filteredSessions"
          :key="s.session_id"
          class="v2-session-item"
          :class="{ active: s.session_id === activeSessionId }"
          @click="handleSelectSession(s.session_id)"
        >
          <div class="v2-session-title">{{ truncate(s.title, 22) }}</div>
          <div class="v2-session-meta">{{ formatTime(s.updated_at || s.created_at) }}</div>
        </button>
        <div v-if="!filteredSessions.length" class="v2-empty-sessions">暂无会话</div>
      </div>
    </aside>

    <main class="v2-main">
      <div ref="messagesRef" class="v2-messages" @scroll="handleScroll">
        <div class="v2-messages-inner">
          <!-- Empty state -->
          <div v-if="!activeMessages.length" class="v2-empty">
            <div class="v2-empty-icon">💬</div>
            <div class="v2-empty-title">有什么可以帮到你？</div>
            <div class="v2-empty-sub">输入数据查询相关问题，我来帮你生成SQL</div>
            <div class="v2-suggestions">
              <button v-for="s in suggestions" :key="s" class="v2-suggestion" @click="handleSuggestion(s)">{{ s }}</button>
            </div>
          </div>

          <!-- Messages -->
          <template v-for="msg in activeMessages" :key="msg.id">
            <!-- User -->
            <div v-if="msg.role === 'user'" class="v2-msg-row v2-msg-user">
              <div class="v2-user-bubble">{{ msg.content }}</div>
            </div>
            <!-- Assistant -->
            <div v-else class="v2-msg-row v2-msg-assistant">
              <div class="v2-assistant-body">
                <!-- Thinking -->
                <div v-if="msg.thinkingText" class="v2-step-row">
                  <details class="v2-thinking-details">
                    <summary class="v2-step-summary">
                      <span class="v2-step-icon">{{ msg.status === 'streaming' ? '⏳' : '💭' }}</span>
                      <span>{{ msg.status === 'streaming' ? '思考中...' : '已完成思考' }}</span>
                      <span class="v2-step-chevron">›</span>
                    </summary>
                    <div class="v2-thinking-content">{{ msg.thinkingText }}</div>
                  </details>
                </div>

                <!-- Tool calls (grouped) -->
                <div v-if="readTools(msg).length" class="v2-step-row">
                  <details class="v2-tool-details">
                    <summary class="v2-step-summary">
                      <span class="v2-step-icon">📄</span>
                      <span>已浏览 {{ readTools(msg).length }} 个文件</span>
                      <span class="v2-step-chevron">›</span>
                    </summary>
                    <div class="v2-tool-file-list">
                      <div v-for="f in readTools(msg)" :key="f.id" class="v2-tool-file-item">{{ extractFileName(f) }}</div>
                    </div>
                  </details>
                </div>
                <div v-for="tool in skillTools(msg)" :key="tool.id" class="v2-step-row">
                  <div class="v2-step-summary v2-step-static">
                    <span class="v2-step-icon">{{ tool.status === 'streaming' ? '⏳' : '🔧' }}</span>
                    <span>{{ extractSkillLabel(tool) }}</span>
                  </div>
                </div>

                <!-- Main text -->
                <div v-if="cleanMainText(msg)" class="v2-main-text">
                  <div v-html="renderMarkdown(cleanMainText(msg))"></div>
                  <span v-if="msg.status === 'streaming'" class="v2-cursor">▋</span>
                </div>

                <!-- Citations -->
                <div v-if="msg.citations.length" class="v2-citations">
                  <a v-for="(c, ci) in msg.citations" :key="ci" :href="c.url || '#'" target="_blank" rel="noopener" class="v2-citation-chip">
                    <span class="v2-citation-idx">{{ ci + 1 }}</span>
                    <span>{{ c.title || c.url || '来源' }}</span>
                  </a>
                </div>

                <!-- SQL -->
                <div v-if="msg.sql" class="v2-sql-card">
                  <div class="v2-sql-header">
                    <span>SQL</span>
                    <div class="v2-sql-actions">
                      <button class="v2-btn-sm" @click="copyText(msg.sql)">复制</button>
                      <button class="v2-btn-sm v2-btn-exec" @click="handleExecSql(msg)">执行</button>
                    </div>
                  </div>
                  <pre class="v2-sql-code"><code>{{ msg.sql }}</code></pre>
                </div>

                <!-- Execution result -->
                <div v-if="msg.execution" class="v2-exec-card">
                  <div class="v2-exec-head">
                    <span>执行结果</span>
                    <span class="v2-exec-meta">{{ msg.execution.row_count || 0 }} 行 · {{ msg.execution.duration_ms || 0 }}ms</span>
                  </div>
                  <div v-if="msg.execution.error" class="v2-exec-error">{{ msg.execution.error }}</div>
                  <div v-else-if="execColumns(msg.execution).length && msg.execution.rows?.length" class="v2-table-wrap">
                    <table class="v2-table">
                      <thead><tr><th v-for="col in execColumns(msg.execution)" :key="col">{{ col }}</th></tr></thead>
                      <tbody><tr v-for="(row, ri) in msg.execution.rows" :key="ri"><td v-for="col in execColumns(msg.execution)" :key="col">{{ row[col] }}</td></tr></tbody>
                    </table>
                  </div>
                  <div v-else class="v2-exec-empty">无数据</div>
                </div>

                <!-- Error -->
                <div v-if="msg.error" class="v2-error-card">
                  <span class="v2-error-icon">⚠</span>
                  <span>{{ msg.error }}</span>
                </div>

                <!-- Persistent thinking indicator (always shows during streaming) -->
                <div v-if="msg.status === 'streaming'" class="v2-loading">
                  <span class="v2-loading-text">正在思考</span>
                  <span class="v2-loading-dots"><span>.</span><span>.</span><span>.</span></span>
                </div>

              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- Composer -->
      <div class="v2-composer-wrap">
        <div class="v2-composer">
          <div class="v2-composer-top">
            <div class="v2-composer-ctrl">
              <select v-model="selectedProvider" class="v2-select">
                <option v-for="p in settings.providers" :key="p.provider_id" :value="p.provider_id">{{ p.display_name }}</option>
              </select>
            </div>
            <div class="v2-composer-ctrl">
              <select v-model="selectedModel" class="v2-select">
                <option v-for="m in availableModels" :key="m" :value="m">{{ m }}</option>
              </select>
            </div>
          </div>
          <div class="v2-composer-input-row">
            <textarea
              v-model="inputText"
              class="v2-textarea"
              placeholder="输入你的问题..."
              rows="2"
              @keydown.ctrl.enter.prevent="handleSend"
              @keydown.meta.enter.prevent="handleSend"
            />
            <button class="v2-btn-send" :disabled="!inputText.trim() || generating" @click="handleSend">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 2L11 13"/><path d="M22 2L15 22L11 13L2 9L22 2Z"/></svg>
            </button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, triggerRef, watch } from 'vue'
import { marked } from 'marked'
import { createNl2SqlApiClient } from '../api/nl2sqlApi'

marked.setOptions({ breaks: true, gfm: true })

const props = defineProps({
  nl2sqlBase: { type: String, default: '' }
})

const api = createNl2SqlApiClient({ baseURL: props.nl2sqlBase, timeout: 300000 })

// ─── State ───
const sessions = ref([])
const activeSessionId = ref('')
const generating = ref(false)
const inputText = ref('')
const searchKeyword = ref('')
const messagesRef = ref(null)
const autoScroll = ref(true)
const hydratedIds = new Set()

const settings = reactive({
  default_provider_id: 'openrouter',
  default_model: 'anthropic/claude-sonnet-4.5',
  providers: []
})
const selectedProvider = ref(settings.default_provider_id)
const selectedModel = ref(settings.default_model)

const suggestions = ['查询今日活跃用户数', '最近7天订单趋势', '各业务线本月收入对比', '昨日新增用户按来源分布']

// ─── Computed ───
const activeSession = computed(() => sessions.value.find(s => s.session_id === activeSessionId.value) || null)
const activeMessages = computed(() => activeSession.value?.messages || [])
const filteredSessions = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  if (!kw) return sessions.value
  return sessions.value.filter(s => String(s.title || '').toLowerCase().includes(kw))
})
const activeProviderConfig = computed(() => {
  const list = Array.isArray(settings.providers) ? settings.providers : []
  return list.find(p => p.provider_id === selectedProvider.value) || list[0] || null
})
const availableModels = computed(() => {
  const p = activeProviderConfig.value
  const models = Array.isArray(p?.models) ? [...p.models] : []
  const fb = p?.default_model || settings.default_model
  if (fb && !models.includes(fb)) models.unshift(fb)
  return models
})

// ─── Helpers ───
const truncate = (v, max) => { const t = String(v || '新会话'); return t.length > max ? t.slice(0, max) + '...' : t }
const formatTime = (v) => {
  if (!v) return ''
  const d = new Date(v), now = new Date()
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
const uid = () => `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const copyText = async (v) => { try { await navigator.clipboard.writeText(String(v || '')) } catch (_) {} }
const execColumns = (exec) => {
  if (Array.isArray(exec?.columns) && exec.columns.length) return exec.columns
  const row = Array.isArray(exec?.rows) ? exec.rows[0] : null
  return row && typeof row === 'object' ? Object.keys(row) : []
}

// Tool categorization helpers
const readTools = (msg) => (msg.tools || []).filter(t => {
  const n = String(t.name || '').toLowerCase()
  return n === 'read' || n === 'read_file' || n === 'readfile'
})
const skillTools = (msg) => (msg.tools || []).filter(t => {
  const n = String(t.name || '').toLowerCase()
  return n === 'skill' || n === 'launch_skill'
})
const extractFileName = (tool) => {
  const inp = tool.input
  let fp = ''
  if (typeof inp === 'string') {
    const parsed = parseMaybeJson(inp)
    fp = parsed?.file_path || inp
  } else if (inp && typeof inp === 'object') {
    fp = inp.file_path || inp.path || ''
  }
  const parts = String(fp || 'file').split('/')
  return parts[parts.length - 1] || 'file'
}
const cleanMainText = (msg) => {
  let t = String(msg.mainText || '')
  // Strip skill preamble: "Base directory for this skill:..." through "ARGUMENTS: ..." line
  t = t.replace(/Base directory for this skill:[\s\S]*?(?:ARGUMENTS:\s*[^\n]*\n?)/gi, '')
  // Also strip standalone "ARGUMENTS: ..." lines
  t = t.replace(/^ARGUMENTS:\s*[^\n]*\n?/gm, '')
  return t.replace(/^\s+/, '')
}

const renderMarkdown = (text) => {
  if (!text) return ''
  try {
    // Strip SQL fenced blocks (they are displayed separately in SQL card)
    let clean = text.replace(/```sql[\s\S]*?```/gi, '')
    return marked.parse(clean)
  } catch (_) { return text }
}

const extractSkillLabel = (tool) => {
  let skill = ''
  // Try from input
  const inp = tool.input
  if (typeof inp === 'string') {
    const parsed = parseMaybeJson(inp)
    skill = parsed?.skill || ''
    if (!skill) { const m = inp.match(/"skill"\s*:\s*"([^"]+)"/); if (m) skill = m[1] }
  } else if (inp && typeof inp === 'object') {
    skill = inp.skill || ''
  }
  // Try from output (e.g. "Launching skill: dataagent-nl2sql")
  if (!skill && tool.output) {
    const out = typeof tool.output === 'string' ? tool.output : String(tool.output)
    const m = out.match(/Launching skill:\s*(\S+)/i)
    if (m) skill = m[1]
  }
  const name = skill ? skill.replace(/^.*\//, '') : ''
  return name ? `${name} skill` : String(tool.name || 'Skill')
}

const sortSessions = () => {
  sessions.value.sort((a, b) => new Date(b.updated_at || b.created_at || 0) - new Date(a.updated_at || a.created_at || 0))
}

const normSession = (s) => ({
  session_id: String(s?.session_id || ''),
  title: String(s?.title || '新会话'),
  message_count: Number(s?.message_count || 0),
  created_at: String(s?.created_at || new Date().toISOString()),
  updated_at: String(s?.updated_at || new Date().toISOString()),
  messages: []
})

const makeAssistantMsg = () => reactive({
  id: `a_${uid()}`, role: 'assistant', content: '', status: 'streaming',
  mainText: '', thinkingText: '', sql: '', tools: [], citations: [],
  execution: null, error: null, stop_reason: '',
  resolved_database: null, provider_id: null, model: null,
  _blocks: {}, _partials: {},
  created_at: new Date().toISOString()
})

// ─── SSE Event Processing (reimplemented) ───
const appendStr = (base, delta) => {
  const d = String(delta || ''); if (!d) return base || ''
  const b = String(base || ''); if (!b) return d
  if (d === b) return b; if (d.startsWith(b)) return d
  return b + d
}

const parseMaybeJson = (t) => { try { return JSON.parse(String(t || '').trim()) } catch (_) { return null } }

const ensureClaudeBlock = (msg, index, claudeType) => {
  const key = `cb-${index}`
  if (!msg._blocks[key]) msg._blocks[key] = { type: claudeType, text: '', status: 'streaming', tool_name: '', tool_id: '', input: null, output: null, partial_json: '' }
  return msg._blocks[key]
}

const processEvent = (msg, evt) => {
  if (!evt || typeof evt !== 'object') return
  const type = String(evt.type || '')
  const payload = evt.payload && typeof evt.payload === 'object' ? evt.payload : {}

  if (evt.message_id) msg.id = String(evt.message_id)
  if (payload.provider_id) msg.provider_id = String(payload.provider_id)
  if (payload.model) msg.model = String(payload.model)
  if (payload.resolved_database) msg.resolved_database = String(payload.resolved_database)

  // Claude protocol
  if (type === 'message_start') {
    const m = evt.message || payload.message || {}
    if (m.id) msg.id = String(m.id)
    if (m.model) msg.model = String(m.model)
    msg.status = 'streaming'
    return
  }
  if (type === 'ping') return

  if (type === 'content_block_start') {
    const idx = evt.index ?? payload.index
    const cb = evt.content_block || payload.content_block || {}
    const cType = String(cb.type || 'unknown')
    const block = ensureClaudeBlock(msg, idx, cType)
    block.type = cType; block.status = 'streaming'
    if (cType === 'text' && cb.text) { block.text = cb.text; msg.mainText = appendStr(msg.mainText, cb.text) }
    if (cType === 'thinking' && cb.thinking) block.text = cb.thinking
    if (cType === 'tool_use') {
      if (cb.id) block.tool_id = String(cb.id)
      if (cb.name) block.tool_name = String(cb.name)
      if ('input' in cb) block.input = cb.input
      msg.tools.push({ id: block.tool_id || `t_${uid()}`, name: block.tool_name, status: 'streaming', input: block.input, output: null, _blockKey: `cb-${idx}` })
    }
    return
  }

  if (type === 'content_block_delta') {
    const idx = evt.index ?? payload.index
    const delta = evt.delta || payload.delta || {}
    const dType = String(delta.type || '')
    const block = ensureClaudeBlock(msg, idx, 'unknown')
    block.status = 'streaming'

    if (dType === 'text_delta') {
      block.type = 'text'; block.text = appendStr(block.text, delta.text)
      msg.mainText = appendStr(msg.mainText, delta.text)
      msg.content = appendStr(msg.content, delta.text)
    } else if (dType === 'thinking_delta') {
      block.type = 'thinking'; block.text = appendStr(block.text, delta.thinking)
      msg.thinkingText = appendStr(msg.thinkingText, delta.thinking)
    } else if (dType === 'thinking_summary_delta') {
      block.type = 'thinking'
    } else if (dType === 'input_json_delta') {
      block.partial_json = appendStr(block.partial_json, delta.partial_json || '')
      const parsed = parseMaybeJson(block.partial_json)
      const cType = block.type
      if (cType === 'tool_use') {
        block.input = parsed !== null ? parsed : block.partial_json
        const tool = msg.tools.find(t => t._blockKey === `cb-${idx}`)
        if (tool) tool.input = block.input
      }
    } else if (dType === 'citation_start_delta' && delta.citation) {
      msg.citations.push(delta.citation)
    }
    return
  }

  if (type === 'content_block_stop') {
    const idx = evt.index ?? payload.index
    const block = ensureClaudeBlock(msg, idx, 'unknown')
    block.status = 'success'
    if (block.partial_json) {
      const parsed = parseMaybeJson(block.partial_json)
      if (parsed !== null && block.type === 'tool_use') block.input = parsed
    }
    const tool = msg.tools.find(t => t._blockKey === `cb-${idx}`)
    if (tool) { tool.status = 'success'; tool.input = block.input }
    return
  }

  if (type === 'message_delta') {
    const d = evt.delta || payload.delta || {}
    if (d.stop_reason != null) msg.stop_reason = String(d.stop_reason)
    return
  }

  if (type === 'message_stop') {
    msg.status = msg.status === 'failed' ? 'failed' : 'success'
    msg.tools.forEach(t => { if (t.status === 'streaming') t.status = 'success' })
    return
  }

  // DataAgent custom protocol
  if (type === 'text.delta') {
    msg.mainText = appendStr(msg.mainText, payload.text)
    msg.content = appendStr(msg.content, payload.text)
    return
  }
  if (type === 'text.complete') {
    if (typeof payload.text === 'string') { msg.mainText = payload.text; msg.content = payload.text }
    return
  }
  if (type === 'thinking.delta') {
    msg.thinkingText = appendStr(msg.thinkingText, payload.text)
    return
  }
  if (type === 'thinking.complete') {
    if (typeof payload.text === 'string') msg.thinkingText = payload.text
    return
  }
  if (type.startsWith('tool.')) {
    const toolId = String(payload.tool_id || payload.block_id || `t_${uid()}`)
    let tool = msg.tools.find(t => t.id === toolId)
    if (!tool) { tool = { id: toolId, name: String(payload.tool_name || 'Tool'), status: 'streaming', input: null, output: null }; msg.tools.push(tool) }
    if (payload.tool_name) tool.name = String(payload.tool_name)
    if ('input' in payload) tool.input = payload.input
    if ('output' in payload) tool.output = payload.output
    if (type === 'tool.pending') tool.status = 'pending'
    if (type === 'tool.in_progress' || type === 'tool.streaming') tool.status = 'streaming'
    if (type === 'tool.complete') { tool.status = 'success'; tool.output = payload.output ?? tool.output }
    return
  }

  if (type === 'error') {
    msg.status = 'failed'
    msg.error = String(payload.message || '请求失败')
    return
  }

  if (type === 'done') {
    msg.status = String(payload.status || msg.status || 'success')
    if (payload.content) msg.content = String(payload.content)
    if (payload.sql) msg.sql = String(payload.sql)
    if (payload.execution) msg.execution = payload.execution
    if (payload.error) msg.error = typeof payload.error === 'object' ? String(payload.error.message || '请求失败') : String(payload.error)
    if (payload.resolved_database) msg.resolved_database = String(payload.resolved_database)
    if (payload.model) msg.model = String(payload.model)
    // If main text not set from streaming, try to fill from content
    if (!msg.mainText && msg.content) msg.mainText = msg.content
  }
}

// ─── Session & Message Actions ───
const loadSettings = async () => {
  try {
    const p = await api.getSettings()
    settings.default_provider_id = p?.default_provider_id || settings.default_provider_id
    settings.default_model = p?.default_model || settings.default_model
    settings.providers = Array.isArray(p?.providers) ? p.providers : []
    selectedProvider.value = settings.default_provider_id || settings.providers[0]?.provider_id || 'openrouter'
    const prov = settings.providers.find(x => x.provider_id === selectedProvider.value)
    selectedModel.value = prov?.default_model || settings.default_model || ''
  } catch (e) { console.warn('load settings failed', e) }
}

const hydrateSession = async (sid) => {
  if (!sid || hydratedIds.has(sid)) return
  try {
    const detail = await api.getSession(sid)
    const target = sessions.value.find(s => s.session_id === sid)
    if (target && detail) {
      target.title = String(detail.title || target.title)
      target.updated_at = String(detail.updated_at || target.updated_at)
      const rawMsgs = Array.isArray(detail.messages) ? detail.messages : []
      target.messages = rawMsgs.map(m => {
        if (!m) return null
        const role = String(m.role || 'assistant')
        if (role === 'user') return { id: String(m.message_id || uid()), role: 'user', content: String(m.content || ''), created_at: m.created_at }
        // assistant
        const am = makeAssistantMsg()
        am.id = String(m.message_id || am.id)
        am.content = String(m.content || '')
        am.mainText = am.content
        am.sql = String(m.sql || '')
        am.execution = m.execution || null
        am.status = String(m.status || 'success')
        am.resolved_database = m.resolved_database || null
        am.stop_reason = String(m.stop_reason || '')
        am.created_at = m.created_at || am.created_at
        // reconstruct blocks
        if (Array.isArray(m.blocks)) {
          for (const b of m.blocks) {
            if (!b) continue
            const bt = String(b.type || '')
            if (bt === 'thinking') am.thinkingText = String(b.text || '')
            if (bt === 'main_text' && b.text) am.mainText = String(b.text)
            if (bt === 'tool_use' || bt === 'tool_result' || bt === 'tool') {
              am.tools.push({ id: String(b.block_id || uid()), name: String(b.tool_name || 'Tool'), status: String(b.status || 'success'), input: b.input, output: b.output })
            }
            if (bt === 'error') am.error = String(b.text || '请求失败')
            if (b.payload?.citations) am.citations.push(...(Array.isArray(b.payload.citations) ? b.payload.citations : []))
          }
        }
        return am
      }).filter(Boolean)
      target.message_count = target.messages.length
    }
    hydratedIds.add(sid)
  } catch (e) { console.warn('hydrate failed', e) }
}

const loadSessions = async () => {
  try {
    const list = await api.listSessions()
    sessions.value = (Array.isArray(list) ? list : []).map(normSession)
    sortSessions()
    if (!activeSessionId.value && sessions.value.length) activeSessionId.value = sessions.value[0].session_id
    if (activeSessionId.value) await hydrateSession(activeSessionId.value)
  } catch (e) { console.warn('load sessions failed', e) }
}

const handleNewSession = async () => {
  const s = normSession(await api.createSession())
  sessions.value.unshift(s)
  hydratedIds.add(s.session_id)
  activeSessionId.value = s.session_id
  autoScroll.value = true
  scrollToBottom(true)
}

const handleSelectSession = async (sid) => {
  activeSessionId.value = sid
  await hydrateSession(sid)
  autoScroll.value = true
  scrollToBottom(true)
}

const handleSend = async () => {
  const text = inputText.value.trim()
  if (!text || generating.value) return
  inputText.value = ''
  scrollToBottom()

  if (!activeSessionId.value) {
    const s = normSession(await api.createSession(text.length > 20 ? text.slice(0, 20) + '...' : text))
    sessions.value.unshift(s)
    hydratedIds.add(s.session_id)
    activeSessionId.value = s.session_id
  }
  await hydrateSession(activeSessionId.value)

  const session = activeSession.value
  if (!session) return
  if (!Array.isArray(session.messages)) session.messages = []

  session.messages.push({ id: `u_${uid()}`, role: 'user', content: text, created_at: new Date().toISOString() })
  const assistantMsg = makeAssistantMsg()
  session.messages.push(assistantMsg)
  generating.value = true
  scrollToBottom()

  const abortCtrl = new AbortController()
  const fetchTimer = setTimeout(() => abortCtrl.abort(), 300000) // 5 min
  try {
    await api.streamMessage(activeSessionId.value, {
      content: text,
      provider_id: selectedProvider.value,
      model: selectedModel.value,
      debug: true,
      stream: true
    }, {
      onEvent: (evt) => { processEvent(assistantMsg, evt); triggerRef(sessions); scrollToBottom() },
      signal: abortCtrl.signal
    })
    if (assistantMsg.status === 'streaming') assistantMsg.status = 'success'
    session.updated_at = new Date().toISOString()
    session.message_count = session.messages.length
    if (session.title === '新会话') session.title = text.length > 30 ? text.slice(0, 30) + '...' : text
    sortSessions()
    scrollToBottom(true)
  } catch (err) {
    assistantMsg.status = 'failed'
    assistantMsg.error = String(err?.message || '请求失败')
  } finally {
    clearTimeout(fetchTimer)
    generating.value = false
  }
}

const handleSuggestion = (v) => { inputText.value = v; handleSend() }

const handleExecSql = async (msg) => {
  if (!msg?.sql) return
  try {
    msg.execution = await api.executeSql({ sql: msg.sql, database: msg.resolved_database || null })
  } catch (e) {
    msg.execution = { error: String(e?.message || '执行失败'), row_count: 0, duration_ms: 0, rows: [], columns: [] }
  }
}

// ─── Scroll ───
const isNearBottom = () => { const el = messagesRef.value; if (!el) return true; return el.scrollHeight - el.scrollTop - el.clientHeight < 60 }
const handleScroll = () => { autoScroll.value = isNearBottom() }
const scrollToBottom = (force = false) => {
  if (!force && !autoScroll.value) return
  nextTick(() => { const el = messagesRef.value; if (el) el.scrollTop = el.scrollHeight })
}

// ─── Watchers ───
watch(() => [selectedProvider.value, availableModels.value.join('|')], () => {
  if (!availableModels.value.includes(selectedModel.value)) selectedModel.value = availableModels.value[0] || settings.default_model || ''
})

onMounted(async () => {
  await loadSettings()
  await loadSessions()
  scrollToBottom(true)
})
</script>

<style scoped>
/* ═══ Layout ═══ */
.v2-layout {
  --bg: #faf8f5; --bg-sidebar: #f5f3ef; --bg-card: #ffffff;
  --text: #1a1a1a; --text-muted: #7a7670; --text-light: #a09a92;
  --border: #e8e2d9; --border-light: #f0ece6;
  --accent: #1a73e8; --accent-soft: #e8f0fe;
  --user-bg: #2c2c2c; --user-text: #ffffff;
  height: 100vh; width: 100vw; display: grid; grid-template-columns: 260px 1fr;
  background: var(--bg); color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Inter', sans-serif;
}

/* ═══ Sidebar ═══ */
.v2-sidebar { border-right: 1px solid var(--border); display: flex; flex-direction: column; min-height: 0; background: var(--bg-sidebar); }
.v2-sidebar-head { padding: 16px 16px 10px; display: flex; align-items: center; justify-content: space-between; }
.v2-brand { font-size: 15px; font-weight: 700; color: var(--text); letter-spacing: -0.01em; }
.v2-btn-new { border: 1px solid var(--border); background: var(--bg-card); color: var(--text); border-radius: 20px; height: 32px; padding: 0 14px; font-size: 12px; font-weight: 500; cursor: pointer; transition: all 0.15s; }
.v2-btn-new:hover { background: var(--border-light); border-color: #d5cfc6; }
.v2-sidebar-search { padding: 0 16px 12px; }
.v2-search-input { width: 100%; border: 1px solid var(--border); border-radius: 10px; height: 34px; padding: 0 12px; background: var(--bg-card); outline: none; font-size: 13px; color: var(--text); transition: border-color 0.15s; box-sizing: border-box; }
.v2-search-input:focus { border-color: var(--accent); }
.v2-session-list { flex: 1; min-height: 0; overflow-y: auto; padding: 0 10px 14px; }
.v2-session-item { width: 100%; border: none; border-radius: 10px; background: transparent; text-align: left; padding: 10px 12px; margin-bottom: 4px; cursor: pointer; transition: background 0.12s; display: block; }
.v2-session-item:hover { background: #ece8e1; }
.v2-session-item.active { background: #e4dfd7; }
.v2-session-title { font-size: 13px; font-weight: 600; color: var(--text); line-height: 1.4; }
.v2-session-meta { margin-top: 3px; font-size: 11px; color: var(--text-muted); }
.v2-empty-sessions { color: var(--text-light); font-size: 13px; text-align: center; padding: 28px 0; }

/* ═══ Main ═══ */
.v2-main { min-width: 0; min-height: 0; display: flex; flex-direction: column; background: var(--bg); }

/* ═══ Messages area ═══ */
.v2-messages { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.v2-messages-inner { max-width: 860px; margin: 0 auto; padding: 28px 24px 36px; }

/* ═══ Empty state ═══ */
.v2-empty { min-height: 360px; display: flex; flex-direction: column; justify-content: center; align-items: center; text-align: center; }
.v2-empty-icon { font-size: 40px; margin-bottom: 12px; }
.v2-empty-title { font-size: 22px; font-weight: 700; color: var(--text); }
.v2-empty-sub { margin-top: 8px; color: var(--text-muted); font-size: 14px; }
.v2-suggestions { margin-top: 22px; display: flex; gap: 10px; flex-wrap: wrap; justify-content: center; }
.v2-suggestion { border: 1px solid var(--border); border-radius: 20px; background: var(--bg-card); height: 36px; padding: 0 16px; color: var(--text); cursor: pointer; font-size: 13px; transition: all 0.15s; }
.v2-suggestion:hover { background: var(--border-light); border-color: #d5cfc6; }

/* ═══ Message rows ═══ */
.v2-msg-row { margin-bottom: 22px; display: flex; }
.v2-msg-user { justify-content: flex-end; }
.v2-msg-assistant { justify-content: flex-start; }

/* ═══ User bubble ═══ */
.v2-user-bubble { max-width: 70%; border-radius: 18px 18px 4px 18px; background: var(--user-bg); color: var(--user-text); padding: 12px 16px; white-space: pre-wrap; line-height: 1.6; font-size: 14px; }

/* ═══ Assistant body ═══ */
.v2-assistant-body { max-width: 100%; width: 100%; }

/* ═══ Steps (thinking / tool) ═══ */
.v2-step-row { margin-bottom: 8px; }
.v2-step-summary { display: flex; align-items: center; gap: 8px; cursor: pointer; user-select: none; list-style: none; color: var(--text-muted); font-size: 13px; padding: 6px 0; }
.v2-step-summary::-webkit-details-marker { display: none; }
.v2-step-summary::marker { display: none; content: ''; }
.v2-step-icon { font-size: 14px; flex-shrink: 0; }
.v2-step-chevron { margin-left: auto; font-size: 16px; font-weight: 300; color: var(--text-light); transition: transform 0.2s; }
details[open] > .v2-step-summary .v2-step-chevron { transform: rotate(90deg); }
.v2-thinking-content { margin: 4px 0 6px 24px; padding: 10px 14px; background: #f7f5f1; border-radius: 10px; white-space: pre-wrap; line-height: 1.6; color: #5a554d; font-size: 13px; max-height: 300px; overflow-y: auto; }

/* ═══ Loading indicator ═══ */
.v2-loading { display: flex; align-items: center; padding: 8px 0; }
.v2-loading-text { font-size: 14px; color: var(--text-muted); }
.v2-loading-dots { display: inline-flex; margin-left: 2px; }
.v2-loading-dots span { font-size: 14px; color: var(--text-muted); animation: v2dot 1.4s ease-in-out infinite; }
.v2-loading-dots span:nth-child(2) { animation-delay: 0.2s; }
.v2-loading-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes v2dot { 0%, 20% { opacity: 0.2; } 50% { opacity: 1; } 80%, 100% { opacity: 0.2; } }
.v2-step-static { cursor: default; }
.v2-tool-file-list { margin: 4px 0 6px 24px; }
.v2-tool-file-item { font-size: 12px; color: var(--text-muted); padding: 3px 0; line-height: 1.4; }
.v2-tool-file-item::before { content: '· '; color: var(--text-light); }

/* ═══ Main text (markdown) ═══ */
.v2-main-text { line-height: 1.75; color: var(--text); font-size: 14.5px; }
.v2-main-text :deep(p) { margin: 0 0 10px; }
.v2-main-text :deep(p:last-child) { margin-bottom: 0; }
.v2-main-text :deep(strong) { font-weight: 600; color: var(--text); }
.v2-main-text :deep(ul), .v2-main-text :deep(ol) { margin: 6px 0 10px 18px; padding: 0; }
.v2-main-text :deep(li) { margin-bottom: 4px; }
.v2-main-text :deep(code) { background: #f0ece6; border-radius: 4px; padding: 1px 5px; font-size: 13px; color: #8b4513; }
.v2-main-text :deep(pre) { background: #1e1e1e; color: #d4d4d4; border-radius: 10px; padding: 14px 16px; overflow-x: auto; margin: 10px 0; font-size: 13px; line-height: 1.55; }
.v2-main-text :deep(pre code) { background: none; color: inherit; padding: 0; font-size: inherit; }
.v2-main-text :deep(blockquote) { border-left: 3px solid var(--border); margin: 8px 0; padding: 4px 12px; color: var(--text-muted); }
.v2-main-text :deep(h1), .v2-main-text :deep(h2), .v2-main-text :deep(h3) { margin: 16px 0 8px; font-weight: 600; }
.v2-main-text :deep(h1) { font-size: 18px; }
.v2-main-text :deep(h2) { font-size: 16px; }
.v2-main-text :deep(h3) { font-size: 15px; }
.v2-main-text :deep(a) { color: var(--accent); text-decoration: none; }
.v2-main-text :deep(a:hover) { text-decoration: underline; }
.v2-main-text :deep(table) { width: 100%; border-collapse: collapse; font-size: 13px; margin: 10px 0; }
.v2-main-text :deep(th), .v2-main-text :deep(td) { border: 1px solid var(--border-light); padding: 6px 10px; text-align: left; }
.v2-main-text :deep(th) { background: #f7f5f1; font-weight: 600; }
.v2-cursor { color: var(--accent); animation: v2blink 1s ease-in-out infinite; }
.v2-citations { margin-top: 12px; display: flex; gap: 8px; flex-wrap: wrap; }
.v2-citation-chip { display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--border); border-radius: 8px; padding: 5px 10px; font-size: 12px; color: var(--text-muted); text-decoration: none; background: var(--bg-card); transition: border-color 0.15s; }
.v2-citation-chip:hover { border-color: var(--accent); color: var(--accent); }
.v2-citation-idx { background: var(--border-light); border-radius: 4px; width: 18px; height: 18px; display: flex; align-items: center; justify-content: center; font-size: 10px; font-weight: 600; }

/* ═══ SQL card ═══ */
.v2-sql-card { margin-top: 14px; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; background: var(--bg-card); }
.v2-sql-header { display: flex; justify-content: space-between; align-items: center; padding: 8px 14px; border-bottom: 1px solid var(--border-light); }
.v2-sql-header span { font-size: 12px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.03em; }
.v2-sql-actions { display: flex; gap: 6px; }
.v2-btn-sm { height: 26px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-card); color: var(--text); padding: 0 10px; cursor: pointer; font-size: 11px; transition: all 0.12s; }
.v2-btn-sm:hover { background: var(--border-light); }
.v2-btn-exec { border-color: var(--accent); color: var(--accent); }
.v2-btn-exec:hover { background: var(--accent-soft); }
.v2-sql-code { margin: 0; padding: 14px 16px; background: #1e1e1e; color: #d4d4d4; overflow-x: auto; font-size: 13px; line-height: 1.55; }

/* ═══ Execution card ═══ */
.v2-exec-card { margin-top: 10px; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; background: var(--bg-card); }
.v2-exec-head { display: flex; justify-content: space-between; align-items: center; padding: 8px 14px; border-bottom: 1px solid var(--border-light); }
.v2-exec-head span:first-child { font-size: 12px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; }
.v2-exec-meta { font-size: 11px; color: var(--text-light); }
.v2-exec-error { padding: 12px 14px; color: #c4321c; font-size: 13px; white-space: pre-wrap; }
.v2-table-wrap { overflow-x: auto; }
.v2-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.v2-table th, .v2-table td { border-bottom: 1px solid var(--border-light); padding: 7px 10px; text-align: left; white-space: nowrap; }
.v2-table th { background: #faf8f5; color: var(--text-muted); font-weight: 600; font-size: 11px; text-transform: uppercase; }
.v2-exec-empty { padding: 12px 14px; color: var(--text-light); font-size: 12px; }

/* ═══ Error ═══ */
.v2-error-card { margin-top: 10px; display: flex; align-items: flex-start; gap: 8px; padding: 10px 14px; background: #fef2f0; border: 1px solid #f5d5cf; border-radius: 10px; color: #9a2c18; font-size: 13px; line-height: 1.5; }
.v2-error-icon { flex-shrink: 0; font-size: 15px; }

/* ═══ Done marker ═══ */

/* ═══ Composer ═══ */
.v2-composer-wrap { padding: 12px 24px 18px; flex-shrink: 0; background: var(--bg); }
.v2-composer { max-width: 860px; margin: 0 auto; border: 1px solid var(--border); border-radius: 16px; background: var(--bg-card); box-shadow: 0 1px 6px rgba(0,0,0,0.05); overflow: hidden; }
.v2-composer-top { display: flex; gap: 8px; padding: 8px 14px 4px; flex-wrap: wrap; }
.v2-composer-ctrl { display: flex; align-items: center; }
.v2-select { border: 1px solid var(--border); border-radius: 8px; height: 28px; padding: 0 8px; background: var(--bg); font-size: 12px; color: var(--text); outline: none; }
.v2-composer-input-row { display: flex; align-items: flex-end; padding: 4px 10px 10px 14px; gap: 8px; }
.v2-textarea { flex: 1; border: none; resize: none; outline: none; font-size: 14px; line-height: 1.6; color: var(--text); background: transparent; min-height: 40px; max-height: 120px; font-family: inherit; }
.v2-textarea::placeholder { color: var(--text-light); }
.v2-btn-send { width: 36px; height: 36px; border-radius: 50%; border: none; background: var(--user-bg); color: #fff; cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; transition: opacity 0.15s; }
.v2-btn-send:disabled { opacity: 0.3; cursor: not-allowed; }
.v2-btn-send:not(:disabled):hover { opacity: 0.85; }

/* ═══ Animations ═══ */
@keyframes v2blink { 0% { opacity: 0.3; } 50% { opacity: 1; } 100% { opacity: 0.3; } }

/* ═══ Responsive ═══ */
@media (max-width: 900px) {
  .v2-layout { grid-template-columns: 1fr; }
  .v2-sidebar { display: none; }
  .v2-messages-inner, .v2-composer { max-width: 100%; }
  .v2-user-bubble { max-width: 88%; }
}
</style>
