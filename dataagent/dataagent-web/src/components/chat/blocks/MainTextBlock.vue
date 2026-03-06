<template>
  <div class="main-text">
    <div class="main-body">
      {{ renderedText }}<span v-if="isStreaming" class="stream-cursor">▋</span>
    </div>
    <ol v-if="citations.length" class="citation-list">
      <li v-for="(item, idx) in citations" :key="item.uuid || `${idx}`" class="citation-item">
        <a
          :href="item.url || '#'"
          target="_blank"
          rel="noopener noreferrer"
          class="citation-link"
        >
          [{{ idx + 1 }}] {{ item.title || item.url || '来源' }}
        </a>
      </li>
    </ol>
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
const { renderedText } = usePacedText(sourceText, isStreaming)
const citations = computed(() => {
  const list = props.block?.payload?.citations
  return Array.isArray(list) ? list : []
})
</script>

<style scoped>
.main-text {
  font-size: 14px;
  color: #0f172a;
  line-height: 1.72;
}

.main-body {
  white-space: pre-wrap;
}

.citation-list {
  margin: 10px 0 0;
  padding-left: 18px;
  color: #4b5563;
  font-size: 12px;
  line-height: 1.5;
}

.citation-item + .citation-item {
  margin-top: 4px;
}

.citation-link {
  color: #315a9d;
  text-decoration: none;
}

.citation-link:hover {
  text-decoration: underline;
}

.stream-cursor {
  color: #255dce;
  margin-left: 2px;
  animation: blink 1s ease-in-out infinite;
}

@keyframes blink {
  0% { opacity: 0.3; }
  50% { opacity: 1; }
  100% { opacity: 0.3; }
}
</style>
