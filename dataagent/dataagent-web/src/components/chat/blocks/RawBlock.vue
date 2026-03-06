<template>
  <div class="raw-line">
    <div class="raw-label">原始事件</div>
    <div class="raw-preview">{{ previewText }}</div>
    <details class="raw-details">
      <summary>查看完整事件</summary>
      <pre class="raw-text"><code>{{ detailText }}</code></pre>
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

const toText = (value, max = 3000) => {
  const raw = String(value || '')
  if (raw.length <= max) return raw
  return `${raw.slice(0, max)}\n...已截断，共 ${raw.length} 字符`
}

const detailText = computed(() => toText(props.block?.text))
const previewText = computed(() => {
  const compact = String(props.block?.text || '').replace(/\s+/g, ' ').trim()
  if (!compact) return ''
  if (compact.length <= 160) return compact
  return `${compact.slice(0, 160)}...`
})
</script>

<style scoped>
.raw-line {
  padding: 4px 0 0;
}

.raw-label {
  font-size: 11px;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.raw-preview {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.45;
  color: #475569;
  white-space: pre-wrap;
}

.raw-details {
  margin-top: 5px;
}

.raw-details summary {
  cursor: pointer;
  color: #4b648f;
  user-select: none;
}

.raw-text {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  background: #fbfdff;
  border: 1px solid #e6edf9;
  border-radius: 8px;
  padding: 8px;
  color: #334155;
}
</style>
