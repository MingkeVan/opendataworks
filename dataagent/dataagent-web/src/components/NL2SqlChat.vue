<template>
  <div class="nl2sql-chat">
    <!-- 左侧侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <div class="logo-area">
          <span class="logo-icon">✦</span>
          <span class="logo-text">智能问数助手</span>
        </div>
        <button class="btn-new" @click="handleNewSession">
          <span class="icon-plus">+</span> 新建对话
        </button>
      </div>

      <div class="search-box">
        <input
          v-model="searchKeyword"
          type="text"
          placeholder="搜索对话..."
          class="search-input"
        />
      </div>

      <div class="session-list">
        <div
          v-for="session in filteredSessions"
          :key="session.session_id"
          class="session-item"
          :class="{ active: session.session_id === activeSessionId }"
          @click="handleSelectSession(session.session_id)"
        >
          <div class="session-info">
            <div class="session-title">{{ truncate(session.title, 22) }}</div>
            <div class="session-time">{{ formatTime(session.updated_at || session.created_at) }}</div>
          </div>
          <button class="btn-delete" @click.stop="handleDeleteSession(session.session_id)">×</button>
        </div>
        <div v-if="!filteredSessions.length" class="empty-sessions">
          <span class="empty-icon">💬</span>
          <span>暂无对话</span>
        </div>
      </div>

      <!-- 侧边栏底部设置入口 -->
      <div class="sidebar-footer">
        <button class="btn-settings" @click="showSettings = !showSettings">
          ⚙ 设置
        </button>
      </div>
    </aside>

    <!-- 右侧主聊天区域 -->
    <main class="chat-main">
      <!-- 顶部标题栏 -->
      <header class="chat-header">
        <div class="header-title">{{ activeSession?.title || '新会话' }}</div>
        <div class="header-actions">
          <select v-model="selectedModel" class="db-select model-select">
            <option value="">默认模型</option>
            <option value="glm-4">GLM-4</option>
            <option value="glm-4-plus">GLM-4-Plus</option>
            <option value="glm-5">GLM-5</option>
            <option value="claude-sonnet-4-20250514">Claude 3.5 Sonnet</option>
          </select>
          <select v-model="selectedDatabase" class="db-select">
            <option value="">选择数据库</option>
            <option v-for="db in databases" :key="db" :value="db">{{ db }}</option>
          </select>
          <button class="btn-icon" title="刷新索引" @click="handleReload">🔄</button>
        </div>
      </header>

      <!-- 消息流区域 -->
      <div class="messages-container" ref="messagesRef" @scroll="handleMessagesScroll">
        <div class="messages-inner">
          <!-- 空状态 -->
          <div v-if="!activeMessages.length" class="empty-chat">
            <div class="empty-hero">
              <span class="hero-icon">✦</span>
              <h2>智能问数助手</h2>
              <p>基于语义层的自然语言查询，输入你的数据问题</p>
            </div>
            <div class="suggestion-grid">
              <div
                v-for="(suggestion, index) in suggestions"
                :key="index"
                class="suggestion-card"
                @click="handleSuggestion(suggestion)"
              >
                <span class="suggestion-icon">{{ suggestion.icon }}</span>
                <span class="suggestion-text">{{ suggestion.text }}</span>
              </div>
            </div>
          </div>

          <!-- 消息列表 -->
          <template v-for="(msg, idx) in activeMessages" :key="idx">
            <!-- 用户消息 -->
            <div v-if="msg.role === 'user'" class="message user-message">
              <div class="message-avatar user-avatar">你</div>
              <div class="message-body">
                <div class="message-content">{{ msg.content }}</div>
              </div>
            </div>

            <!-- 助手消息 -->
            <div v-else class="message assistant-message">
              <div class="message-avatar assistant-avatar">AI</div>
              <div class="message-body">
                <!-- 思考过程 -->
                <div v-if="msg.thinking_steps?.length" class="thinking-section">
                  <div
                    class="thinking-header"
                    @click="toggleThinking(thinkingKey(msg, idx))"
                  >
                    <span class="thinking-icon">{{ isThinkingExpanded(thinkingKey(msg, idx)) ? '▼' : '▶' }}</span>
                    <span class="thinking-label">思考过程</span>
                    <span class="thinking-count">{{ msg.thinking_steps.length }} 步</span>
                  </div>
                  <div v-if="isThinkingExpanded(thinkingKey(msg, idx))" class="thinking-steps">
                    <div
                      v-for="(step, si) in msg.thinking_steps"
                      :key="si"
                      class="step-item"
                    >
                      <span class="step-status" :class="step.status">
                        {{ stepIcon(step.status) }}
                      </span>
                      <div class="step-content">
                        <div class="step-name">{{ step.step_name }}</div>
                        <div class="step-summary" v-if="step.summary">{{ step.summary }}</div>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- 解释文本 -->
                <div v-if="msg.explanation" class="explanation-text">{{ msg.explanation }}</div>
                <div
                  v-else-if="showAssistantContentFallback(msg)"
                  class="explanation-text explanation-fallback"
                >
                  {{ msg.content }}
                </div>
                <div
                  v-else-if="showAssistantEmptyState(msg)"
                  class="assistant-empty"
                >
                  已完成分析，但未生成可展示结果，请换个问法重试。
                </div>

                <!-- SQL 代码块 -->
                <div v-if="msg.sql" class="sql-block">
                  <div class="sql-header">
                    <span class="sql-label">生成 SQL</span>
                    <div class="sql-actions">
                      <button class="btn-sm" @click="copyText(msg.sql)">📋 复制</button>
                      <button class="btn-sm btn-primary" @click="handleExecuteSql(msg.sql)">▶ 执行</button>
                    </div>
                  </div>
                  <pre class="sql-code"><code>{{ msg.sql }}</code></pre>
                </div>

                <!-- 查询结果 -->
                <div v-if="msg.execution" class="result-block">
                  <div class="result-header">
                    <span class="result-label">查询结果</span>
                    <span class="result-meta">
                      {{ msg.execution.row_count }} 行
                      <template v-if="msg.execution.has_more">（已截断）</template>
                      · {{ msg.execution.duration_ms }}ms
                    </span>
                  </div>
                  <div v-if="msg.execution.error" class="result-error">
                    ❌ {{ msg.execution.error }}
                  </div>
                  <div v-else-if="msg.execution.rows?.length" class="result-table-wrapper">
                    <table class="result-table">
                      <thead>
                        <tr>
                          <th v-for="col in msg.execution.columns" :key="col">{{ col }}</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="(row, ri) in msg.execution.rows" :key="ri">
                          <td v-for="col in msg.execution.columns" :key="col">{{ row[col] }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </div>

                <!-- 匹配信息 -->
                <div v-if="msg.matched_tables?.length || msg.matched_rules?.length" class="match-info">
                  <span v-for="t in msg.matched_tables" :key="t" class="match-tag table-tag">📊 {{ t }}</span>
                  <span v-for="r in msg.matched_rules" :key="r" class="match-tag rule-tag">📏 {{ r }}</span>
                  <span v-if="msg.confidence" class="confidence-badge">
                    置信度 {{ Math.round(msg.confidence * 100) }}%
                  </span>
                </div>
              </div>
            </div>
          </template>

          <!-- 生成中指示 -->
          <div v-if="generating" class="message assistant-message">
            <div class="message-avatar assistant-avatar">AI</div>
            <div class="message-body">
              <div class="generating-indicator">
                <span class="dot-flashing"></span>
                <span class="generating-text">正在分析并生成 SQL...</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 底部输入区域 -->
      <div class="input-area">
        <div class="input-wrapper">
          <textarea
            v-model="inputText"
            class="input-textarea"
            placeholder="输入你的数据问题...（Ctrl+Enter 发送）"
            rows="3"
            @keydown.ctrl.enter.prevent="handleSend"
            @keydown.meta.enter.prevent="handleSend"
          ></textarea>
          <div class="input-actions">
            <span class="input-hint">Ctrl + Enter 发送</span>
            <button
              class="btn-send"
              :disabled="!inputText.trim() || generating"
              @click="handleSend"
            >
              <span class="send-icon">➤</span> 发送
            </button>
          </div>
        </div>
      </div>
    </main>

    <!-- 设置面板 -->
    <div v-if="showSettings" class="settings-overlay" @click.self="showSettings = false">
      <div class="settings-panel">
        <h3>⚙ 服务设置</h3>
        <div class="setting-group">
          <label>Anthropic API Key</label>
          <input
            v-model="settingsForm.anthropic_api_key"
            type="password"
            placeholder="sk-ant-..."
            class="setting-input"
          />
        </div>
        <div class="setting-group">
          <label>Claude 模型</label>
          <select v-model="settingsForm.claude_model" class="setting-input">
            <option value="claude-sonnet-4-20250514">claude-sonnet-4</option>
            <option value="claude-opus-4-20250514">claude-opus-4</option>
            <option value="claude-3-5-sonnet-20241022">claude-3.5-sonnet</option>
            <option value="claude-3-5-haiku-20241022">claude-3.5-haiku</option>
          </select>
        </div>
        <div class="setting-group">
          <label>MySQL 主机</label>
          <input v-model="settingsForm.mysql_host" class="setting-input" placeholder="localhost" />
        </div>
        <div class="setting-group">
          <label>MySQL 端口</label>
          <input v-model.number="settingsForm.mysql_port" type="number" class="setting-input" />
        </div>
        <div class="setting-group">
          <label>元数据 Schema（只读）</label>
          <input v-model="settingsForm.mysql_database" class="setting-input" placeholder="opendataworks" />
        </div>
        <div class="setting-group">
          <label>知识 Schema（dataagent）</label>
          <input v-model="settingsForm.knowledge_mysql_database" class="setting-input" placeholder="dataagent" />
        </div>
        <div class="setting-group">
          <label>会话 Schema（dataagent）</label>
          <input v-model="settingsForm.session_mysql_database" class="setting-input" placeholder="dataagent" />
        </div>
        <div class="setting-group">
          <label>Doris 主机</label>
          <input v-model="settingsForm.doris_host" class="setting-input" placeholder="localhost" />
        </div>
        <div class="setting-group">
          <label>Doris 端口</label>
          <input v-model.number="settingsForm.doris_port" type="number" class="setting-input" />
        </div>
        <div class="setting-group">
          <label>Tool Runtime</label>
          <select v-model="settingsForm.tool_runtime_mode" class="setting-input">
            <option value="native">native（内置 tool use）</option>
            <option value="mcp_http">mcp_http（外部 MCP 网关）</option>
          </select>
        </div>
        <div class="setting-group">
          <label>MCP HTTP Endpoint</label>
          <input v-model="settingsForm.mcp_http_endpoint" class="setting-input" placeholder="http://localhost:8800/tools/invoke" />
        </div>
        <div class="setting-group">
          <label>Skills 输出目录</label>
          <input v-model="settingsForm.skills_output_dir" class="setting-input" placeholder="../skills/dataagent" />
        </div>
        <div class="setting-actions">
          <button class="btn-cancel" @click="showSettings = false">取消</button>
          <button class="btn-save" @click="handleSaveSettings">保存</button>
        </div>
        <div v-if="settingsStatus" class="settings-status" :class="settingsStatusType">
          {{ settingsStatus }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { createNl2SqlApiClient } from '../api/nl2sqlApi'
import { useNl2SqlSession } from '../composables/useNl2SqlSession'

const props = defineProps({
  nl2sqlBase: {
    type: String,
    default: 'http://localhost:8900'
  }
})

const emit = defineEmits(['sql-generated', 'sql-executed'])

const nl2sqlApi = createNl2SqlApiClient({ baseURL: props.nl2sqlBase })

const {
  loading,
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
} = useNl2SqlSession(nl2sqlApi)

const inputText = ref('')
const searchKeyword = ref('')
const selectedModel = ref('')
const selectedDatabase = ref('')
const databases = ref([])
const showSettings = ref(false)
const messagesRef = ref(null)
const expandedThinking = reactive({})
const shouldAutoScroll = ref(true)
const settingsForm = reactive({
  anthropic_api_key: '',
  claude_model: 'claude-sonnet-4-20250514',
  mysql_host: '',
  mysql_port: 3306,
  mysql_database: '',
  knowledge_mysql_database: 'dataagent',
  session_mysql_database: 'dataagent',
  doris_host: '',
  doris_port: 9030,
  tool_runtime_mode: 'native',
  mcp_http_endpoint: '',
  skills_output_dir: '../skills/dataagent'
})
const settingsStatus = ref('')
const settingsStatusType = ref('')

const suggestions = [
  { icon: '📊', text: '查询今日活跃用户数' },
  { icon: '📈', text: '按产品分类统计收入' },
  { icon: '🔍', text: '最近7天订单趋势' },
  { icon: '👥', text: '查看用户增长情况' },
]

const filteredSessions = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  if (!kw) return sessions.value
  return sessions.value.filter(s =>
    (s.title || '').toLowerCase().includes(kw)
  )
})

const truncate = (text, len) => {
  if (!text) return '新会话'
  return text.length > len ? text.slice(0, len) + '...' : text
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

const stepIcon = (status) => {
  switch (status) {
    case 'success': return '✅'
    case 'failed': return '❌'
    case 'running': return '⏳'
    case 'skipped': return '⏭'
    default: return '⬜'
  }
}

const thinkingKey = (msg, idx) => {
  const stamp = msg?.timestamp || msg?.created_at || ''
  const role = msg?.role || ''
  const tail = (msg?.content || msg?.sql || '').slice(0, 30)
  return `${idx}-${role}-${stamp}-${tail}`
}

const toggleThinking = (key) => {
  expandedThinking[key] = !isThinkingExpanded(key)
}

const isThinkingExpanded = (key) => {
  return expandedThinking[key] !== false
}

const showAssistantContentFallback = (msg) => {
  if (!msg || msg.role !== 'assistant') return false
  const hasExplanation = !!msg.explanation
  const hasSql = !!msg.sql
  const hasExecution = !!msg.execution
  const content = typeof msg.content === 'string' ? msg.content.trim() : ''
  return !hasExplanation && !hasSql && !hasExecution && !!content
}

const showAssistantEmptyState = (msg) => {
  if (!msg || msg.role !== 'assistant') return false
  const hasExplanation = !!msg.explanation
  const hasSql = !!msg.sql
  const hasExecution = !!msg.execution
  const hasContent = !!(typeof msg.content === 'string' && msg.content.trim())
  return !hasExplanation && !hasSql && !hasExecution && !hasContent
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
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

const copyText = async (text) => {
  try {
    await navigator.clipboard.writeText(text)
  } catch (e) {
    console.error('Copy failed:', e)
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

const handleDeleteSession = async (sessionId) => {
  await deleteSession(sessionId)
}

const handleSend = async () => {
  const text = inputText.value.trim()
  if (!text || generating.value) return
  inputText.value = ''
  scrollToBottom()

  try {
    const response = await sendMessage(text, selectedDatabase.value || null, selectedModel.value || null)
    emit('sql-generated', response)
    scrollToBottom(true)
  } catch (error) {
    console.error('Send failed:', error)
  }
}

const handleSuggestion = (suggestion) => {
  inputText.value = suggestion.text
  handleSend()
}

const handleExecuteSql = async (sql) => {
  try {
    const result = await executeSql(sql, selectedDatabase.value || null)
    emit('sql-executed', result)
  } catch (error) {
    console.error('Execute SQL failed:', error)
  }
}

const handleReload = async () => {
  try {
    const res = await nl2sqlApi.reloadSemantic(selectedDatabase.value || null)
    if (res?.skills_sync) {
      console.info('Skills synced:', res.skills_sync)
    }
  } catch (error) {
    console.error('Reload failed:', error)
  }
}

const handleSaveSettings = async () => {
  try {
    settingsStatus.value = '保存中...'
    settingsStatusType.value = ''
    const patch = {}
    Object.entries(settingsForm).forEach(([k, v]) => {
      if (v !== '' && v !== null && v !== undefined) {
        patch[k] = v
      }
    })
    await nl2sqlApi.updateSettings(patch)
    settingsStatus.value = '✅ 保存成功'
    settingsStatusType.value = 'success'
    setTimeout(() => { settingsStatus.value = '' }, 2000)
  } catch (error) {
    settingsStatus.value = `❌ 保存失败: ${error.message}`
    settingsStatusType.value = 'error'
  }
}

const loadSettings = async () => {
  try {
    const cfg = await nl2sqlApi.getSettings()
    if (cfg) {
      settingsForm.claude_model = cfg.claude_model || settingsForm.claude_model
      settingsForm.mysql_host = cfg.mysql_host || ''
      settingsForm.mysql_port = cfg.mysql_port || 3306
      settingsForm.mysql_database = cfg.mysql_database || ''
      settingsForm.knowledge_mysql_database = cfg.knowledge_mysql_database || 'dataagent'
      settingsForm.session_mysql_database = cfg.session_mysql_database || 'dataagent'
      settingsForm.doris_host = cfg.doris_host || ''
      settingsForm.doris_port = cfg.doris_port || 9030
      settingsForm.tool_runtime_mode = cfg.tool_runtime_mode || 'native'
      settingsForm.mcp_http_endpoint = cfg.mcp_http_endpoint || ''
      settingsForm.skills_output_dir = cfg.skills_output_dir || '../skills/dataagent'
    }
  } catch (e) {
    console.warn('Failed to load settings', e)
  }
}

watch(() => activeMessages.value.length, () => {
  activeMessages.value.forEach((msg, idx) => {
    if (msg?.role === 'assistant' && Array.isArray(msg.thinking_steps) && msg.thinking_steps.length > 0) {
      const key = thinkingKey(msg, idx)
      if (expandedThinking[key] === undefined) {
        expandedThinking[key] = true
      }
    }
  })
  scrollToBottom()
})

watch(() => activeSessionId.value, () => {
  shouldAutoScroll.value = true
  scrollToBottom(true)
})

onMounted(async () => {
  await loadSessions()
  await loadSettings()
  scrollToBottom(true)
})
</script>

<style scoped>
/* ---- 根布局 ---- */
.nl2sql-chat {
  display: grid;
  grid-template-columns: 280px 1fr;
  height: 100%;
  min-height: 0;
  background: #ffffff;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Inter', sans-serif;
  color: #1a1f36;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 4px 32px rgba(0, 0, 0, 0.08);
}

/* ---- 侧边栏 ---- */
.sidebar {
  background: linear-gradient(180deg, #0f1629 0%, #1a1f36 100%);
  color: #c9d1e3;
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(255, 255, 255, 0.06);
}

.sidebar-header {
  padding: 20px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.logo-area {
  display: flex;
  align-items: center;
  gap: 8px;
}

.logo-icon {
  font-size: 20px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.logo-text {
  font-size: 15px;
  font-weight: 700;
  color: #e8ecf4;
  letter-spacing: 0.5px;
}

.btn-new {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 14px;
  border: 1px solid rgba(99, 102, 241, 0.4);
  border-radius: 10px;
  background: rgba(99, 102, 241, 0.1);
  color: #a5b4fc;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-new:hover {
  background: rgba(99, 102, 241, 0.2);
  border-color: #6366f1;
  color: #c7d2fe;
}

.icon-plus {
  font-size: 16px;
  font-weight: 300;
}

.search-box {
  padding: 0 16px 8px;
}

.search-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  color: #c9d1e3;
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}

.search-input::placeholder {
  color: #5b6584;
}

.search-input:focus {
  border-color: rgba(99, 102, 241, 0.4);
  background: rgba(255, 255, 255, 0.06);
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 12px;
}

.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  margin-bottom: 4px;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
}

.session-item:hover {
  background: rgba(255, 255, 255, 0.06);
}

.session-item.active {
  background: rgba(99, 102, 241, 0.15);
  border-left: 3px solid #6366f1;
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-title {
  font-size: 13px;
  font-weight: 500;
  color: #e0e5f0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-time {
  font-size: 11px;
  color: #5b6584;
  margin-top: 2px;
}

.btn-delete {
  opacity: 0;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 6px;
  background: rgba(239, 68, 68, 0.15);
  color: #f87171;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}

.session-item:hover .btn-delete {
  opacity: 1;
}

.btn-delete:hover {
  background: rgba(239, 68, 68, 0.3);
}

.empty-sessions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 40px 0;
  color: #5b6584;
  font-size: 13px;
}

.empty-icon {
  font-size: 28px;
  opacity: 0.5;
}

.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.btn-settings {
  width: 100%;
  padding: 8px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #7c8aaa;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-settings:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #a5b4fc;
}

/* ---- 主聊天区 ---- */
.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #fafbfe;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  background: #fff;
  border-bottom: 1px solid #edf0f7;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1f36;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.db-select {
  padding: 6px 10px;
  border: 1px solid #dde3ef;
  border-radius: 8px;
  font-size: 13px;
  color: #3d4663;
  background: #fff;
  outline: none;
}

.model-select {
  width: 140px;
}

.btn-icon {
  width: 32px;
  height: 32px;
  border: 1px solid #dde3ef;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}

.btn-icon:hover {
  background: #f0f3fa;
}

/* ---- 消息流 ---- */
.messages-container {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  background: linear-gradient(180deg, #f5f7fd 0%, #fafbfe 100%);
}

.messages-inner {
  max-width: 860px;
  margin: 0 auto;
  padding: 20px 24px 28px;
}

.empty-chat {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  gap: 32px;
}

.empty-hero {
  text-align: center;
}

.hero-icon {
  font-size: 48px;
  display: block;
  margin-bottom: 12px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6, #a78bfa);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.empty-hero h2 {
  font-size: 22px;
  font-weight: 700;
  color: #1a1f36;
  margin: 0 0 8px;
}

.empty-hero p {
  font-size: 14px;
  color: #7c8aaa;
  margin: 0;
}

.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  width: 100%;
  max-width: 500px;
}

.suggestion-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid #e5e9f5;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 13px;
  color: #3d4663;
}

.suggestion-card:hover {
  border-color: #a5b4fc;
  background: #f5f3ff;
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.1);
}

.suggestion-icon {
  font-size: 18px;
}

/* ---- 消息 ---- */
.message {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.message-avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.user-avatar {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff;
}

.assistant-avatar {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
}

.message-body {
  flex: 1;
  min-width: 0;
}

.user-message {
  flex-direction: row-reverse;
}

.user-message .message-content {
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
  border: 1px solid #c7d2fe;
  border-radius: 16px 16px 4px 16px;
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.6;
  color: #312e81;
  max-width: 70%;
  margin-left: auto;
}

.assistant-message .message-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* ---- 思考过程 ---- */
.thinking-section {
  background: linear-gradient(135deg, #fffbeb, #fef3c7);
  border: 1px solid #fde68a;
  border-radius: 12px;
  overflow: hidden;
}

.thinking-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  user-select: none;
}

.thinking-icon {
  font-size: 10px;
  color: #92400e;
}

.thinking-label {
  font-size: 13px;
  font-weight: 600;
  color: #92400e;
}

.thinking-count {
  font-size: 11px;
  color: #b45309;
  margin-left: auto;
}

.thinking-steps {
  border-top: 1px solid #fde68a;
  padding: 8px 14px;
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 0;
}

.step-status {
  font-size: 14px;
  flex-shrink: 0;
  margin-top: 1px;
}

.step-name {
  font-size: 13px;
  font-weight: 500;
  color: #78350f;
}

.step-summary {
  font-size: 12px;
  color: #a16207;
  margin-top: 2px;
}

/* ---- 解释文本 ---- */
.explanation-text {
  font-size: 14px;
  line-height: 1.7;
  color: #374151;
  padding: 2px 0;
  white-space: pre-wrap;
}

.explanation-fallback {
  white-space: pre-wrap;
}

.assistant-empty {
  font-size: 13px;
  color: #9a3412;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  border-radius: 10px;
  padding: 10px 12px;
}

/* ---- SQL 块 ---- */
.sql-block {
  background: #1e1e2e;
  border-radius: 12px;
  overflow: hidden;
}

.sql-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: rgba(255, 255, 255, 0.04);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.sql-label {
  font-size: 12px;
  font-weight: 600;
  color: #a5b4fc;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.sql-actions {
  display: flex;
  gap: 6px;
}

.btn-sm {
  padding: 4px 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.06);
  color: #c9d1e3;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-sm:hover {
  background: rgba(255, 255, 255, 0.12);
}

.btn-sm.btn-primary {
  background: rgba(99, 102, 241, 0.3);
  border-color: rgba(99, 102, 241, 0.5);
  color: #c7d2fe;
}

.btn-sm.btn-primary:hover {
  background: rgba(99, 102, 241, 0.5);
}

.sql-code {
  margin: 0;
  padding: 14px 16px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #e2e8f0;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---- 查询结果 ---- */
.result-block {
  background: #fff;
  border: 1px solid #e5e9f5;
  border-radius: 12px;
  overflow: hidden;
}

.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: #f8faff;
  border-bottom: 1px solid #e5e9f5;
}

.result-label {
  font-size: 13px;
  font-weight: 600;
  color: #1a1f36;
}

.result-meta {
  font-size: 12px;
  color: #7c8aaa;
}

.result-error {
  padding: 12px 14px;
  font-size: 13px;
  color: #dc2626;
  background: #fef2f2;
}

.result-table-wrapper {
  max-height: 360px;
  overflow: auto;
}

.result-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.result-table th {
  padding: 8px 12px;
  text-align: left;
  font-weight: 600;
  color: #3d4663;
  background: #f8faff;
  border-bottom: 2px solid #e5e9f5;
  white-space: nowrap;
  position: sticky;
  top: 0;
}

.result-table td {
  padding: 7px 12px;
  border-bottom: 1px solid #f0f3fa;
  color: #4b5563;
  white-space: nowrap;
}

.result-table tbody tr:hover {
  background: #f5f7fd;
}

.result-table tbody tr:nth-child(even) {
  background: #fafbfe;
}

/* ---- 匹配信息 ---- */
.match-info {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px 0;
}

.match-tag {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
}

.table-tag {
  background: #eff6ff;
  color: #1d4ed8;
  border: 1px solid #bfdbfe;
}

.rule-tag {
  background: #f0fdf4;
  color: #15803d;
  border: 1px solid #bbf7d0;
}

.confidence-badge {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  background: #fef3c7;
  color: #92400e;
  border: 1px solid #fde68a;
}

/* ---- 生成指示器 ---- */
.generating-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
}

.dot-flashing {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #6366f1;
  animation: dot-flashing 1s infinite alternate;
}

@keyframes dot-flashing {
  0% { opacity: 0.2; transform: scale(0.8); }
  100% { opacity: 1; transform: scale(1.2); }
}

.generating-text {
  font-size: 13px;
  color: #7c8aaa;
  font-style: italic;
}

/* ---- 底部输入 ---- */
.input-area {
  padding: 16px 24px 20px;
  background: #fff;
  border-top: 1px solid #edf0f7;
}

.input-wrapper {
  max-width: 860px;
  margin: 0 auto;
}

.input-textarea {
  width: 100%;
  padding: 12px 14px;
  border: 2px solid #e5e9f5;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
  resize: none;
  outline: none;
  transition: border-color 0.2s;
  font-family: inherit;
  color: #1a1f36;
  background: #fafbfe;
  box-sizing: border-box;
}

.input-textarea::placeholder {
  color: #9ca3af;
}

.input-textarea:focus {
  border-color: #6366f1;
  background: #fff;
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.input-hint {
  font-size: 12px;
  color: #9ca3af;
}

.btn-send {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.3);
}

.btn-send:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.4);
}

.btn-send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-icon {
  font-size: 14px;
}

/* ---- 设置面板 ---- */
.settings-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.settings-panel {
  background: #fff;
  border-radius: 16px;
  padding: 28px;
  width: 420px;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.settings-panel h3 {
  font-size: 17px;
  font-weight: 700;
  margin: 0 0 20px;
  color: #1a1f36;
}

.setting-group {
  margin-bottom: 14px;
}

.setting-group label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  margin-bottom: 5px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.setting-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}

.setting-input:focus {
  border-color: #6366f1;
}

.setting-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}

.btn-cancel {
  padding: 8px 18px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #fff;
  color: #374151;
  font-size: 13px;
  cursor: pointer;
}

.btn-save {
  padding: 8px 18px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.settings-status {
  margin-top: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  text-align: center;
}

.settings-status.success {
  background: #f0fdf4;
  color: #15803d;
}

.settings-status.error {
  background: #fef2f2;
  color: #dc2626;
}

/* ---- 滚动条美化 ---- */
.messages-container::-webkit-scrollbar,
.session-list::-webkit-scrollbar,
.result-table-wrapper::-webkit-scrollbar {
  width: 6px;
}

.messages-container::-webkit-scrollbar-track,
.session-list::-webkit-scrollbar-track {
  background: transparent;
}

.messages-container::-webkit-scrollbar-thumb,
.session-list::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.1);
  border-radius: 3px;
}

.result-table-wrapper::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.15);
  border-radius: 3px;
}

/* ---- 响应式 ---- */
@media (max-width: 768px) {
  .nl2sql-chat {
    grid-template-columns: 1fr;
  }

  .sidebar {
    display: none;
  }

  .suggestion-grid {
    grid-template-columns: 1fr;
  }

  .user-message .message-content {
    max-width: 90%;
  }
}
</style>
