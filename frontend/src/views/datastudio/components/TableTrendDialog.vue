<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="760px"
    append-to-body
    destroy-on-close
  >
    <div class="trend-dialog-body" v-loading="historyLoading">
      <div v-if="series.length" ref="chartRef" class="trend-chart"></div>
      <el-empty v-else description="暂无统计趋势数据（等待定时同步后可查看）" :image-size="72" />
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { tableApi } from '@/api/table'
import { loadEcharts } from '@/utils/loadEcharts'
import { formatDateTime, formatRowCountDisplay, formatStorageSizeDisplay, parseTimeToMs } from '../tableFormat'

// P2-2 F17c：表行数/数据量趋势弹窗从 DataStudioRightPanel.vue 抽出。
// 自包含 ECharts 生命周期；父组件通过 ref 调用 open(metric) 打开。
const props = defineProps({
  table: {
    type: Object,
    default: null,
  },
})

const visible = ref(false)
const metric = ref('rowCount')
const historyLoading = ref(false)
const series = ref([])
const chartRef = ref(null)
let chartInstance = null

const dialogTitle = computed(() => {
  const metricName = metric.value === 'dataSize' ? '数据量' : '行数'
  const tableName = props.table?.tableName || '-'
  return `${tableName} ${metricName}趋势`
})

const open = async (nextMetric) => {
  if (!props.table?.id) return
  metric.value = nextMetric === 'dataSize' ? 'dataSize' : 'rowCount'
  visible.value = true
  await loadSeries()
}

const loadSeries = async () => {
  const tableId = props.table?.id
  if (!tableId) {
    series.value = []
    return
  }

  historyLoading.value = true
  try {
    const history = await tableApi.getStatisticsHistory(tableId, 60)
    const list = Array.isArray(history) ? history : []
    series.value = [...list].sort((a, b) => {
      return parseTimeToMs(a?.statisticsTime || a?.createdAt) - parseTimeToMs(b?.statisticsTime || b?.createdAt)
    })
  } catch (error) {
    series.value = []
    console.error('加载统计趋势失败', error)
  } finally {
    historyLoading.value = false
  }

  await nextTick()
  void renderChart()
}

const buildValues = () => {
  const labels = []
  const values = []
  series.value.forEach((item) => {
    const time = item?.statisticsTime || item?.createdAt || ''
    const value = metric.value === 'dataSize'
      ? Number(item?.dataSize ?? 0)
      : Number(item?.rowCount ?? 0)
    labels.push(formatDateTime(time))
    values.push(Number.isFinite(value) ? value : 0)
  })
  return { labels, values }
}

const renderChart = async () => {
  if (!visible.value || !chartRef.value || !series.value.length) return

  if (!chartInstance) {
    const echarts = await loadEcharts()
    if (!visible.value || !chartRef.value || !series.value.length) {
      return
    }
    chartInstance = echarts.init(chartRef.value)
  }

  const { labels, values } = buildValues()
  const metricLabel = metric.value === 'dataSize' ? '数据量' : '行数'

  chartInstance.setOption({
    animationDuration: 300,
    grid: { top: 30, left: 56, right: 20, bottom: 66, containLabel: true },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (val) => (
        metric.value === 'dataSize'
          ? formatStorageSizeDisplay(Number(val))
          : formatRowCountDisplay(Number(val))
      )
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: {
        rotate: labels.length > 10 ? 28 : 0,
        color: '#5d7491',
        fontSize: 11
      },
      axisLine: { lineStyle: { color: '#d8e3f1' } }
    },
    yAxis: {
      type: 'value',
      name: metricLabel,
      nameTextStyle: { color: '#5d7491', fontSize: 12 },
      axisLine: { show: false },
      axisLabel: {
        color: '#5d7491',
        fontSize: 11,
        formatter: (val) => (
          metric.value === 'dataSize'
            ? formatStorageSizeDisplay(Number(val))
            : formatRowCountDisplay(Number(val))
        )
      },
      splitLine: { lineStyle: { color: '#eef3fa' } }
    },
    series: [
      {
        name: metricLabel,
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2, color: '#4178c1' },
        itemStyle: { color: '#4178c1' },
        areaStyle: { color: 'rgba(65, 120, 193, 0.16)' },
        data: values
      }
    ]
  })

  chartInstance.resize()
}

watch(
  () => metric.value,
  () => {
    if (visible.value) {
      void renderChart()
    }
  }
)

watch(
  () => visible.value,
  async (value) => {
    if (value) {
      await nextTick()
      void renderChart()
      return
    }
    if (chartInstance) {
      chartInstance.dispose()
      chartInstance = null
    }
  }
)

onBeforeUnmount(() => {
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})

defineExpose({ open })
</script>

<style scoped>
.trend-dialog-body {
  min-height: 320px;
}

.trend-chart {
  width: 100%;
  height: 320px;
}
</style>
