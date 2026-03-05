<template>
  <div v-if="entryVisible" class="assistant-host">
    <el-button class="assistant-fab" type="primary" circle @click="openWorkspace">
      <el-icon><ChatDotRound /></el-icon>
    </el-button>

    <AssistantWorkspaceDialog
      v-model:visible="workspaceVisible"
      :loading="loading"
      :sessions="sessions"
      :active-session-id="activeSessionId"
      :history-list="historyList"
      :loading-history="loadingHistory"
      @add-session="handleAddSession"
      @select-session="handleSelectSession"
      @close-session="handleCloseSession"
      @send-message="handleSendMessage"
      @approve-run="handleApproveRun"
      @cancel-run="handleCancelRun"
      @update-context="handleUpdateContext"
      @import-history="handleImportHistory"
      @use-history-sql="handleUseHistorySql"
      @execute-sql="handleExecuteSql"
      @regenerate-chart="handleRegenerateChart"
      @update-sql-draft="handleUpdateSqlDraft"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { createAssistantApiClient } from '../api/assistantApi'
import { useAssistantSession } from '../composables/useAssistantSession'
import { useAssistantStream } from '../composables/useAssistantStream'
import AssistantWorkspaceDialog from './AssistantWorkspaceDialog.vue'

const props = defineProps({
  entryVisible: {
    type: Boolean,
    default: true
  },
  autoOpen: {
    type: Boolean,
    default: false
  },
  apiBase: {
    type: String,
    default: '/api'
  },
  streamBase: {
    type: String,
    default: '/api'
  },
  requestTimeout: {
    type: Number,
    default: 60000
  }
})

const assistantApi = createAssistantApiClient({
  apiBase: props.apiBase,
  streamBase: props.streamBase,
  timeout: props.requestTimeout
})

const {
  loading,
  sessions,
  activeSessionId,
  loadSessions,
  createBlankSession,
  selectSession,
  closeSession,
  updateSessionContext,
  updateDraftSql,
  sendMessage,
  applyStreamEvent
} = useAssistantSession(assistantApi)

const { subscribeRun, closeRun, closeAll } = useAssistantStream(assistantApi)

const workspaceVisible = ref(false)
const loaded = ref(false)
const historyList = ref([])
const loadingHistory = ref(false)
const subscribedRuns = new Set()

const isEntryEnabled = computed(() => !!props.entryVisible)

const ensureLoaded = async () => {
  if (loaded.value) return
  await loadSessions()
  loaded.value = true
  subscribeLatestRunning()
}

const subscribeLatestRunning = () => {
  sessions.value.forEach((state) => {
    const run = state.runs[state.runs.length - 1]
    if (!run) return
    if (['running', 'waiting_approval', 'cancel_requested', 'queued'].includes(run.status)) {
      subscribeRunForSession(state.session.sessionId, run.runId)
    }
  })
}

const subscribeRunForSession = (sessionId, runId) => {
  if (!runId || subscribedRuns.has(runId)) return
  subscribedRuns.add(runId)
  subscribeRun(runId, {
    onEvent: (payload) => {
      applyStreamEvent(sessionId, payload)
      if (['run_completed', 'run_failed', 'run_cancelled'].includes(payload.event)) {
        closeRun(runId)
        subscribedRuns.delete(runId)
      }
    },
    onError: () => {
      subscribedRuns.delete(runId)
    }
  })
}

const getSessionState = (sessionId) => {
  return sessions.value.find(item => item.session.sessionId === sessionId)
}

const openWorkspace = async () => {
  await ensureLoaded()
  workspaceVisible.value = true
}

const handleAddSession = async () => {
  await createBlankSession()
}

const handleSelectSession = async (sessionId) => {
  await selectSession(sessionId)
}

const handleCloseSession = async (sessionId) => {
  await closeSession(sessionId)
}

const handleUpdateContext = ({ sessionId, patch }) => {
  updateSessionContext(sessionId, patch)
}

const handleUpdateSqlDraft = ({ sessionId, sql }) => {
  updateDraftSql(sessionId, sql)
}

const handleSendMessage = async ({ sessionId, content }) => {
  try {
    const response = await sendMessage(sessionId, content)
    if (response?.runId) {
      subscribeRunForSession(sessionId, response.runId)
    }
  } catch (error) {
    ElMessage.error(error.message || '发送失败')
  }
}

const handleExecuteSql = async ({ sessionId, sql }) => {
  updateDraftSql(sessionId, sql)
  await handleSendMessage({ sessionId, content: sql })
}

const handleApproveRun = async ({ sessionId, runId, approved }) => {
  try {
    await assistantApi.approveRun(runId, { approved, comment: '' })
    subscribeRunForSession(sessionId, runId)
  } catch (error) {
    ElMessage.error(error.message || '审批操作失败')
  }
}

const handleCancelRun = async ({ runId }) => {
  try {
    await assistantApi.cancelRun(runId)
  } catch (error) {
    ElMessage.error(error.message || '取消失败')
  }
}

const handleImportHistory = async ({ sessionId }) => {
  const state = getSessionState(sessionId)
  if (!state) return

  loadingHistory.value = true
  try {
    const context = state.session.context || {}
    const page = await assistantApi.listQueryHistory({
      pageNum: 1,
      pageSize: 20,
      clusterId: context.sourceId || undefined,
      database: context.database || undefined
    })
    historyList.value = page?.records || []
    if (!historyList.value.length) {
      ElMessage.info('暂无可导入的查询历史')
    }
  } catch (error) {
    ElMessage.error(error.message || '加载查询历史失败')
  } finally {
    loadingHistory.value = false
  }
}

const handleUseHistorySql = ({ sessionId, sql }) => {
  updateDraftSql(sessionId, sql)
}

const handleRegenerateChart = async ({ sessionId, chartType }) => {
  if (!chartType) return
  const state = getSessionState(sessionId)
  if (!state?.draftSql) {
    ElMessage.warning('没有可用于重选图表的 SQL 草稿')
    return
  }
  const prompt = `${state.draftSql}\n\n-- chartType: ${chartType}`
  await handleSendMessage({ sessionId, content: prompt })
}

watch(isEntryEnabled, async (value) => {
  if (!value) {
    workspaceVisible.value = false
    return
  }
  await ensureLoaded()
}, { immediate: true })

onMounted(async () => {
  if (props.autoOpen) {
    await openWorkspace()
    return
  }
  if (isEntryEnabled.value) {
    await ensureLoaded()
  }
})

watch(() => workspaceVisible.value, async (value) => {
  if (value) {
    await ensureLoaded()
  }
})

watch(() => activeSessionId.value, () => {
  historyList.value = []
})

onMounted(() => {
  window.addEventListener('beforeunload', closeAll)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', closeAll)
  closeAll()
})
</script>

<style scoped>
.assistant-host {
  position: fixed;
  right: 20px;
  bottom: 22px;
  z-index: 1200;
}

.assistant-fab {
  width: 52px;
  height: 52px;
  box-shadow: 0 10px 24px rgba(80, 108, 255, 0.35);
}

@media (max-width: 768px) {
  .assistant-host {
    right: 12px;
    bottom: 12px;
  }

  .assistant-fab {
    width: 46px;
    height: 46px;
  }
}
</style>
