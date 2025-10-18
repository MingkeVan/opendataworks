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
          <el-input v-model="filters.keyword" placeholder="表名/描述" clearable style="width: 220px" />
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
      <div class="chart-container" ref="chartRef" v-loading="loading"></div>
      <div class="empty" v-if="!loading && (!graphData || graphData.nodes.length === 0)">
        <el-empty description="暂无血缘数据，请调整筛选条件" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { lineageApi } from '@/api/lineage'
import { businessDomainApi, dataDomainApi } from '@/api/domain'

const chartRef = ref(null)
const loading = ref(false)
const graphData = ref(null)
const businessDomains = ref([])
const dataDomains = ref([])
const route = useRoute()
const router = useRouter()

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

let chartInstance = null
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
  loadData()
}

const handleReset = () => {
  filters.layer = ''
  filters.businessDomain = ''
  filters.dataDomain = ''
  filters.keyword = ''
  focusTable.value = ''
  router.replace({ query: {} })
  loadData()
}

const loadData = async () => {
  loading.value = true
  try {
    graphData.value = await lineageApi.getLineageGraph(buildParams())
    await nextTick()
    renderChart()
  } catch (error) {
    console.error('加载血缘数据失败:', error)
  } finally {
    loading.value = false
  }
}

const renderChart = () => {
  if (!chartRef.value) {
    return
  }

  if (chartInstance) {
    chartInstance.dispose()
  }

  chartInstance = echarts.init(chartRef.value)

  const layerColors = {
    ODS: '#409EFF',
    DWD: '#67C23A',
    DIM: '#E6A23C',
    DWS: '#F56C6C',
    ADS: '#909399'
  }

  const nodes = (graphData.value?.nodes || []).map((node) => ({
    id: node.id,
    name: node.name,
    category: node.layer,
    businessDomain: node.businessDomain,
    dataDomain: node.dataDomain,
    itemStyle: {
      color: layerColors[node.layer] || '#409EFF'
    },
    label: {
      show: true
    }
  }))

  const links = (graphData.value?.edges || []).map(edge => ({
    source: edge.source,
    target: edge.target
  }))

  const categories = layerOptions.map(item => ({ name: item.value }))

  const option = {
    title: {
      text: '数据血缘关系图',
      left: 'center'
    },
    tooltip: {
      formatter: (params) => {
        if (params.dataType === 'node') {
          const { name, category, businessDomain, dataDomain } = params.data
          const lines = [
            `表名: ${name}`,
            `层级: ${category}`
          ]
          if (businessDomain) lines.push(`业务域: ${businessDomain}`)
          if (dataDomain) lines.push(`数据域: ${dataDomain}`)
          return lines.join('<br/>')
        }
        return ''
      }
    },
    legend: [{
      data: categories.map(c => c.name),
      orient: 'vertical',
      left: 'left'
    }],
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodes,
      links,
      categories,
      roam: true,
      label: {
        position: 'right',
        formatter: '{b}'
      },
      force: {
        repulsion: 500,
        edgeLength: 150
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: {
          width: 5
        }
      }
    }]
  }

  chartInstance.setOption(option)
  highlightFocus()
}

const highlightFocus = () => {
  if (!chartInstance || !focusTable.value || !graphData.value?.nodes?.length) {
    return
  }

  const index = graphData.value.nodes.findIndex(node => node.name === focusTable.value)
  if (index === -1) {
    return
  }

  chartInstance.dispatchAction({
    type: 'focusNodeAdjacency',
    seriesIndex: 0,
    dataIndex: index
  })
  chartInstance.dispatchAction({
    type: 'showTip',
    seriesIndex: 0,
    dataIndex: index
  })
}

const handleResize = () => {
  chartInstance?.resize()
}

watch(
  () => route.query.focus,
  (focus) => {
    focusTable.value = focus || ''
    nextTick(() => highlightFocus())
  }
)

onMounted(async () => {
  await loadBusinessDomains()
  await loadDataDomains()
  await loadData()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<style scoped>
.lineage-view {
  height: 100%;
}

.filter-card {
  margin-bottom: 16px;
}

.chart-card {
  min-height: 500px;
}

.chart-container {
  width: 100%;
  height: calc(100vh - 260px);
}

.empty {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 320px;
}
</style>
