<template>
  <div class="data-query-page">
    <el-card class="unified-card" shadow="never">
      <!-- Top Query Config Panel -->
      <div class="query-panel">
        <div class="panel-header">
          <div class="left">
            <el-icon><DataBoard /></el-icon>
            <span class="title">数据查询</span>
          </div>
          <div class="right">
             <el-tag type="info" size="small" effect="plain">只读模式 (自动拦截 DDL/DML)</el-tag>
          </div>
        </div>

        <div class="schema-selector">
          <el-select 
            v-model="queryForm.clusterId" 
            placeholder="选择集群" 
            filterable 
            style="width: 200px"
            @change="handleClusterChange"
          >
            <template #prefix><el-icon><Connection /></el-icon></template>
            <el-option
              v-for="cluster in clusterOptions"
              :key="cluster.id"
              :label="cluster.clusterName"
              :value="cluster.id"
            />
          </el-select>
          
          <el-icon class="separator"><ArrowRight /></el-icon>
          
          <el-select 
            v-model="queryForm.database" 
            placeholder="选择数据库" 
            filterable 
            clearable
            :disabled="!queryForm.clusterId"
            style="width: 200px"
            @change="handleDatabaseChange"
          >
            <template #prefix><el-icon><Coin /></el-icon></template>
            <el-option
              v-for="db in databaseOptions"
              :key="db"
              :label="db"
              :value="db"
            />
          </el-select>

           <el-icon class="separator"><ArrowRight /></el-icon>

          <el-select 
            v-model="selectedTable" 
            placeholder="选择数据表 (可选)" 
            filterable 
            clearable
            :disabled="!queryForm.database"
            style="width: 240px"
            @change="handleTableChange"
          >
            <template #prefix><el-icon><Memo /></el-icon></template>
            <el-option
              v-for="tb in tableOptions"
              :key="tb.tableName"
              :label="tb.tableName"
              :value="tb.tableName"
            >
              <span style="float: left">{{ tb.tableName }}</span>
              <span style="float: right; color: #8492a6; font-size: 13px">{{ tb.tableComment }}</span>
            </el-option>
          </el-select>

           <div class="limit-setting">
              <span class="label">Limit</span>
              <el-input-number 
                v-model="queryForm.limit" 
                :min="1" 
                :max="5000" 
                :step="100" 
                controls-position="right"
                style="width: 100px" 
              />
           </div>
           
           <div class="actions">
              <el-button type="primary" :loading="queryLoading" @click="executeQuery">
                <el-icon><VideoPlay /></el-icon> 执行
              </el-button>
              <el-button @click="resetForm"><el-icon><RefreshRight /></el-icon> 重置</el-button>
              <el-button type="success" @click="saveAsTask" plain>
                 <el-icon><Plus /></el-icon> 存为任务
              </el-button>
           </div>
        </div>

        <div class="sql-editor-container">
           <el-input
            v-model="queryForm.sql"
            type="textarea"
            :rows="5"
            placeholder="-- 输入 SQL 语句，支持 SELECT/SHOW/DESC 等只读操作
SELECT * FROM table_name LIMIT 100;"
            resize="vertical"
            class="sql-editor"
          />
        </div>
      </div>

      <!-- Result Area -->
      <div class="result-panel">
         <el-tabs v-model="activeResultTab" type="border-card" class="result-tabs">
            <el-tab-pane name="table">
               <template #label>
                 <span class="tab-label"><el-icon><List /></el-icon> 结果表格</span>
               </template>
               
               <div class="table-toolbar">
                  <div class="meta-info" v-if="queryResult.executedAt">
                     <span class="meta-item"><el-icon><Timer /></el-icon> {{ formatDuration(queryResult.durationMs) }}</span>
                     <span class="meta-item"><el-icon><Files /></el-icon> {{ queryResult.rows.length }} 行</span>
                     <span class="meta-item" v-if="queryResult.hasMore" style="color: #e6a23c"><el-icon><Warning /></el-icon> 结果已截断</span>
                  </div>
                  <div class="export-actions">
                     <el-button size="small" :disabled="!queryResult.rows.length" @click="exportResult">
                        <el-icon><Download /></el-icon> 导出 CSV
                     </el-button>
                  </div>
               </div>

               <div class="table-wrapper">
                 <el-empty v-if="!queryResult.rows.length && !queryLoading" description="暂无数据" :image-size="80"/>
                 
                 <template v-else>
                    <el-table 
                      :data="paginatedRows" 
                      border 
                      height="100%" 
                      size="small" 
                      stripe
                      highlight-current-row
                    >
                      <el-table-column
                        v-for="col in queryResult.columns"
                        :key="col"
                        :prop="col"
                        :label="col"
                        show-overflow-tooltip
                        min-width="120"
                        sortable
                      />
                    </el-table>
                 </template>
               </div>
               
               <div class="pagination-bar" v-if="queryResult.rows.length > 0">
                 <el-pagination
                    v-model:current-page="resultPage.current"
                    v-model:page-size="resultPage.size"
                    :page-sizes="[10, 20, 50, 100, 500]"
                    layout="total, sizes, prev, pager, next"
                    :total="queryResult.rows.length"
                    small
                    background
                 />
               </div>
            </el-tab-pane>

            <el-tab-pane name="chart">
              <template #label>
                 <span class="tab-label"><el-icon><TrendCharts /></el-icon> 可视化图表</span>
               </template>

              <div class="chart-layout">
                <!-- Chart Config Sidebar -->
                <div class="chart-config">
                   <div class="config-section">
                      <div class="section-header">图表类型</div>
                      <div class="chart-type-selector">
                         <div 
                           class="type-item" 
                           :class="{ active: chartConfig.type === 'bar' }"
                           @click="chartConfig.type = 'bar'"
                          >
                            <el-icon><Histogram /></el-icon> 柱状图
                         </div>
                         <div 
                           class="type-item" 
                           :class="{ active: chartConfig.type === 'line' }"
                           @click="chartConfig.type = 'line'"
                          >
                            <el-icon><DataLine /></el-icon> 折线图
                         </div>
                         <div 
                           class="type-item" 
                           :class="{ active: chartConfig.type === 'pie' }"
                           @click="chartConfig.type = 'pie'"
                          >
                            <el-icon><PieChart /></el-icon> 饼图
                         </div>
                      </div>
                   </div>

                   <el-divider style="margin: 16px 0" />

                   <div class="config-section">
                      <div class="section-header">
                        {{ chartConfig.type === 'pie' ? '分类字段 (标签)' : '横轴 (X轴)' }}
                      </div>
                      <el-select v-model="chartConfig.xAxis" placeholder="选择字段" style="width: 100%" size="small" teleported>
                        <el-option v-for="col in queryResult.columns" :key="col" :label="col" :value="col" />
                      </el-select>
                   </div>

                    <div class="config-section" style="margin-top: 16px">
                      <div class="section-header">
                         {{ chartConfig.type === 'pie' ? '数值字段 (扇区)' : '纵轴 (Y轴)' }}
                      </div>
                      <el-select 
                        v-model="chartConfig.yAxis" 
                        placeholder="选择数值字段" 
                        multiple 
                        collapse-tags
                        collapse-tags-tooltip
                        style="width: 100%" 
                        size="small"
                        teleported
                        :popper-options="{ placement: 'bottom-start' }"
                      >
                        <el-option v-for="col in queryResult.columns" :key="col" :label="col" :value="col" />
                      </el-select>
                      <div class="hint">可多选</div>
                   </div>

                   <div class="config-section" style="margin-top: auto">
                      <el-button type="primary" style="width: 100%" @click="renderChart" :disabled="!canRenderChart">
                        生成图表
                      </el-button>
                   </div>
                </div>

                <!-- Chart Canvas -->
                <div class="chart-canvas-wrapper">
                   <div v-if="!canRenderChart && !chartInstance" class="chart-placeholder">
                      <el-icon :size="48" color="#dcdfe6"><DataAnalysis /></el-icon>
                      <p>请在左侧配置图表参数</p>
                   </div>
                   <div ref="chartRef" class="chart-container"></div>
                </div>
              </div>
            </el-tab-pane>

             <el-tab-pane name="history">
               <template #label>
                 <span class="tab-label"><el-icon><Clock /></el-icon> 历史记录</span>
               </template>
               <div class="history-list">
                  <el-table :data="historyData" style="width: 100%" size="small">
                    <el-table-column prop="executedAt" label="执行时间" width="160">
                       <template #default="{row}">{{ formatDate(row.executedAt) }}</template>
                    </el-table-column>
                    <el-table-column prop="sqlText" label="SQL" show-overflow-tooltip>
                       <template #default="{row}"><code class="code-font">{{ row.sqlText }}</code></template>
                    </el-table-column>
                    <el-table-column prop="durationMs" label="耗时" width="100">
                       <template #default="{row}">{{ formatDuration(row.durationMs) }}</template>
                    </el-table-column>
                    <el-table-column fixed="right" label="操作" width="80">
                      <template #default="{row}">
                        <el-button link type="primary" size="small" @click="useHistory(row)">复用</el-button>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div class="history-pagination">
                    <el-pagination
                      v-model:current-page="historyPager.pageNum"
                      :page-size="historyPager.pageSize"
                      :total="historyPager.total"
                      layout="prev, pager, next"
                      small
                      @current-change="fetchHistory"
                    />
                  </div>
               </div>
            </el-tab-pane>
         </el-tabs>
      </div>

    </el-card>

    <TaskEditDrawer ref="taskDrawerRef" />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { dorisClusterApi } from '@/api/doris'
import { dataQueryApi } from '@/api/query'
import {
  DataBoard, Connection, Coin, Memo, ArrowRight, VideoPlay, RefreshRight, 
  List, TrendCharts, Clock, Timer, Files, Warning, Download,
  Histogram, DataLine, PieChart, DataAnalysis, Plus
} from '@element-plus/icons-vue'
import TaskEditDrawer from '@/views/tasks/TaskEditDrawer.vue'

// --- State ---
const clusterOptions = ref([])
const databaseOptions = ref([])
const tableOptions = ref([])
const selectedTable = ref('')

const queryForm = reactive({
  clusterId: null,
  database: '',
  limit: 200,
  sql: ''
})

const queryLoading = ref(false)
const queryResult = ref({
  columns: [],
  rows: [],
  hasMore: false,
  durationMs: 0,
  executedAt: ''
})

// Result Table Pagination
const activeResultTab = ref('table')
const resultPage = reactive({
  current: 1,
  size: 20
})

const paginatedRows = computed(() => {
  const start = (resultPage.current - 1) * resultPage.size
  const end = start + resultPage.size
  return queryResult.value.rows.slice(start, end)
})

// Visual Chart
const chartRef = ref(null)
const taskDrawerRef = ref(null)
let chartInstance = null
const chartConfig = reactive({
  type: 'bar',
  xAxis: '',
  yAxis: [] // Array
})

// History
const historyData = ref([])
const historyPager = reactive({ pageNum: 1, pageSize: 15, total: 0 })

// --- Computed ---
const numericColumns = computed(() => {
  if (!queryResult.value.rows.length) return []
  // Analyze first 10 rows to detect numeric columns
  const cols = queryResult.value.columns
  const sample = queryResult.value.rows.slice(0, 10)
  return cols.filter(col => {
    return sample.every(row => {
       const val = row[col]
       return val === null || val === '' || !isNaN(Number(val))
    })
  })
})

const canRenderChart = computed(() => {
   return chartConfig.xAxis && chartConfig.yAxis.length > 0 && queryResult.value.rows.length > 0
})

// --- Lifecycle ---
onMounted(() => {
  loadClusters()
  fetchHistory()
  window.addEventListener('resize', resizeChart)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  disposeChart()
})

// --- Methods ---

async function loadClusters() {
  try {
    clusterOptions.value = await dorisClusterApi.list()
  } catch(e) { console.error(e) }
}

async function handleClusterChange(val) {
   queryForm.database = ''
   selectedTable.value = ''
   databaseOptions.value = []
   tableOptions.value = []
   if (!val) return
   
   try {
     const res = await dorisClusterApi.getDatabases(val)
     databaseOptions.value = res || []
   } catch(e) {
     ElMessage.error('获取数据库列表失败')
   }
}

async function handleDatabaseChange(val) {
  selectedTable.value = ''
  tableOptions.value = []
  if (!val || !queryForm.clusterId) return
  
  try {
     const res = await dorisClusterApi.getTables(queryForm.clusterId, val)
     tableOptions.value = res || []
  } catch(e) {
     console.error('get tables failed', e)
  }
}

function handleTableChange(val) {
  if (!val) return
  // Generate SQL Preview
  queryForm.sql = `SELECT * \nFROM \`${queryForm.database}\`.\`${val}\` \nLIMIT ${queryForm.limit};`
}

async function executeQuery() {
  if (!queryForm.sql.trim()) return ElMessage.warning('请输入 SQL')
  
  queryLoading.value = true
  try {
    const res = await dataQueryApi.execute({
       clusterId: queryForm.clusterId,
       database: queryForm.database || undefined,
       sql: queryForm.sql,
       limit: queryForm.limit
    })
    
    queryResult.value = {
      columns: res.columns || [],
      rows: res.rows || [],
      hasMore: res.hasMore,
      durationMs: res.durationMs,
      executedAt: res.executedAt
    }
    
    // Reset Views
    resultPage.current = 1
    activeResultTab.value = 'table'
    disposeChart()
    
    fetchHistory()
  } catch(e) {
    ElMessage.error(e.response?.data?.message || e.message || '查询失败')
  } finally {
    queryLoading.value = false
  }
}

function resetForm() {
  queryForm.sql = ''
  queryForm.database = ''
  selectedTable.value = ''
}

function exportResult() {
   if (!queryResult.value.rows.length) return
   // Simple CSV Export logic re-used
   const header = queryResult.value.columns.join(',')
   const body = queryResult.value.rows.map(row => {
      return queryResult.value.columns.map(col => {
         const val = row[col]
         if (val === null || val === undefined) return ''
         const s = String(val)
         return s.includes(',') ? `"${s}"` : s
      }).join(',')
   }).join('\n')
   
   const blob = new Blob([header + '\n' + body], { type: 'text/csv;charset=utf-8;' })
   const link = document.createElement('a')
   link.href = URL.createObjectURL(blob)
   link.download = `export_${Date.now()}.csv`
   link.click()
}

function saveAsTask() {
   if (!queryForm.sql.trim()) {
      ElMessage.warning('请先输入 SQL');
      return;
   }
   taskDrawerRef.value?.open(null, {
      taskSql: queryForm.sql,
      taskName: '新建查询任务_' + new Date().getTime(),
      taskDesc: `From Data Query Page\nCluster: ${queryForm.clusterId}\nDatabase: ${queryForm.database}`
   })
}

// Charting
function renderChart() {
  if (!chartRef.value || !chartConfig.xAxis || !chartConfig.yAxis.length) return
  
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  
  const xData = queryResult.value.rows.map(r => r[chartConfig.xAxis])
  const series = chartConfig.yAxis.map(yKey => ({
     name: yKey,
     type: chartConfig.type,
     data: queryResult.value.rows.map(r => r[yKey]),
     smooth: true
  }))
  
  const option = {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { top: 40, left: 50, right: 30, bottom: 60, containLabel: true },
    xAxis: { type: 'category', data: xData },
    yAxis: { type: 'value' },
    series
  }
  
  chartInstance.setOption(option, true)
}

function resizeChart() {
   chartInstance?.resize()
}

function disposeChart() {
  chartInstance?.dispose()
  chartInstance = null
}

// Watch for Chart Config Changes to auto-re-render if needed (optional)
watch(() => chartConfig.type, () => {
   if (chartInstance) renderChart()
})


// History
async function fetchHistory() {
  try {
    const res = await dataQueryApi.history({
       pageNum: historyPager.pageNum,
       pageSize: historyPager.pageSize
    })
    historyData.value = res.records || []
    historyPager.total = res.total || 0
  } catch(e) {}
}

function useHistory(row) {
   queryForm.sql = row.sqlText
   queryForm.clusterId = row.clusterId
   queryForm.database = row.databaseName
}

// Utils
function formatDuration(ms) {
   if (!ms) return '0ms'
   return ms < 1000 ? `${ms}ms` : `${(ms/1000).toFixed(2)}s`
}

function formatDate(s) {
   if (!s) return '-'
   return s.replace('T', ' ').split('.')[0]
}
</script>

<style scoped>
.data-query-page {
  padding: 12px;
  height: calc(100vh - 84px);
  background-color: #f0f2f5;
  display: flex;
  flex-direction: column;
}


.unified-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 8px;
}

:deep(.el-card__body) {
  padding: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* Query Panel */
.query-panel {
  padding: 16px;
  background-color: #fff;
  border-bottom: 1px solid #ebeef5;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
}

.title {
  font-size: 16px;
  font-weight: 600;
  margin-left: 8px;
  color: #1f2f3d;
}

.schema-selector {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.separator {
  color: #c0c4cc;
}

.limit-setting {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.label { font-size: 13px; color: #606266; }

.actions {
  margin-left: 12px;
  display: flex;
  gap: 8px;
}

.sql-editor-container {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 2px;
}

:deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  font-family: 'Fira Code', monospace;
  font-size: 14px;
}

/* Result Panel */
.result-panel {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background-color: #f5f7fa;
  padding: 12px;
}

.result-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
  border: none;
  box-shadow: 0 1px 4px rgba(0,0,0,0.05);
}

:deep(.el-tabs__content) {
  flex: 1;
  padding: 0 !important;
  overflow: hidden;
  position: relative;
  min-height: 0;
}

:deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

/* Table Tab */
.table-toolbar {
  padding: 8px 12px;
  background-color: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.meta-info {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #606266;
}
.meta-item { display: flex; align-items: center; gap: 4px; }

.table-wrapper {
  flex: 1;
  overflow: hidden;
  background: #fff;
}

.pagination-bar {
  padding: 8px;
  background: #fff;
  border-top: 1px solid #ebeef5;
  display: flex;
  justify-content: flex-end;
}

/* Chart Tab */
.chart-layout {
  display: flex;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.chart-config {
  width: 240px;
  min-width: 240px;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid #ebeef5;
  padding: 16px;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  overflow-x: hidden;
}

.section-header {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.chart-type-selector {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.type-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  color: #606266;
  transition: all 0.2s;
}

.type-item:hover { border-color: #409eff; color: #409eff; }
.type-item.active { background-color: #ecf5ff; border-color: #409eff; color: #409eff; }

.hint {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.chart-canvas-wrapper {
  flex: 1;
  background: #fff;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chart-container {
  width: 100%;
  height: 100%;
}

.chart-placeholder {
  text-align: center;
  color: #909399;
}
.chart-placeholder p { margin-top: 12px; font-size: 14px; }

/* History Tab */
.history-list {
  background: #fff;
  height: 100%;
  padding: 16px;
  overflow-y: auto;
}

.history-pagination {
  margin-top: 16px;
  text-align: right;
}

.code-font {
  font-family: monospace;
}
</style>
