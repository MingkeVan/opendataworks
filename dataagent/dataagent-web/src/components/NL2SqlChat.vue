<template>
  <div class="chat-layout">
    <aside class="sidebar">
      <div class="sidebar-head">
        <h1 class="brand">DataAgent</h1>
        <button class="btn btn-new" @click="handleNewSession">新建</button>
      </div>

      <div class="sidebar-search">
        <input
          v-model="searchKeyword"
          class="search-input"
          type="text"
          placeholder="搜索会话"
        />
      </div>

      <div class="session-list">
        <button
          v-for="session in filteredSessions"
          :key="session.session_id"
          class="session-item"
          :class="{ active: session.session_id === activeSessionId }"
          @click="handleSelectSession(session.session_id)"
        >
          <div class="session-title">{{ truncate(session.title, 20) }}</div>
          <div class="session-time">{{ formatTime(session.updated_at || session.created_at) }}</div>
          <span class="session-count">{{ session.message_count || 0 }}</span>
        </button>
        <div v-if="!filteredSessions.length" class="empty-sessions">暂无会话</div>
      </div>
    </aside>

    <main class="chat-main">
      <header class="chat-header">
        <div class="header-title">{{ activeSession?.title || '新会话' }}</div>
        <div v-if="activeResolvedDatabase" class="header-db">自动库 {{ activeResolvedDatabase }}</div>
      </header>

      <section ref="messagesRef" class="messages" @scroll="handleMessagesScroll">
        <div class="messages-inner">
          <div v-if="!activeMessages.length" class="empty-chat">
            <div class="empty-title">开始提问</div>
            <div class="empty-subtitle">支持流式思考过程、工具执行过程与结果展示</div>
            <div class="suggestions">
              <button
                v-for="item in suggestions"
                :key="item"
                class="suggestion-btn"
                @click="handleSuggestion(item)"
              >
                {{ item }}
              </button>
            </div>
          </div>

          <template v-for="msg in activeMessages" :key="msg.message_id || `${msg.role}-${msg.created_at}`">
            <div v-if="msg.role === 'user'" class="message-row user">
              <div class="user-bubble">{{ msg.content }}</div>
            </div>

            <div v-else class="message-row assistant">
              <article class="assistant-stream" :class="{ streaming: msg.status === 'streaming' }">
                <header class="assistant-head">
                  <span class="assistant-role">Agent</span>
                  <span class="assistant-state">{{ resolveStatusLabel(msg.status) }}</span>
                  <span v-if="msg.stop_reason" class="assistant-stop-reason">结束原因: {{ msg.stop_reason }}</span>
                </header>

                <section class="assistant-flow">
                  <div
                    v-for="block in orderedBlocks(msg)"
                    :key="block.block_id"
                    class="flow-item"
                    :class="blockRowClass(block.type)"
                  >
                    <div v-if="blockLabel(block.type)" class="flow-label">{{ blockLabel(block.type) }}</div>
                    <component
                      :is="resolveBlockComponent(block.type)"
                      :block="block"
                      @copy="copyText"
                      @execute="() => handleExecuteSql(msg)"
                    />
                  </div>
                  <div v-if="msg.sql" class="flow-item type-sql">
                    <div class="flow-label">SQL</div>
                    <SqlBlock :sql="msg.sql" @copy="copyText" @execute="() => handleExecuteSql(msg)" />
                  </div>

                  <div v-if="msg.execution" class="flow-item type-execution">
                    <div class="flow-label">执行结果</div>
                    <ExecutionBlock :execution="msg.execution" />
                  </div>

                  <div v-if="showContentFallback(msg)" class="flow-item type-fallback">
                    <div class="message-content-fallback">{{ msg.content }}</div>
                  </div>
                </section>
              </article>
            </div>
          </template>
        </div>
      </section>

      <footer class="composer-wrap">
        <div class="composer">
          <div class="composer-top">
            <div class="composer-control">
              <label>提供商</label>
              <select v-model="selectedProvider" class="composer-select">
                <option
                  v-for="provider in settings.providers"
                  :key="provider.provider_id"
                  :value="provider.provider_id"
                >
                  {{ provider.display_name }}
                </option>
              </select>
            </div>
            <div class="composer-control">
              <label>模型</label>
              <select v-model="selectedModel" class="composer-select">
                <option
                  v-for="name in availableModels"
                  :key="name"
                  :value="name"
                >
                  {{ name }}
                </option>
              </select>
            </div>
            <label class="debug-toggle">
              <input v-model="debugMode" type="checkbox" />
              显示过程事件
            </label>
          </div>

          <textarea
            v-model="inputText"
            class="composer-input"
            placeholder="输入问题，Ctrl+Enter 发送"
            rows="3"
            @keydown.ctrl.enter.prevent="handleSend"
            @keydown.meta.enter.prevent="handleSend"
          />

          <div class="composer-bottom">
            <span class="composer-hint">Ctrl + Enter 发送</span>
            <button class="btn btn-send" :disabled="!inputText.trim() || generating" @click="handleSend">
              发送
            </button>
          </div>
        </div>
      </footer>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'

import { createNl2SqlApiClient } from '../api/nl2sqlApi'
import { useNl2SqlSession } from '../composables/useNl2SqlSession'
import ErrorBlock from './chat/blocks/ErrorBlock.vue'
import ExecutionBlock from './chat/blocks/ExecutionBlock.vue'
import MainTextBlock from './chat/blocks/MainTextBlock.vue'
import MessageLimitBlock from './chat/blocks/MessageLimitBlock.vue'
import RawBlock from './chat/blocks/RawBlock.vue'
import SqlBlock from './chat/blocks/SqlBlock.vue'
import ThinkingBlock from './chat/blocks/ThinkingBlock.vue'
import ToolBlock from './chat/blocks/ToolBlock.vue'

const props = defineProps({
  nl2sqlBase: {
    type: String,
    default: 'http://localhost:8900'
  }
})

const nl2sqlApi = createNl2SqlApiClient({ baseURL: props.nl2sqlBase })

const {
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
  sendMessage,
  executeSql
} = useNl2SqlSession(nl2sqlApi)

const inputText = ref('')
const searchKeyword = ref('')
const messagesRef = ref(null)
const shouldAutoScroll = ref(true)
const debugMode = ref(true)

const settings = reactive({
  default_provider_id: 'openrouter',
  default_model: 'anthropic/claude-sonnet-4.5',
  providers: []
})

const selectedProvider = ref(settings.default_provider_id)
const selectedModel = ref(settings.default_model)

const suggestions = [
  '查询今日活跃用户数',
  '最近7天订单趋势',
  '各业务线本月收入对比',
  '昨日新增用户按来源分布'
]

const filteredSessions = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (!keyword) return sessions.value
  return sessions.value.filter((item) => String(item.title || '').toLowerCase().includes(keyword))
})

const activeResolvedDatabase = computed(() => {
  const msgs = activeMessages.value || []
  for (let i = msgs.length - 1; i >= 0; i -= 1) {
    const db = msgs[i]?.resolved_database
    if (typeof db === 'string' && db.trim()) return db.trim()
  }
  return ''
})

const activeProviderConfig = computed(() => {
  const providers = Array.isArray(settings.providers) ? settings.providers : []
  return providers.find((item) => item.provider_id === selectedProvider.value) || providers[0] || null
})

const availableModels = computed(() => {
  const provider = activeProviderConfig.value
  const models = Array.isArray(provider?.models) ? [...provider.models] : []
  const fallback = provider?.default_model || settings.default_model
  if (fallback && !models.includes(fallback)) {
    models.unshift(fallback)
  }
  return models
})

const resolveBlockComponent = (type) => {
  if (type === 'main_text') return MainTextBlock
  if (type === 'thinking') return ThinkingBlock
  if (type === 'tool' || type === 'tool_use' || type === 'tool_result') return ToolBlock
  if (type === 'message_limit') return MessageLimitBlock
  if (type === 'error') return ErrorBlock
  if (type === 'raw') return RawBlock
  return MainTextBlock
}

const isDebugBlock = (type) =>
  type === 'thinking' || type === 'tool' || type === 'tool_use' || type === 'tool_result' || type === 'raw'

const displayBlocks = (msg) => {
  const blocks = Array.isArray(msg?.blocks) ? msg.blocks : []
  return blocks.filter((block) => {
    if (!debugMode.value && isDebugBlock(block.type)) return false
    return true
  })
}

const orderedBlocks = (msg) => displayBlocks(msg)

const blockRowClass = (type) => {
  if (type === 'main_text') return 'type-main_text'
  if (type === 'thinking') return 'type-thinking'
  if (type === 'tool' || type === 'tool_use') return 'type-tool_use'
  if (type === 'tool_result') return 'type-tool_result'
  if (type === 'message_limit') return 'type-message_limit'
  if (type === 'error') return 'type-error'
  if (type === 'raw') return 'type-raw'
  return 'type-generic'
}

const blockLabel = (type) => {
  if (type === 'thinking') return '思考'
  if (type === 'tool' || type === 'tool_use') return '工具调用'
  if (type === 'tool_result') return '工具结果'
  if (type === 'raw') return '事件'
  if (type === 'error') return '错误'
  if (type === 'message_limit') return '配额'
  return ''
}

const resolveStatusLabel = (status) => {
  if (status === 'streaming') return '流式输出中'
  if (status === 'failed') return '失败'
  return '完成'
}

const showContentFallback = (msg) => {
  const blocks = orderedBlocks(msg)
  const hasTextBlock = blocks.some((block) => block.type === 'main_text')
  const hasErrorBlock = blocks.some((block) => block.type === 'error')
  return Boolean(msg?.content) && !hasTextBlock && !hasErrorBlock
}

const truncate = (value, maxLength) => {
  const text = String(value || '新会话')
  return text.length > maxLength ? `${text.slice(0, maxLength)}...` : text
}

const formatTime = (value) => {
  if (!value) return ''
  const d = new Date(value)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

const isNearBottom = () => {
  const el = messagesRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 56
}

const handleMessagesScroll = () => {
  shouldAutoScroll.value = isNearBottom()
}

const scrollToBottom = (force = false) => {
  if (!force && !shouldAutoScroll.value) return
  nextTick(() => {
    const el = messagesRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

const copyText = async (value) => {
  try {
    await navigator.clipboard.writeText(String(value || ''))
  } catch (_e) {
    // ignore
  }
}

const handleNewSession = async () => {
  await createSession()
  shouldAutoScroll.value = true
  scrollToBottom(true)
}

const handleSelectSession = async (sessionId) => {
  await selectSession(sessionId)
  shouldAutoScroll.value = true
  scrollToBottom(true)
}

const handleSend = async () => {
  const text = inputText.value.trim()
  if (!text || generating.value) return

  inputText.value = ''
  scrollToBottom()

  try {
    await sendMessage(text, {
      providerId: selectedProvider.value,
      model: selectedModel.value,
      debug: debugMode.value,
      stream: true
    })
    scrollToBottom(true)
  } catch (error) {
    console.error('send failed', error)
  }
}

const handleSuggestion = (value) => {
  inputText.value = value
  handleSend()
}

const handleExecuteSql = async (msg) => {
  if (!msg?.sql) return
  try {
    const result = await executeSql(msg.sql, msg.resolved_database || null)
    msg.execution = result
  } catch (error) {
    msg.execution = {
      error: String(error?.message || '执行失败'),
      row_count: 0,
      duration_ms: 0,
      rows: [],
      columns: []
    }
  }
}

const loadSettings = async () => {
  try {
    const payload = await nl2sqlApi.getSettings()
    settings.default_provider_id = payload?.default_provider_id || settings.default_provider_id
    settings.default_model = payload?.default_model || settings.default_model
    settings.providers = Array.isArray(payload?.providers) ? payload.providers : []
    selectedProvider.value = settings.default_provider_id || settings.providers[0]?.provider_id || 'openrouter'
    const provider = settings.providers.find((item) => item.provider_id === selectedProvider.value)
    selectedModel.value = provider?.default_model || settings.default_model || ''
  } catch (error) {
    console.warn('load settings failed', error)
  }
}

watch(
  () => activeMessages.value.length,
  () => {
    scrollToBottom()
  }
)

watch(
  () => streamTick.value,
  () => {
    scrollToBottom()
  }
)

watch(
  () => [selectedProvider.value, availableModels.value.join('|')],
  () => {
    if (!availableModels.value.includes(selectedModel.value)) {
      selectedModel.value = availableModels.value[0] || settings.default_model || ''
    }
  }
)

watch(
  () => loading.value,
  () => {
    scrollToBottom()
  }
)

onMounted(async () => {
  await loadSettings()
  await loadSessions()
  scrollToBottom(true)
})
</script>

<style scoped>
.chat-layout {
  --line: #dce6f5;
  --text: #0f172a;
  --muted: #64748b;
  --brand: #255dce;
  --brand-soft: #eaf1ff;
  --panel: #f8fbff;
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: 260px 1fr;
  background: linear-gradient(180deg, #f8fbff 0%, #ffffff 30%);
  color: var(--text);
}

.sidebar {
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #fcfdff;
}

.sidebar-head {
  padding: 14px 14px 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.brand {
  font-size: 16px;
  letter-spacing: 0.08em;
  margin: 0;
}

.btn {
  border: 1px solid #d5deea;
  background: #fff;
  color: #1f2937;
  border-radius: 9px;
  cursor: pointer;
  height: 32px;
  padding: 0 12px;
}

.btn-new {
  font-size: 12px;
}

.sidebar-search {
  padding: 0 14px 10px;
}

.search-input {
  width: 100%;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  height: 32px;
  padding: 0 10px;
  background: #ffffff;
  outline: none;
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 10px 12px;
}

.session-item {
  width: 100%;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  text-align: left;
  padding: 10px 10px;
  margin-bottom: 6px;
  cursor: pointer;
}

.session-item:hover {
  background: #f3f7ff;
}

.session-item.active {
  border-color: #c9daf8;
  background: #eef4ff;
}

.session-title {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.session-time {
  margin-top: 4px;
  font-size: 12px;
  color: var(--muted);
}

.session-count {
  margin-top: 2px;
  display: inline-block;
  font-size: 11px;
  color: #7f8ba0;
}

.empty-sessions {
  color: #8a94a7;
  font-size: 13px;
  text-align: center;
  padding: 24px 0;
}

.chat-main {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chat-header {
  height: 52px;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
  background: #ffffff;
  flex-shrink: 0;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
}

.header-db {
  font-size: 12px;
  color: var(--muted);
}

.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: linear-gradient(180deg, #f7faff 0%, #ffffff 26%, #ffffff 100%);
}

.messages-inner {
  max-width: 960px;
  margin: 0 auto;
  padding: 20px 22px 28px;
}

.empty-chat {
  min-height: 320px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  text-align: center;
}

.empty-title {
  font-size: 20px;
  font-weight: 700;
}

.empty-subtitle {
  margin-top: 10px;
  color: #64748b;
  font-size: 14px;
}

.suggestions {
  margin-top: 18px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
  padding: 0 20px;
}

.suggestion-btn {
  border: 1px solid #dbe4ef;
  border-radius: 999px;
  background: #fff;
  height: 34px;
  padding: 0 14px;
  color: #334155;
  cursor: pointer;
}

.message-row {
  margin-bottom: 18px;
  display: flex;
}

.message-row.user {
  justify-content: flex-end;
}

.message-row.assistant {
  justify-content: flex-start;
}

.user-bubble {
  width: min(72%, 760px);
  border-radius: 12px;
  background: #e9f1ff;
  border: 1px solid #d6e4ff;
  padding: 10px 12px;
  white-space: pre-wrap;
  line-height: 1.6;
  color: #1e3a8a;
  font-size: 14px;
}

.assistant-stream {
  width: min(100%, 820px);
  padding-left: 14px;
  border-left: 2px solid #d7e4ff;
}

.assistant-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.assistant-role {
  font-size: 12px;
  color: #38507f;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  font-weight: 700;
}

.assistant-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}

.assistant-stop-reason {
  font-size: 12px;
  color: #64748b;
}

.assistant-state::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #8ea3c7;
}

.assistant-stream.streaming .assistant-state::before {
  background: var(--brand);
  animation: pulse 1.1s ease-in-out infinite;
}

.assistant-flow {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.flow-item {
  position: relative;
  padding-left: 12px;
}

.flow-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #b5c6e8;
}

.flow-item.type-main_text::before {
  background: #7e97c7;
}

.flow-item.type-message_limit::before {
  background: #8ea3c7;
}

.flow-item.type-error::before {
  background: #dc2626;
}

.assistant-stream.streaming .flow-item:last-child::before {
  background: var(--brand);
  animation: pulse 1.1s ease-in-out infinite;
}

.flow-label {
  font-size: 11px;
  color: #7184a6;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 4px;
}

.message-content-fallback {
  white-space: pre-wrap;
  line-height: 1.6;
  color: #0f172a;
  font-size: 14px;
}

@keyframes pulse {
  0% { opacity: 0.35; }
  50% { opacity: 1; }
  100% { opacity: 0.35; }
}

.composer-wrap {
  border-top: 1px solid var(--line);
  background: #ffffff;
  padding: 12px 16px 14px;
  flex-shrink: 0;
}

.composer {
  max-width: 920px;
  margin: 0 auto;
  border: 1px solid #d4deee;
  border-radius: 12px;
  padding: 10px;
  background: #fcfdff;
}

.composer-top {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.composer-control {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.composer-control label {
  font-size: 11px;
  color: #64748b;
}

.composer-select {
  border: 1px solid #d8e0ea;
  border-radius: 8px;
  height: 30px;
  padding: 0 10px;
  background: #ffffff;
}

.debug-toggle {
  margin-left: auto;
  font-size: 12px;
  color: #475467;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.composer-input {
  width: 100%;
  border: none;
  resize: none;
  outline: none;
  font-size: 14px;
  line-height: 1.65;
  color: #111827;
  background: transparent;
  min-height: 64px;
}

.composer-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.composer-hint {
  color: #94a3b8;
  font-size: 12px;
}

.btn-send {
  border-color: var(--brand);
  color: var(--brand);
}

.btn-send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 980px) {
  .chat-layout {
    grid-template-columns: 1fr;
  }

  .sidebar {
    display: none;
  }

  .messages-inner,
  .composer {
    max-width: 100%;
  }

  .user-bubble {
    width: min(92%, 760px);
  }

  .composer-top {
    gap: 8px;
  }

  .debug-toggle {
    margin-left: 0;
  }
}
</style>
