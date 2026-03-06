<template>
  <div class="sql-panel">
    <div class="sql-head">
      <span class="sql-title">SQL</span>
      <div class="sql-actions">
        <button class="btn" @click="$emit('copy', sql)">复制</button>
        <button class="btn btn-primary" @click="$emit('execute', sql)">执行</button>
      </div>
    </div>
    <pre class="sql-code"><code>{{ sql }}</code></pre>
  </div>
</template>

<script setup>
defineEmits(['copy', 'execute'])

defineProps({
  sql: {
    type: String,
    default: ''
  }
})
</script>

<style scoped>
.sql-panel {
  padding: 2px 0 0;
}

.sql-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.sql-title {
  font-size: 11px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: #64748b;
}

.sql-actions {
  display: flex;
  gap: 8px;
}

.btn {
  height: 26px;
  border: 1px solid #d8e2f0;
  border-radius: 7px;
  background: #fff;
  color: #374151;
  padding: 0 9px;
  cursor: pointer;
  font-size: 12px;
}

.btn-primary {
  border-color: #255dce;
  color: #255dce;
}

.sql-code {
  margin: 0;
  padding: 10px 11px;
  border-radius: 10px;
  background: #0f172a;
  color: #e2e8f0;
  overflow-x: auto;
  font-size: 12px;
  line-height: 1.5;
}
</style>
