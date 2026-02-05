<template>
  <div class="lineage-view">
    <el-card shadow="never" class="filter-card">
      <el-form :model="filters" inline label-width="80px">
        <el-form-item label="层级">
          <el-select v-model="filters.layer" placeholder="全部" clearable style="width: 140px">
            <el-option v-for="layer in layerOptions" :key="layer.value" :label="layer.label" :value="layer.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务域">
          <el-select v-model="filters.businessDomain" placeholder="全部" clearable style="width: 160px" @change="handleBusinessChange">
            <el-option v-for="item in businessDomains" :key="item.domainCode" :label="item.domainName" :value="item.domainCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据域">
          <el-select v-model="filters.dataDomain" placeholder="全部" clearable style="width: 160px">
            <el-option v-for="item in dataDomains" :key="item.domainCode" :label="item.domainName" :value="item.domainCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" placeholder="表名/描述" clearable style="width: 220px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            查询
          </el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="chart-card">
      <template #header>
        <div class="card-header">
          <span>数据血缘图</span>
          <div class="graph-controls">
            <el-radio-group v-model="currentLayout" size="small" @change="handleLayoutChange">
              <el-radio-button value="dagre">层级图 (Dagre)</el-radio-button>
              <el-radio-button value="force" disabled>网状图 (Force)</el-radio-button>
              <el-radio-button value="indented">树状图 (Vertical)</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </template>
      <div class="chart-container" v-loading="loading">
        <LineageFlow
          v-if="graphData && graphData.nodes && graphData.nodes.length"
          ref="lineageFlowRef"
          :graph="graphData"
          :layout="currentLayout"
          :focus="focusTable"
          @nodeClick="handleNodeClick"
          @cycleDetected="handleCycleDetected"
        />
      </div>
      <div class="empty" v-if="!loading && (!graphData || graphData.nodes.length === 0)">
        <el-empty description="暂无血缘数据，请调整筛选条件" />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="表详情" width="500px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="表名">{{ currentNode?.name }}</el-descriptions-item>
        <el-descriptions-item label="层级">{{ currentNode?.layer }}</el-descriptions-item>
        <el-descriptions-item label="业务域">{{ currentNode?.businessDomain || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数据域">{{ currentNode?.dataDomain || '-' }}</el-descriptions-item>
        <el-descriptions-item label="上游节点数">{{ currentNode?.inDegree || 0 }}</el-descriptions-item>
        <el-descriptions-item label="下游节点数">{{ currentNode?.outDegree || 0 }}</el-descriptions-item>
      </el-descriptions>

      <div class="task-section" v-loading="tasksLoading">
        <div class="task-entry">
          <div class="task-entry-header">
            <span>写入任务 ({{ relatedTasks.writeTasks.length }})</span>
            <el-button
              type="primary"
              size="small"
              plain
              :disabled="!currentNode?.tableId"
              @click="goCreateRelatedTask('write')"
            >
              <el-icon><Plus /></el-icon>
              新增写入任务
            </el-button>
          </div>
          <div v-if="relatedTasks.writeTasks.length" class="task-entry-list">
            <div v-for="task in relatedTasks.writeTasks" :key="task.id" class="task-entry-item">
              <div class="task-entry-title">
                <el-link type="primary" @click="goTaskDetail(task.id)">
                  {{ task.taskName || '-' }}
                </el-link>
                <el-tag v-if="task.status" size="small" :type="taskStatusTag(task.status)">
                  {{ task.status }}
                </el-tag>
              </div>
              <div class="task-entry-meta">
                <span>引擎: {{ task.engine || '-' }}</span>
                <span v-if="task.taskCode">编码: {{ task.taskCode }}</span>
              </div>
            </div>
          </div>
          <el-empty v-else description="暂无写入任务" :image-size="50" />
        </div>

        <div class="task-entry">
          <div class="task-entry-header">
            <span>读取任务 ({{ relatedTasks.readTasks.length }})</span>
            <el-button
              type="primary"
              size="small"
              plain
              :disabled="!currentNode?.tableId"
              @click="goCreateRelatedTask('read')"
            >
              <el-icon><Plus /></el-icon>
              新增读取任务
            </el-button>
          </div>
          <div v-if="relatedTasks.readTasks.length" class="task-entry-list">
            <div v-for="task in relatedTasks.readTasks" :key="task.id" class="task-entry-item">
              <div class="task-entry-title">
                <el-link type="primary" @click="goTaskDetail(task.id)">
                  {{ task.taskName || '-' }}
                </el-link>
                <el-tag v-if="task.status" size="small" :type="taskStatusTag(task.status)">
                  {{ task.status }}
                </el-tag>
              </div>
              <div class="task-entry-meta">
                <span>引擎: {{ task.engine || '-' }}</span>
                <span v-if="task.taskCode">编码: {{ task.taskCode }}</span>
              </div>
            </div>
          </div>
          <el-empty v-else description="暂无读取任务" :image-size="50" />
        </div>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">关闭</el-button>
          <el-button type="primary" @click="goToTableDetail">
            查看详情
          </el-button>
        </span>
      </template>
    </el-dialog>

    <TaskEditDrawer ref="taskDrawerRef" @success="handleTaskSuccess" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, Plus } from '@element-plus/icons-vue'
import { lineageApi } from '@/api/lineage'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { tableApi } from '@/api/table'
import { ElMessage, ElNotification } from 'element-plus'
import TaskEditDrawer from '@/views/tasks/TaskEditDrawer.vue'
import LineageFlow from './LineageFlow.vue'

const loading = ref(false)
const graphData = ref(null)
const businessDomains = ref([])
const dataDomains = ref([])
const route = useRoute()
const router = useRouter()
const currentLayout = ref('dagre')
const dialogVisible = ref(false)
const currentNode = ref(null)
const relatedTasks = ref({ writeTasks: [], readTasks: [] })
const tasksLoading = ref(false)
const taskDrawerRef = ref(null)
const lineageFlowRef = ref(null)

const filters = reactive({
  layer: '',
  businessDomain: '',
  dataDomain: '',
  keyword: ''
})

const layerOptions = [
  { label: 'ODS', value: 'ODS' },
  { label: 'DWD', value: 'DWD' },
  { label: 'DIM', value: 'DIM' },
  { label: 'DWS', value: 'DWS' },
  { label: 'ADS', value: 'ADS' }
]

const focusTable = ref(route.query.focus || '')

const loadRelatedTasks = async () => {
  relatedTasks.value = { writeTasks: [], readTasks: [] }

  const tableId = currentNode.value?.tableId
  if (!tableId) return

  tasksLoading.value = true
  try {
    const data = await tableApi.getTasks(tableId)
    relatedTasks.value = {
      writeTasks: Array.isArray(data?.writeTasks) ? data.writeTasks : [],
      readTasks: Array.isArray(data?.readTasks) ? data.readTasks : []
    }
  } catch (error) {
    console.error('加载关联任务失败:', error)
  } finally {
    tasksLoading.value = false
  }
}

const buildParams = () => {
  const params = {}
  if (filters.layer) params.layer = filters.layer
  if (filters.businessDomain) params.businessDomain = filters.businessDomain
  if (filters.dataDomain) params.dataDomain = filters.dataDomain
  if (filters.keyword) params.keyword = filters.keyword.trim()
  return params
}

const loadBusinessDomains = async () => {
  businessDomains.value = await businessDomainApi.list()
}

const loadDataDomains = async () => {
  const params = {}
  if (filters.businessDomain) {
    params.businessDomain = filters.businessDomain
  }
  dataDomains.value = await dataDomainApi.list(params)
}

const handleBusinessChange = async () => {
  filters.dataDomain = ''
  await loadDataDomains()
}

const handleSearch = () => {
  // If we have data, try client-side search first for highlighting
  if (lineageFlowRef.value && graphData.value) {
    lineageFlowRef.value.searchNode(filters.keyword.trim())
  }
  // Also reload data to ensure filtering if backend supports it
  loadData()
}

const handleReset = () => {
  filters.layer = ''
  filters.businessDomain = ''
  filters.dataDomain = ''
  filters.keyword = ''
  focusTable.value = ''
  currentLayout.value = 'dagre'
  router.replace({ query: {} })
  loadData()
}

const handleLayoutChange = (val) => {
  lineageFlowRef.value?.fitGraph?.()
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await lineageApi.getLineageGraph(buildParams())
    if (!res || !res.nodes || res.nodes.length === 0) {
        graphData.value = null
    } else {
        graphData.value = {
          nodes: (res?.nodes || []).map((node) => ({
            ...node,
            id: node.id || node.name,
            name: node.name || node.id
          })),
          edges: (res?.edges || []).map((edge) => ({
            ...edge,
            source: edge.source,
            target: edge.target
          }))
        }
    }
    
    await nextTick()
  } catch (error) {
    console.error('加载血缘数据失败:', error)
  } finally {
    loading.value = false
  }
}

const handleCycleDetected = (cycles) => {
  if (!Array.isArray(cycles) || !cycles.length) return
  ElNotification({
    title: '循环依赖提醒',
    message: `检测到 ${cycles.length} 处循环依赖，已用红色虚线标出`,
    type: 'warning',
    duration: 0
  })
}

const handleNodeClick = (nodeModel) => {
  currentNode.value = nodeModel
  dialogVisible.value = true
  loadRelatedTasks()
}

const goToTableDetail = async () => {
  const tableId = currentNode.value?.tableId
  if (!tableId) return
  try {
    const info = await tableApi.getById(tableId)
    const clusterId = info?.clusterId || info?.sourceId
    const database = info?.dbName || info?.databaseName || info?.database
    if (!clusterId || !database) {
      ElMessage.warning('缺少集群或数据库信息，无法打开 DataStudio')
      return
    }
    router.push({
      path: '/datastudio',
      query: { clusterId: String(clusterId), database: String(database), tableId: String(tableId) }
    })
    dialogVisible.value = false
  } catch (error) {
    ElMessage.error('加载表信息失败')
  }
}

const goCreateRelatedTask = (relation) => {
  const tableId = currentNode.value?.tableId
  if (!tableId) return
  taskDrawerRef.value?.open(null, { relation, tableId })
}

const goTaskDetail = (taskId) => {
  if (!taskId) return
  taskDrawerRef.value?.open(taskId)
}

const handleTaskSuccess = async () => {
  await loadRelatedTasks()
  await loadData()
}

const taskStatusTag = (status) => {
  const normalized = String(status || '').toLowerCase()
  if (normalized === 'published') return 'success'
  if (normalized === 'running') return 'warning'
  if (normalized === 'draft') return 'info'
  return ''
}

watch(
  () => route.query.focus,
  (focus) => {
    focusTable.value = focus || ''
  }
)

onMounted(async () => {
  await loadBusinessDomains()
  await loadDataDomains()
  await loadData()
  // No need for window resize listener here as LineageGraph handles it if initialized,
  // but it's good practice to manage lifecycle here if we want more control.
  // LineageGraph handles it internally.
})
</script>

<style scoped>
.lineage-view {
  height: 100%;
  padding: 6px;
}

.lineage-view :deep(.el-card) {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.lineage-view :deep(.el-card__body) {
  padding: 16px;
}

.filter-card {
  margin-bottom: 12px;
}

.chart-card {
  min-height: 500px;
  display: flex;
  flex-direction: column;
}

.card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.chart-container {
  width: 100%;
  height: calc(100vh - 260px);
  background: #fdfdfd; 
}

.empty {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 320px;
}

.task-section {
  margin-top: 16px;
}

.task-entry + .task-entry {
  margin-top: 14px;
}

.task-entry-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-weight: 500;
}

.task-entry-list {
  max-height: 240px;
  overflow: auto;
  padding-right: 6px;
}

.task-entry-item {
  padding: 10px 12px;
  border-radius: 10px;
  background: #fafafa;
  border: 1px solid rgba(0, 0, 0, 0.04);
  margin-bottom: 10px;
}

.task-entry-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.task-entry-meta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  color: #909399;
  font-size: 12px;
  margin-top: 6px;
}
</style>
