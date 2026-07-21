<template>
  <div class="eval-results">
    <div class="eval-results__toolbar">
      <div>
        <div class="eval-results__title">评测结果</div>
        <div class="eval-results__subtitle">查看评测运行记录与趋势</div>
      </div>
      <div class="eval-results__filters">
        <el-select v-model="filterDatasetId" clearable placeholder="按评测集筛选" class="eval-results__select">
          <el-option
            v-for="ds in datasets"
            :key="ds.dataset_id"
            :label="ds.name"
            :value="ds.dataset_id"
          />
        </el-select>
        <el-select v-model="filterEngine" clearable placeholder="按引擎筛选" class="eval-results__select-sm">
          <el-option label="builtin" value="builtin" />
          <el-option label="deepeval" value="deepeval" />
          <el-option label="opik" value="opik" />
        </el-select>
      </div>
    </div>

    <div class="eval-results__trend-section">
      <div class="eval-results__trend-header">
        <div class="eval-results__trend-title">趋势</div>
      </div>
      <div v-loading="trendLoading" class="eval-results__trend-chart">
        <div v-if="trendData.length" ref="chartRef" class="eval-results__chart-canvas" />
        <el-empty v-else description="暂无趋势数据" :image-size="80" />
      </div>
    </div>

    <div class="eval-results__run-section">
      <div class="eval-results__run-title">运行记录</div>
      <el-table
        v-loading="runsLoading"
        :data="runs"
        stripe
        class="eval-results__table"
      >
        <el-table-column prop="run_label" label="运行标签" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" @click="openRunDetail(row)">{{ row.run_label || row.run_id.slice(0, 12) }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="evaluation_engine" label="引擎" width="100" />
        <el-table-column prop="model" label="模型" width="160" show-overflow-tooltip />
        <el-table-column label="通过" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.passed ? 'success' : 'danger'">
              {{ row.passed ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="平均分" width="90" align="center">
          <template #default="{ row }">{{ formatScore(row.average_score) }}</template>
        </el-table-column>
        <el-table-column label="用例" width="120" align="center">
          <template #default="{ row }">
            <span class="eval-results__pass-count">{{ row.passed_cases }}</span> /
            <span>{{ row.total_cases }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="run_status" label="状态" width="80" />
        <el-table-column label="运行时间" width="170">
          <template #default="{ row }">{{ formatTime(row.started_at || row.ingested_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openRunDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!runsLoading && !runs.length"
        description="暂无运行记录"
        :image-size="100"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import * as echarts from 'echarts/core'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { dataagentApi } from '@/api/dataagent'
import { withAgentContext } from '@/router/agentContext'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent])

const route = useRoute()
const router = useRouter()

const datasets = ref([])
const runs = ref([])
const trendData = ref([])
const runsLoading = ref(false)
const trendLoading = ref(false)
const filterDatasetId = ref('')
const filterEngine = ref('')

const chartRef = ref(null)
let chartInstance = null
let resizeObserver = null

const formatTime = (val) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-'
const formatScore = (val) => typeof val === 'number' ? val.toFixed(2) : '-'

const notifyError = (error, fallback) => {
  if (!error?.__odwNotified) ElMessage.error(error?.message || fallback)
}

const loadDatasets = async () => {
  try {
    datasets.value = await dataagentApi.listEvalDatasets()
  } catch { /* ignore */ }
}

const loadRuns = async () => {
  runsLoading.value = true
  try {
    runs.value = await dataagentApi.listEvalRuns({
      dataset_id: filterDatasetId.value || undefined,
      evaluation_engine: filterEngine.value || undefined,
      limit: 100
    })
  } catch (e) {
    runs.value = []
    notifyError(e, '加载运行记录失败')
  } finally {
    runsLoading.value = false
  }
}

const loadTrends = async () => {
  trendLoading.value = true
  try {
    trendData.value = await dataagentApi.getEvalTrends({
      dataset_id: filterDatasetId.value || undefined,
      evaluation_engine: filterEngine.value || undefined,
      limit: 50
    })
  } catch (e) {
    trendData.value = []
    notifyError(e, '加载趋势数据失败')
  } finally {
    trendLoading.value = false
  }
}

const buildChartOption = () => {
  if (!trendData.value.length) return null

  const xLabels = trendData.value.map((p) => {
    if (p.started_at) return dayjs(p.started_at).format('MM-DD HH:mm')
    return p.run_label || p.run_id?.slice(0, 8) || ''
  })

  const series = [
    {
      name: '平均分',
      type: 'line',
      data: trendData.value.map((p) => p.average_score ?? null),
      smooth: true,
      connectNulls: true
    },
    {
      name: '意图准确率',
      type: 'line',
      data: trendData.value.map((p) => p.intent_accuracy ?? null),
      smooth: true,
      connectNulls: true
    },
    {
      name: '实体准确率',
      type: 'line',
      data: trendData.value.map((p) => p.ontology_accuracy ?? null),
      smooth: true,
      connectNulls: true
    },
    {
      name: '幻觉率',
      type: 'line',
      data: trendData.value.map((p) => p.hallucination_rate ?? null),
      smooth: true,
      connectNulls: true
    },
    {
      name: '结果一致率',
      type: 'line',
      data: trendData.value.map((p) => p.result_consistency_rate ?? null),
      smooth: true,
      connectNulls: true
    },
    {
      name: '有效通过率',
      type: 'line',
      data: trendData.value.map((p) => p.effective_pass_rate ?? null),
      smooth: true,
      connectNulls: true
    }
  ].filter((s) => s.data.some((v) => v !== null && v !== undefined))

  return {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 50, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: xLabels, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value', min: 0 },
    series
  }
}

const disposeChart = () => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
}

const renderChart = async () => {
  await nextTick()
  const container = chartRef.value
  if (!container) return
  const option = buildChartOption()
  if (!option) {
    disposeChart()
    return
  }
  if (!chartInstance) {
    chartInstance = echarts.init(container)
    resizeObserver = new ResizeObserver(() => chartInstance?.resize())
    resizeObserver.observe(container)
  }
  chartInstance.clear()
  chartInstance.setOption(option, { notMerge: true })
  chartInstance.resize()
}

const openRunDetail = (run) => {
  router.push(withAgentContext({
    name: 'EvaluationRunDetail',
    params: { runId: run.run_id }
  }, route.query))
}

watch([filterDatasetId, filterEngine], () => {
  loadRuns()
  loadTrends()
})

watch(trendData, () => renderChart(), { deep: true })

onMounted(async () => {
  await loadDatasets()
  await Promise.all([loadRuns(), loadTrends()])
})

onBeforeUnmount(() => disposeChart())
</script>

<style scoped>
.eval-results {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}

.eval-results__toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.eval-results__title {
  font-size: 18px;
  font-weight: 600;
  color: #0f172a;
}

.eval-results__subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
}

.eval-results__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.eval-results__select {
  width: 200px;
}

.eval-results__select-sm {
  width: 140px;
}

.eval-results__trend-section {
  background: #fff;
  border: 1px solid #dbe2ea;
  border-radius: 8px;
  padding: 16px;
}

.eval-results__trend-header {
  margin-bottom: 12px;
}

.eval-results__trend-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.eval-results__trend-chart {
  min-height: 320px;
}

.eval-results__chart-canvas {
  width: 100%;
  height: 320px;
}

.eval-results__run-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.eval-results__run-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.eval-results__table {
  min-width: 0;
}

.eval-results__pass-count {
  color: #22c55e;
  font-weight: 600;
}

@media (max-width: 768px) {
  .eval-results__toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .eval-results__filters {
    flex-direction: column;
    align-items: stretch;
  }

  .eval-results__select,
  .eval-results__select-sm {
    width: 100%;
  }
}
</style>
