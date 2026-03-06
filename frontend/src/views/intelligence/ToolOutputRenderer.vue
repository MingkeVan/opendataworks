<template>
  <div class="tool-output" :class="{ failed: hasError }">
    <div class="tool-output-head">
      <div>
        <div class="tool-output-label">{{ displayLabel }}</div>
        <div class="tool-output-meta">
          <span>{{ statusLabel }}</span>
          <span v-if="toolName">· {{ toolName }}</span>
          <span v-if="kind === 'sql_execution' && rowCountText">· {{ rowCountText }}</span>
          <span v-if="kind === 'sql_execution' && durationText">· {{ durationText }}</span>
        </div>
      </div>
      <div v-if="scriptName" class="tool-output-chip">{{ scriptName }}</div>
    </div>

    <div v-if="summaryText" class="tool-output-summary">{{ summaryText }}</div>

    <div v-if="errorText" class="tool-output-error">{{ errorText }}</div>

    <template v-if="kind === 'sql_execution'">
      <pre v-if="sqlText" class="tool-code"><code>{{ sqlText }}</code></pre>

      <div v-if="columns.length && rows.length" class="tool-table-wrap">
        <table class="tool-table">
          <thead>
            <tr>
              <th v-for="column in columns" :key="column">{{ column }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
              <td v-for="column in columns" :key="column">{{ row[column] }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="!errorText" class="tool-output-empty">无数据</div>
    </template>

    <template v-else-if="kind === 'chart_spec'">
      <VChart
        v-if="chartOption"
        class="tool-chart"
        :option="chartOption"
        autoresize
      />
      <div v-else class="tool-output-empty">图表数据为空</div>
    </template>

    <template v-else-if="kind === 'python_execution'">
      <pre v-if="stdoutText" class="tool-code"><code>{{ stdoutText }}</code></pre>
      <pre v-if="resultText" class="tool-code tool-code-light"><code>{{ resultText }}</code></pre>
    </template>

    <pre v-else-if="rawText" class="tool-code tool-code-light"><code>{{ rawText }}</code></pre>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([
  CanvasRenderer,
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent
])

const props = defineProps({
  tool: {
    type: Object,
    default: () => ({})
  }
})

const parseMaybeJson = (value) => {
  if (typeof value !== 'string') return null
  const raw = value.trim()
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch (_error) {
    const firstBrace = raw.indexOf('{')
    const lastBrace = raw.lastIndexOf('}')
    if (firstBrace >= 0 && lastBrace > firstBrace) {
      try {
        return JSON.parse(raw.slice(firstBrace, lastBrace + 1))
      } catch (_innerError) {
        return null
      }
    }
    return null
  }
}

const isPlainObject = (value) => value && typeof value === 'object' && !Array.isArray(value)

const extractTextParts = (value) => {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) {
    return value.map((item) => {
      if (typeof item === 'string') return item
      if (isPlainObject(item)) {
        if (typeof item.text === 'string') return item.text
        if (typeof item.content === 'string') return item.content
      }
      return ''
    }).filter(Boolean).join('\n')
  }
  if (isPlainObject(value)) {
    if (typeof value.text === 'string') return value.text
    if (typeof value.content === 'string') return value.content
    if (typeof value.stdout === 'string') return value.stdout
    if (typeof value.result === 'string') return value.result
  }
  return ''
}

const normalizeOutput = (value) => {
  if (isPlainObject(value) && value.kind) return value
  if (Array.isArray(value)) {
    for (const item of value) {
      if (isPlainObject(item) && item.kind) return item
      const text = extractTextParts(item)
      const parsed = parseMaybeJson(text)
      if (isPlainObject(parsed) && parsed.kind) return parsed
    }
  }

  const text = extractTextParts(value)
  const parsed = parseMaybeJson(text)
  if (isPlainObject(parsed) && parsed.kind) return parsed
  return null
}

const prettyPrint = (value) => {
  if (!value && value !== 0) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch (_error) {
    return String(value)
  }
}

const outputPayload = computed(() => normalizeOutput(props.tool?.output) || {})
const kind = computed(() => String(outputPayload.value.kind || 'raw'))
const toolName = computed(() => String(props.tool?.name || '').trim())
const scriptName = computed(() => String(outputPayload.value.script || '').trim())
const summaryText = computed(() => String(outputPayload.value.summary || outputPayload.value.description || '').trim())
const sqlText = computed(() => String(outputPayload.value.sql || '').trim())
const rows = computed(() => Array.isArray(outputPayload.value.rows) ? outputPayload.value.rows : [])
const columns = computed(() => {
  if (Array.isArray(outputPayload.value.columns) && outputPayload.value.columns.length) {
    return outputPayload.value.columns
  }
  const firstRow = rows.value[0]
  return isPlainObject(firstRow) ? Object.keys(firstRow) : []
})
const stdoutText = computed(() => String(outputPayload.value.stdout || '').trim())
const resultText = computed(() => prettyPrint(outputPayload.value.result))
const rawText = computed(() => {
  const source = outputPayload.value.kind ? outputPayload.value : props.tool?.output
  return prettyPrint(source)
})
const errorText = computed(() => String(outputPayload.value.error || '').trim())
const hasError = computed(() => Boolean(errorText.value) || String(props.tool?.status || '') === 'failed')
const rowCountText = computed(() => {
  const value = Number(outputPayload.value.row_count)
  return Number.isFinite(value) && value > 0 ? `${value} 行` : ''
})
const durationText = computed(() => {
  const value = Number(outputPayload.value.duration_ms)
  return Number.isFinite(value) && value >= 0 ? `${value} ms` : ''
})

const displayLabel = computed(() => {
  if (outputPayload.value.tool_label) return String(outputPayload.value.tool_label)
  if (kind.value === 'sql_execution') return 'SQL 执行'
  if (kind.value === 'python_execution') return 'Python 执行'
  if (kind.value === 'chart_spec') return '图表渲染'
  return toolName.value || '工具输出'
})

const statusLabel = computed(() => {
  const status = String(props.tool?.status || 'success')
  if (status === 'pending') return '等待执行'
  if (status === 'streaming') return '执行中'
  if (status === 'failed') return '执行失败'
  return '执行完成'
})

const toNumeric = (value) => {
  if (typeof value === 'number') return value
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : value
}

const chartOption = computed(() => {
  if (kind.value !== 'chart_spec') return null

  const spec = outputPayload.value
  const dataset = Array.isArray(spec.dataset) ? spec.dataset : []
  if (!dataset.length) return null

  const chartType = String(spec.chart_type || 'line').toLowerCase()
  const title = String(spec.title || '')
  const description = String(spec.description || '')
  const xField = String(spec.x_field || 'name')
  const seriesSpecs = Array.isArray(spec.series) ? spec.series : []

  if (chartType === 'pie') {
    const seriesField = String(seriesSpecs[0]?.field || spec.value_field || 'value')
    return {
      backgroundColor: 'transparent',
      title: title ? { text: title, left: 'center', top: 8, textStyle: { fontSize: 14, fontWeight: 600, color: '#162131' } } : undefined,
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, textStyle: { color: '#607185' } },
      series: [
        {
          type: 'pie',
          radius: spec.donut ? ['44%', '70%'] : '68%',
          center: ['50%', '52%'],
          label: { color: '#425466' },
          data: dataset.map((row) => ({
            name: String(row[xField] ?? row.name ?? ''),
            value: toNumeric(row[seriesField] ?? row.value ?? 0)
          }))
        }
      ]
    }
  }

  const resolvedSeries = seriesSpecs.length
    ? seriesSpecs
    : Object.keys(dataset[0] || {})
      .filter((key) => key !== xField)
      .slice(0, 3)
      .map((field) => ({ field, name: field, type: chartType }))

  if (!resolvedSeries.length) return null

  return {
    backgroundColor: 'transparent',
    title: title
      ? {
          text: title,
          subtext: description || '',
          left: 'left',
          top: 6,
          textStyle: { fontSize: 14, fontWeight: 600, color: '#162131' },
          subtextStyle: { color: '#607185', fontSize: 12 }
        }
      : undefined,
    tooltip: { trigger: 'axis' },
    legend: { top: 8, right: 0, textStyle: { color: '#607185' } },
    grid: { left: 24, right: 16, top: title ? 68 : 32, bottom: 28, containLabel: true },
    xAxis: {
      type: 'category',
      data: dataset.map((row) => row[xField]),
      axisLabel: { color: '#607185' },
      axisLine: { lineStyle: { color: '#d7e4ef' } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#607185' },
      splitLine: { lineStyle: { color: '#eef3f8' } }
    },
    series: resolvedSeries.map((series) => ({
      type: String(series.type || chartType),
      name: String(series.name || series.field || '指标'),
      smooth: chartType === 'line',
      barMaxWidth: chartType === 'bar' ? 34 : undefined,
      itemStyle: chartType === 'bar' ? { borderRadius: [8, 8, 0, 0] } : undefined,
      data: dataset.map((row) => toNumeric(row[series.field]))
    }))
  }
})
</script>

<style scoped>
.tool-output {
  padding: 16px 18px;
  border: 1px solid #dfe8f1;
  border-radius: 18px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.tool-output.failed {
  border-color: rgba(190, 24, 93, 0.2);
  background: linear-gradient(180deg, #fff8fb 0%, #fff 100%);
}

.tool-output-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.tool-output-label {
  font-size: 14px;
  font-weight: 700;
  color: #162131;
}

.tool-output-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #607185;
}

.tool-output-chip {
  padding: 5px 10px;
  border-radius: 999px;
  background: #eef6ff;
  color: #31567a;
  font-size: 12px;
  font-weight: 600;
}

.tool-output-summary {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.65;
  color: #334155;
}

.tool-output-error {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(190, 24, 93, 0.08);
  color: #9f1239;
  font-size: 13px;
  line-height: 1.6;
}

.tool-code {
  margin: 14px 0 0;
  padding: 14px 16px;
  border-radius: 14px;
  background: #102033;
  color: #edf5ff;
  font-size: 12px;
  line-height: 1.7;
  overflow: auto;
}

.tool-code-light {
  background: #f3f7fb;
  color: #233142;
}

.tool-table-wrap {
  margin-top: 14px;
  overflow: auto;
  border: 1px solid #e1e8f0;
  border-radius: 14px;
  background: #fff;
}

.tool-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 480px;
}

.tool-table th,
.tool-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #edf2f7;
  text-align: left;
  font-size: 12px;
  color: #233142;
  white-space: nowrap;
}

.tool-table th {
  background: #f8fbff;
  color: #607185;
  font-weight: 700;
}

.tool-chart {
  margin-top: 14px;
  height: 320px;
  width: 100%;
}

.tool-output-empty {
  margin-top: 14px;
  color: #8da0b3;
  font-size: 13px;
}
</style>
