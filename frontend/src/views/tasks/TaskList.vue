<template>
  <div class="task-list">
    <el-card>
      <div class="toolbar">
        <div class="filters">
          <el-select v-model="filters.taskType" placeholder="任务类型" clearable style="width: 150px; margin-right: 10px">
            <el-option label="批任务" value="batch" />
            <el-option label="流任务" value="stream" />
          </el-select>
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 150px; margin-right: 10px">
            <el-option label="草稿" value="draft" />
            <el-option label="已发布" value="published" />
            <el-option label="运行中" value="running" />
          </el-select>
          <el-button type="primary" @click="loadData">查询</el-button>
        </div>
        <el-button type="primary" @click="$router.push('/tasks/create')">
          <el-icon><Plus /></el-icon>
          新建任务
        </el-button>
      </div>

      <el-table :data="tableData" style="width: 100%; margin-top: 20px" v-loading="loading">
        <el-table-column prop="taskName" label="任务名称" width="200" />
        <el-table-column prop="taskCode" label="任务编码" width="150" />
        <el-table-column prop="taskType" label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="row.taskType === 'batch' ? '' : 'success'">
              {{ row.taskType === 'batch' ? '批任务' : '流任务' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="engine" label="引擎" width="120" />
        <el-table-column prop="dolphinNodeType" label="节点类型" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.dolphinNodeType" :type="getNodeTypeColor(row.dolphinNodeType)">
              {{ row.dolphinNodeType }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="数据源" width="150">
          <template #default="{ row }">
            <span v-if="row.datasourceName">
              {{ row.datasourceName }}
              <el-tag size="small" v-if="row.datasourceType">{{ row.datasourceType }}</el-tag>
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="最近执行" width="200">
          <template #default="{ row }">
            <div v-if="row.executionStatus" class="execution-status">
              <div>
                <el-tag :type="getExecutionStatusType(row.executionStatus.status)" size="small">
                  {{ getExecutionStatusText(row.executionStatus.status) }}
                </el-tag>
              </div>
              <div class="execution-time" v-if="row.executionStatus.startTime">
                {{ formatTime(row.executionStatus.startTime) }}
              </div>
            </div>
            <span v-else class="text-gray">未执行</span>
          </template>
        </el-table-column>
        <el-table-column prop="scheduleCron" label="调度配置" width="150" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">{{ getStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="owner" label="负责人" width="120" />
        <el-table-column label="操作" width="400" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/tasks/${row.id}/edit`)">编辑</el-button>
            <el-button link type="success" @click="handlePublish(row.id)" v-if="row.status === 'draft'">发布</el-button>
            <el-popconfirm
              title="确定要重新发布到DolphinScheduler吗？这将覆盖现有配置。"
              @confirm="handleRepublish(row.id)"
              v-if="row.status === 'published'"
            >
              <template #reference>
                <el-button link type="warning">重新发布</el-button>
              </template>
            </el-popconfirm>
            <el-button link type="warning" @click="handleExecuteTask(row.id)" v-if="row.status === 'published'">执行任务</el-button>
            <el-button link type="primary" @click="handleExecuteWorkflow(row.id)" v-if="row.status === 'published'">执行工作流</el-button>
            <el-button link type="info" @click="openDolphinWorkflow(row)" v-if="row.executionStatus && row.executionStatus.dolphinWorkflowUrl">
              查看工作流
            </el-button>
            <el-button link type="info" @click="openDolphinTask(row)" v-if="row.executionStatus && row.executionStatus.dolphinTaskUrl">
              查看任务
            </el-button>
            <el-popconfirm title="确定删除吗?" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.pageNum"
        v-model:page-size="pagination.pageSize"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadData"
        @current-change="loadData"
        style="margin-top: 20px; justify-content: flex-end"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { taskApi } from '@/api/task'

const loading = ref(false)
const tableData = ref([])
const pagination = reactive({
  pageNum: 1,
  pageSize: 20,
  total: 0
})

const filters = reactive({
  taskType: '',
  status: ''
})

const loadData = async () => {
  loading.value = true
  try {
    const res = await taskApi.list({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      ...filters
    })
    tableData.value = res.records
    pagination.total = res.total

    // 加载每个任务的执行状态
    await loadExecutionStatuses()
  } catch (error) {
    console.error('加载数据失败:', error)
  } finally {
    loading.value = false
  }
}

const loadExecutionStatuses = async () => {
  if (!tableData.value || tableData.value.length === 0) {
    return
  }

  // 并行加载所有任务的执行状态
  const promises = tableData.value.map(async (task) => {
    try {
      const status = await taskApi.getExecutionStatus(task.id)
      task.executionStatus = status
    } catch (error) {
      console.error(`加载任务 ${task.id} 执行状态失败:`, error)
      task.executionStatus = null
    }
  })

  await Promise.all(promises)
}

const handlePublish = async (id) => {
  try {
    await taskApi.publish(id)
    ElMessage.success('发布成功')
    loadData()
  } catch (error) {
    console.error('发布失败:', error)

    // 从后端响应中提取错误信息
    const errorMessage = error.response?.data?.message || error.message || '发布失败，请稍后重试'

    ElMessage.error({
      message: errorMessage,
      duration: 6000,
      showClose: true
    })
  }
}

const handleRepublish = async (id) => {
  try {
    await taskApi.publish(id)
    ElMessage.success('重新发布成功')
    loadData()
  } catch (error) {
    console.error('重新发布失败:', error)

    // 从后端响应中提取错误信息
    const errorMessage = error.response?.data?.message || error.message || '重新发布失败，请稍后重试'

    ElMessage.error({
      message: errorMessage,
      duration: 6000,
      showClose: true
    })
  }
}

const handleExecuteTask = async (id) => {
  try {
    await taskApi.execute(id)
    ElMessage.success('单任务执行已触发')
    // 刷新执行状态
    setTimeout(() => loadData(), 1000)
  } catch (error) {
    console.error('执行失败:', error)
    const errorMessage = error.response?.data?.message || error.message || '执行失败，请稍后重试'
    ElMessage.error({
      message: errorMessage,
      duration: 6000,
      showClose: true
    })
  }
}

const handleExecuteWorkflow = async (id) => {
  try {
    await taskApi.executeWorkflow(id)
    ElMessage.success('工作流执行已触发')
    // 刷新执行状态
    setTimeout(() => loadData(), 1000)
  } catch (error) {
    console.error('执行失败:', error)
    const errorMessage = error.response?.data?.message || error.message || '执行失败，请稍后重试'
    ElMessage.error({
      message: errorMessage,
      duration: 6000,
      showClose: true
    })
  }
}

const handleDelete = async (id) => {
  try {
    await taskApi.delete(id)
    ElMessage.success('删除成功')
    loadData()
  } catch (error) {
    console.error('删除失败:', error)
    const errorMessage = error.response?.data?.message || error.message || '删除失败，请稍后重试'
    ElMessage.error({
      message: errorMessage,
      duration: 6000,
      showClose: true
    })
  }
}

const getStatusType = (status) => {
  const types = {
    draft: 'info',
    published: 'success',
    running: 'warning',
    paused: '',
    failed: 'danger'
  }
  return types[status] || ''
}

const getNodeTypeColor = (nodeType) => {
  const colors = {
    'SQL': 'success',
    'SHELL': 'warning',
    'PYTHON': 'primary'
  }
  return colors[nodeType] || 'info'
}

const getStatusText = (status) => {
  const texts = {
    draft: '草稿',
    published: '已发布',
    running: '运行中',
    paused: '已暂停',
    failed: '失败'
  }
  return texts[status] || status
}

const getExecutionStatusType = (status) => {
  const types = {
    pending: 'info',
    running: 'warning',
    success: 'success',
    failed: 'danger',
    killed: 'info'
  }
  return types[status] || 'info'
}

const getExecutionStatusText = (status) => {
  const texts = {
    pending: '等待中',
    running: '运行中',
    success: '成功',
    failed: '失败',
    killed: '已终止'
  }
  return texts[status] || status
}

const formatTime = (timeStr) => {
  if (!timeStr) return '-'
  const date = new Date(timeStr)
  const now = new Date()
  const diff = now - date
  const hours = Math.floor(diff / (1000 * 60 * 60))
  const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))

  if (hours < 1) {
    return `${minutes}分钟前`
  } else if (hours < 24) {
    return `${hours}小时前`
  } else {
    return date.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  }
}

const openDolphinWorkflow = (row) => {
  if (row.executionStatus && row.executionStatus.dolphinWorkflowUrl) {
    window.open(row.executionStatus.dolphinWorkflowUrl, '_blank')
  }
}

const openDolphinTask = (row) => {
  if (row.executionStatus && row.executionStatus.dolphinTaskUrl) {
    window.open(row.executionStatus.dolphinTaskUrl, '_blank')
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.task-list {
  height: 100%;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filters {
  display: flex;
  align-items: center;
}

.execution-status {
  font-size: 12px;
  line-height: 1.5;
}

.execution-time {
  color: #909399;
  margin-top: 4px;
}

.text-gray {
  color: #909399;
}
</style>
