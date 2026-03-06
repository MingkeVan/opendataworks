<template>
  <div class="exec-panel">
    <div class="exec-head">
      <span class="exec-title">执行结果</span>
      <span class="exec-meta">{{ metaText }}</span>
    </div>

    <div v-if="execution.error" class="exec-error">{{ execution.error }}</div>

    <div v-else-if="columns.length && execution.rows?.length" class="table-wrap">
      <table class="result-table">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col">{{ col }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, idx) in execution.rows" :key="idx">
            <td v-for="col in columns" :key="col">{{ row[col] }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="exec-empty">无数据</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  execution: {
    type: Object,
    required: true
  }
})

const columns = computed(() => {
  if (Array.isArray(props.execution.columns) && props.execution.columns.length) {
    return props.execution.columns
  }
  const row = Array.isArray(props.execution.rows) ? props.execution.rows[0] : null
  if (row && typeof row === 'object') {
    return Object.keys(row)
  }
  return []
})

const metaText = computed(() => {
  const rowCount = Number(props.execution.row_count || 0)
  const duration = Number(props.execution.duration_ms || 0)
  const more = props.execution.has_more ? '（已截断）' : ''
  return `${rowCount} 行 ${more} · ${duration}ms`
})
</script>

<style scoped>
.exec-panel {
  padding: 2px 0 0;
}

.exec-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.exec-title {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #64748b;
}

.exec-meta {
  font-size: 12px;
  color: #6b7280;
}

.exec-error {
  color: #b42318;
  white-space: pre-wrap;
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid #e6edf9;
  border-radius: 10px;
  background: #ffffff;
}

.result-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.result-table th,
.result-table td {
  border: 1px solid #edf2fb;
  padding: 6px 8px;
  text-align: left;
  white-space: nowrap;
}

.result-table th {
  background: #f8fafc;
  color: #374151;
}

.exec-empty {
  color: #6b7280;
  font-size: 12px;
}
</style>
