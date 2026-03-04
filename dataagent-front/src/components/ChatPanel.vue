<template>
  <section class="chat-panel">
    <div class="stream-container">
      <el-scrollbar ref="scrollbarRef" class="message-scroll">
        <div class="message-list">
          <div
            v-for="item in timelineItems"
            :key="item.key"
            class="message-item"
            :class="item.className"
          >
            <template v-if="item.kind === 'message'">
              <div class="item-head">
                <span class="role-pill">{{ item.role === 'assistant' ? '助手' : '你' }}</span>
                <span class="item-time">{{ formatTime(item.at) }}</span>
              </div>
              <div class="text-content">{{ item.content }}</div>
            </template>

            <template v-else-if="item.kind === 'step'">
              <div class="item-head">
                <span class="role-pill system">计划/结论</span>
                <el-tag size="small" :type="statusTagType(item.status)">{{ item.status }}</el-tag>
              </div>
              <div class="step-title">{{ item.name }}</div>
              <div class="step-summary">{{ item.summary }}</div>
            </template>

            <template v-else-if="item.kind === 'artifact'">
              <div class="item-head">
                <span class="role-pill tool">工具结果</span>
                <span class="artifact-type">{{ item.title || item.artifactType }}</span>
              </div>

              <template v-if="item.artifactType === 'sql'">
                <el-input
                  :model-value="item.parsed?.sql || ''"
                  type="textarea"
                  :rows="5"
                  resize="none"
                  @update:model-value="emit('update-sql-draft', $event)"
                />
                <div class="card-actions">
                  <el-button size="small" @click="copyText(item.parsed?.sql || '')">复制 SQL</el-button>
                  <el-button size="small" type="primary" @click="emitExecute(item.parsed?.sql || '')">执行 SQL</el-button>
                </div>
              </template>

              <template v-else-if="item.artifactType === 'query_result'">
                <div class="result-summary">
                  <span>返回 {{ item.parsed?.previewRowCount || 0 }} 行</span>
                  <span v-if="item.parsed?.hasMore">（结果已截断）</span>
                </div>
                <pre class="json-preview">{{ toPretty(item.parsed?.rows || []) }}</pre>
              </template>

              <template v-else-if="item.artifactType === 'chart'">
                <BiPanel
                  :chart="item.parsed"
                  @regenerate-chart="(chartType) => emit('regenerate-chart', chartType)"
                />
              </template>

              <template v-else>
                <pre class="json-preview">{{ toPretty(item.parsed || {}) }}</pre>
              </template>
            </template>
          </div>
        </div>
      </el-scrollbar>

      <div v-if="pendingApproval" class="approval-card">
        <div class="approval-title">检测到高风险 SQL，等待审批</div>
        <div class="approval-sql">{{ pendingApproval.sql }}</div>
        <div class="approval-actions">
          <el-button size="small" type="danger" plain @click="emitApprove(false)">拒绝</el-button>
          <el-button size="small" type="primary" @click="emitApprove(true)">批准并执行</el-button>
        </div>
      </div>
    </div>

    <div class="input-area">
      <el-input
        v-model="messageText"
        type="textarea"
        :rows="3"
        resize="none"
        placeholder="输入问题或 SQL...（Ctrl/Cmd + Enter 发送）"
        @keydown.ctrl.enter.prevent="submit"
        @keydown.meta.enter.prevent="submit"
      />
      <div class="input-actions">
        <el-button size="small" @click="cancelRun" :disabled="!latestRun">停止</el-button>
        <el-button size="small" type="primary" @click="submit">发送</el-button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import BiPanel from './BiPanel.vue'

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  },
  steps: {
    type: Array,
    default: () => []
  },
  artifacts: {
    type: Array,
    default: () => []
  },
  pendingApproval: {
    type: Object,
    default: null
  },
  latestRun: {
    type: Object,
    default: null
  }
})

const emit = defineEmits([
  'send-message',
  'approve-run',
  'cancel-run',
  'execute-sql',
  'regenerate-chart',
  'update-sql-draft'
])

const messageText = ref('')
const scrollbarRef = ref(null)

const parseArtifactContent = (artifact) => {
  if (!artifact) return null
  if (artifact.parsed !== undefined) return artifact.parsed
  if (!artifact.contentJson) return null
  try {
    return JSON.parse(artifact.contentJson)
  } catch (error) {
    return artifact.contentJson
  }
}

const timelineItems = computed(() => {
  const list = []

  props.messages.forEach((message) => {
    list.push({
      key: `msg-${message.id}-${message.createdAt}`,
      kind: 'message',
      role: message.role,
      content: message.content,
      at: message.createdAt,
      className: message.role === 'assistant' ? 'assistant' : 'user'
    })
  })

  props.steps.forEach((step, index) => {
    list.push({
      key: `step-${step.runId}-${step.stepOrder || index}-${step.at}`,
      kind: 'step',
      name: step.stepName || step.stepKey,
      summary: step.summary,
      status: step.status,
      at: step.at,
      className: 'system'
    })
  })

  props.artifacts.forEach((artifact, index) => {
    list.push({
      key: `artifact-${artifact.id || index}`,
      kind: 'artifact',
      artifactType: artifact.artifactType,
      title: artifact.title,
      parsed: parseArtifactContent(artifact),
      at: artifact.createdAt,
      className: 'tool'
    })
  })

  return list.sort((a, b) => {
    const at = new Date(a.at || 0).getTime()
    const bt = new Date(b.at || 0).getTime()
    return at - bt
  })
})

const formatTime = (value) => {
  if (!value) return ''
  return dayjs(value).format('HH:mm:ss')
}

const statusTagType = (status) => {
  if (status === 'success') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'waiting' || status === 'running') return 'warning'
  return 'info'
}

const submit = () => {
  const text = messageText.value.trim()
  if (!text) return
  emit('send-message', text)
  messageText.value = ''
}

const emitApprove = (approved) => {
  if (!props.pendingApproval) return
  emit('approve-run', {
    runId: props.pendingApproval.runId,
    approved
  })
}

const cancelRun = () => {
  if (!props.latestRun?.runId) return
  emit('cancel-run', props.latestRun.runId)
}

const emitExecute = (sql) => {
  const text = (sql || '').trim()
  if (!text) {
    ElMessage.warning('SQL 为空')
    return
  }
  emit('execute-sql', text)
}

const copyText = async (text) => {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

const toPretty = (value) => {
  return JSON.stringify(value, null, 2)
}

const scrollToBottom = () => {
  nextTick(() => {
    const wrap = scrollbarRef.value?.wrapRef
    if (!wrap) return
    wrap.scrollTop = wrap.scrollHeight
  })
}

watch(() => timelineItems.value.length, () => {
  scrollToBottom()
})
</script>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}

.stream-container {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.message-scroll {
  flex: 1;
  min-height: 0;
  background: linear-gradient(180deg, #fbfcfe 0%, #f7f9fc 100%);
}

.message-list {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.message-item {
  border-radius: 12px;
  padding: 10px;
  border: 1px solid #e8edf5;
  background: #fff;
}

.message-item.user {
  border-color: #b8d6ff;
  background: #edf5ff;
  margin-left: 36px;
}

.message-item.assistant {
  border-color: #e5e8ef;
  margin-right: 36px;
}

.message-item.system {
  border-color: #ffe1a8;
  background: #fffaf0;
}

.message-item.tool {
  border-color: #c9d8ff;
  background: #f6f9ff;
}

.item-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.role-pill {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #eaf0ff;
  color: #345;
}

.role-pill.system {
  background: #ffeecf;
  color: #7a5600;
}

.role-pill.tool {
  background: #dce7ff;
  color: #304f9f;
}

.item-time {
  font-size: 11px;
  color: #8492a6;
}

.text-content {
  margin-top: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
}

.step-title {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
}

.step-summary {
  margin-top: 4px;
  font-size: 12px;
  color: #5f6b7b;
  white-space: pre-wrap;
}

.artifact-type {
  font-size: 12px;
  color: #52627a;
}

.card-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.json-preview {
  margin-top: 8px;
  padding: 8px;
  max-height: 240px;
  overflow: auto;
  border: 1px solid #d9e3f4;
  border-radius: 8px;
  background: #fff;
  font-size: 12px;
  color: #334155;
}

.result-summary {
  margin-top: 8px;
  font-size: 12px;
  color: #3f4f66;
}

.approval-card {
  border-top: 1px solid #f1d7a3;
  background: #fffaf0;
  padding: 10px 12px;
}

.approval-title {
  font-weight: 600;
  color: #9a6b00;
}

.approval-sql {
  margin-top: 6px;
  font-size: 12px;
  color: #5e4b1f;
  white-space: pre-wrap;
  max-height: 120px;
  overflow: auto;
}

.approval-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.input-area {
  border-top: 1px solid #e8edf5;
  padding: 10px 12px;
  background: #fff;
}

.input-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
