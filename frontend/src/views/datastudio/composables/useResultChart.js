import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { loadEcharts } from '@/utils/loadEcharts'
import {
  detectNumericColumns,
  scoreDimensionColumn,
  scoreMetricColumn,
} from '../chartColumnSelect'

export function useResultChart({
  activeTab,
  tabStates,
  parseResultTabIndex,
  getResultSetByIndex,
}) {
  const chartRefs = ref({})
  const chartInstances = new Map()

  const getChartKey = (tabId, resultIndex) => `${String(tabId)}::${Number(resultIndex)}`

  const getNumericColumns = (tabId, resultIndex = 0) => {
    const set = getResultSetByIndex(tabId, resultIndex)
    return detectNumericColumns(set.columns, set.rows)
  }

  const applyDefaultChartSelection = (tabId) => {
    const state = tabStates[tabId]
    if (!state) return

    const sets = Array.isArray(state?.queryResult?.resultSets) ? state.queryResult.resultSets : []
    sets.forEach((set, idx) => {
      const columns = Array.isArray(set?.columns) ? set.columns : []
      const rows = Array.isArray(set?.rows) ? set.rows : []
      if (!columns.length || rows.length === 0) return

      const chart = state.charts?.[idx]
      if (!chart) return
      if (chart.xAxis || (Array.isArray(chart.yAxis) && chart.yAxis.length)) return

      const numericColumns = getNumericColumns(tabId, idx)
      if (!numericColumns.length || columns.length < 2) return

      const dimensionCandidates = columns.filter((col) => !numericColumns.includes(col))
      const xCandidates = dimensionCandidates.length ? dimensionCandidates : columns
      const xAxis = xCandidates
        .map((col, order) => ({ col, order, score: scoreDimensionColumn(col) }))
        .sort((a, b) => (b.score - a.score) || (a.order - b.order))[0]?.col

      const metricCandidates = numericColumns.filter((col) => col !== xAxis)
      if (!xAxis || !metricCandidates.length) return

      const yAxis = metricCandidates
        .map((col, order) => ({ col, order, score: scoreMetricColumn(col) }))
        .sort((a, b) => (b.score - a.score) || (a.order - b.order))[0]?.col

      if (!yAxis) return

      chart.xAxis = xAxis
      chart.yAxis = [yAxis]
    })
  }

  const canRenderChart = (tabId, resultIndex = 0) => {
    const state = tabStates[tabId]
    if (!state) return false
    const set = getResultSetByIndex(tabId, resultIndex)
    const chart = state.charts?.[resultIndex]
    return (
      set.rows.length > 0 &&
      !!chart?.xAxis &&
      Array.isArray(chart?.yAxis) &&
      chart.yAxis.length > 0
    )
  }

  const setChartRef = (tabId, resultIndex, el) => {
    if (!tabId || el == null) return
    const key = getChartKey(tabId, resultIndex)
    chartRefs.value[key] = el

    // ECharts may capture wheel events and block scrolling the result pane.
    // Stop propagation in capture phase so outer scroll can work naturally.
    if (el?.dataset?.scrollGuard !== '1') {
      el.dataset.scrollGuard = '1'
      el.addEventListener(
        'wheel',
        (event) => {
          event.stopPropagation()
        },
        { capture: true, passive: true }
      )
    }
  }

  const syncResultPaneLayout = (tabId) => {
    const state = tabStates[tabId]
    if (!state) return
    const idx = parseResultTabIndex(state?.resultTab)
    if (idx === null) return
    const view = state?.resultViewTabs?.[idx] || 'table'
    if (view === 'chart') {
      chartInstances.get(getChartKey(tabId, idx))?.resize()
    }
  }

  const renderChart = async (tabId, resultIndex = 0) => {
    const state = tabStates[tabId]
    if (!state) return
    const key = getChartKey(tabId, resultIndex)
    const container = chartRefs.value[key]
    if (!container) return

    const set = getResultSetByIndex(tabId, resultIndex)
    const chart = state.charts?.[resultIndex]
    if (!chart) return

    const shouldRender = canRenderChart(tabId, resultIndex)
    let instance = chartInstances.get(key)
    if (!shouldRender) {
      instance?.clear()
      return
    }
    if (!instance) {
      const echarts = await loadEcharts()
      if (!chartRefs.value[key] || chartRefs.value[key] !== container || !container.isConnected) {
        return
      }
      if (!canRenderChart(tabId, resultIndex)) {
        return
      }
      instance = echarts.init(container)
      chartInstances.set(key, instance)
    }

    if (chart.type === 'pie') {
      const xKey = chart.xAxis
      const yKey = chart.yAxis[0]
      if (!xKey || !yKey) {
        instance.clear()
        return
      }
      const data = set.rows.map((row) => ({
        name: row?.[xKey],
        value: Number(row?.[yKey] || 0)
      }))
      instance.clear()
      instance.setOption({
        tooltip: { trigger: 'item' },
        legend: { bottom: 0 },
        series: [
          {
            type: 'pie',
            radius: ['20%', '65%'],
            data
          }
        ]
      })
      instance.resize()
      return
    }

    const xData = set.rows.map((row) => row?.[chart.xAxis])
    const series = chart.yAxis.map((keyName) => ({
      name: keyName,
      type: chart.type,
      data: set.rows.map((row) => Number(row?.[keyName] || 0)),
      smooth: chart.type === 'line'
    }))
    instance.clear()
    instance.setOption({
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0 },
      grid: { top: 40, left: 50, right: 30, bottom: 60, containLabel: true },
      xAxis: { type: 'category', data: xData },
      yAxis: { type: 'value' },
      series
    })
    instance.resize()
  }

  const disposeChart = (tabId, resultIndex = null) => {
    const id = String(tabId || '')
    if (!id) return
    if (resultIndex !== null && resultIndex !== undefined) {
      const key = getChartKey(id, resultIndex)
      const instance = chartInstances.get(key)
      if (instance) {
        instance.dispose()
        chartInstances.delete(key)
      }
      if (chartRefs.value?.[key]) {
        delete chartRefs.value[key]
      }
      return
    }

    const prefix = `${id}::`
    Array.from(chartInstances.keys()).forEach((key) => {
      if (!String(key).startsWith(prefix)) return
      const instance = chartInstances.get(key)
      if (instance) {
        instance.dispose()
      }
      chartInstances.delete(key)
    })
    Object.keys(chartRefs.value).forEach((key) => {
      if (String(key).startsWith(prefix)) {
        delete chartRefs.value[key]
      }
    })
  }

  watch(
    () => {
      const tabId = activeTab.value
      if (!tabId) return null
      const state = tabStates[tabId]
      const idx = parseResultTabIndex(state?.resultTab)
      if (idx === null) return null
      const view = state?.resultViewTabs?.[idx] || 'table'
      const chart = state?.charts?.[idx]
      const set = Array.isArray(state?.queryResult?.resultSets) ? state.queryResult.resultSets[idx] : null
      const rowsLen = Array.isArray(set?.rows) ? set.rows.length : 0
      return [tabId, idx, view, chart?.type, chart?.xAxis, chart?.yAxis?.join(','), rowsLen]
    },
    async (payload) => {
      if (!payload) return
      const [tabId, idx, view] = payload
      await nextTick()
      if (view === 'chart') {
        void renderChart(tabId, idx)
        return
      }
      if (view === 'table') {
        syncResultPaneLayout(tabId)
      }
    }
  )

  onBeforeUnmount(() => {
    chartInstances.forEach((instance) => instance.dispose())
    chartInstances.clear()
  })

  return {
    getNumericColumns,
    applyDefaultChartSelection,
    canRenderChart,
    setChartRef,
    syncResultPaneLayout,
    disposeChart,
  }
}
