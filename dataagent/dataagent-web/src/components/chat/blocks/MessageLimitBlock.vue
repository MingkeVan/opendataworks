<template>
  <div class="limit-line">
    <span class="limit-label">配额</span>
    <span class="limit-text">{{ block.text || '配额信息已更新' }}</span>
  </div>
</template>

<script setup>
defineProps({
  block: {
    type: Object,
    required: true
  }
})
</script>

<style scoped>
.limit-line {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #dbe5f7;
  background: #f6f9ff;
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
}

.limit-label {
  color: #5772a4;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-size: 11px;
}

.limit-text {
  color: #334155;
}
</style>
