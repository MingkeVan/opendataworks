<template>
  <div class="data-query-page">
    <el-card class="unified-card" shadow="never">
      <!-- 紧凑的查询区域 -->
      <div class="query-section">
        <div class="query-header">
          <span class="section-title">数据查询</span>
          <el-tag type="info" effect="plain" size="small">仅支持只读 SQL，自动拦截 DELETE/DROP/ALTER</el-tag>
        </div>

        <el-form class="compact-form" size="small">
          <!-- 第一行：集群、数据库、行数 -->
          <div class="params-row">
            <div class="param-item">
              <label class="form-label">集群</label>
              <el-select v-model="queryForm.clusterId" placeholder="默认集群" clearable filterable>
                <el-option
                  v-for="cluster in clusterOptions"
                  :key="cluster.id"
                  :label="cluster.clusterName"
                  :value="cluster.id"
                />
              </el-select>
            </div>

            <div class="param-item">
              <label class="form-label">数据库</label>
              <el-input v-model="queryForm.database" placeholder="doris_ods" clearable />
            </div>

            <div class="param-item">
              <label class="form-label">返回行数</label>
              <el-input-number
                v-model="queryForm.limit"
                :min="10"
                :max="1000"
                :step="10"
                controls-position="right"
                style="width: 100%"
              />
            </div>
          </div>

          <!-- 第二行：SQL编辑器 + 执行按钮 -->
          <div class="sql-row">
            <div class="sql-wrapper">
              <label class="form-label">SQL 语句</label>
              <el-input
                v-model="queryForm.sql"
                type="textarea"
                :rows="4"
                placeholder="请输入 SELECT/SHOW/EXPLAIN 等只读 SQL"
                resize="vertical"
                class="sql-editor"
              />
            </div>
            <div class="action-buttons">
              <el-button type="primary" :loading="queryLoading" @click="executeQuery">
                执行查询
              </el-button>
              <el-button @click="resetForm">清空</el-button>
            </div>
          </div>
        </el-form>
      </div>

      <!-- 分隔线 -->
      <el-divider style="margin: 16px 0" />

      <!-- 查询结果区域 -->
      <div class="result-section">
        <div class="result-header">
          <div class="result-header-left">
            <span class="section-title">查询结果</span>
            <div class="result-meta" v-if="queryResult.executedAt">
              <el-tag type="success" effect="plain" size="small">耗时 {{ formatDuration(queryResult.durationMs) }}</el-tag>
              <el-tag type="info" effect="plain" size="small">执行于 {{ formatDate(queryResult.executedAt) }}</el-tag>
              <el-tag v-if="queryResult.hasMore" type="warning" effect="plain" size="small">结果已截断，建议增加过滤条件</el-tag>
            </div>
          </div>
          <div class="result-header-right">
            <el-button
              size="small"
              :disabled="!queryResult.rows.length"
              @click="exportResult"
            >
              导出结果
            </el-button>
          </div>
        </div>

        <div v-if="!queryResult.rows.length" class="empty-result">
          <el-empty description="暂无查询结果" :image-size="100" />
        </div>
        <div v-else class="result-content">
          <el-tabs v-model="activeResultTab" class="result-tabs">
            <el-tab-pane label="表格" name="table">
              <el-table :data="queryResult.rows" border height="500px" size="small">
                <el-table-column
                  v-for="column in queryResult.columns"
                  :key="column"
                  :prop="column"
                  :label="column"
                  show-overflow-tooltip
                  min-width="120"
                />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="图表" name="chart">
              <div v-if="chartMessage" class="chart-message">{{ chartMessage }}</div>
              <div v-else ref="chartRef" class="chart-container"></div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>

      <!-- 分隔线 -->
      <el-divider style="margin: 16px 0" />

      <!-- 查询历史区域 -->
      <div class="history-section">
        <div class="history-header">
          <span class="section-title">查询历史</span>
        </div>

        <el-table :data="historyData" v-loading="historyLoading" border size="small">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="preview-wrapper" v-if="getHistoryPreview(row)">
                <el-table :data="getHistoryPreview(row).rows" size="small" border>
                  <el-table-column
                    v-for="column in getHistoryPreview(row).columns"
                    :key="column"
                    :prop="column"
                    :label="column"
                    show-overflow-tooltip
                  />
                </el-table>
              </div>
              <div v-else class="preview-empty">暂无预览数据</div>
            </template>
          </el-table-column>
          <el-table-column prop="sqlText" label="SQL" min-width="260">
            <template #default="{ row }">
              <code class="sql-text">{{ row.sqlText }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="clusterName" label="集群" width="140" />
          <el-table-column prop="databaseName" label="数据库" width="140" />
          <el-table-column prop="previewRowCount" label="返回行数" width="120" />
          <el-table-column prop="durationMs" label="耗时" width="120">
            <template #default="{ row }">
              {{ formatDuration(row.durationMs) }}
            </template>
          </el-table-column>
          <el-table-column prop="executedAt" label="执行时间" width="180">
            <template #default="{ row }">
              {{ formatDate(row.executedAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button type="primary" link @click="useHistory(row)">复用查询</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="history-pagination">
          <el-pagination
            background
            layout="total, prev, pager, next"
            :total="historyPager.total"
            :page-size="historyPager.pageSize"
            :current-page="historyPager.pageNum"
            @current-change="handleHistoryPageChange"
          />
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { dorisClusterApi } from '@/api/doris'
import { dataQueryApi } from '@/api/query'

const queryForm = reactive({
  clusterId: null,
  database: '',
  limit: 200,
  sql: ''
})

const clusterOptions = ref([])
const queryLoading = ref(false)
const queryResult = ref({
  columns: [],
  rows: [],
  previewRowCount: 0,
  hasMore: false,
  durationMs: 0,
  executedAt: ''
})
const activeResultTab = ref('table')
const chartRef = ref(null)
let chartInstance = null
const chartMessage = ref('')

const historyData = ref([])
const historyLoading = ref(false)
const historyPager = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})
const previewCache = new Map()

onMounted(() => {
  loadClusters()
  fetchHistory()
  window.addEventListener('resize', resizeChart)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})

watch(
  () => queryResult.value.rows,
  () => {
    if (activeResultTab.value === 'chart') {
      renderChart()
    }
  },
  { deep: true }
)

watch(activeResultTab, (val) => {
  if (val === 'chart') {
    renderChart()
  }
})

async function loadClusters() {
  try {
    clusterOptions.value = await dorisClusterApi.list()
  } catch (err) {
    console.error('load clusters failed', err)
  }
}

async function executeQuery() {
  if (!queryForm.sql || !queryForm.sql.trim()) {
    ElMessage.warning('请输入要执行的 SQL')
    return
  }
  queryLoading.value = true
  try {
    const payload = {
      clusterId: queryForm.clusterId,
      database: queryForm.database || undefined,
      limit: queryForm.limit,
      sql: queryForm.sql
    }
    const data = await dataQueryApi.execute(payload)
    queryResult.value = {
      columns: data.columns || [],
      rows: data.rows || [],
      previewRowCount: data.previewRowCount || 0,
      hasMore: data.hasMore || false,
      durationMs: data.durationMs || 0,
      executedAt: data.executedAt
    }
    activeResultTab.value = 'table'
    chartMessage.value = ''
    historyPager.pageNum = 1
    fetchHistory()
  } catch (err) {
    console.error('execute query failed', err)
    const message = err?.response?.data?.message || err?.message || '执行查询失败'
    ElMessage.error(message)
  } finally {
    queryLoading.value = false
  }
}

function resetForm() {
  queryForm.sql = ''
  queryForm.database = ''
  queryForm.limit = 200
}

function exportResult() {
  if (!queryResult.value.rows.length) {
    ElMessage.warning('暂无数据可以导出')
    return
  }
  const columns = queryResult.value.columns
  const csvRows = [columns.join(',')]
  queryResult.value.rows.forEach((row) => {
    const values = columns.map((column) => formatCsvValue(row[column]))
    csvRows.push(values.join(','))
  })
  const blob = new Blob([csvRows.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `query-result-${Date.now()}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

function formatCsvValue(value) {
  if (value === null || value === undefined) {
    return ''
  }
  const stringValue = String(value)
  if (stringValue.includes(',') || stringValue.includes('"') || stringValue.includes('\n')) {
    return '"' + stringValue.replace(/"/g, '""') + '"'
  }
  return stringValue
}

async function fetchHistory() {
  historyLoading.value = true
  try {
    const data = await dataQueryApi.history({
      pageNum: historyPager.pageNum,
      pageSize: historyPager.pageSize
    })
    previewCache.clear()
    historyData.value = data.records || []
    historyPager.total = data.total || 0
  } catch (err) {
    console.error('load history failed', err)
    ElMessage.error(err?.response?.data?.message || err?.message || '加载历史记录失败')
  } finally {
    historyLoading.value = false
  }
}

function handleHistoryPageChange(page) {
  historyPager.pageNum = page
  fetchHistory()
}

function getHistoryPreview(row) {
  if (!row || !row.resultPreview) {
    return null
  }
  if (previewCache.has(row.id)) {
    return previewCache.get(row.id)
  }
  try {
    const parsed = JSON.parse(row.resultPreview)
    if (parsed && Array.isArray(parsed.columns) && Array.isArray(parsed.rows)) {
      previewCache.set(row.id, parsed)
      return parsed
    }
  } catch (err) {
    console.warn('parse preview failed', err)
  }
  previewCache.set(row.id, null)
  return null
}

function useHistory(row) {
  if (!row) {
    return
  }
  queryForm.sql = row.sqlText
  queryForm.database = row.databaseName || ''
  queryForm.clusterId = row.clusterId || null
  executeQuery()
}

function renderChart() {
  nextTick(() => {
    if (!chartRef.value) {
      return
    }
    const rows = queryResult.value.rows
    const columns = queryResult.value.columns
    if (!rows.length || columns.length < 2) {
      chartMessage.value = '暂无可视化数据，请在结果中包含至少两列且存在数值列'
      disposeChart()
      return
    }
    const categoryKey = columns[0]
    const numericKeys = columns.slice(1).filter((column) => rows.some((row) => isNumeric(row[column])))
    if (!numericKeys.length) {
      chartMessage.value = '未检测到数值列，无法生成图表'
      disposeChart()
      return
    }
    chartMessage.value = ''
    if (!chartInstance) {
      chartInstance = echarts.init(chartRef.value)
    }
    const categories = rows.map((row) => row[categoryKey])
    const series = numericKeys.map((key) => ({
      name: key,
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: rows.map((row) => (isNumeric(row[key]) ? Number(row[key]) : 0))
    }))
    const option = {
      tooltip: { trigger: 'axis' },
      legend: { data: numericKeys },
      xAxis: { type: 'category', data: categories, boundaryGap: false },
      yAxis: { type: 'value' },
      series
    }
    chartInstance.setOption(option)
  })
}

function resizeChart() {
  if (chartInstance) {
    chartInstance.resize()
  }
}

function disposeChart() {
  if (chartInstance) {
    chartInstance.clear()
  }
}

function isNumeric(value) {
  if (value === null || value === undefined) {
    return false
  }
  const number = Number(value)
  return !Number.isNaN(number)
}

function formatDuration(duration) {
  if (!duration && duration !== 0) {
    return '-'
  }
  if (duration < 1000) {
    return `${duration} ms`
  }
  return `${(duration / 1000).toFixed(2)} s`
}

function formatDate(value) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (num) => `${num}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
</script>

<style scoped>
.data-query-page {
  padding: 6px;
  height: calc(100vh - 80px);
  background-color: #f8fafc;
}

.unified-card {
  height: 100%;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

:deep(.el-card__body) {
  padding: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

/* 查询区域 - 紧凑布局 */
.query-section {
  flex-shrink: 0;
  margin-bottom: 16px;
}

.query-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
}

.compact-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 第一行：参数 */
.params-row {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.param-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 13px;
  color: #606266;
  font-weight: 500;
}

.param-item :deep(.el-select),
.param-item :deep(.el-input),
.param-item :deep(.el-input-number) {
  width: 100%;
}

/* 第二行：SQL + 按钮 */
.sql-row {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.sql-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sql-editor {
  font-family: 'Fira Code', 'Source Code Pro', Consolas, monospace;
  font-size: 13px;
  border-radius: 8px;
}

.sql-editor :deep(.el-textarea__inner) {
  border-radius: 8px;
}

.action-buttons {
  display: flex;
  gap: 8px;
  padding-bottom: 2px;
}

/* 结果区域 */
.result-section {
  flex-shrink: 0;
  margin-bottom: 16px;
}

.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.result-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.result-header-right {
  flex-shrink: 0;
}

.result-meta {
  display: flex;
  gap: 8px;
  align-items: center;
}

.empty-result {
  padding: 60px 0;
  text-align: center;
}

.result-content {
  /* 不设置固定高度，让内容自适应 */
}

.result-tabs :deep(.el-table) {
  /* 表格使用固定高度 */
}

.chart-container {
  width: 100%;
  height: 500px;
}

.chart-message {
  text-align: center;
  padding: 80px 0;
  color: #909399;
  font-size: 14px;
}

/* 历史区域 */
.history-section {
  flex-shrink: 0;
}

.history-header {
  margin-bottom: 12px;
}

.preview-wrapper {
  padding: 12px;
  background-color: #f8fafc;
  border-radius: 8px;
}

.preview-empty {
  padding: 12px;
  color: #a0a0a0;
}

.sql-text {
  display: inline-block;
  max-width: 100%;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'Fira Code', 'Source Code Pro', Consolas, monospace;
  font-size: 12px;
}

.history-pagination {
  margin-top: 16px;
  text-align: right;
}
</style>
