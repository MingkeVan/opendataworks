import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'
import { taskApi } from '@/api/task'

// WorkflowDetail 名称/任务组/描述的行内编辑（W4，同 DataStudio composable 套路）。
// 从 WorkflowDetail.vue 逐字抽出，行为保持不变。编辑态 ref 由本 composable 拥有并返回；
// workflow/workflowTaskIds 注入，loadWorkflowDetail/buildDolphinConfigParams 为前向引用，惰性传入。
export function useInlineFieldEditing({
  workflow,
  workflowTaskIds,
  loadWorkflowDetail,
  buildDolphinConfigParams,
}) {
  const isEditingName = ref(false)
  const isEditingDescription = ref(false)
  const isEditingTaskGroup = ref(false)
  const editingName = ref('')
  const editingDescription = ref('')
  const editingTaskGroup = ref('')
  const savingField = ref(false)
  const taskGroupsLoading = ref(false)
  const taskGroupOptions = ref([])

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
      const res = await taskApi.fetchTaskGroups(buildDolphinConfigParams())
      taskGroupOptions.value = res || []
    } catch (error) {
      console.error('加载任务组失败', error)
      ElMessage.warning('任务组目录加载失败，可继续编辑并保存')
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

  return {
    isEditingName,
    isEditingDescription,
    isEditingTaskGroup,
    editingName,
    editingDescription,
    editingTaskGroup,
    savingField,
    taskGroupsLoading,
    taskGroupOptions,
    startEditName,
    cancelEditName,
    saveNameField,
    loadTaskGroupOptions,
    handleTaskGroupDropdown,
    startEditTaskGroup,
    cancelEditTaskGroup,
    saveTaskGroupField,
    startEditDescription,
    cancelEditDescription,
    saveDescriptionField,
  }
}
