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
              <el-radio-button label="dagre">层级图 (Dagre)</el-radio-button>
              <el-radio-button label="force">网状图 (Force)</el-radio-button>
              <el-radio-button label="indented">树状图 (Vertical)</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </template>
      <div class="chart-container" ref="chartRef" v-loading="loading"></div>
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
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">关闭</el-button>
          <el-button type="primary" @click="goToTableDetail">
            查看详情
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { lineageApi } from '@/api/lineage'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { LineageGraph } from './LineageGraph'
import { ElNotification } from 'element-plus'

const chartRef = ref(null)
const loading = ref(false)
const graphData = ref(null)
const businessDomains = ref([])
const dataDomains = ref([])
const route = useRoute()
const router = useRouter()
const currentLayout = ref('dagre')
const dialogVisible = ref(false)
const currentNode = ref(null)

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

let lineageGraph = null
const focusTable = ref(route.query.focus || '')

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
  if (lineageGraph && graphData.value) {
     lineageGraph.searchNode(filters.keyword.trim());
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
  if (lineageGraph) {
    lineageGraph.changeLayout(val)
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await lineageApi.getLineageGraph(buildParams())
    if (!res || !res.nodes || res.nodes.length === 0) {
        graphData.value = null
    } else {
        const nodes = (res?.nodes || []).map(node => ({
        ...node,
        // Ensure required fields
        id: node.id || node.name, 
        label: node.name,
        // Map properties for custom rendering
        layer: node.layer,
        inDegree: node.inDegree || 0,
        outDegree: node.outDegree || 0,
        style: {
            stroke: getLayerColor(node.layer)
        }
        }));
        
        const edges = (res?.edges || []).map(edge => ({
        source: edge.source,
        target: edge.target,
        }));

        graphData.value = { nodes, edges }
    }
    
    await nextTick()
    renderChart()
  } catch (error) {
    console.error('加载血缘数据失败:', error)
  } finally {
    loading.value = false
  }
}

const getLayerColor = (layer) => {
    const colors = {
    ODS: '#409EFF',
    DWD: '#67C23A',
    DIM: '#E6A23C',
    DWS: '#F56C6C',
    ADS: '#909399'
  }
  return colors[layer] || '#409EFF';
}

const renderChart = () => {
  if (!chartRef.value) return

  if (!lineageGraph) {
    lineageGraph = new LineageGraph(chartRef.value)
    lineageGraph.init({
        layout: {
            type: currentLayout.value,
             rankdir: 'LR',
             nodesep: 30,
             ranksep: 80,
        }
    })
    // Bind cycle detection callback
    // Bind cycle detection callback
    lineageGraph.onCycleDetected = (cycles) => {
        ElNotification({
            title: '循环依赖提醒',
            message: `检测到 ${cycles.length} 处循环依赖，已用红色虚线标出`,
            type: 'warning',
            duration: 0
        })
    }
    
    // Bind node click callback
    lineageGraph.onNodeClick = (nodeModel) => {
        currentNode.value = nodeModel
        dialogVisible.value = true
    }
  }

  lineageGraph.render(graphData.value)
  
  // Handle initial focus
  if (focusTable.value) {
      lineageGraph.focusNode(focusTable.value); // Assuming ID is name or passed correctly
  }
}

const goToTableDetail = () => {
  if (currentNode.value) {
    router.push({
      path: '/tables',
      query: { tableName: currentNode.value.name }
    })
    dialogVisible.value = false
  }
}

const handleResize = () => {
  lineageGraph?.handleResize()
}

watch(
  () => route.query.focus,
  (focus) => {
    focusTable.value = focus || ''
    nextTick(() => {
        if(lineageGraph) lineageGraph.focusNode(focusTable.value)
    })
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

onBeforeUnmount(() => {
  if (lineageGraph) {
    lineageGraph.destroy()
    lineageGraph = null
  }
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
</style>
