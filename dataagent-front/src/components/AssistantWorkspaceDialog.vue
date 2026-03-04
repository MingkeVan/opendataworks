<template>
  <el-dialog
    :model-value="visible"
    :fullscreen="fullscreen"
    :width="fullscreen ? '100%' : '88vw'"
    top="6vh"
    class="assistant-workspace-dialog"
    :show-close="false"
    :close-on-click-modal="false"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="workspace-shell" v-loading="loading">
      <aside class="left-sidebar">
        <div class="left-header">
          <div class="title">智能问数助手</div>
          <el-button size="small" type="primary" @click="emit('add-session')">新建</el-button>
        </div>
        <el-input
          v-model="keyword"
          size="small"
          placeholder="搜索会话"
          clearable
        />
        <el-scrollbar class="session-scroll">
          <div
            v-for="state in filteredSessions"
            :key="state.session.sessionId"
            class="session-item"
            :class="{ active: state.session.sessionId === activeSessionId }"
            @click="emit('select-session', state.session.sessionId)"
          >
            <div class="session-main">
              <div class="session-title">{{ tabLabel(state) }}</div>
              <div class="session-time">{{ formatTime(state.session.updatedAt || state.session.createdAt) }}</div>
            </div>
            <el-button
              text
              type="danger"
              class="close-btn"
              @click.stop="emit('close-session', state.session.sessionId)"
            >
              关闭
            </el-button>
          </div>
        </el-scrollbar>
      </aside>

      <section class="right-chat">
        <header class="chat-header">
          <div class="chat-title">{{ activeState?.session?.title || '新会话' }}</div>
          <div class="chat-actions">
            <el-button size="small" @click="emit('import-history', { sessionId: activeState?.session?.sessionId })" :disabled="!activeState">导入历史 SQL</el-button>
            <el-button size="small" @click="toggleFullscreen">{{ fullscreen ? '退出全屏' : '全屏' }}</el-button>
            <el-button size="small" @click="emit('update:visible', false)">关闭</el-button>
          </div>
        </header>

        <div class="context-row" v-if="activeState">
          <el-input
            :model-value="activeState.session.context?.sourceId"
            size="small"
            placeholder="sourceId"
            @input="onContextChange('sourceId', toNumber($event))"
          />
          <el-input
            :model-value="activeState.session.context?.database"
            size="small"
            placeholder="database"
            @input="onContextChange('database', $event)"
          />
          <el-select
            :model-value="activeState.session.context?.limitProfile || 'text_answer'"
            size="small"
            @change="onContextChange('limitProfile', $event)"
          >
            <el-option label="validation" value="validation" />
            <el-option label="text_answer" value="text_answer" />
            <el-option label="bi_sampling" value="bi_sampling" />
            <el-option label="manual_execute" value="manual_execute" />
          </el-select>
          <el-select
            :model-value="activeState.session.context?.mode || 'need-confirm'"
            size="small"
            @change="onContextChange('mode', $event)"
          >
            <el-option label="need-confirm" value="need-confirm" />
            <el-option label="yolo" value="yolo" />
          </el-select>
          <el-input
            v-if="activeState.session.context?.limitProfile === 'manual_execute'"
            :model-value="activeState.session.context?.manualLimit"
            size="small"
            placeholder="manual limit"
            @input="onContextChange('manualLimit', toNumber($event))"
          />
        </div>

        <div class="history-row" v-if="activeState && historyList.length">
          <el-select v-model="selectedHistorySql" size="small" class="history-select" placeholder="选择最近 SQL">
            <el-option
              v-for="item in historyList"
              :key="item.id"
              :label="historyLabel(item)"
              :value="item.sqlText"
            />
          </el-select>
          <el-button size="small" @click="applyHistory">使用</el-button>
        </div>

        <ChatPanel
          v-if="activeState"
          :messages="activeState.messages"
          :steps="activeState.steps"
          :artifacts="activeState.artifacts"
          :pending-approval="activeState.pendingApproval"
          :latest-run="latestRun"
          @send-message="(content) => emit('send-message', { sessionId: activeState.session.sessionId, content })"
          @approve-run="(payload) => emit('approve-run', { sessionId: activeState.session.sessionId, ...payload })"
          @cancel-run="(runId) => emit('cancel-run', { sessionId: activeState.session.sessionId, runId })"
          @execute-sql="(sql) => emit('execute-sql', { sessionId: activeState.session.sessionId, sql })"
          @regenerate-chart="(chartType) => emit('regenerate-chart', { sessionId: activeState.session.sessionId, chartType })"
          @update-sql-draft="(sql) => emit('update-sql-draft', { sessionId: activeState.session.sessionId, sql })"
        />

        <el-empty v-else description="暂无会话" :image-size="80" />
      </section>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import dayjs from 'dayjs'
import ChatPanel from './ChatPanel.vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  loading: {
    type: Boolean,
    default: false
  },
  sessions: {
    type: Array,
    default: () => []
  },
  activeSessionId: {
    type: String,
    default: ''
  },
  historyList: {
    type: Array,
    default: () => []
  },
  loadingHistory: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits([
  'update:visible',
  'add-session',
  'select-session',
  'close-session',
  'send-message',
  'approve-run',
  'cancel-run',
  'update-context',
  'import-history',
  'use-history-sql',
  'execute-sql',
  'regenerate-chart',
  'update-sql-draft'
])

const keyword = ref('')
const fullscreen = ref(false)
const selectedHistorySql = ref('')

const activeState = computed(() => {
  return props.sessions.find(item => item.session.sessionId === props.activeSessionId) || null
})

const latestRun = computed(() => {
  const runs = activeState.value?.runs || []
  if (!runs.length) return null
  return runs[runs.length - 1]
})

const filteredSessions = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return props.sessions
  return props.sessions.filter(item => {
    const title = item.session.title || ''
    return title.toLowerCase().includes(kw)
  })
})

const toggleFullscreen = () => {
  fullscreen.value = !fullscreen.value
}

const tabLabel = (state) => {
  const title = state.session.title || '新会话'
  return title.length > 16 ? `${title.slice(0, 16)}...` : title
}

const formatTime = (value) => {
  if (!value) return '-'
  return dayjs(value).format('MM-DD HH:mm')
}

const toNumber = (value) => {
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  return Number.isNaN(num) ? null : num
}

const onContextChange = (key, value) => {
  if (!activeState.value) return
  emit('update-context', {
    sessionId: activeState.value.session.sessionId,
    patch: { [key]: value }
  })
}

const applyHistory = () => {
  if (!selectedHistorySql.value || !activeState.value) return
  emit('use-history-sql', {
    sessionId: activeState.value.session.sessionId,
    sql: selectedHistorySql.value
  })
  emit('update-sql-draft', {
    sessionId: activeState.value.session.sessionId,
    sql: selectedHistorySql.value
  })
}

const historyLabel = (item) => {
  const sql = item.sqlText || ''
  const db = item.databaseName || '-'
  const preview = sql.length > 48 ? `${sql.slice(0, 48)}...` : sql
  return `[${db}] ${preview}`
}
</script>

<style scoped>
.workspace-shell {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 0;
  height: 80vh;
  min-height: 640px;
}

.left-sidebar {
  border-right: 1px solid #e4e7ed;
  background: linear-gradient(180deg, #f8fbff 0%, #f5f7fa 100%);
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
}

.left-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2d3d;
}

.session-scroll {
  flex: 1;
  min-height: 0;
}

.session-item {
  border: 1px solid #e6edf5;
  border-radius: 10px;
  padding: 8px;
  background: #fff;
  margin-bottom: 8px;
  cursor: pointer;
}

.session-item.active {
  border-color: #4c7dff;
  background: #f2f6ff;
}

.session-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.session-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2d3d;
}

.session-time {
  font-size: 12px;
  color: #7a8799;
}

.close-btn {
  padding: 0;
  margin-top: 2px;
  font-size: 12px;
}

.right-chat {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #ffffff;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid #ebeef5;
}

.chat-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
}

.chat-actions {
  display: flex;
  gap: 8px;
}

.context-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(90px, 1fr));
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #f0f2f5;
}

.history-row {
  display: flex;
  gap: 8px;
  padding: 8px 14px;
  border-bottom: 1px solid #f0f2f5;
}

.history-select {
  flex: 1;
}

:deep(.assistant-workspace-dialog .el-dialog__header) {
  display: none;
}

:deep(.assistant-workspace-dialog .el-dialog__body) {
  padding: 0;
}

:deep(.assistant-workspace-dialog .el-dialog) {
  border-radius: 16px;
  overflow: hidden;
}

@media (max-width: 1200px) {
  .workspace-shell {
    grid-template-columns: 240px 1fr;
  }

  .context-row {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}

@media (max-width: 900px) {
  .workspace-shell {
    grid-template-columns: 1fr;
    height: 86vh;
  }

  .left-sidebar {
    display: none;
  }
}
</style>
