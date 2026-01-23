<template>
  <div class="workflow-detail">
    <el-card>
      <template #header>
        <div class="header">
          <div class="title">
            <el-button link @click="$router.push('/workflows')">
              <el-icon><ArrowLeft /></el-icon>
            </el-button>
            <span class="name">{{ workflow?.workflow?.workflowName || '工作流详情' }}</span>
            <el-tag v-if="workflow?.workflow?.status" :type="getWorkflowStatusType(workflow.workflow.status)" size="small">
              {{ getWorkflowStatusText(workflow.workflow.status) }}
            </el-tag>
            <el-tag
               v-if="pendingApprovalFlags[workflow?.workflow?.id]"
               size="small"
               type="warning"
               effect="plain"
             >
               待审批
             </el-tag>
          </div>
          <div class="actions">
            <el-button
               v-if="workflow?.workflow"
               link
               type="primary"
               :loading="getActionLoading(workflow.workflow.id, 'deploy')"
               :disabled="isDeployDisabled(workflow.workflow)"
               @click="handleDeploy(workflow.workflow)"
             >
               发布
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="primary"
               :loading="getActionLoading(workflow.workflow.id, 'execute')"
               :disabled="isExecuteDisabled(workflow.workflow)"
               @click="handleExecute(workflow.workflow)"
             >
               执行
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="success"
               :loading="getActionLoading(workflow.workflow.id, 'online')"
               :disabled="isOnlineDisabled(workflow.workflow)"
               @click="handleOnline(workflow.workflow)"
             >
               上线
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="warning"
               :loading="getActionLoading(workflow.workflow.id, 'offline')"
               :disabled="isOfflineDisabled(workflow.workflow)"
               @click="handleOffline(workflow.workflow)"
             >
               下线
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="info"
               :icon="Link"
               @click="openDolphin(workflow.workflow)"
               :disabled="!canJumpToDolphin(workflow.workflow)"
             >
               Dolphin
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="danger"
               :icon="Delete"
               @click="handleDelete(workflow.workflow)"
             >
               删除
             </el-button>
          </div>
        </div>
      </template>

      <div class="content" v-loading="loading">
        <!-- Basic Info Section with Inline Editing -->
        <div class="basic-info-section">
          <div class="section-title">基本信息</div>
            <el-descriptions :column="2" border>
            <!-- Editable Name Field -->
            <el-descriptions-item label="名称">
              <div v-if="!isEditingName" class="editable-field" @click="startEditName">
                <span>{{ workflow?.workflow?.workflowName }}</span>
                <el-icon class="edit-icon"><Edit /></el-icon>
              </div>
              <div v-else class="edit-field">
                <el-input 
                  v-model="editingName" 
                  size="small" 
                  maxlength="100"
                  style="width: 200px; margin-right: 8px;"
                />
                <el-button size="small" type="primary" :loading="savingField" @click="saveNameField">确认</el-button>
                <el-button size="small" @click="cancelEditName">取消</el-button>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="项目">{{ workflow?.workflow?.projectCode }}</el-descriptions-item>
            <el-descriptions-item label="默认任务组">
              <div v-if="!isEditingTaskGroup" class="editable-field" @click="startEditTaskGroup">
                <span>{{ workflow?.workflow?.taskGroupName || '-' }}</span>
                <el-icon class="edit-icon"><Edit /></el-icon>
              </div>
              <div v-else class="edit-field">
                <el-select
                  v-model="editingTaskGroup"
                  size="small"
                  clearable
                  filterable
                  :loading="taskGroupsLoading"
                  style="width: 200px; margin-right: 8px;"
                  @visible-change="handleTaskGroupDropdown"
                >
                  <el-option
                    v-for="group in taskGroupOptions"
                    :key="group.id"
                    :label="group.name"
                    :value="group.name"
                  />
                </el-select>
                <el-button size="small" type="primary" :loading="savingField" @click="saveTaskGroupField">确认</el-button>
                <el-button size="small" @click="cancelEditTaskGroup">取消</el-button>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="Dolphin 流程编码">
              <el-link 
                v-if="canJumpToDolphin(workflow?.workflow)" 
                type="primary" 
                :underline="false" 
                @click="openDolphin(workflow?.workflow)"
              >
                {{ workflow?.workflow?.workflowCode }}
                <el-icon class="el-icon--right"><Link /></el-icon>
              </el-link>
              <span v-else>{{ workflow?.workflow?.workflowCode || '-' }}</span>
            </el-descriptions-item>
            <!-- Editable Description Field -->
            <el-descriptions-item label="描述" :span="2">
              <div v-if="!isEditingDescription" class="editable-field" @click="startEditDescription">
                <span>{{ workflow?.workflow?.description || '-' }}</span>
                <el-icon class="edit-icon"><Edit /></el-icon>
              </div>
              <div v-else class="edit-field">
                <el-input 
                  v-model="editingDescription" 
                  type="textarea"
                  :rows="2"
                  size="small" 
                  maxlength="300"
                  style="width: 400px; margin-right: 8px;"
                />
                <el-button size="small" type="primary" :loading="savingField" @click="saveDescriptionField">确认</el-button>
                <el-button size="small" @click="cancelEditDescription">取消</el-button>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(workflow?.workflow?.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(workflow?.workflow?.updatedAt) }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <el-tabs v-model="activeTab" class="detail-tabs">
          <el-tab-pane label="Tasks" name="tasks">
          <div class="tab-content">
            <WorkflowTaskManager 
              v-if="workflow?.workflow?.id" 
              :workflow-id="workflow.workflow.id"
              :workflow-task-ids="workflowTaskIds"
              @update="loadWorkflowDetail"
            />
          </div>
        </el-tab-pane>
          <el-tab-pane label="执行历史" name="executions">
            <el-table
              v-if="workflow?.recentInstances?.length"
              :data="workflow.recentInstances"
              border
              size="small"
            >
              <el-table-column prop="instanceId" label="实例ID" width="140">
                <template #default="{ row }">
                  <el-link type="primary" @click="openDolphinInstance(row)" :disabled="!buildDolphinInstanceUrl(row)">
                    #{{ row.instanceId }}
                  </el-link>
                </template>
              </el-table-column>
              <el-table-column prop="state" label="状态" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="getInstanceStateType(row.state)">
                    {{ getInstanceStateText(row.state) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="triggerType" label="触发方式" width="120">
                <template #default="{ row }">
                  {{ getTriggerText(row.triggerType) }}
                </template>
              </el-table-column>
              <el-table-column prop="startTime" label="开始时间" width="170">
                <template #default="{ row }">
                  {{ formatDateTime(row.startTime) }}
                </template>
              </el-table-column>
              <el-table-column prop="endTime" label="结束时间" width="170">
                <template #default="{ row }">
                  {{ formatDateTime(row.endTime) }}
                </template>
              </el-table-column>
              <el-table-column prop="durationMs" label="耗时" width="120">
                <template #default="{ row }">
                  {{ formatDuration(row.durationMs, row.startTime, row.endTime) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty
              v-else
              description="暂无执行记录"
            />
          </el-tab-pane>
          <el-tab-pane label="变更记录" name="changes">
            <el-table
              v-if="workflow?.publishRecords?.length"
              :data="workflow.publishRecords"
              border
              size="small"
            >
              <el-table-column prop="operation" label="操作" width="120">
                <template #default="{ row }">
                  {{ getOperationText(row.operation) }}
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="140">
                <template #default="{ row }">
                  <el-tag size="small" :type="getPublishRecordStatusType(row.status)">
                    {{ getPublishRecordStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="versionId" label="版本ID" width="120" />
              <el-table-column prop="operator" label="操作人" width="120" />
              <el-table-column prop="createdAt" label="时间" width="170">
                <template #default="{ row }">
                  {{ formatDateTime(row.createdAt) }}
                </template>
              </el-table-column>
              <el-table-column prop="log" label="备注">
                <template #default="{ row }">
                  {{ formatLog(row.log) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else description="暂无发布记录" />
          </el-tab-pane>
          <el-tab-pane label="全局变量" name="globals">
            <div class="global-params-section">
                <div class="params-header">
                    <el-button type="primary" size="small" @click="addGlobalParam">
                        <el-icon><Plus /></el-icon> 添加变量
                    </el-button>
                    <el-button type="success" size="small" @click="saveGlobalParams" :loading="savingParams">
                        保存配置
                    </el-button>
                </div>
                <el-table :data="globalParamsList" border size="small" style="width: 100%; margin-top: 10px">
                    <el-table-column label="变量名 (Prop)" prop="prop" width="200">
                        <template #default="{ row }">
                            <el-input v-model="row.prop" placeholder="prop" size="small" />
                        </template>
                    </el-table-column>
                    <el-table-column label="方向 (Direct)" prop="direct" width="120">
                        <template #default="{ row }">
                            <el-select v-model="row.direct" size="small">
                                <el-option label="IN" value="IN" />
                                <el-option label="OUT" value="OUT" />
                            </el-select>
                        </template>
                    </el-table-column>
                    <el-table-column label="类型 (Type)" prop="type" width="120">
                        <template #default="{ row }">
                            <el-select v-model="row.type" size="small">
                                <el-option label="VARCHAR" value="VARCHAR" />
                                <el-option label="INTEGER" value="INTEGER" />
                                <el-option label="LONG" value="LONG" />
                                <el-option label="FLOAT" value="FLOAT" />
                                <el-option label="DOUBLE" value="DOUBLE" />
                                <el-option label="DATE" value="DATE" />
                                <el-option label="TIME" value="TIME" />
                                <el-option label="TIMESTAMP" value="TIMESTAMP" />
                                <el-option label="BOOLEAN" value="BOOLEAN" />
                            </el-select>
                        </template>
                    </el-table-column>
                    <el-table-column label="变量值 (Value)" prop="value">
                        <template #default="{ row }">
                            <el-input v-model="row.value" placeholder="value" size="small" />
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="80" align="center">
                        <template #default="{ $index }">
                            <el-button type="danger" link @click="removeGlobalParam($index)">
                                <el-icon><Delete /></el-icon>
                            </el-button>
                        </template>
                    </el-table-column>
                </el-table>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useRouter } from 'vue-router'
import { ArrowLeft, Link, Delete, Edit, Plus } from '@element-plus/icons-vue'
import { workflowApi } from '@/api/workflow'
import { taskApi } from '@/api/task'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import WorkflowTaskManager from './WorkflowTaskManager.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const workflow = ref(null)
const activeTab = ref('tasks')
const dolphinWebuiUrl = ref('')
const pendingApprovalFlags = reactive({})
const actionLoading = reactive({})

// Inline editing states
const isEditingName = ref(false)
const isEditingDescription = ref(false)
const isEditingTaskGroup = ref(false)
const editingName = ref('')
const editingDescription = ref('')
const editingTaskGroup = ref('')
const savingField = ref(false)

const taskGroupsLoading = ref(false)
const taskGroupOptions = ref([])

// Global params state
const globalParamsList = ref([])
const savingParams = ref(false)

// Computed workflow task IDs
const workflowTaskIds = computed(() => {
  const relations = workflow.value?.taskRelations || []
  return relations.map(r => Number(r.taskId)).filter(id => Number.isFinite(id))
})

// Inline edit methods
const startEditName = () => {
  editingName.value = workflow.value?.workflow?.workflowName || ''
  isEditingName.value = true
}

const cancelEditName = () => {
  isEditingName.value = false
  editingName.value = ''
}

const saveNameField = async () => {
  if (!editingName.value.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  savingField.value = true
  try {
    const wf = workflow.value?.workflow
    await workflowApi.update(wf.id, {
      workflowName: editingName.value.trim(),
      description: wf.description,
      taskGroupName: wf.taskGroupName || null,
      tasks: workflowTaskIds.value.map(taskId => ({ taskId })),
      globalParams: wf.globalParams,
      operator: 'portal-ui'
    })
    ElMessage.success('名称更新成功')
    isEditingName.value = false
    loadWorkflowDetail()
  } catch (error) {
    console.error('更新名称失败', error)
    ElMessage.error(error?.response?.data?.message || '更新失败')
  } finally {
    savingField.value = false
  }
}

const loadTaskGroupOptions = async () => {
  if (taskGroupOptions.value.length) {
    return
  }
  taskGroupsLoading.value = true
  try {
    const res = await taskApi.fetchTaskGroups()
    taskGroupOptions.value = res || []
  } catch (error) {
    console.error('加载任务组失败', error)
    ElMessage.error('加载任务组失败，请稍后重试')
  } finally {
    taskGroupsLoading.value = false
  }
}

const handleTaskGroupDropdown = async (visible) => {
  if (visible && !taskGroupOptions.value.length) {
    await loadTaskGroupOptions()
  }
}

const startEditTaskGroup = async () => {
  editingTaskGroup.value = workflow.value?.workflow?.taskGroupName || ''
  isEditingTaskGroup.value = true
  await loadTaskGroupOptions()
}

const cancelEditTaskGroup = () => {
  isEditingTaskGroup.value = false
  editingTaskGroup.value = ''
}

const saveTaskGroupField = async () => {
  savingField.value = true
  try {
    const wf = workflow.value?.workflow
    await workflowApi.update(wf.id, {
      workflowName: wf.workflowName,
      description: wf.description,
      taskGroupName: editingTaskGroup.value || null,
      tasks: workflowTaskIds.value.map(taskId => ({ taskId })),
      globalParams: wf.globalParams,
      operator: 'portal-ui'
    })
    ElMessage.success('任务组更新成功')
    isEditingTaskGroup.value = false
    loadWorkflowDetail()
  } catch (error) {
    console.error('更新任务组失败', error)
    ElMessage.error(error?.response?.data?.message || '更新失败')
  } finally {
    savingField.value = false
  }
}

const startEditDescription = () => {
  editingDescription.value = workflow.value?.workflow?.description || ''
  isEditingDescription.value = true
}

const cancelEditDescription = () => {
  isEditingDescription.value = false
  editingDescription.value = ''
}

const saveDescriptionField = async () => {
  savingField.value = true
  try {
    const wf = workflow.value?.workflow
    await workflowApi.update(wf.id, {
      workflowName: wf.workflowName,
      description: editingDescription.value,
      taskGroupName: wf.taskGroupName || null,
      tasks: workflowTaskIds.value.map(taskId => ({ taskId })),
      globalParams: wf.globalParams,
      operator: 'portal-ui'
    })
    ElMessage.success('描述更新成功')
    isEditingDescription.value = false
    loadWorkflowDetail()
  } catch (error) {
    console.error('更新描述失败', error)
    ElMessage.error(error?.response?.data?.message || '更新失败')
  } finally {
    savingField.value = false
  }
}

const addGlobalParam = () => {
    globalParamsList.value.push({
        prop: '',
        direct: 'IN',
        type: 'VARCHAR',
        value: ''
    })
}

const removeGlobalParam = (index) => {
    globalParamsList.value.splice(index, 1)
}

const saveGlobalParams = async () => {
    savingParams.value = true
    try {
        const wf = workflow.value?.workflow
        await workflowApi.update(wf.id, {
            workflowName: wf.workflowName,
            description: wf.description,
            taskGroupName: wf.taskGroupName || null,
            tasks: workflowTaskIds.value.map(taskId => ({ taskId })),
            globalParams: JSON.stringify(globalParamsList.value),
            operator: 'portal-ui'
        })
        ElMessage.success('全局变量保存成功')
        loadWorkflowDetail()
    } catch (error) {
        console.error('保存全局变量失败', error)
        ElMessage.error(error?.response?.data?.message || '保存失败')
    } finally {
        savingParams.value = false
    }
}

const loadWorkflowDetail = async () => {
  const id = route.params.id
  if (!id) return
  
  loading.value = true
  try {
    const res = await workflowApi.detail(id)
    workflow.value = res // logic in WorkflowList suggests res is directly the detail object
    // Check if the API returns wrapped object
    if (res && res.workflow) {
        workflow.value = res
    } else {
        // Fallback or error handling if needed, but based on WorkflowList: workflowDetail.value = await workflowApi.detail(workflowId)
        workflow.value = { workflow: res } // Wrap it if it's flat
    }
    
    // Parse global params
    if (workflow.value?.workflow?.globalParams) {
        try {
            globalParamsList.value = JSON.parse(workflow.value.workflow.globalParams)
        } catch (e) {
            console.error('Failed to parse global params', e)
            globalParamsList.value = []
        }
    } else {
        globalParamsList.value = []
    }

    syncPendingFlag(workflow.value?.workflow?.id, workflow.value?.publishRecords || [])
  } catch (error) {
    console.error('加载工作流详情失败', error)
    ElMessage.error('加载工作流详情失败')
  } finally {
    loading.value = false
  }
}

const syncPendingFlag = (workflowId, records) => {
  if (!workflowId) {
    return
  }
  const hasPending = Array.isArray(records)
    && records.some((record) => record.status === 'pending_approval')
  if (hasPending) {
    pendingApprovalFlags[workflowId] = true
  } else {
    delete pendingApprovalFlags[workflowId]
  }
}

const loadDolphinConfig = async () => {
  try {
    const config = await taskApi.getDolphinWebuiConfig()
    dolphinWebuiUrl.value = config?.webuiUrl || ''
  } catch (error) {
    console.warn('加载 Dolphin 配置失败', error)
  }
}

const getWorkflowStatusType = (status) => {
  const map = {
    draft: 'info',
    online: 'success',
    offline: 'warning',
    failed: 'danger'
  }
  return map[status] || 'info'
}

const getWorkflowStatusText = (status) => {
  const map = {
    draft: '草稿',
    online: '在线',
    offline: '下线',
    failed: '失败'
  }
  return map[status] || status || '-'
}

const getInstanceStateType = (state) => {
  const map = {
    SUCCESS: 'success',
    FAILED: 'danger',
    RUNNING: 'warning',
    STOP: 'info',
    KILL: 'info'
  }
  return map[state] || 'info'
}

const getInstanceStateText = (state) => {
  const map = {
    SUCCESS: '成功',
    FAILED: '失败',
    RUNNING: '运行中',
    STOP: '终止',
    KILL: '被终止'
  }
  return map[state] || state || '-'
}

const getTriggerText = (type) => {
  const map = {
    manual: '手动',
    schedule: '调度',
    api: 'API'
  }
  return map[type] || type || '-'
}

const getOperationText = (operation) => {
  const map = {
    deploy: '部署',
    online: '上线',
    offline: '下线'
  }
  return map[operation] || operation || '-'
}

const getPublishRecordStatusType = (status) => {
  const map = {
    success: 'success',
    failed: 'danger',
    pending: 'info',
    pending_approval: 'warning',
    rejected: 'danger'
  }
  return map[status] || 'info'
}

const getPublishRecordStatusText = (status) => {
  const map = {
    success: '成功',
    failed: '失败',
    pending: '进行中',
    pending_approval: '待审批',
    rejected: '已拒绝'
  }
  return map[status] || status || '-'
}

const formatDateTime = (value) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const formatDuration = (durationMs, startTime, endTime) => {
  let duration = durationMs
  if (!duration && startTime && endTime) {
    duration = dayjs(endTime).diff(dayjs(startTime))
  }
  if (!duration) {
    return '-'
  }
  const seconds = Math.floor(duration / 1000)
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60
  return minutes ? `${minutes}分${remainSeconds}秒` : `${remainSeconds}秒`
}

const formatLog = (log) => {
  if (!log) {
    return '-'
  }
  try {
    const parsed = JSON.parse(log)
    if (parsed && typeof parsed === 'object') {
      return Object.entries(parsed)
        .map(([key, value]) => `${key}: ${value}`)
        .join(', ')
    }
    return log
  } catch (error) {
    return log
  }
}

const buildDolphinWorkflowUrl = (workflow) => {
  if (!dolphinWebuiUrl.value || !workflow?.projectCode || !workflow?.workflowCode) {
    return ''
  }
  const base = dolphinWebuiUrl.value.replace(/\/+$/, '')
  return `${base}/ui/projects/${workflow.projectCode}/workflow/definitions/${workflow.workflowCode}`
}

const canJumpToDolphin = (workflow) => {
  return Boolean(
    dolphinWebuiUrl.value
    && workflow?.workflowCode
    && workflow?.projectCode
  )
}

const openDolphin = (workflow) => {
  const url = buildDolphinWorkflowUrl(workflow)
  if (!url) {
    ElMessage.warning('尚未配置 Dolphin WebUI 地址')
    return
  }
  window.open(url, '_blank')
}

// Action Handlers
const getErrorMessage = (error) => {
  return error?.response?.data?.message || error?.message || '操作失败，请稍后重试'
}

const setActionLoading = (workflowId, action, value) => {
  if (!workflowId) return
  if (!actionLoading[workflowId]) {
    actionLoading[workflowId] = {}
  }
  if (value) {
    actionLoading[workflowId][action] = true
  } else {
    delete actionLoading[workflowId][action]
    if (Object.keys(actionLoading[workflowId]).length === 0) {
      delete actionLoading[workflowId]
    }
  }
}

const getActionLoading = (workflowId, action) => {
  return Boolean(actionLoading[workflowId]?.[action])
}

const updatePendingFlag = (workflowId, status) => {
  if (!workflowId) return
  if (status === 'pending_approval') {
    pendingApprovalFlags[workflowId] = true
  } else {
    delete pendingApprovalFlags[workflowId]
  }
}

const isDeployDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  return getActionLoading(row.id, 'deploy')
}

const isExecuteDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'execute')) return true
  return !row.workflowCode
}

const isOnlineDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'online')) return true
  return row.status === 'online'
}

const isOfflineDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'offline')) return true
  return row.status !== 'online'
}

const handleDeploy = async (row) => {
  if (!row?.id) return
  setActionLoading(row.id, 'deploy', true)
  try {
    const record = await workflowApi.publish(row.id, {
      operation: 'deploy',
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, record?.status)
    if (record?.status === 'pending_approval') {
      ElMessage.warning('发布已提交审批，等待审批通过')
    } else {
      ElMessage.success('发布成功')
    }
    loadWorkflowDetail()
  } catch (error) {
    console.error('发布失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'deploy', false)
  }
}

const handleExecute = async (row) => {
  if (!row?.id) return
  setActionLoading(row.id, 'execute', true)
  try {
    const executionId = await workflowApi.execute(row.id)
    ElMessage.success(`已触发执行，实例ID：${executionId || '-'}`)
    loadWorkflowDetail()
  } catch (error) {
    console.error('执行失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'execute', false)
  }
}

const handleOnline = async (row) => {
  if (!row?.id) return
  setActionLoading(row.id, 'online', true)
  try {
    const deployRecord = await workflowApi.publish(row.id, {
      operation: 'deploy',
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, deployRecord?.status)
    if (deployRecord?.status === 'pending_approval') {
      ElMessage.warning('部署已提交审批，审批通过后再上线')
      return
    }

    const onlineRecord = await workflowApi.publish(row.id, {
      operation: 'online',
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, onlineRecord?.status)
    if (onlineRecord?.status === 'pending_approval') {
      ElMessage.warning('上线已提交审批，等待审批通过')
    } else {
      ElMessage.success('上线成功')
    }
    loadWorkflowDetail()
  } catch (error) {
    console.error('上线失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'online', false)
  }
}

const handleOffline = async (row) => {
  if (!row?.id) return
  setActionLoading(row.id, 'offline', true)
  try {
    const record = await workflowApi.publish(row.id, {
      operation: 'offline',
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, record?.status)
    if (record?.status === 'pending_approval') {
      ElMessage.warning('下线已提交审批')
    } else {
      ElMessage.success('下线成功')
    }
    loadWorkflowDetail()
  } catch (error) {
    console.error('下线失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'offline', false)
  }
}

const handleDelete = async (row) => {
  if (!row?.id) return

  try {
    await ElMessageBox.confirm(
      `确定要删除工作流"${row.workflowName}"吗？<br/><br/>
      <div style="color: #666; font-size: 12px;">
      • 将删除工作流相关的所有数据（版本、发布记录、执行历史等）<br/>
      • 任务定义将被保留，可被其他工作流复用<br/>
      • 此操作不可恢复
      </div>`,
      '确认删除',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true
      }
    )

    setActionLoading(row.id, 'delete', true)
    try {
      await workflowApi.delete(row.id)
      ElMessage.success('工作流删除成功')
      // 跳转到工作流列表页
      router.push('/workflows')
    } catch (error) {
      console.error('删除失败', error)
      ElMessage.error(getErrorMessage(error))
    } finally {
      setActionLoading(row.id, 'delete', false)
    }
  } catch {
    // 用户取消删除
  }
}

const buildDolphinInstanceUrl = (instance) => {
  const wf = workflow.value?.workflow
  if (!wf || !dolphinWebuiUrl.value) {
    return ''
  }
  if (!wf.projectCode || !wf.workflowCode || !instance?.instanceId) {
    return ''
  }
  const base = dolphinWebuiUrl.value.replace(/\/+$/, '')
  return `${base}/ui/projects/${wf.projectCode}/workflow/instances/${instance.instanceId}?code=${wf.workflowCode}`
}

const openDolphinInstance = (instance) => {
  const url = buildDolphinInstanceUrl(instance)
  if (!url) {
    ElMessage.warning('无法跳转到实例详情')
    return
  }
  window.open(url, '_blank')
}

onMounted(() => {
  loadWorkflowDetail()
  loadDolphinConfig()
})
</script>

<style scoped>
.workflow-detail {
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 18px;
  font-weight: bold;
}

.name {
  margin-right: 8px;
}

.basic-info-section {
  margin-bottom: 20px;
}

.section-title {
  font-size: 16px;
  font-weight: bold;
  margin-bottom: 12px;
  color: #303133;
}

.editable-field {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.editable-field:hover {
  background-color: #f5f7fa;
}

.editable-field .edit-icon {
  margin-left: 8px;
  color: #909399;
  font-size: 14px;
  opacity: 0;
  transition: opacity 0.2s;
}

.editable-field:hover .edit-icon {
  opacity: 1;
}

.edit-field {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
</style>
