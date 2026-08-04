<template>
  <div class="execution-monitor">
    <el-card class="header-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="page-title">执行监控</div>
            <div class="page-subtitle">
              统一展示平台触发与 Dolphin 定时产生的工作流实例，取最近 {{ RECENT_INSTANCE_WINDOW }} 次
            </div>
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
              <div class="stat-value">{{ formatDurationSeconds(statistics.avgDurationSeconds) }}</div>
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
      <WorkflowInstanceTable
        :instances="executionList"
        :loading="loading"
        empty-text="当前筛选条件下暂无工作流执行记录"
        expandable
        show-workflow-name
        show-source
        show-error-message
      />

      <el-pagination
        v-model:current-page="queryParams.pageNum"
        v-model:page-size="queryParams.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
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
  Refresh,
  Search,
  Timer
} from '@element-plus/icons-vue'
import { getWorkflowExecutionInstances } from '@/api/execution'
import { workflowApi } from '@/api/workflow'
import WorkflowInstanceTable from '@/components/WorkflowInstanceTable.vue'
import { formatDurationSeconds } from '@/components/workflowInstanceDisplay'
import { buildWorkflowExecutionParams } from './executionMonitorModel'

// 与后端 WorkflowExecutionMonitorService.RECENT_INSTANCE_WINDOW 保持一致，仅用于文案。
const RECENT_INSTANCE_WINDOW = 50

const loading = ref(false)
const executionList = ref([])
const total = ref(0)
const statistics = ref({})
const workflowOptions = ref([])
const workflowOptionsLoading = ref(false)

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
      refresh: refresh === true
    }))
    executionList.value = response.records || []
    total.value = response.total || 0
    statistics.value = response.statistics || {}
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
  loadExecutionList()
}

const refreshData = () => {
  loadExecutionList(true)
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

.pagination {
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
