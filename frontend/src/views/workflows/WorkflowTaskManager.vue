<template>
  <div class="workflow-task-manager">
    <div class="task-layout">
      <!-- Left Panel: Available Tasks (not in any workflow) -->
      <div class="left-panel">
        <div class="panel-header">
          <span class="panel-title">可添加任务</span>
          <div class="panel-actions">
            <el-input
              v-model="leftSearch"
              placeholder="搜索任务"
              size="small"
              clearable
              style="width: 100px;"
            />
            <el-button
              :icon="Refresh"
              size="small"
              circle
              :loading="leftLoading"
              @click="loadAvailableTasks"
            />
          </div>
        </div>
        <div class="panel-content" v-loading="leftLoading">
          <el-table
            ref="leftTableRef"
            :data="filteredAvailableTasks"
            size="small"
            border
            height="400"
            @selection-change="handleLeftSelectionChange"
          >
            <el-table-column type="selection" width="40" />
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="taskName" label="名称" min-width="120" show-overflow-tooltip />
            <el-table-column prop="taskType" label="类型" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.taskType === 'batch' ? 'primary' : 'success'">
                  {{ row.taskType === 'batch' ? '批' : '流' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="panel-footer">
          <el-button
            type="primary"
            size="small"
            :disabled="!selectedLeftTasks.length"
            @click="addTasksToWorkflow"
          >
            <el-icon><Right /></el-icon>
            添加选中 ({{ selectedLeftTasks.length }})
          </el-button>
        </div>
      </div>

      <!-- Right Panel: Current Workflow Tasks -->
      <div class="right-panel">
        <div class="panel-header">
          <span class="panel-title">当前工作流任务</span>
          <div class="panel-actions">
            <el-tag v-if="newlyAddedTasks.length > 0" type="warning" size="small" effect="light">
              有 {{ newlyAddedTasks.length }} 个未保存任务
            </el-tag>
            <el-button
              type="primary"
              size="small"
              :loading="saving"
              @click="saveWorkflow"
            >
              保存工作流
            </el-button>
          </div>
        </div>
        <div class="panel-content">
          <TaskTable
            ref="rightTableRef"
            :workflow-id="workflowId"
            :additional-data="newlyAddedTasks"
            :show-toolbar="true"
            :embedded="true"
            :show-remove-action="true"
            :hide-delete-action="true"
            @remove="handleRemoveTask"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, toRaw } from 'vue'
import { ElMessage } from 'element-plus'
import { Right, Refresh } from '@element-plus/icons-vue'
import { taskApi } from '@/api/task'
import { workflowApi } from '@/api/workflow'
import TaskTable from '../tasks/TaskTable.vue'

const props = defineProps({
  workflowId: {
    type: Number,
    required: true
  },
  workflowTaskIds: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['update'])

const leftLoading = ref(false)
const saving = ref(false)
const availableTasks = ref([])
const selectedLeftTasks = ref([])
const leftSearch = ref('')
const leftTableRef = ref(null)
const rightTableRef = ref(null)

// Newly added tasks (not yet saved)
const newlyAddedTasks = ref([])

// Load available tasks (tasks not belonging to any workflow)
const loadAvailableTasks = async () => {
  leftLoading.value = true
  try {
    // Get all tasks
    const res = await taskApi.list({ pageNum: 1, pageSize: 500 })
    const allFetchedTasks = res.records || []
    
    // Filter out tasks that already belong to a workflow (backend returns workflowId)
    // AND filter out tasks that are newly added (pending save)
    const newlyAddedIds = new Set(newlyAddedTasks.value.map(t => t.id))
    
    availableTasks.value = allFetchedTasks.filter(t => 
      !t.workflowId && !newlyAddedIds.has(t.id)
    )
  } catch (error) {
    console.error('加载任务列表失败', error)
    ElMessage.error('加载任务列表失败')
  } finally {
    leftLoading.value = false
  }
}

// Filtered by search
const filteredAvailableTasks = computed(() => {
  const search = leftSearch.value.trim().toLowerCase()
  if (!search) return availableTasks.value
  return availableTasks.value.filter(task => 
    task.taskName?.toLowerCase().includes(search) ||
    String(task.id).includes(search)
  )
})

const handleLeftSelectionChange = (selection) => {
  selectedLeftTasks.value = selection
}

const addTasksToWorkflow = () => {
  if (!selectedLeftTasks.value.length) return
  
  // Add to newly added tasks
  newlyAddedTasks.value = [...newlyAddedTasks.value, ...selectedLeftTasks.value]
  // Remove from available tasks
  const addedIds = new Set(selectedLeftTasks.value.map(t => t.id))
  availableTasks.value = availableTasks.value.filter(t => !addedIds.has(t.id))
  
  selectedLeftTasks.value = []
  leftTableRef.value?.clearSelection()
  
  ElMessage.success(`已添加 ${addedIds.size} 个任务，请点击"保存工作流"持久化`)
}

const handleRemoveTask = (taskId) => {
  // Check if it's a newly added task
  const newlyAddedIdx = newlyAddedTasks.value.findIndex(t => t.id === taskId)
  if (newlyAddedIdx >= 0) {
    // Remove from newly added and return to available
    const [removed] = newlyAddedTasks.value.splice(newlyAddedIdx, 1)
    availableTasks.value = [removed, ...availableTasks.value]
    ElMessage.info('已从待添加列表中移除任务')
  } else {
    // This is an existing task - need to handle via API
    ElMessage.warning('暂不支持移除已保存的任务，请使用工作流编辑功能')
  }
}

const saveWorkflow = async () => {
  saving.value = true
  try {
    // Get current workflow details
    const detail = await workflowApi.detail(props.workflowId)
    const wf = detail?.workflow
    if (!wf) {
      ElMessage.error('无法获取工作流信息')
      return
    }

    // Combine existing task IDs with newly added.
    // Keep save-click available even without local changes to trigger backend normalization/json rebuild.
    const relationTaskIds = Array.isArray(detail?.taskRelations)
      ? detail.taskRelations
          .map((relation) => Number(relation?.taskId))
          .filter((id) => Number.isFinite(id))
      : []
    const existingTaskIds = relationTaskIds.length
      ? relationTaskIds
      : (props.workflowTaskIds || [])
          .map((id) => Number(id))
          .filter((id) => Number.isFinite(id))
    const newTaskIds = newlyAddedTasks.value.map(t => t.id)
    const allTaskIds = Array.from(new Set([...existingTaskIds, ...newTaskIds]))

    await workflowApi.update(props.workflowId, {
      workflowName: wf.workflowName,
      description: wf.description,
      taskGroupName: wf.taskGroupName || null,
      tasks: allTaskIds.map(taskId => ({ taskId })),
      globalParams: wf.globalParams,
      operator: 'portal-ui'
    })
    
    // Clear newly added tasks
    newlyAddedTasks.value = []
    
    // Refresh right table
    rightTableRef.value?.refresh()
    
    ElMessage.success('工作流保存成功')
    emit('update')
  } catch (error) {
    console.error('保存工作流失败', error)
    ElMessage.error(error?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadAvailableTasks()
})
</script>

<style scoped>
.workflow-task-manager {
  width: 100%;
}

.task-layout {
  display: flex;
  gap: 16px;
  min-height: 500px;
}

.left-panel {
  flex: 0 0 30%;
  max-width: 30%;
  display: flex;
  flex-direction: column;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fafafa;
}

.right-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}

.panel-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.panel-content {
  flex: 1;
  overflow: auto;
  padding: 12px;
}

.panel-footer {
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
  text-align: center;
}

.left-panel .panel-content {
  padding: 8px;
}
</style>
