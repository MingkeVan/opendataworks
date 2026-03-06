<template>
  <div class="tool-line" :class="`status-${block.status || 'success'}`">
    <div class="tool-head">
      <span class="tool-dot"></span>
      <span class="tool-kind">{{ kindText }}</span>
      <span class="tool-name">{{ toolName }}</span>
      <span class="tool-status">{{ statusText }}</span>
    </div>

    <div v-if="previewText" class="tool-preview">{{ previewText }}</div>

    <details v-if="hasDetails" class="tool-details">
      <summary>查看详情</summary>
      <div class="detail-grid">
        <div v-if="inputText" class="detail-item">
          <div class="detail-label">输入</div>
          <pre><code>{{ inputText }}</code></pre>
        </div>
        <div v-if="outputText" class="detail-item">
          <div class="detail-label">输出</div>
          <pre><code>{{ outputText }}</code></pre>
        </div>
        <div v-if="payloadText && !inputText && !outputText" class="detail-item">
          <div class="detail-label">事件</div>
          <pre><code>{{ payloadText }}</code></pre>
        </div>
      </div>
    </details>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  block: {
    type: Object,
    required: true
  }
})

const toolName = computed(() => {
  const name = String(props.block?.tool_name || '').trim()
  return name || 'Tool'
})

const blockType = computed(() => String(props.block?.type || props.block?.payload?.claude_type || '').toLowerCase())

const kindText = computed(() => {
  if (blockType.value === 'tool_result') return '工具结果'
  return '工具调用'
})

const statusText = computed(() => {
  const status = String(props.block?.status || 'success')
  if (status === 'pending') return '等待中'
  if (status === 'streaming') return '执行中'
  if (status === 'failed') return '失败'
  return '完成'
})

const toDetailText = (value, max = 3000) => {
  if (value === null || value === undefined) return ''
  const raw = (() => {
    if (typeof value === 'string') return value
    try {
      return JSON.stringify(value, null, 2)
    } catch (_error) {
      return String(value)
    }
  })()
  if (raw.length <= max) return raw
  return `${raw.slice(0, max)}\n...已截断，共 ${raw.length} 字符`
}

const toInlinePreview = (value, max = 200) => {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') {
    const cleaned = value.replace(/\s+/g, ' ').trim()
    if (cleaned.length <= max) return cleaned
    return `${cleaned.slice(0, max)}...`
  }
  if (Array.isArray(value)) {
    return `Array(${value.length})`
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value)
    if (!keys.length) return 'Object{}'
    const head = keys.slice(0, 6).join(', ')
    return `Object{${head}${keys.length > 6 ? ', ...' : ''}}`
  }
  return String(value)
}

const inputText = computed(() => toDetailText(props.block?.input))
const outputText = computed(() => toDetailText(props.block?.output))
const payloadText = computed(() => toDetailText(props.block?.payload))

const previewText = computed(() => {
  if (blockType.value === 'tool_result' && props.block?.output !== null && props.block?.output !== undefined) {
    return toInlinePreview(props.block.output, 220)
  }
  if (blockType.value === 'tool_use' && props.block?.input !== null && props.block?.input !== undefined) {
    return toInlinePreview(props.block.input, 180)
  }
  if (props.block?.output !== null && props.block?.output !== undefined) {
    return toInlinePreview(props.block.output, 220)
  }
  if (props.block?.input !== null && props.block?.input !== undefined) {
    return toInlinePreview(props.block.input, 160)
  }
  return toInlinePreview(props.block?.payload, 160)
})

const hasDetails = computed(() => Boolean(inputText.value || outputText.value || payloadText.value))
</script>

<style scoped>
.tool-line {
  font-size: 12px;
  color: #334155;
}

.tool-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9ca3af;
}

.tool-name {
  color: #0f172a;
  font-weight: 600;
}

.tool-kind {
  color: #64748b;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.tool-status {
  color: #64748b;
}

.tool-preview {
  margin-top: 4px;
  color: #475569;
  line-height: 1.5;
  white-space: pre-wrap;
}

.tool-details {
  margin-top: 6px;
}

.tool-details summary {
  color: #4b648f;
  cursor: pointer;
  user-select: none;
}

.detail-grid {
  margin-top: 8px;
  display: grid;
  gap: 8px;
}

.detail-item {
  border: 1px solid #e6edf9;
  border-radius: 8px;
  background: #fbfdff;
  padding: 8px;
}

.detail-label {
  font-size: 11px;
  color: #7184a6;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 4px;
}

.detail-item pre {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-x: auto;
}

.status-streaming .tool-dot {
  background: #255dce;
  animation: pulse 1.1s ease-in-out infinite;
}

.status-pending .tool-dot {
  background: #c08b00;
}

.status-failed .tool-dot {
  background: #dc2626;
}

@keyframes pulse {
  0% { opacity: 0.35; }
  50% { opacity: 1; }
  100% { opacity: 0.35; }
}
</style>
