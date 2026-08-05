<template>
  <el-table
    v-loading="loading"
    :data="instances"
    :row-key="getInstanceRowKey"
    :expand-row-keys="expandedRowKeys"
    stripe
    :empty-text="emptyText"
    style="width: 100%"
    @expand-change="handleExpandChange"
  >
    <el-table-column v-if="expandable" type="expand" width="44">
      <template #default="{ row }">
        <div class="task-instance-panel">
          <el-empty
            v-if="!row.expandable"
            description="该记录在提交 Dolphin 前失败，没有任务实例"
            :image-size="56"
          />
          <div v-else-if="taskLoading[getInstanceRowKey(row)]" class="expand-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            正在读取 Dolphin 任务实例…
          </div>
          <el-alert
            v-else-if="taskErrors[getInstanceRowKey(row)]"
            type="error"
            :closable="false"
            show-icon
          >
            <template #title>
              <span>{{ taskErrors[getInstanceRowKey(row)] }}</span>
              <el-button link type="primary" @click="retryLoadTasks(row)">重试</el-button>
            </template>
          </el-alert>
          <el-table
            v-else
            :data="taskInstances[getInstanceRowKey(row)] || []"
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
                <el-tag :type="getInstanceStatusType(task.status)" size="small">
                  {{ getInstanceStatusText(task.status) }}
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
              <template #default="{ row: task }">{{ formatInstanceDateTime(task.startTime) }}</template>
            </el-table-column>
            <el-table-column prop="endTime" label="结束时间" width="170">
              <template #default="{ row: task }">{{ formatInstanceDateTime(task.endTime) }}</template>
            </el-table-column>
            <el-table-column prop="durationSeconds" label="时长" width="100">
              <template #default="{ row: task }">{{ formatDurationSeconds(task.durationSeconds) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-table-column>

    <el-table-column
      v-if="showWorkflowName"
      prop="workflowName"
      label="工作流"
      min-width="180"
      show-overflow-tooltip
    />
    <el-table-column prop="instanceId" label="实例ID" width="130">
      <template #default="{ row }">
        <el-link
          v-if="row.instanceId && row.dolphinInstanceUrl"
          type="primary"
          @click="openDolphinInstance(row)"
        >
          #{{ row.instanceId }}
        </el-link>
        <span v-else>{{ row.instanceId || '-' }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="scheduleTime" label="调度日期" min-width="170">
      <template #default="{ row }">{{ formatInstanceDateTime(row.scheduleTime) }}</template>
    </el-table-column>
    <el-table-column prop="status" label="状态" width="110">
      <template #default="{ row }">
        <el-tag :type="getInstanceStatusType(row.status)" size="small">
          {{ getInstanceStatusText(row.status) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="triggerType" label="触发方式" width="100">
      <template #default="{ row }">
        <el-tag size="small" effect="plain">{{ getTriggerTypeText(row.triggerType) }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column v-if="showSource" prop="source" label="来源" width="100">
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
    <el-table-column prop="startTime" label="开始时间" min-width="170">
      <template #default="{ row }">{{ formatInstanceDateTime(row.startTime) }}</template>
    </el-table-column>
    <el-table-column prop="endTime" label="结束时间" min-width="170">
      <template #default="{ row }">{{ formatInstanceDateTime(row.endTime) }}</template>
    </el-table-column>
    <el-table-column prop="durationSeconds" label="时长" width="100">
      <template #default="{ row }">{{ formatDurationSeconds(row.durationSeconds) }}</template>
    </el-table-column>
    <el-table-column
      v-if="showErrorMessage"
      prop="errorMessage"
      label="错误信息"
      min-width="180"
      show-overflow-tooltip
    >
      <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
    </el-table-column>
  </el-table>
</template>

<script setup>
/**
 * 工作流实例表格，供「执行监控」与工作流详情「执行历史」共用。
 *
 * 组件只负责渲染一行实例，不持有任何列表查询逻辑：查哪些实例（全部工作流还是
 * 某一个）完全由调用方决定并通过 instances 传入。唯一自行发起的请求是展开行的
 * 任务实例懒加载，那是行级的、与作用域无关。
 */
import { reactive, ref, watch } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { getWorkflowExecutionTasks } from '@/api/execution'
import {
  formatDurationSeconds,
  formatInstanceDateTime,
  getExecutionSourceText,
  getInstanceRowKey,
  getInstanceStatusText,
  getInstanceStatusType,
  getTriggerTypeText
} from './workflowInstanceDisplay'

const props = defineProps({
  instances: {
    type: Array,
    default: () => []
  },
  loading: {
    type: Boolean,
    default: false
  },
  emptyText: {
    type: String,
    default: '暂无执行记录'
  },
  showWorkflowName: {
    type: Boolean,
    default: false
  },
  showSource: {
    type: Boolean,
    default: false
  },
  showErrorMessage: {
    type: Boolean,
    default: false
  },
  expandable: {
    type: Boolean,
    default: false
  }
})

const expandedRowKeys = ref([])
const taskInstances = reactive({})
const taskLoading = reactive({})
const taskErrors = reactive({})

watch(
  () => props.instances,
  () => {
    expandedRowKeys.value = []
  }
)

const handleExpandChange = (row, expandedRows) => {
  const key = getInstanceRowKey(row)
  const expanded = expandedRows.some((item) => getInstanceRowKey(item) === key)
  if (!expanded) {
    expandedRowKeys.value = expandedRowKeys.value.filter((item) => item !== key)
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
  const key = getInstanceRowKey(row)
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
  const key = getInstanceRowKey(row)
  delete taskInstances[key]
  loadTaskInstances(row)
}

const openDolphinInstance = (row) => {
  window.open(row.dolphinInstanceUrl, '_blank')
}
</script>

<style scoped>
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
</style>
