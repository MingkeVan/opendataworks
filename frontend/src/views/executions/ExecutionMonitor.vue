<template>
  <div class="execution-monitor">
    <el-card class="header-card">
      <template #header>
        <div class="card-header">
          <span>执行监控</span>
          <div class="header-actions">
            <el-button type="primary" :icon="Refresh" @click="refreshData">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- 统计卡片 -->
      <el-row :gutter="20" class="stats-row">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon total">
              <el-icon><Document /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalExecutions || 0 }}</div>
              <div class="stat-label">总执行次数</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon success">
              <el-icon><CircleCheck /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.successCount || 0 }}</div>
              <div class="stat-label">成功次数</div>
              <div class="stat-rate success-rate">{{ statistics.successRate || 0 }}%</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon failed">
              <el-icon><CircleClose /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.failedCount || 0 }}</div>
              <div class="stat-label">失败次数</div>
              <div class="stat-rate failed-rate">{{ statistics.failureRate || 0 }}%</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon duration">
              <el-icon><Timer /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.avgDurationSeconds || 0 }}s</div>
              <div class="stat-label">平均执行时长</div>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 筛选条件 -->
    <el-card class="filter-card">
      <el-form :inline="true" :model="queryParams" class="filter-form">
        <el-form-item label="任务ID">
          <el-input
            v-model="queryParams.taskId"
            placeholder="请输入任务ID"
            clearable
            style="width: 200px"
          />
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

      <!-- 快捷筛选 -->
      <div class="quick-filters">
        <el-button-group>
          <el-button
            :type="activeFilter === 'all' ? 'primary' : ''"
            @click="handleQuickFilter('all')"
          >
            全部
          </el-button>
          <el-button
            :type="activeFilter === 'running' ? 'primary' : ''"
            @click="handleQuickFilter('running')"
          >
            运行中
          </el-button>
          <el-button
            :type="activeFilter === 'failed' ? 'primary' : ''"
            @click="handleQuickFilter('failed')"
          >
            失败
          </el-button>
        </el-button-group>
      </div>
    </el-card>

    <!-- 执行历史表格 -->
    <el-card class="table-card">
      <el-table
        v-loading="loading"
        :data="executionList"
        stripe
        style="width: 100%"
      >
        <el-table-column prop="id" label="执行ID" width="80" />
        <el-table-column prop="taskId" label="任务ID" width="100" />
        <el-table-column prop="executionId" label="实例ID" width="180" />

        <!-- DolphinScheduler 相关列 -->
        <el-table-column prop="workflowName" label="工作流名称" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.workflowName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="workflowCode" label="工作流ID" width="120">
          <template #default="{ row }">
            {{ row.workflowCode || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="dolphinInstanceId" label="DS实例ID" width="120">
          <template #default="{ row }">
            {{ row.dolphinInstanceId || '-' }}
          </template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dolphinState" label="DS状态" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" effect="plain" v-if="row.dolphinState">
              {{ row.dolphinState }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="triggerType" label="触发方式" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">
              {{ getTriggerTypeText(row.triggerType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.startTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="endTime" label="结束时间" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.endTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="durationSeconds" label="执行时长" width="100">
          <template #default="{ row }">
            {{ formatDuration(row.durationSeconds) }}
          </template>
        </el-table-column>
        <el-table-column prop="host" label="执行主机" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.host || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="rowsOutput" label="输出行数" width="100" />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              @click="handleViewDetail(row)"
            >
              详情
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              v-if="!isTerminalStatus(row.status)"
              @click="handleSyncStatus(row)"
            >
              同步状态
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              v-if="row.workflowInstanceUrl"
              @click="openDolphinWebUI(row.workflowInstanceUrl)"
            >
              DS实例
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              @click="handleViewLog(row)"
            >
              查看日志
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 - 仅在"全部"模式下显示 -->
      <el-pagination
        v-if="activeFilter === 'all'"
        v-model:current-page="queryParams.pageNum"
        v-model:page-size="queryParams.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleQuery"
        @current-change="handleQuery"
        style="margin-top: 20px; justify-content: flex-end"
      />
      <!-- 快捷筛选模式提示 -->
      <div v-else style="margin-top: 20px; text-align: center; color: #909399;">
        共 {{ total }} 条记录（快捷筛选模式，不支持分页）
        <el-button link type="primary" @click="handleQuickFilter('all')" style="margin-left: 10px;">
          切换到全部模式以使用分页
        </el-button>
      </div>
    </el-card>

    <!-- 执行详情对话框 -->
    <el-dialog
      v-model="detailDialogVisible"
      title="执行详情"
      width="800px"
    >
      <el-descriptions :column="2" border v-if="currentExecution">
        <el-descriptions-item label="执行ID">{{ currentExecution.id }}</el-descriptions-item>
        <el-descriptions-item label="任务ID">{{ currentExecution.taskId }}</el-descriptions-item>
        <el-descriptions-item label="实例ID">{{ currentExecution.executionId }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(currentExecution.status)">
            {{ getStatusText(currentExecution.status) }}
          </el-tag>
        </el-descriptions-item>

        <!-- DolphinScheduler 信息 -->
        <el-descriptions-item label="工作流名称" v-if="currentExecution.workflowName">
          {{ currentExecution.workflowName }}
        </el-descriptions-item>
        <el-descriptions-item label="工作流ID" v-if="currentExecution.workflowCode">
          {{ currentExecution.workflowCode }}
        </el-descriptions-item>
        <el-descriptions-item label="任务代码" v-if="currentExecution.taskCode">
          {{ currentExecution.taskCode }}
        </el-descriptions-item>
        <el-descriptions-item label="DS实例ID" v-if="currentExecution.dolphinInstanceId">
          {{ currentExecution.dolphinInstanceId }}
        </el-descriptions-item>
        <el-descriptions-item label="DS状态" v-if="currentExecution.dolphinState">
          <el-tag size="small">{{ currentExecution.dolphinState }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="执行主机" v-if="currentExecution.host">
          {{ currentExecution.host }}
        </el-descriptions-item>
        <el-descriptions-item label="运行次数" v-if="currentExecution.runTimes">
          {{ currentExecution.runTimes }}
        </el-descriptions-item>
        <el-descriptions-item label="命令类型" v-if="currentExecution.commandType">
          {{ currentExecution.commandType }}
        </el-descriptions-item>

        <el-descriptions-item label="触发方式">
          {{ getTriggerTypeText(currentExecution.triggerType) }}
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">
          {{ formatDateTime(currentExecution.startTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="结束时间">
          {{ formatDateTime(currentExecution.endTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="执行时长">
          {{ formatDuration(currentExecution.durationSeconds) }}
        </el-descriptions-item>
        <el-descriptions-item label="输出行数">
          {{ currentExecution.rowsOutput || '-' }}
        </el-descriptions-item>

        <!-- WebUI 链接 -->
        <el-descriptions-item label="工作流实例" v-if="currentExecution.workflowInstanceUrl">
          <el-link :href="currentExecution.workflowInstanceUrl" type="primary" target="_blank" :icon="Link">
            打开DolphinScheduler
          </el-link>
        </el-descriptions-item>
        <el-descriptions-item label="任务定义" v-if="currentExecution.taskDefinitionUrl">
          <el-link :href="currentExecution.taskDefinitionUrl" type="primary" target="_blank" :icon="Link">
            查看任务定义
          </el-link>
        </el-descriptions-item>

        <el-descriptions-item label="日志URL">
          <el-link :href="currentExecution.logUrl" type="primary" target="_blank" v-if="currentExecution.logUrl">
            查看日志
          </el-link>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="错误信息" :span="2" v-if="currentExecution.errorMessage">
          <el-alert type="error" :closable="false">
            {{ currentExecution.errorMessage }}
          </el-alert>
        </el-descriptions-item>
      </el-descriptions>

      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 日志查看对话框 -->
    <el-dialog
      v-model="logDialogVisible"
      title="执行日志"
      width="900px"
    >
      <div class="log-container">
        <pre class="log-content">{{ executionLog }}</pre>
      </div>

      <template #footer>
        <el-button @click="logDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleRefreshLog">刷新日志</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Refresh,
  Search,
  Document,
  CircleCheck,
  CircleClose,
  Timer,
  Link
} from '@element-plus/icons-vue'
import {
  getExecutionHistory,
  getExecutionDetail,
  getExecutionStatistics,
  getFailedExecutions,
  getRunningExecutions,
  syncExecutionStatus
} from '@/api/execution'

// 数据定义
const loading = ref(false)
const executionList = ref([])
const total = ref(0)
const statistics = ref({})
const activeFilter = ref('all')
const dateRange = ref([])

const queryParams = reactive({
  taskId: null,
  pageNum: 1,
  pageSize: 10,
  startTime: null,
  endTime: null
})

// 对话框控制
const detailDialogVisible = ref(false)
const logDialogVisible = ref(false)
const currentExecution = ref(null)
const executionLog = ref('')

// 页面加载
onMounted(() => {
  loadStatistics()
  loadExecutionList()
})

// 加载统计信息
const loadStatistics = async () => {
  try {
    const params = {}
    if (queryParams.taskId) {
      params.taskId = queryParams.taskId
    }
    if (dateRange.value && dateRange.value.length === 2) {
      params.startTime = dateRange.value[0]
      params.endTime = dateRange.value[1]
    }

    const res = await getExecutionStatistics(params)
    statistics.value = res
  } catch (error) {
    console.error('Failed to load statistics:', error)
  }
}

// 加载执行列表
const loadExecutionList = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: queryParams.pageNum,
      pageSize: queryParams.pageSize
    }

    if (queryParams.taskId) {
      params.taskId = queryParams.taskId
    }

    const res = await getExecutionHistory(params)
    executionList.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    ElMessage.error('加载执行历史失败: ' + error.message)
  } finally {
    loading.value = false
  }
}

// 快捷筛选
const handleQuickFilter = async (filter) => {
  activeFilter.value = filter
  loading.value = true

  try {
    if (filter === 'all') {
      await loadExecutionList()
    } else if (filter === 'running') {
      const res = await getRunningExecutions()
      executionList.value = res || []
      total.value = executionList.value.length
    } else if (filter === 'failed') {
      const res = await getFailedExecutions(100)
      executionList.value = res || []
      total.value = executionList.value.length
    }
  } catch (error) {
    ElMessage.error('加载数据失败: ' + error.message)
  } finally {
    loading.value = false
  }
}

// 查询
const handleQuery = () => {
  queryParams.pageNum = 1

  // 解析时间范围
  if (dateRange.value && dateRange.value.length === 2) {
    queryParams.startTime = dateRange.value[0]
    queryParams.endTime = dateRange.value[1]
  } else {
    queryParams.startTime = null
    queryParams.endTime = null
  }

  loadStatistics()
  loadExecutionList()
}

// 重置
const handleReset = () => {
  queryParams.taskId = null
  queryParams.pageNum = 1
  queryParams.pageSize = 10
  queryParams.startTime = null
  queryParams.endTime = null
  dateRange.value = []
  activeFilter.value = 'all'

  loadStatistics()
  loadExecutionList()
}

// 刷新数据
const refreshData = () => {
  loadStatistics()
  loadExecutionList()
}

// 查看详情
const handleViewDetail = async (row) => {
  try {
    const detail = await getExecutionDetail(row.id)
    currentExecution.value = detail
    detailDialogVisible.value = true
  } catch (error) {
    ElMessage.error('加载执行详情失败: ' + error.message)
  }
}

// 同步状态
const handleSyncStatus = async (row) => {
  try {
    await syncExecutionStatus(row.id)
    ElMessage.success('状态同步成功')
    await loadExecutionList()
  } catch (error) {
    ElMessage.error('状态同步失败: ' + error.message)
  }
}

// 查看日志
const handleViewLog = (row) => {
  currentExecution.value = row
  executionLog.value = '正在加载日志...\n\n暂未实现从 DolphinScheduler 获取日志的功能'
  logDialogVisible.value = true
}

// 刷新日志
const handleRefreshLog = () => {
  executionLog.value = '日志已刷新...\n\n暂未实现从 DolphinScheduler 获取日志的功能'
}

// 打开 DolphinScheduler WebUI
const openDolphinWebUI = (url) => {
  if (url) {
    window.open(url, '_blank')
  }
}

// 工具函数
const getStatusType = (status) => {
  const statusMap = {
    'success': 'success',
    'failed': 'danger',
    'running': 'primary',
    'pending': 'info',
    'killed': 'warning',
    'paused': 'warning'
  }
  return statusMap[status] || 'info'
}

const getStatusText = (status) => {
  const statusMap = {
    'success': '成功',
    'failed': '失败',
    'running': '运行中',
    'pending': '待执行',
    'killed': '已终止',
    'paused': '已暂停'
  }
  return statusMap[status] || status
}

const getTriggerTypeText = (type) => {
  const typeMap = {
    'manual': '手动',
    'schedule': '调度',
    'api': 'API'
  }
  return typeMap[type] || type
}

const isTerminalStatus = (status) => {
  return ['success', 'failed', 'killed'].includes(status)
}

const formatDateTime = (datetime) => {
  if (!datetime) return '-'
  return datetime
}

const formatDuration = (seconds) => {
  if (!seconds || seconds === 0) return '-'
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return `${minutes}m ${remainingSeconds}s`
}
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
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.stats-row {
  margin-top: 10px;
}

.stat-card {
  display: flex;
  align-items: center;
  padding: 20px;
  background: #f8fafc;
  border-radius: 12px;
  transition: all 0.3s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.12);
}

.stat-icon {
  width: 60px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 28px;
  margin-right: 15px;
}

.stat-icon.total {
  background: #fff7e6;
  color: #fa8c16;
}

.stat-icon.success {
  background: #f6ffed;
  color: #52c41a;
}

.stat-icon.failed {
  background: #fff1f0;
  color: #f5222d;
}

.stat-icon.duration {
  background: #e6f7ff;
  color: #1890ff;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  line-height: 1;
  margin-bottom: 5px;
}

.stat-label {
  font-size: 14px;
  color: #666;
}

.stat-rate {
  font-size: 12px;
  margin-top: 5px;
}

.stat-rate.success-rate {
  color: #52c41a;
}

.stat-rate.failed-rate {
  color: #f5222d;
}

.filter-card {
  margin-top: 20px;
}

.filter-form {
  margin-bottom: 10px;
}

.quick-filters {
  display: flex;
  justify-content: flex-start;
  margin-top: 10px;
}

.table-card {
  margin-top: 20px;
}

.log-container {
  max-height: 500px;
  overflow-y: auto;
  background: #1e1e1e;
  padding: 15px;
  border-radius: 8px;
}

.log-content {
  color: #d4d4d4;
  font-family: 'Courier New', Courier, monospace;
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
  white-space: pre-wrap;
  word-wrap: break-word;
}
</style>
