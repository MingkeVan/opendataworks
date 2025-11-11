<template>
  <el-drawer
    v-model="visibleProxy"
    title="新建工作流"
    size="40%"
    destroy-on-close
  >
    <div class="drawer-body">
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

        <el-form-item label="关联任务" prop="selectedTaskIds">
          <el-select
            v-model="form.selectedTaskIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择需要纳入该工作流的任务"
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
        title="可通过入口/出口标记优化 DAG 展示"
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
        <el-table-column label="入口">
          <template #default="{ row }">
            <el-switch
              v-if="taskBindings[row.id]"
              v-model="taskBindings[row.id].entry"
              :inline-prompt="true"
              active-text="入口"
              inactive-text="否"
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="出口">
          <template #default="{ row }">
            <el-switch
              v-if="taskBindings[row.id]"
              v-model="taskBindings[row.id].exit"
              :inline-prompt="true"
              active-text="出口"
              inactive-text="否"
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="请选择任务后配置入口/出口" class="task-empty" />
    </div>

    <template #footer>
      <div class="drawer-footer">
        <el-button @click="closeDrawer">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">创建工作流</el-button>
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
  }
})

const emit = defineEmits(['update:modelValue', 'created'])

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
const tasksLoading = ref(false)
const taskOptions = ref([])
const taskBindings = reactive({})

const form = reactive({
  workflowName: '',
  description: '',
  selectedTaskIds: []
})

const rules = {
  workflowName: [{ required: true, message: '请输入工作流名称', trigger: 'blur' }],
  selectedTaskIds: [{ required: true, message: '请至少选择一个任务', trigger: 'change' }]
}

const selectedTaskDetails = computed(() =>
  form.selectedTaskIds
    .map((id) => taskOptions.value.find((task) => task.id === id))
    .filter(Boolean)
)

watch(
  () => form.selectedTaskIds.slice(),
  (ids) => {
    // ensure binding exists for new ids
    ids.forEach((id) => {
      if (!taskBindings[id]) {
        taskBindings[id] = {
          entry: ids.length === 1,
          exit: false
        }
      }
    })
    // remove bindings for deselected ids
    Object.keys(taskBindings).forEach((key) => {
      const numericKey = Number(key)
      if (!ids.includes(numericKey)) {
        delete taskBindings[key]
      }
    })
  }
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
  resetForm()
  if (!taskOptions.value.length) {
    await loadTaskOptions()
  }
}

const resetForm = () => {
  form.workflowName = ''
  form.description = ''
  form.selectedTaskIds = []
  Object.keys(taskBindings).forEach((key) => delete taskBindings[key])
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

const closeDrawer = () => {
  visibleProxy.value = false
}

const buildTaskPayload = () =>
  form.selectedTaskIds.map((taskId) => ({
    taskId,
    entry: Boolean(taskBindings[taskId]?.entry),
    exit: Boolean(taskBindings[taskId]?.exit)
  }))

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate()
  submitting.value = true
  try {
    const payload = {
      workflowName: form.workflowName.trim(),
      description: form.description,
      tasks: buildTaskPayload(),
      operator: 'portal-ui'
    }
    await workflowApi.create(payload)
    ElMessage.success('工作流创建成功')
    emit('created')
    closeDrawer()
  } catch (error) {
    console.error('创建工作流失败', error)
    const message = error.response?.data?.message || error.message || '创建工作流失败'
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
