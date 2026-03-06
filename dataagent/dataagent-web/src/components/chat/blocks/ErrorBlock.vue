<template>
  <div class="error-line">
    <div class="error-title">{{ title }}</div>
    <div class="error-text">{{ message }}</div>
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

const payload = computed(() => props.block.payload || {})
const title = computed(() => {
  const code = String(payload.value.code || '').trim()
  return code ? `错误: ${code}` : '错误'
})
const message = computed(() => {
  const text = String(props.block.text || payload.value.message || '请求失败')
  return text
})
</script>

<style scoped>
.error-line {
  border-left: 3px solid #dc2626;
  padding: 4px 0 4px 10px;
}

.error-title {
  color: #b91c1c;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 4px;
}

.error-text {
  color: #7f1d1d;
  white-space: pre-wrap;
  line-height: 1.6;
}
</style>
