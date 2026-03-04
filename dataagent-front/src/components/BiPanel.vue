<template>
  <section class="bi-panel">
    <header class="panel-header">
      <div class="header-title">智能 BI</div>
      <div class="header-actions">
        <el-select v-model="selectedType" size="small" class="type-select" @change="emitRegenerate">
          <el-option label="自动" value="" />
          <el-option label="折线" value="line" />
          <el-option label="柱状" value="bar" />
          <el-option label="饼图" value="pie" />
          <el-option label="多折线" value="multi-line" />
        </el-select>
        <el-button size="small" @click="copyChart">复制图表配置</el-button>
      </div>
    </header>

    <div class="chart-wrapper">
      <div v-if="hasChart" ref="chartRef" class="chart-canvas"></div>
      <el-empty v-else description="暂无图表，可先执行智能问数" :image-size="72" />
    </div>

    <div class="reasoning" v-if="chart?.chart_reasoning">
      <span class="label">chart_reasoning:</span>
      <span>{{ chart.chart_reasoning }}</span>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'

const props = defineProps({
  chart: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['regenerate-chart'])

const chartRef = ref(null)
const selectedType = ref('')
let chartInstance = null

const hasChart = computed(() => !!props.chart?.echartsOption)

const renderChart = () => {
  if (!hasChart.value || !chartRef.value) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  chartInstance.setOption(props.chart.echartsOption, true)
}

const disposeChart = () => {
  if (!chartInstance) return
  chartInstance.dispose()
  chartInstance = null
}

const copyChart = async () => {
  if (!props.chart?.echartsOption) return
  const text = JSON.stringify(props.chart.echartsOption, null, 2)
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('图表配置已复制')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

const emitRegenerate = () => {
  emit('regenerate-chart', selectedType.value)
}

watch(() => props.chart, () => {
  nextTick(() => {
    if (!hasChart.value) {
      disposeChart()
      return
    }
    renderChart()
  })
}, { deep: true })

onMounted(() => {
  if (hasChart.value) {
    renderChart()
  }
  window.addEventListener('resize', renderChart)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', renderChart)
  disposeChart()
})
</script>

<style scoped>
.bi-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  height: 100%;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.header-title {
  font-size: 14px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.type-select {
  width: 96px;
}

.chart-wrapper {
  flex: 1;
  min-height: 180px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.chart-canvas {
  width: 100%;
  height: 240px;
}

.reasoning {
  border-radius: 8px;
  background: #f4f7ff;
  border: 1px solid #d9e2ff;
  padding: 8px 10px;
  color: #42526e;
  font-size: 12px;
}

.label {
  margin-right: 6px;
  font-weight: 600;
}
</style>
