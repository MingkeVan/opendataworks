<template>
  <div class="execution-monitor">
    <el-card class="header-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="page-title">执行监控</div>
            <div class="page-subtitle">统一展示平台触发与 Dolphin 定时产生的工作流实例</div>
          </div>
          <el-button type="primary" :icon="Refresh" :loading="loading" @click="refreshData">
            刷新
          </el-button>
        </div>
      </template>

      <el-row :gutter="16" class="stats-row">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon total"><el-icon><Document /></el-icon></div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalExecutions || 0 }}</div>
              <div class="stat-label">当前筛选执行</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon success"><el-icon><CircleCheck /></el-icon></div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.successCount || 0 }}</div>
              <div class="stat-label">成功</div>
              <div class="stat-rate success-rate">{{ statistics.successRate || 0 }}%</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon failed"><el-icon><CircleClose /></el-icon></div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.failedCount || 0 }}</div>
              <div class="stat-label">失败</div>
              <div class="stat-rate failed-rate">{{ statistics.failureRate || 0 }}%</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon duration"><el-icon><Timer /></el-icon></div>
            <div class="stat-info">
              <div class="stat-value">{{ formatDuration(statistics.avgDurationSeconds) }}</div>
              <div class="stat-label">平均执行时长</div>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-card class="filter-card">
      <el-form :inline="true" class="filter-form">
        <el-form-item label="工作流">
          <el-select
            v-model="queryParams.workflowId"
            placeholder="全部工作流"
            clearable
            filterable
            :loading="workflowOptionsLoading"
            style="width: 220px"
          >
            <el-option
              v-for="workflow in workflowOptions"
              :key="workflow.id"
              :label="workflow.workflowName"
              :value="workflow.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="dateRange"
            type="datetimerange"
            range-separator="-"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 380px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleQuery">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-button-group>
        <el-button
          v-for="filter in quickFilters"
          :key="filter.value"
          :type="queryParams.status === filter.value ? 'primary' : ''"
          @click="handleQuickFilter(filter.value)"
        >
          {{ filter.label }}
        </el-button>
      </el-button-group>
    </el-card>

    <el-card class="table-card">
      <el-table
        v-loading="loading"
        :data="executionList"
        :row-key="getExecutionRowKey"
        :expand-row-keys="expandedRowKeys"
        stripe
        empty-text="当前筛选条件下暂无工作流执行记录"
        style="width: 100%"
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="task-instance-panel">
              <el-empty
                v-if="!row.expandable"
                description="该记录在提交 Dolphin 前失败，没有任务实例"
                :image-size="56"
              />
              <div v-else-if="taskLoading[getExecutionRowKey(row)]" class="expand-loading">
                <el-icon class="is-loading"><Loading /></el-icon>
                正在读取 Dolphin 任务实例…
              </div>
              <el-alert
                v-else-if="taskErrors[getExecutionRowKey(row)]"
                type="error"
                :closable="false"
                show-icon
              >
                <template #title>
                  <span>{{ taskErrors[getExecutionRowKey(row)] }}</span>
                  <el-button link type="primary" @click="retryLoadTasks(row)">重试</el-button>
                </template>
              </el-alert>
              <el-table
                v-else
                :data="taskInstances[getExecutionRowKey(row)] || []"
                size="small"
                border
                empty-text="本次运行没有任务实例"
              >
                <el-table-column prop="platformTaskId" label="任务ID" width="90">
                  <template #default="{ row: task }">{{ task.platformTaskId || '-' }}</template>
                </el-table-column>
                <el-table-column prop="taskName" label="任务名称" min-width="180" />
                <el-table-column prop="status" label="状态" width="110">
                  <template #default="{ row: task }">
                    <el-tag :type="getStatusType(task.status)" size="small">
                      {{ getStatusText(task.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="host" label="主机" min-width="150">
                  <template #default="{ row: task }">{{ task.host || '-' }}</template>
                </el-table-column>
                <el-table-column prop="retryTimes" label="重试次数" width="90">
                  <template #default="{ row: task }">{{ task.retryTimes ?? 0 }}</template>
                </el-table-column>
                <el-table-column prop="startTime" label="开始时间" width="170">
                  <template #default="{ row: task }">{{ formatDateTime(task.startTime) }}</template>
                </el-table-column>
                <el-table-column prop="endTime" label="结束时间" width="170">
                  <template #default="{ row: task }">{{ formatDateTime(task.endTime) }}</template>
                </el-table-column>
                <el-table-column prop="durationSeconds" label="时长" width="100">
                  <template #default="{ row: task }">{{ formatDuration(task.durationSeconds) }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="workflowName" label="工作流" min-width="180" show-overflow-tooltip />
        <el-table-column prop="instanceId" label="实例ID" width="130">
          <template #default="{ row }">{{ row.instanceId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="triggerType" label="触发方式" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ getTriggerTypeText(row.triggerType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="100">
          <template #default="{ row }">
            <el-tooltip
              :content="row.executionSource === 'cache' ? 'Dolphin 暂不可用，当前展示缓存数据' : ''"
              :disabled="row.executionSource !== 'cache'"
            >
              <el-tag :type="row.source === 'platform' ? 'primary' : 'info'" size="small">
                {{ getExecutionSourceText(row.source) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column prop="endTime" label="结束时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column prop="durationSeconds" label="时长" width="100">
          <template #default="{ row }">{{ formatDuration(row.durationSeconds) }}</template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="queryParams.pageNum"
        v-model:page-size="queryParams.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination"
        @size-change="loadExecutionList"
        @current-change="loadExecutionList"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CircleCheck,
  CircleClose,
  Document,
  Loading,
  Refresh,
  Search,
  Timer
} from '@element-plus/icons-vue'
import { getWorkflowExecutionInstances, getWorkflowExecutionTasks } from '@/api/execution'
import { workflowApi } from '@/api/workflow'
import {
  buildWorkflowExecutionParams,
  getExecutionRowKey,
  getExecutionSourceText,
  getTriggerTypeText
} from './executionMonitorModel'

const loading = ref(false)
const executionList = ref([])
const total = ref(0)
const statistics = ref({})
const dateRange = ref([])
const workflowOptions = ref([])
const workflowOptionsLoading = ref(false)
const expandedRowKeys = ref([])
const taskInstances = reactive({})
const taskLoading = reactive({})
const taskErrors = reactive({})

const queryParams = reactive({
  workflowId: null,
  status: '',
  pageNum: 1,
  pageSize: 10
})

const quickFilters = [
  { label: '全部', value: '' },
  { label: '运行中', value: 'running' },
  { label: '失败', value: 'failed' }
]

const loadWorkflowOptions = async () => {
  workflowOptionsLoading.value = true
  try {
    const response = await workflowApi.list({ pageNum: 1, pageSize: 200 })
    workflowOptions.value = response.records || []
  } catch (error) {
    console.error('加载工作流选项失败:', error)
  } finally {
    workflowOptionsLoading.value = false
  }
}

const loadExecutionList = async (refresh = false) => {
  loading.value = true
  try {
    const response = await getWorkflowExecutionInstances(buildWorkflowExecutionParams({
      ...queryParams,
      dateRange: dateRange.value,
      refresh: refresh === true
    }))
    executionList.value = response.records || []
    total.value = response.total || 0
    statistics.value = response.statistics || {}
    expandedRowKeys.value = []
  } catch (error) {
    ElMessage.error(`加载执行监控失败: ${error.message}`)
  } finally {
    loading.value = false
  }
}

const handleQuery = () => {
  queryParams.pageNum = 1
  loadExecutionList()
}

const handleQuickFilter = (status) => {
  queryParams.status = status
  queryParams.pageNum = 1
  loadExecutionList()
}

const handleReset = () => {
  queryParams.workflowId = null
  queryParams.status = ''
  queryParams.pageNum = 1
  queryParams.pageSize = 10
  dateRange.value = []
  loadExecutionList()
}

const refreshData = () => {
  loadExecutionList(true)
}

const handleExpandChange = (row, expandedRows) => {
  const key = getExecutionRowKey(row)
  const expanded = expandedRows.some(item => getExecutionRowKey(item) === key)
  if (!expanded) {
    expandedRowKeys.value = expandedRowKeys.value.filter(item => item !== key)
    return
  }
  if (!expandedRowKeys.value.includes(key)) {
    expandedRowKeys.value.push(key)
  }
  if (row.expandable && !taskInstances[key] && !taskLoading[key]) {
    loadTaskInstances(row)
  }
}

const loadTaskInstances = async (row) => {
  const key = getExecutionRowKey(row)
  taskLoading[key] = true
  taskErrors[key] = ''
  try {
    taskInstances[key] = await getWorkflowExecutionTasks(row.workflowId, row.instanceId)
  } catch (error) {
    taskErrors[key] = `任务实例加载失败：${error.message}`
  } finally {
    taskLoading[key] = false
  }
}

const retryLoadTasks = (row) => {
  const key = getExecutionRowKey(row)
  delete taskInstances[key]
  loadTaskInstances(row)
}

const getStatusType = (status) => {
  const types = {
    success: 'success',
    failed: 'danger',
    running: 'primary',
    pending: 'info',
    waiting: 'warning',
    not_run: 'info',
    unavailable: 'danger',
    killed: 'warning',
    paused: 'warning'
  }
  return types[status] || 'info'
}

const getStatusText = (status) => {
  const labels = {
    success: '成功',
    failed: '失败',
    running: '运行中',
    pending: '待执行',
    waiting: '等待执行',
    not_run: '本次未运行',
    unavailable: '状态不可用',
    killed: '已终止',
    paused: '已暂停'
  }
  return labels[status] || status || '-'
}

const formatDateTime = (datetime) => datetime || '-'

const formatDuration = (seconds) => {
  const value = Number(seconds)
  if (!Number.isFinite(value) || value < 0) return '-'
  if (value < 60) return `${Math.round(value)}s`
  const minutes = Math.floor(value / 60)
  const remainingSeconds = Math.round(value % 60)
  return `${minutes}m ${remainingSeconds}s`
}

onMounted(() => {
  loadWorkflowOptions()
  loadExecutionList()
})
</script>

<style scoped>
.execution-monitor {
  padding: 6px;
}

.header-card,
.filter-card,
.table-card {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.page-subtitle {
  margin-top: 4px;
  color: #909399;
  font-size: 13px;
}

.stats-row {
  margin-top: 4px;
}

.stat-card {
  display: flex;
  align-items: center;
  min-height: 88px;
  padding: 16px;
  background: #f8fafc;
  border-radius: 12px;
}

.stat-icon {
  display: flex;
  width: 52px;
  height: 52px;
  align-items: center;
  justify-content: center;
  margin-right: 14px;
  border-radius: 50%;
  font-size: 25px;
}

.stat-icon.total {
  color: #fa8c16;
  background: #fff7e6;
}

.stat-icon.success {
  color: #52c41a;
  background: #f6ffed;
}

.stat-icon.failed {
  color: #f5222d;
  background: #fff1f0;
}

.stat-icon.duration {
  color: #1890ff;
  background: #e6f7ff;
}

.stat-info {
  flex: 1;
}

.stat-value {
  margin-bottom: 5px;
  font-size: 26px;
  font-weight: 700;
  line-height: 1;
}

.stat-label {
  color: #606266;
  font-size: 14px;
}

.stat-rate {
  margin-top: 4px;
  font-size: 12px;
}

.success-rate {
  color: #52c41a;
}

.failed-rate {
  color: #f5222d;
}

.filter-card,
.table-card {
  margin-top: 18px;
}

.filter-form {
  margin-bottom: 8px;
}

.task-instance-panel {
  padding: 12px 24px 18px 68px;
  background: #f8fafc;
}

.expand-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 90px;
  gap: 8px;
  color: #909399;
}

.pagination {
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
