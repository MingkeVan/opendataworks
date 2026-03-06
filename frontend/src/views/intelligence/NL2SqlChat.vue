<template>
  <div class="query-workbench">
    <aside class="query-sidebar">
      <div class="query-sidebar-head">
        <div>
          <div class="query-brand">智能问数</div>
          <div class="query-brand-meta">DataAgent 会话空间</div>
        </div>
        <button class="query-btn-new" @click="handleNewSession">新建</button>
      </div>

      <div class="query-sidebar-search">
        <input
          v-model="searchKeyword"
          class="query-search-input"
          type="text"
          placeholder="搜索会话"
        >
      </div>

      <div class="query-session-list">
        <button
          v-for="session in filteredSessions"
          :key="session.session_id"
          class="query-session-item"
          :class="{ active: session.session_id === activeSessionId }"
          @click="handleSelectSession(session.session_id)"
        >
          <div class="query-session-title">{{ truncate(session.title, 26) }}</div>
          <div class="query-session-meta">{{ formatTime(session.updated_at || session.created_at) }}</div>
        </button>
        <div v-if="!filteredSessions.length" class="query-empty-sessions">暂无会话</div>
      </div>
    </aside>

    <main class="query-main">
      <div ref="messagesRef" class="query-messages" @scroll="handleScroll">
        <div class="query-messages-inner">
          <div class="query-main-head">
            <div>
              <p class="query-main-kicker">Natural Language To SQL</p>
              <h3>{{ activeSession ? truncate(activeSession.title, 48) : '开始一次新的数据分析' }}</h3>
              <p class="query-main-subtitle">仅展示你已开启且通过校验的供应商与模型，支持连续追问、工具执行轨迹和图表渲染。</p>
            </div>
            <div class="query-model-badge">
              <span>{{ activeProviderConfig?.display_name || 'Provider 未配置' }}</span>
              <strong>{{ selectedModel || settings.default_model || '默认模型' }}</strong>
            </div>
          </div>

          <div v-if="!settings.providers.length" class="query-config-empty">
            <div class="query-config-empty-title">还没有可用的智能问数模型</div>
            <div class="query-config-empty-text">请先在配置页填写供应商 Token，并把模型加入“已验证候选”。</div>
          </div>

          <div v-if="!activeMessages.length" class="query-empty">
            <div class="query-empty-mark">AI</div>
            <div class="query-empty-title">输入业务问题，直接开始分析</div>
            <div class="query-empty-subtitle">我会调用 Skill 查询元数据，执行 SQL 或 Python，并在适合时自动生成图表。</div>
            <div class="query-suggestions">
              <button
                v-for="suggestion in suggestions"
                :key="suggestion"
                class="query-suggestion"
                @click="handleSuggestion(suggestion)"
              >
                {{ suggestion }}
              </button>
            </div>
          </div>

          <template v-for="msg in activeMessages" :key="msg.id">
            <div v-if="msg.role === 'user'" class="query-message-row query-message-user">
              <div class="query-user-bubble">{{ msg.content }}</div>
            </div>

            <div v-else class="query-message-row query-message-assistant">
              <div class="query-assistant-body">
                <div v-if="msg.thinkingText" class="query-step-row">
                  <details class="query-step-details">
                    <summary class="query-step-summary">
                      <span class="query-step-badge">思考</span>
                      <span>{{ msg.status === 'streaming' ? '正在整理查询思路' : '查询思路' }}</span>
                      <span class="query-step-chevron">></span>
                    </summary>
                    <div class="query-thinking-content">{{ msg.thinkingText }}</div>
                  </details>
                </div>

                <div v-for="tool in visibleTools(msg)" :key="tool.id" class="query-step-row">
                  <ToolOutputRenderer :tool="tool" />
                </div>

                <div v-if="cleanMainText(msg)" class="query-main-text">
                  <div v-html="renderMarkdown(cleanMainText(msg))"></div>
                  <span v-if="msg.status === 'streaming'" class="query-cursor">|</span>
                </div>

                <div v-if="msg.citations.length" class="query-citations">
                  <a
                    v-for="(citation, index) in msg.citations"
                    :key="index"
                    :href="citation.url || '#'"
                    target="_blank"
                    rel="noopener"
                    class="query-citation-chip"
                  >
                    <span class="query-citation-index">{{ index + 1 }}</span>
                    <span>{{ citation.title || citation.url || '来源' }}</span>
                  </a>
                </div>

                <div v-if="msg.error" class="query-error-card">
                  <span class="query-error-label">错误</span>
                  <span>{{ msg.error }}</span>
                </div>

                <div v-if="msg.status === 'streaming'" class="query-loading">
                  <span class="query-loading-text">正在思考</span>
                  <span class="query-loading-dots">
                    <span>.</span>
                    <span>.</span>
                    <span>.</span>
                  </span>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>

      <div class="query-composer-wrap">
        <div class="query-composer">
          <div class="query-composer-top">
            <div class="query-composer-control">
              <select v-model="selectedProvider" class="query-select" :disabled="!settings.providers.length">
                <option
                  v-for="provider in settings.providers"
                  :key="provider.provider_id"
                  :value="provider.provider_id"
                >
                  {{ provider.display_name }}
                </option>
              </select>
            </div>
            <div class="query-composer-control">
              <select v-model="selectedModel" class="query-select" :disabled="!availableModels.length">
                <option v-for="model in availableModels" :key="model" :value="model">
                  {{ model }}
                </option>
              </select>
            </div>
          </div>

          <div class="query-composer-input-row">
            <textarea
              v-model="inputText"
              class="query-textarea"
              rows="2"
              :disabled="!settings.providers.length || !availableModels.length"
              placeholder="例如：查询最近 7 天各业务线订单收入趋势"
              @keydown.ctrl.enter.prevent="handleSend"
              @keydown.meta.enter.prevent="handleSend"
            />
            <button class="query-btn-send" :disabled="!inputText.trim() || generating || !selectedProvider || !selectedModel" @click="handleSend">
              发送
            </button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, triggerRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import { createNl2SqlApiClient } from '@/api/nl2sql'
import ToolOutputRenderer from './ToolOutputRenderer.vue'

marked.setOptions({ breaks: true, gfm: true })

const api = createNl2SqlApiClient({ timeout: 300000 })

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

const suggestions = [
  '查询今日活跃用户数',
  '最近 7 天订单趋势',
  '各业务线本月收入对比',
  '昨日新增用户按来源分布'
]

const activeSession = computed(() => sessions.value.find((session) => session.session_id === activeSessionId.value) || null)
const activeMessages = computed(() => activeSession.value?.messages || [])
const filteredSessions = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (!keyword) return sessions.value
  return sessions.value.filter((session) => String(session.title || '').toLowerCase().includes(keyword))
})

const activeProviderConfig = computed(() => {
  const list = Array.isArray(settings.providers) ? settings.providers : []
  return list.find((provider) => provider.provider_id === selectedProvider.value) || list[0] || null
})

const availableModels = computed(() => {
  const provider = activeProviderConfig.value
  const models = Array.isArray(provider?.models) ? [...provider.models] : []
  const fallbackModel = provider?.default_model || settings.default_model
  if (fallbackModel && !models.includes(fallbackModel)) {
    models.unshift(fallbackModel)
  }
  return models
})

const truncate = (value, max) => {
  const text = String(value || '新会话')
  return text.length > max ? `${text.slice(0, max)}...` : text
}

const formatTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

const uid = () => `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

const parseMaybeJson = (text) => {
  try {
    return JSON.parse(String(text || '').trim())
  } catch (_error) {
    return null
  }
}

const normalizeToolPayload = (value) => {
  if (value && typeof value === 'object' && !Array.isArray(value) && value.kind) {
    return value
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      if (item && typeof item === 'object' && item.kind) return item
      if (typeof item === 'string') {
        const parsed = parseMaybeJson(item)
        if (parsed?.kind) return parsed
      }
      if (item && typeof item === 'object' && typeof item.text === 'string') {
        const parsed = parseMaybeJson(item.text)
        if (parsed?.kind) return parsed
      }
    }
  }
  if (typeof value === 'string') {
    return parseMaybeJson(value)
  }
  return null
}

const visibleTools = (msg) => (msg.tools || []).filter((tool) => {
  const name = String(tool.name || '').toLowerCase()
  if (normalizeToolPayload(tool.output)?.kind) return true
  if (tool.status === 'streaming' || tool.status === 'pending' || tool.status === 'failed') return true
  return !['read', 'read_file', 'readfile', 'skill', 'launch_skill'].includes(name)
})

const cleanMainText = (msg) => {
  let text = String(msg.mainText || '')
  text = text.replace(/Base directory for this skill:[\s\S]*?(?:ARGUMENTS:\s*[^\n]*\n?)/gi, '')
  text = text.replace(/^ARGUMENTS:\s*[^\n]*\n?/gm, '')
  return text.replace(/^\s+/, '')
}

const escapeHtml = (text) => String(text || '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')

const renderMarkdown = (text) => {
  if (!text) return ''
  try {
    return marked.parse(escapeHtml(text))
  } catch (_error) {
    return escapeHtml(text)
  }
}

const sortSessions = () => {
  sessions.value.sort((left, right) => new Date(right.updated_at || right.created_at || 0) - new Date(left.updated_at || left.created_at || 0))
}

const normSession = (session) => ({
  session_id: String(session?.session_id || ''),
  title: String(session?.title || '新会话'),
  message_count: Number(session?.message_count || 0),
  created_at: String(session?.created_at || new Date().toISOString()),
  updated_at: String(session?.updated_at || new Date().toISOString()),
  messages: []
})

const makeAssistantMsg = () => reactive({
  id: `a_${uid()}`,
  role: 'assistant',
  content: '',
  status: 'streaming',
  mainText: '',
  thinkingText: '',
  tools: [],
  citations: [],
  error: null,
  stop_reason: '',
  provider_id: null,
  model: null,
  _blocks: {},
  _partials: {},
  created_at: new Date().toISOString()
})

const appendStr = (base, delta) => {
  const next = String(delta || '')
  if (!next) return base || ''
  const prev = String(base || '')
  if (!prev) return next
  if (next === prev) return prev
  if (next.startsWith(prev)) return next
  return prev + next
}

const ensureClaudeBlock = (msg, index, claudeType) => {
  const key = `cb-${index}`
  if (!msg._blocks[key]) {
    msg._blocks[key] = {
      type: claudeType,
      text: '',
      status: 'streaming',
      tool_name: '',
      tool_id: '',
      input: null,
      output: null,
      partial_json: ''
    }
  }
  return msg._blocks[key]
}

const normalizeToolId = (value) => {
  const text = String(value || '').trim()
  return text || ''
}

const findMessageTool = (msg, toolId, blockKey = '') => {
  return (msg.tools || []).find((item) => {
    if (toolId && (item.id === toolId || item._toolId === toolId)) return true
    if (blockKey && item._blockKey === blockKey) return true
    return false
  })
}

const upsertMessageTool = (msg, patch = {}) => {
  const toolId = normalizeToolId(patch.toolId)
  const blockKey = String(patch.blockKey || '')
  let tool = findMessageTool(msg, toolId, blockKey)

  if (!tool) {
    tool = {
      id: toolId || `t_${uid()}`,
      _toolId: toolId || '',
      _blockKey: blockKey,
      name: String(patch.name || 'Tool'),
      status: String(patch.status || 'pending'),
      input: null,
      output: null
    }
    msg.tools.push(tool)
  }

  if (toolId) {
    tool.id = toolId
    tool._toolId = toolId
  }
  if (blockKey) tool._blockKey = blockKey
  if (patch.name) tool.name = String(patch.name)
  if (Object.prototype.hasOwnProperty.call(patch, 'input') && patch.input !== undefined) tool.input = patch.input
  if (Object.prototype.hasOwnProperty.call(patch, 'output') && patch.output !== undefined) tool.output = patch.output
  if (patch.status) tool.status = String(patch.status)
  return tool
}

const syncToolsFromBlocks = (msg, blocks) => {
  for (const block of Array.isArray(blocks) ? blocks : []) {
    if (!block || typeof block !== 'object') continue
    const blockType = String(block.type || '')
    if (!['tool_use', 'tool_result', 'tool'].includes(blockType)) continue

    const payload = block.payload && typeof block.payload === 'object' ? block.payload : {}
    const toolId = normalizeToolId(block.tool_id || payload.tool_id || payload.tool_use_id || payload.id)
    const name = String(block.tool_name || payload.tool_name || payload.name || 'Tool')
    const patch = {
      toolId,
      blockKey: String(block.block_id || ''),
      name,
      status: String(block.status || 'success')
    }

    if (blockType === 'tool_use') {
      patch.input = 'input' in block ? block.input : payload.input
    } else if (blockType === 'tool_result') {
      patch.output = 'output' in block ? block.output : (payload.output ?? payload.content)
    } else {
      patch.input = 'input' in block ? block.input : payload.input
      patch.output = 'output' in block ? block.output : (payload.output ?? payload.content)
    }

    upsertMessageTool(msg, patch)
  }
}

const processEvent = (msg, event) => {
  if (!event || typeof event !== 'object') return

  const type = String(event.type || '')
  const payload = event.payload && typeof event.payload === 'object' ? event.payload : {}

  if (event.message_id) msg.id = String(event.message_id)
  if (payload.provider_id) msg.provider_id = String(payload.provider_id)
  if (payload.model) msg.model = String(payload.model)

  if (type === 'message_start') {
    const message = event.message || payload.message || {}
    if (message.id) msg.id = String(message.id)
    if (message.model) msg.model = String(message.model)
    msg.status = 'streaming'
    return
  }

  if (type === 'ping') return

  if (type === 'content_block_start') {
    const index = event.index ?? payload.index
    const contentBlock = event.content_block || payload.content_block || {}
    const contentType = String(contentBlock.type || 'unknown')
    const block = ensureClaudeBlock(msg, index, contentType)

    block.type = contentType
    block.status = 'streaming'

    if (contentType === 'text' && contentBlock.text) {
      block.text = contentBlock.text
      msg.mainText = appendStr(msg.mainText, contentBlock.text)
    }
    if (contentType === 'thinking' && contentBlock.thinking) {
      block.text = contentBlock.thinking
    }
    if (contentType === 'tool_use') {
      if (contentBlock.id) block.tool_id = String(contentBlock.id)
      if (contentBlock.name) block.tool_name = String(contentBlock.name)
      if ('input' in contentBlock) block.input = contentBlock.input
      upsertMessageTool(msg, {
        toolId: block.tool_id,
        blockKey: `cb-${index}`,
        name: block.tool_name,
        input: block.input,
        status: 'streaming'
      })
    }
    if (contentType === 'tool_result') {
      if (contentBlock.tool_use_id) block.tool_id = String(contentBlock.tool_use_id)
      if (contentBlock.name) block.tool_name = String(contentBlock.name)
      if ('content' in contentBlock) block.output = contentBlock.content
      upsertMessageTool(msg, {
        toolId: block.tool_id,
        blockKey: `cb-${index}`,
        name: block.tool_name || 'Tool',
        output: block.output,
        status: 'streaming'
      })
    }
    return
  }

  if (type === 'content_block_delta') {
    const index = event.index ?? payload.index
    const delta = event.delta || payload.delta || {}
    const deltaType = String(delta.type || '')
    const block = ensureClaudeBlock(msg, index, 'unknown')

    block.status = 'streaming'

    if (deltaType === 'text_delta') {
      block.type = 'text'
      block.text = appendStr(block.text, delta.text)
      msg.mainText = appendStr(msg.mainText, delta.text)
      msg.content = appendStr(msg.content, delta.text)
    } else if (deltaType === 'thinking_delta') {
      block.type = 'thinking'
      block.text = appendStr(block.text, delta.thinking)
      msg.thinkingText = appendStr(msg.thinkingText, delta.thinking)
    } else if (deltaType === 'thinking_summary_delta') {
      block.type = 'thinking'
    } else if (deltaType === 'input_json_delta') {
      block.partial_json = appendStr(block.partial_json, delta.partial_json || '')
      const parsed = parseMaybeJson(block.partial_json)
      if (block.type === 'tool_use') {
        block.input = parsed !== null ? parsed : block.partial_json
        upsertMessageTool(msg, {
          toolId: block.tool_id,
          blockKey: `cb-${index}`,
          input: block.input,
          status: 'streaming'
        })
      }
    } else if (deltaType === 'citation_start_delta' && delta.citation) {
      msg.citations.push(delta.citation)
    }
    return
  }

  if (type === 'content_block_stop') {
    const index = event.index ?? payload.index
    const block = ensureClaudeBlock(msg, index, 'unknown')
    block.status = 'success'
    if (block.partial_json) {
      const parsed = parseMaybeJson(block.partial_json)
      if (parsed !== null && block.type === 'tool_use') block.input = parsed
    }
    if (block.type === 'tool_use') {
      upsertMessageTool(msg, {
        toolId: block.tool_id,
        blockKey: `cb-${index}`,
        input: block.input,
        status: 'streaming'
      })
    }
    if (block.type === 'tool_result') {
      upsertMessageTool(msg, {
        toolId: block.tool_id,
        blockKey: `cb-${index}`,
        output: block.output,
        status: 'success'
      })
    }
    return
  }

  if (type === 'message_delta') {
    const delta = event.delta || payload.delta || {}
    if (delta.stop_reason != null) msg.stop_reason = String(delta.stop_reason)
    return
  }

  if (type === 'message_stop') {
    msg.status = msg.status === 'failed' ? 'failed' : 'success'
    msg.tools.forEach((tool) => {
      if (tool.status === 'streaming') tool.status = 'success'
    })
    return
  }

  if (type === 'text.delta') {
    msg.mainText = appendStr(msg.mainText, payload.text)
    msg.content = appendStr(msg.content, payload.text)
    return
  }

  if (type === 'text.complete') {
    if (typeof payload.text === 'string') {
      msg.mainText = payload.text
      msg.content = payload.text
    }
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
    upsertMessageTool(msg, {
      toolId,
      name: String(payload.tool_name || 'Tool'),
      input: 'input' in payload ? payload.input : undefined,
      output: 'output' in payload ? payload.output : undefined,
      status: type === 'tool.pending'
        ? 'pending'
        : (type === 'tool.complete' ? 'success' : 'streaming')
    })
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
    if (payload.error) {
      msg.error = typeof payload.error === 'object'
        ? String(payload.error.message || '请求失败')
        : String(payload.error)
    }
    if (payload.model) msg.model = String(payload.model)
    if (Array.isArray(payload.blocks)) syncToolsFromBlocks(msg, payload.blocks)
    if (!msg.mainText && msg.content) msg.mainText = msg.content
  }
}

const loadSettings = async () => {
  try {
    const payload = await api.getSettings()
    settings.default_provider_id = payload?.default_provider_id || settings.default_provider_id
    settings.default_model = payload?.default_model || settings.default_model
    settings.providers = Array.isArray(payload?.providers) ? payload.providers : []
    selectedProvider.value = settings.default_provider_id || settings.providers[0]?.provider_id || ''
    const provider = settings.providers.find((item) => item.provider_id === selectedProvider.value)
    selectedModel.value = provider?.default_model || settings.default_model || ''
  } catch (error) {
    console.warn('load settings failed', error)
  }
}

const hydrateSession = async (sessionId) => {
  if (!sessionId || hydratedIds.has(sessionId)) return

  try {
    const detail = await api.getSession(sessionId)
    const target = sessions.value.find((session) => session.session_id === sessionId)
    if (target && detail) {
      target.title = String(detail.title || target.title)
      target.updated_at = String(detail.updated_at || target.updated_at)
      const rawMessages = Array.isArray(detail.messages) ? detail.messages : []
      target.messages = rawMessages.map((message) => {
        if (!message) return null
        const role = String(message.role || 'assistant')
        if (role === 'user') {
          return {
            id: String(message.message_id || uid()),
            role: 'user',
            content: String(message.content || ''),
            created_at: message.created_at
          }
        }

        const assistantMessage = makeAssistantMsg()
        assistantMessage.id = String(message.message_id || assistantMessage.id)
        assistantMessage.content = String(message.content || '')
        assistantMessage.mainText = assistantMessage.content
        assistantMessage.status = String(message.status || 'success')
        assistantMessage.stop_reason = String(message.stop_reason || '')
        assistantMessage.created_at = message.created_at || assistantMessage.created_at

        if (Array.isArray(message.blocks)) {
          for (const block of message.blocks) {
            if (!block) continue
            const blockType = String(block.type || '')
            if (blockType === 'thinking') assistantMessage.thinkingText = String(block.text || '')
            if (blockType === 'main_text' && block.text) assistantMessage.mainText = String(block.text)
            if (blockType === 'error') assistantMessage.error = String(block.text || '请求失败')
            if (block.payload?.citations) {
              assistantMessage.citations.push(...(Array.isArray(block.payload.citations) ? block.payload.citations : []))
            }
          }
          syncToolsFromBlocks(assistantMessage, message.blocks)
        }

        return assistantMessage
      }).filter(Boolean)
      target.message_count = target.messages.length
    }

    hydratedIds.add(sessionId)
  } catch (error) {
    console.warn('hydrate session failed', error)
  }
}

const loadSessions = async () => {
  try {
    const list = await api.listSessions()
    sessions.value = (Array.isArray(list) ? list : []).map(normSession)
    sortSessions()
    if (!activeSessionId.value && sessions.value.length) {
      activeSessionId.value = sessions.value[0].session_id
    }
    if (activeSessionId.value) {
      await hydrateSession(activeSessionId.value)
    }
  } catch (error) {
    console.warn('load sessions failed', error)
  }
}

const handleNewSession = async () => {
  const session = normSession(await api.createSession())
  sessions.value.unshift(session)
  hydratedIds.add(session.session_id)
  activeSessionId.value = session.session_id
  autoScroll.value = true
  scrollToBottom(true)
}

const handleSelectSession = async (sessionId) => {
  activeSessionId.value = sessionId
  await hydrateSession(sessionId)
  autoScroll.value = true
  scrollToBottom(true)
}

const handleSend = async () => {
  const text = inputText.value.trim()
  if (!text || generating.value || !selectedProvider.value || !selectedModel.value) return

  inputText.value = ''
  autoScroll.value = true
  scrollToBottom(true)

  let session = null
  let assistantMsg = null
  let abortController = null
  let fetchTimer = null

  try {
    if (!activeSessionId.value) {
      const title = text.length > 20 ? `${text.slice(0, 20)}...` : text
      const created = normSession(await api.createSession(title))
      sessions.value.unshift(created)
      hydratedIds.add(created.session_id)
      activeSessionId.value = created.session_id
    }

    await hydrateSession(activeSessionId.value)

    session = activeSession.value
    if (!session) {
      throw new Error('会话初始化失败')
    }

    if (!Array.isArray(session.messages)) {
      session.messages = []
    }

    session.messages.push({
      id: `u_${uid()}`,
      role: 'user',
      content: text,
      created_at: new Date().toISOString()
    })

    assistantMsg = makeAssistantMsg()
    session.messages.push(assistantMsg)
    generating.value = true
    scrollToBottom(true)

    abortController = new AbortController()
    fetchTimer = setTimeout(() => abortController.abort(), 300000)

    await api.streamMessage(
      activeSessionId.value,
      {
        content: text,
        provider_id: selectedProvider.value,
        model: selectedModel.value,
        debug: true,
        stream: true
      },
      {
        onEvent: (event) => {
          processEvent(assistantMsg, event)
          triggerRef(sessions)
          scrollToBottom()
        },
        signal: abortController.signal
      }
    )

    if (assistantMsg.status === 'streaming') {
      assistantMsg.status = 'success'
    }
    session.updated_at = new Date().toISOString()
    session.message_count = session.messages.length
    if (session.title === '新会话') {
      session.title = text.length > 30 ? `${text.slice(0, 30)}...` : text
    }
    sortSessions()
    scrollToBottom(true)
  } catch (error) {
    const message = String(error?.message || '请求失败')
    if (assistantMsg) {
      assistantMsg.status = 'failed'
      assistantMsg.error = message
    } else {
      ElMessage.error(message)
    }
  } finally {
    if (fetchTimer) clearTimeout(fetchTimer)
    generating.value = false
  }
}

const handleSuggestion = (value) => {
  inputText.value = value
  void handleSend()
}

const isNearBottom = () => {
  const element = messagesRef.value
  if (!element) return true
  return element.scrollHeight - element.scrollTop - element.clientHeight < 60
}

const handleScroll = () => {
  autoScroll.value = isNearBottom()
}

const scrollToBottom = (force = false) => {
  if (!force && !autoScroll.value) return
  nextTick(() => {
    const element = messagesRef.value
    if (element) element.scrollTop = element.scrollHeight
  })
}

watch(
  () => [selectedProvider.value, availableModels.value.join('|')],
  () => {
    if (!availableModels.value.includes(selectedModel.value)) {
      selectedModel.value = availableModels.value[0] || settings.default_model || ''
    }
  }
)

onMounted(async () => {
  await loadSettings()
  await loadSessions()
  scrollToBottom(true)
})
</script>

<style scoped>
.query-workbench {
  --sidebar-bg: linear-gradient(180deg, #102033 0%, #1a334c 100%);
  --sidebar-border: rgba(255, 255, 255, 0.08);
  --sidebar-text: rgba(237, 245, 255, 0.92);
  --sidebar-text-muted: rgba(201, 214, 229, 0.7);
  --surface: #fbfcfe;
  --surface-muted: #f3f7fb;
  --surface-soft: #eef3f8;
  --line: #d7e4ef;
  --line-soft: #e6eef5;
  --text: #162131;
  --text-muted: #607185;
  --text-soft: #8da0b3;
  --accent: #0f8c7b;
  --accent-soft: rgba(15, 140, 123, 0.12);
  --primary: #0f172a;
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(240px, 280px) 1fr;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 28px;
  overflow: hidden;
  background: var(--surface);
  box-shadow: 0 24px 50px rgba(15, 23, 42, 0.08);
}

.query-sidebar {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 18px 14px 16px;
  background: var(--sidebar-bg);
  color: var(--sidebar-text);
}

.query-sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 6px 14px;
}

.query-brand {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.query-brand-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--sidebar-text-muted);
}

.query-btn-new {
  height: 34px;
  padding: 0 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  color: #f8fbff;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.18s ease, transform 0.18s ease;
}

.query-btn-new:hover {
  background: rgba(255, 255, 255, 0.18);
  transform: translateY(-1px);
}

.query-sidebar-search {
  padding: 0 6px 14px;
}

.query-search-input {
  width: 100%;
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--sidebar-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.08);
  color: var(--sidebar-text);
  outline: none;
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.query-search-input::placeholder {
  color: rgba(201, 214, 229, 0.6);
}

.query-search-input:focus {
  border-color: rgba(147, 197, 253, 0.6);
  background: rgba(255, 255, 255, 0.12);
}

.query-session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 6px;
}

.query-session-item {
  width: 100%;
  margin-bottom: 8px;
  padding: 12px 12px 11px;
  border: 1px solid transparent;
  border-radius: 16px;
  background: transparent;
  color: var(--sidebar-text);
  text-align: left;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}

.query-session-item:hover {
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.08);
  transform: translateY(-1px);
}

.query-session-item.active {
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.14);
}

.query-session-title {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
}

.query-session-meta {
  margin-top: 4px;
  font-size: 11px;
  color: var(--sidebar-text-muted);
}

.query-empty-sessions {
  padding: 30px 8px;
  color: var(--sidebar-text-muted);
  font-size: 13px;
  text-align: center;
}

.query-main {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(circle at top right, rgba(15, 140, 123, 0.08), transparent 24%),
    linear-gradient(180deg, #fbfcfe 0%, #f6f9fc 100%);
}

.query-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.query-messages-inner {
  max-width: 980px;
  margin: 0 auto;
  padding: 28px 26px 36px;
}

.query-main-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 20px;
  margin-bottom: 18px;
  border-bottom: 1px dashed var(--line);
}

.query-main-kicker {
  margin: 0 0 6px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.query-main-head h3 {
  margin: 0;
  color: var(--text);
  font-size: 28px;
  font-weight: 700;
}

.query-main-subtitle {
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 14px;
}

.query-model-badge {
  min-width: 220px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.8);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.04);
}

.query-model-badge span {
  display: block;
  color: var(--text-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.query-model-badge strong {
  display: block;
  margin-top: 8px;
  color: var(--text);
  font-size: 14px;
  line-height: 1.5;
  word-break: break-all;
}

.query-config-empty {
  margin-top: 18px;
  padding: 18px 20px;
  border-radius: 18px;
  border: 1px dashed rgba(245, 158, 11, 0.45);
  background: linear-gradient(180deg, rgba(255, 247, 237, 0.92) 0%, rgba(255, 255, 255, 0.98) 100%);
}

.query-config-empty-title {
  font-size: 15px;
  font-weight: 700;
  color: #9a3412;
}

.query-config-empty-text {
  margin-top: 6px;
  font-size: 13px;
  color: #b45309;
}

.query-empty {
  min-height: 360px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.query-empty-mark {
  width: 78px;
  height: 78px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  background:
    linear-gradient(135deg, #0f172a 0%, #165d63 100%);
  color: #f8fbff;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.08em;
  box-shadow: 0 16px 32px rgba(15, 23, 42, 0.14);
}

.query-empty-title {
  margin-top: 18px;
  color: var(--text);
  font-size: 24px;
  font-weight: 700;
}

.query-empty-subtitle {
  margin-top: 10px;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.8;
}

.query-suggestions {
  margin-top: 22px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.query-suggestion {
  height: 38px;
  padding: 0 16px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.18s ease, background-color 0.18s ease, transform 0.18s ease;
}

.query-suggestion:hover {
  border-color: rgba(15, 140, 123, 0.35);
  background: #ffffff;
  transform: translateY(-1px);
}

.query-message-row {
  margin-bottom: 24px;
  display: flex;
}

.query-message-user {
  justify-content: flex-end;
}

.query-user-bubble {
  max-width: 72%;
  padding: 13px 16px;
  border-radius: 20px 20px 6px 20px;
  background: linear-gradient(135deg, #101828 0%, #1f4250 100%);
  color: #f8fbff;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  box-shadow: 0 14px 28px rgba(15, 23, 42, 0.12);
}

.query-message-assistant {
  justify-content: flex-start;
}

.query-assistant-body {
  width: 100%;
  max-width: 100%;
}

.query-step-row {
  margin-bottom: 8px;
}

.query-step-details {
  border-radius: 14px;
}

.query-step-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  list-style: none;
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}

.query-step-summary::-webkit-details-marker {
  display: none;
}

.query-step-summary::marker {
  display: none;
  content: '';
}

.query-step-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 46px;
  height: 24px;
  padding: 0 9px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.query-step-chevron {
  margin-left: auto;
  color: var(--text-soft);
  transition: transform 0.18s ease;
}

details[open] > .query-step-summary .query-step-chevron {
  transform: rotate(90deg);
}

.query-step-static {
  cursor: default;
}

.query-thinking-content,
.query-tool-file-list {
  margin-left: 56px;
  padding: 12px 14px;
  border: 1px solid var(--line-soft);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--text-muted);
  line-height: 1.75;
  box-shadow: 0 10px 20px rgba(15, 23, 42, 0.03);
}

.query-thinking-content {
  white-space: pre-wrap;
  max-height: 300px;
  overflow-y: auto;
}

.query-tool-file-item {
  font-size: 12px;
  line-height: 1.6;
}

.query-tool-file-item + .query-tool-file-item {
  margin-top: 4px;
}

.query-main-text {
  color: var(--text);
  font-size: 14.5px;
  line-height: 1.8;
}

.query-main-text :deep(p) {
  margin: 0 0 12px;
}

.query-main-text :deep(p:last-child) {
  margin-bottom: 0;
}

.query-main-text :deep(strong) {
  color: var(--text);
  font-weight: 700;
}

.query-main-text :deep(ul),
.query-main-text :deep(ol) {
  margin: 8px 0 12px 20px;
  padding: 0;
}

.query-main-text :deep(li) {
  margin-bottom: 4px;
}

.query-main-text :deep(code) {
  padding: 2px 6px;
  border-radius: 6px;
  background: #edf4fa;
  color: #19516e;
  font-size: 13px;
}

.query-main-text :deep(pre) {
  margin: 12px 0;
  padding: 14px 16px;
  border-radius: 16px;
  background: #0f172a;
  color: #dbeafe;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.65;
}

.query-main-text :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
  font-size: inherit;
}

.query-main-text :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--accent);
  background: rgba(15, 140, 123, 0.05);
  color: var(--text-muted);
}

.query-main-text :deep(a) {
  color: var(--accent);
  text-decoration: none;
}

.query-main-text :deep(a:hover) {
  text-decoration: underline;
}

.query-main-text :deep(table) {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
  font-size: 13px;
}

.query-main-text :deep(th),
.query-main-text :deep(td) {
  padding: 8px 10px;
  border: 1px solid var(--line-soft);
  text-align: left;
}

.query-main-text :deep(th) {
  background: #f3f8fc;
  font-weight: 700;
}

.query-cursor {
  color: var(--accent);
  animation: query-cursor-blink 1s ease-in-out infinite;
}

.query-citations {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.query-citation-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--text-muted);
  font-size: 12px;
  text-decoration: none;
  transition: border-color 0.18s ease, color 0.18s ease;
}

.query-citation-chip:hover {
  border-color: rgba(15, 140, 123, 0.35);
  color: var(--accent);
}

.query-citation-index {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: var(--surface-soft);
  color: var(--text);
  font-size: 11px;
  font-weight: 700;
}

.query-sql-card,
.query-exec-card {
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: 18px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.04);
}

.query-sql-header,
.query-exec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.query-sql-header span,
.query-exec-head span:first-child {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.query-sql-actions {
  display: flex;
  gap: 8px;
}

.query-btn-sm {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #ffffff;
  color: var(--text);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.query-btn-sm:hover {
  border-color: rgba(15, 140, 123, 0.28);
  background: #f9fcfb;
}

.query-btn-primary {
  border-color: rgba(15, 140, 123, 0.35);
  color: var(--accent);
}

.query-sql-code {
  margin: 0;
  padding: 15px 16px;
  background: #0f172a;
  color: #dbeafe;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.65;
}

.query-exec-meta {
  color: var(--text-soft);
  font-size: 12px;
}

.query-exec-error {
  padding: 14px;
  color: #b42318;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.query-table-wrap {
  overflow-x: auto;
}

.query-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.query-table th,
.query-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--line-soft);
  text-align: left;
  white-space: nowrap;
}

.query-table th {
  background: #f6fafc;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.query-exec-empty {
  padding: 14px;
  color: var(--text-soft);
  font-size: 13px;
}

.query-error-card {
  margin-top: 12px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid rgba(244, 114, 94, 0.25);
  border-radius: 16px;
  background: #fff6f3;
  color: #a2391c;
  font-size: 13px;
  line-height: 1.65;
}

.query-error-label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 40px;
  height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(244, 114, 94, 0.12);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.query-loading {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
}

.query-loading-text {
  color: var(--text-muted);
  font-size: 14px;
}

.query-loading-dots {
  display: inline-flex;
}

.query-loading-dots span {
  color: var(--text-muted);
  font-size: 14px;
  animation: query-dot 1.4s ease-in-out infinite;
}

.query-loading-dots span:nth-child(2) {
  animation-delay: 0.2s;
}

.query-loading-dots span:nth-child(3) {
  animation-delay: 0.4s;
}

.query-composer-wrap {
  flex-shrink: 0;
  padding: 12px 22px 22px;
}

.query-composer {
  max-width: 980px;
  margin: 0 auto;
  border: 1px solid var(--line);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 18px 36px rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

.query-composer-top {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 14px 0;
}

.query-composer-control {
  display: flex;
  align-items: center;
}

.query-select {
  min-width: 180px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-muted);
  color: var(--text);
  font-size: 12px;
  outline: none;
}

.query-composer-input-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 8px 14px 14px;
}

.query-textarea {
  flex: 1;
  min-height: 46px;
  max-height: 140px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--text);
  font-size: 14px;
  line-height: 1.75;
  font-family: inherit;
}

.query-textarea::placeholder {
  color: var(--text-soft);
}

.query-btn-send {
  min-width: 84px;
  height: 42px;
  padding: 0 16px;
  border: none;
  border-radius: 999px;
  background: linear-gradient(135deg, #0f172a 0%, #165d63 100%);
  color: #f8fbff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.query-btn-send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.query-btn-send:not(:disabled):hover {
  opacity: 0.92;
  transform: translateY(-1px);
}

@keyframes query-cursor-blink {
  0% {
    opacity: 0.25;
  }

  50% {
    opacity: 1;
  }

  100% {
    opacity: 0.25;
  }
}

@keyframes query-dot {
  0%,
  20% {
    opacity: 0.2;
  }

  50% {
    opacity: 1;
  }

  80%,
  100% {
    opacity: 0.2;
  }
}

@media (max-width: 1024px) {
  .query-main-head {
    flex-direction: column;
  }

  .query-model-badge {
    min-width: 0;
    width: 100%;
  }
}

@media (max-width: 960px) {
  .query-workbench {
    grid-template-columns: 1fr;
  }

  .query-sidebar {
    display: none;
  }

  .query-messages-inner,
  .query-composer {
    max-width: 100%;
  }

  .query-user-bubble {
    max-width: 88%;
  }
}

@media (max-width: 768px) {
  .query-workbench {
    border-radius: 22px;
  }

  .query-messages-inner {
    padding: 20px 16px 24px;
  }

  .query-main-head h3 {
    font-size: 24px;
  }

  .query-composer-wrap {
    padding: 10px 12px 14px;
  }

  .query-composer-input-row {
    flex-direction: column;
    align-items: stretch;
  }

  .query-btn-send {
    width: 100%;
  }
}
</style>
