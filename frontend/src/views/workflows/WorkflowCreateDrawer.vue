<template>
  <el-drawer
    v-model="visibleProxy"
    :title="drawerTitle"
    size="40%"
    destroy-on-close
  >
    <div class="drawer-body" v-loading="drawerLoading">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
        v-loading="submitting"
      >
        <el-form-item label="名称" prop="workflowName">
          <el-input v-model="form.workflowName" maxlength="100" show-word-limit placeholder="请输入工作流名称" />
        </el-form-item>

        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="300"
            show-word-limit
            placeholder="可选，补充工作流说明"
          />
        </el-form-item>

        <el-form-item label="默认任务组">
          <el-select
            v-model="form.taskGroupName"
            placeholder="可选"
            clearable
            filterable
            :loading="taskGroupsLoading"
            @visible-change="handleTaskGroupDropdown"
          >
            <el-option
              v-for="group in taskGroupOptions"
              :key="group.id"
              :label="group.name"
              :value="group.name"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="关联任务">
          <el-select
            v-model="form.selectedTaskIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="可选择需要纳入该工作流的任务（可选）"
            :loading="tasksLoading"
            @visible-change="handleTaskDropdown"
          >
            <el-option
              v-for="task in taskOptions"
              :key="task.id"
              :label="`${task.taskName} (#${task.id})`"
              :value="task.id"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <el-alert
        title="入口/出口由系统根据血缘自动识别"
        description="保存后平台会根据任务读写关系计算 DAG 入口和出口节点，无需手动标记。"
        type="info"
        :closable="false"
        show-icon
        class="tips"
      />

      <el-table
        v-if="selectedTaskDetails.length"
        :data="selectedTaskDetails"
        size="small"
        border
        class="task-table"
      >
        <el-table-column prop="taskName" label="任务" min-width="160">
          <template #default="{ row }">
            <div class="task-name">
              <div class="title">{{ row.taskName }}</div>
              <div class="meta">ID: {{ row.id }} · 类型: {{ row.taskType || '-' }}</div>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="请选择任务后查看列表" class="task-empty" />
    </div>

    <template #footer>
      <div class="drawer-footer">
        <el-button @click="closeDrawer">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEditMode ? '保存修改' : '创建工作流' }}
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'
import { taskApi } from '@/api/task'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  workflowId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits(['update:modelValue', 'created', 'updated'])

const visibleProxy = computed({
  get() {
    return props.modelValue
  },
  set(value) {
    emit('update:modelValue', value)
  }
})

const formRef = ref(null)
const submitting = ref(false)
const drawerLoading = ref(false)
const tasksLoading = ref(false)
const taskOptions = ref([])
const taskGroupsLoading = ref(false)
const taskGroupOptions = ref([])
const workflowNumericId = computed(() => {
  if (props.workflowId === null || props.workflowId === undefined || props.workflowId === '') {
    return null
  }
  const id = Number(props.workflowId)
  // Ensure id is a positive number (DB IDs are usually > 0)
  return (Number.isFinite(id) && id > 0) ? id : null
})
const isEditMode = computed(() => workflowNumericId.value !== null)
const drawerTitle = computed(() => (isEditMode.value ? '编辑工作流' : '新建工作流'))

const form = reactive({
  workflowName: '',
  description: '',
  taskGroupName: '',
  selectedTaskIds: []
})

const rules = {
  workflowName: [{ required: true, message: '请输入工作流名称', trigger: 'blur' }]
}

const selectedTaskDetails = computed(() =>
  form.selectedTaskIds
    .map((id) => taskOptions.value.find((task) => task.id === id))
    .filter(Boolean)
)

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      initialize()
    }
  }
)

const initialize = async () => {
  drawerLoading.value = true
  resetForm()
  try {
    if (!taskOptions.value.length) {
      await loadTaskOptions()
    }
    if (!taskGroupOptions.value.length) {
      await loadTaskGroupOptions()
    }
    const targetId = workflowNumericId.value
    if (isEditMode.value && targetId !== null) {
      await loadWorkflowForEdit(targetId)
    }
  } finally {
    drawerLoading.value = false
  }
}

const resetForm = () => {
  form.workflowName = ''
  form.description = ''
  form.taskGroupName = ''
  form.selectedTaskIds = []
  formRef.value?.clearValidate()
}

const handleTaskDropdown = async (visible) => {
  if (visible && !taskOptions.value.length) {
    await loadTaskOptions()
  }
}

const loadTaskOptions = async () => {
  tasksLoading.value = true
  try {
    const res = await taskApi.list({
      pageNum: 1,
      pageSize: 500
    })
    taskOptions.value = res.records || []
  } catch (error) {
    console.error('加载任务列表失败', error)
    ElMessage.error('加载任务列表失败，请稍后重试')
  } finally {
    tasksLoading.value = false
  }
}

const handleTaskGroupDropdown = async (visible) => {
  if (visible && !taskGroupOptions.value.length) {
    await loadTaskGroupOptions()
  }
}

const loadTaskGroupOptions = async () => {
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

const ensureTaskOptionsLoaded = async (taskIds = []) => {
  const normalizedIds = taskIds
    .map((id) => Number(id))
    .filter((id) => Number.isFinite(id))
  const missingIds = normalizedIds.filter(
    (id) => !taskOptions.value.some((task) => task.id === id)
  )
  if (!missingIds.length) {
    return
  }
  try {
    const tasks = await Promise.all(
      missingIds.map((id) =>
        taskApi
          .getById(id)
          .then((task) => task)
          .catch((error) => {
            console.error(`加载任务 ${id} 失败`, error)
            return null
          })
      )
    )
    tasks
      .filter(Boolean)
      .forEach((task) => {
        if (!taskOptions.value.some((option) => option.id === task.id)) {
          taskOptions.value.push(task)
        }
      })
  } catch (error) {
    console.error('补充任务选项失败', error)
  }
}

const loadWorkflowForEdit = async (workflowId) => {
  try {
    const detail = await workflowApi.detail(workflowId)
    if (!detail?.workflow) {
      ElMessage.error('未找到工作流详情')
      return
    }
    form.workflowName = detail.workflow.workflowName || ''
    form.description = detail.workflow.description || ''
    form.taskGroupName = detail.workflow.taskGroupName || ''
    const relations = Array.isArray(detail.taskRelations) ? detail.taskRelations : []
    const taskIds = relations
      .map((relation) => Number(relation.taskId))
      .filter((id) => Number.isFinite(id))
    form.selectedTaskIds = taskIds
    await ensureTaskOptionsLoaded(taskIds)
  } catch (error) {
    console.error('加载工作流详情失败', error)
    ElMessage.error('加载工作流详情失败，请稍后重试')
  }
}

const closeDrawer = () => {
  visibleProxy.value = false
}

const buildTaskPayload = () =>
  form.selectedTaskIds.map((taskId) => ({
    taskId
  }))

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate()
  submitting.value = true
  try {
    const payload = {
      workflowName: form.workflowName.trim(),
      description: form.description,
      taskGroupName: form.taskGroupName || null,
      tasks: buildTaskPayload(),
      operator: 'portal-ui'
    }
    const targetId = workflowNumericId.value
    if (isEditMode.value && targetId !== null) {
      await workflowApi.update(targetId, payload)
      ElMessage.success('工作流更新成功')
      emit('updated', targetId)
    } else {
      await workflowApi.create(payload)
      ElMessage.success('工作流创建成功')
      emit('created')
    }
    closeDrawer()
  } catch (error) {
    console.error('创建工作流失败', error)
    const fallback = isEditMode.value ? '更新工作流失败' : '创建工作流失败'
    const message = error.response?.data?.message || error.message || fallback
    ElMessage.error(message)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.drawer-body {
  padding-right: 8px;
}

.tips {
  margin: 16px 0;
}

.task-table {
  margin-top: 12px;
}

.task-empty {
  margin-top: 12px;
}

.task-name .title {
  font-weight: 500;
}

.task-name .meta {
  font-size: 12px;
  color: #909399;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
