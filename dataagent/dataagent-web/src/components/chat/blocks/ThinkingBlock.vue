<template>
  <div class="thinking-row" :class="{ streaming: block.status === 'streaming' }">
    <span class="thinking-tag">thinking</span>
    <div v-if="summaries.length" class="thinking-summaries">
      <div v-for="(item, idx) in summaries" :key="`${idx}-${item}`" class="summary-item">{{ item }}</div>
    </div>
    <details class="thinking-detail" :open="block.status === 'streaming'">
      <summary>{{ block.status === 'streaming' ? '展开实时思考' : '展开完整思考' }}</summary>
      <div class="thinking-text">{{ renderedText || '思考中...' }}</div>
    </details>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { usePacedText } from '../../../composables/usePacedText'

const props = defineProps({
  block: {
    type: Object,
    required: true
  }
})

const isStreaming = computed(() => props.block?.status === 'streaming')
const sourceText = computed(() => String(props.block?.text || ''))
const { renderedText } = usePacedText(sourceText, isStreaming, {
  maxStep: 14,
  settleBoost: 24
})

const summaries = computed(() => {
  const raw = props.block?.payload?.summaries
  return Array.isArray(raw) ? raw.filter((item) => typeof item === 'string' && item.trim()) : []
})
</script>

<style scoped>
.thinking-row {
  padding: 6px 0 2px;
}

.thinking-tag {
  display: inline-block;
  font-size: 11px;
  color: #9b7b27;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  margin-bottom: 4px;
}

.thinking-summaries {
  margin-bottom: 4px;
}

.summary-item {
  font-size: 12px;
  color: #8a6b1c;
  line-height: 1.5;
}

.summary-item + .summary-item {
  margin-top: 3px;
}

.thinking-detail summary {
  font-size: 12px;
  color: #8a6b1c;
  cursor: pointer;
  user-select: none;
}

.thinking-text {
  margin-top: 5px;
  white-space: pre-wrap;
  line-height: 1.65;
  color: #7b6120;
  font-size: 13px;
}

.streaming .thinking-tag {
  color: #c28a00;
}
</style>
