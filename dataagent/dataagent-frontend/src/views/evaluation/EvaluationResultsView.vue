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
      <div v-loading="trendLoading" class="eval-results__trend-body">
        <div v-if="trendData.length" class="eval-results__chart-grid">
          <div
            v-for="def in visibleChartDefs"
            :key="def.key"
            class="eval-results__chart-card"
          >
            <div class="eval-results__chart-title">{{ def.title }}</div>
            <div
              :ref="(el) => setChartContainer(def.key, el)"
              class="eval-results__chart-canvas"
            />
          </div>
        </div>
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

const CHART_DEFS = [
  {
    key: 'score',
    title: '平均分',
    percent: false,
    series: [{ name: '平均分', field: 'average_score' }]
  },
  {
    key: 'accuracy',
    title: '准确率',
    percent: true,
    series: [
      { name: '意图', field: 'intent_accuracy' },
      { name: '实体', field: 'ontology_accuracy' },
      { name: '关系', field: 'relation_accuracy' },
      { name: '结果一致', field: 'result_consistency_rate' },
      { name: '数据准确', field: 'data_accuracy' },
      { name: '时间维度', field: 'time_accuracy' },
      { name: '回答', field: 'answer_accuracy' },
      { name: 'SQL/工具', field: 'tool_sql_accuracy' }
    ]
  },
  {
    key: 'pass',
    title: '通过与完成度',
    percent: true,
    series: [
      { name: '有效通过率', field: 'effective_pass_rate' },
      { name: '完成率', field: 'completion_rate' },
      { name: '幻觉率', field: 'hallucination_rate' }
    ]
  },
  {
    key: 'timing',
    title: '耗时（秒）',
    percent: false,
    series: [
      { name: '端到端均值', field: 'avg_e2e_seconds' },
      { name: '端到端 P90', field: 'p90_e2e_seconds' },
      { name: '执行均值', field: 'avg_execution_seconds' },
      { name: '评判均值', field: 'avg_judge_seconds' }
    ]
  },
  {
    key: 'turns',
    title: '轮次与调用',
    percent: false,
    series: [
      { name: 'Agent 轮次', field: 'avg_agent_turns' },
      { name: '用户轮次', field: 'avg_user_turns' },
      { name: '工具调用', field: 'avg_tool_calls' },
      { name: 'SQL 执行', field: 'avg_sql_executions' }
    ]
  },
  {
    key: 'tokens',
    title: 'Token 消耗',
    percent: false,
    series: [
      { name: '输入', field: 'avg_input_tokens' },
      { name: '输出', field: 'avg_output_tokens' },
      { name: '缓存读取', field: 'avg_cache_tokens' }
    ]
  }
]

const chartContainers = new Map()
const chartInstances = new Map()
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

const seriesValues = (def) =>
  def.series.map((s) => ({
    ...s,
    data: trendData.value.map((p) => (typeof p[s.field] === 'number' ? p[s.field] : null))
  }))

const chartHasData = (def) =>
  seriesValues(def).some((s) => s.data.some((v) => v !== null))

const visibleChartDefs = computed(() =>
  trendData.value.length ? CHART_DEFS.filter((def) => chartHasData(def)) : []
)

const setChartContainer = (key, el) => {
  if (el) {
    chartContainers.set(key, el)
  } else {
    chartContainers.delete(key)
    const instance = chartInstances.get(key)
    if (instance) {
      instance.dispose()
      chartInstances.delete(key)
    }
  }
}

const buildChartOption = (def) => {
  const xLabels = trendData.value.map((p) => {
    if (p.started_at) return dayjs(p.started_at).format('MM-DD HH:mm')
    return p.run_label || p.run_id?.slice(0, 8) || ''
  })
  const series = seriesValues(def)
    .filter((s) => s.data.some((v) => v !== null))
    .map((s) => ({
      name: s.name,
      type: 'line',
      data: s.data,
      smooth: true,
      connectNulls: true
    }))
  const yAxis = { type: 'value', min: 0 }
  if (def.percent) {
    yAxis.max = 1
    yAxis.axisLabel = { formatter: (v) => `${Math.round(v * 100)}%` }
  }
  return {
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => {
        if (typeof v !== 'number') return '-'
        return def.percent ? `${(v * 100).toFixed(1)}%` : v.toFixed(2)
      }
    },
    legend: { bottom: 0, type: 'scroll' },
    grid: { left: 50, right: 16, top: 16, bottom: 40 },
    xAxis: { type: 'category', data: xLabels, axisLabel: { rotate: 30 } },
    yAxis,
    series
  }
}

const disposeCharts = () => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  for (const instance of chartInstances.values()) instance.dispose()
  chartInstances.clear()
}

const renderCharts = async () => {
  await nextTick()
  if (!trendData.value.length) {
    disposeCharts()
    return
  }
  if (!resizeObserver) {
    resizeObserver = new ResizeObserver(() => {
      for (const instance of chartInstances.values()) instance.resize()
    })
  }
  for (const def of visibleChartDefs.value) {
    const container = chartContainers.get(def.key)
    if (!container) continue
    let instance = chartInstances.get(def.key)
    if (!instance) {
      instance = echarts.init(container)
      chartInstances.set(def.key, instance)
      resizeObserver.observe(container)
    }
    instance.clear()
    instance.setOption(buildChartOption(def), { notMerge: true })
    instance.resize()
  }
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

watch(trendData, () => renderCharts(), { deep: true })

onMounted(async () => {
  await loadDatasets()
  await Promise.all([loadRuns(), loadTrends()])
})

onBeforeUnmount(() => disposeCharts())
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

.eval-results__trend-body {
  min-height: 280px;
}

.eval-results__chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.eval-results__chart-card {
  border: 1px solid #eef2f6;
  border-radius: 8px;
  padding: 12px;
  min-width: 0;
}

.eval-results__chart-title {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 8px;
}

.eval-results__chart-canvas {
  width: 100%;
  height: 260px;
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

  .eval-results__chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
