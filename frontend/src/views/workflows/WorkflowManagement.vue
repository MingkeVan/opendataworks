<template>
  <div class="workflow-management">
    <el-tabs v-model="activeTab" class="workflow-tabs">
      <el-tab-pane label="工作流列表" name="workflows">
        <WorkflowList />
      </el-tab-pane>
      <el-tab-pane label="任务列表" name="tasks">
        <TaskList />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkflowList from './WorkflowList.vue'
import TaskList from '../tasks/TaskList.vue'

const TAB_WORKFLOWS = 'workflows'
const TAB_TASKS = 'tasks'

const route = useRoute()
const router = useRouter()

const resolveTab = () => (route.query.tab === TAB_TASKS ? TAB_TASKS : TAB_WORKFLOWS)
const activeTab = ref(resolveTab())

const syncRouteQuery = (targetTab) => {
  const normalized = targetTab === TAB_TASKS ? TAB_TASKS : TAB_WORKFLOWS
  if (route.query.tab === normalized) {
    return
  }
  router.replace({
    path: route.path,
    query: {
      ...route.query,
      tab: normalized
    }
  })
}

watch(
  () => route.query.tab,
  () => {
    const tab = resolveTab()
    if (tab !== activeTab.value) {
      activeTab.value = tab
    }
  }
)

watch(
  activeTab,
  (val) => {
    syncRouteQuery(val)
  }
)

onMounted(() => {
  syncRouteQuery(activeTab.value)
})
</script>

<style scoped>
.workflow-management {
  padding: 6px;
}

.workflow-tabs {
  margin-top: 12px;
}

.workflow-tabs :deep(.el-tab-pane) {
  padding-top: 8px;
}
</style>
