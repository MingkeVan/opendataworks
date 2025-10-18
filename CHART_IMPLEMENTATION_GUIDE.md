# 前端数据增长趋势图表实现说明

由于 TableDetail.vue 文件过大，这里提供手动添加数据增长趋势图表的步骤：

## 1. 在模板中添加图表卡片

在表统计信息卡片（第142行 `</el-card>`）后，字段列表卡片（第157行）前，插入以下代码：

```vue
      <el-card shadow="never" class="card" v-if="table && statistics">
        <template #header>
          <div class="card-header">
            数据增长趋势
            <div class="chart-actions">
              <el-radio-group v-model="chartPeriod" size="small" @change="loadHistoryData">
                <el-radio-button label="7days">最近7天</el-radio-button>
                <el-radio-button label="30days">最近30天</el-radio-button>
              </el-radio-group>
            </div>
          </div>
        </template>
        <div v-if="historyLoading" class="chart-loading">
          <el-skeleton :rows="5" animated />
        </div>
        <div v-else-if="!historyData || historyData.length === 0" class="chart-placeholder">
          <el-empty description="暂无历史数据，需要多次刷新统计信息后才能查看趋势" />
        </div>
        <div v-else>
          <div ref="chartRef" class="trend-chart"></div>
        </div>
      </el-card>
```

## 2. 在 script setup 中添加变量和导入

在第309行后添加 ECharts 导入：

```javascript
import * as echarts from 'echarts'
```

在第328行后添加状态变量：

```javascript
const chartRef = ref(null)
const chartPeriod = ref('7days')
const historyData = ref([])
const historyLoading = ref(false)
let chart = null
```

## 3. 添加图表相关方法

在第699行 `formatDateTime` 函数后添加以下方法：

```javascript
const loadHistoryData = async () => {
  if (!table.value) return

  historyLoading.value = true
  try {
    const data = chartPeriod.value === '7days'
      ? await tableApi.getLast7DaysHistory(table.value.id)
      : await tableApi.getLast30DaysHistory(table.value.id)

    historyData.value = data
    await nextTick()
    renderChart()
  } catch (error) {
    console.error('加载历史数据失败', error)
    ElMessage.error('加载历史数据失败')
  } finally {
    historyLoading.value = false
  }
}

const renderChart = () => {
  if (!chartRef.value || !historyData.value.length) return

  // 初始化图表
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }

  // 准备数据
  const dates = historyData.value.map(item => {
    const date = new Date(item.statisticsTime)
    return `${date.getMonth() + 1}/${date.getDate()}`
  })
  const rowCounts = historyData.value.map(item => item.rowCount)
  const dataSizes = historyData.value.map(item => (item.dataSize / 1024 / 1024 / 1024).toFixed(2)) // 转换为GB

  // 配置图表
  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'cross'
      }
    },
    legend: {
      data: ['数据行数', '数据大小(GB)']
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates
    },
    yAxis: [
      {
        type: 'value',
        name: '行数',
        position: 'left',
        axisLabel: {
          formatter: (value) => {
            if (value >= 1000000) {
              return (value / 1000000).toFixed(1) + 'M'
            } else if (value >= 1000) {
              return (value / 1000).toFixed(1) + 'K'
            }
            return value
          }
        }
      },
      {
        type: 'value',
        name: '大小(GB)',
        position: 'right',
        axisLabel: {
          formatter: '{value} GB'
        }
      }
    ],
    series: [
      {
        name: '数据行数',
        type: 'line',
        smooth: true,
        data: rowCounts,
        yAxisIndex: 0,
        itemStyle: {
          color: '#5470c6'
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(84, 112, 198, 0.3)' },
            { offset: 1, color: 'rgba(84, 112, 198, 0.1)' }
          ])
        }
      },
      {
        name: '数据大小(GB)',
        type: 'line',
        smooth: true,
        data: dataSizes,
        yAxisIndex: 1,
        itemStyle: {
          color: '#91cc75'
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(145, 204, 117, 0.3)' },
            { offset: 1, color: 'rgba(145, 204, 117, 0.1)' }
          ])
        }
      }
    ]
  }

  chart.setOption(option)

  // 响应式调整
  window.addEventListener('resize', () => {
    chart?.resize()
  })
}
```

## 4. 修改 refreshStatistics 方法

在第677行的 `refreshStatistics` 方法末尾，成功获取统计信息后添加：

```javascript
    statistics.value = data
    // 加载历史数据用于趋势图
    if (data) {
      loadHistoryData()
    }
```

## 5. 添加清理逻辑

在 `onMounted` 之前添加 `onUnmounted`：

```javascript
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'

// 在 onMounted 之后添加
onUnmounted(() => {
  if (chart) {
    chart.dispose()
    chart = null
  }
  window.removeEventListener('resize', () => {
    chart?.resize()
  })
})
```

## 6. 添加 CSS 样式

在第892行 `.stat-footer` 样式后添加：

```css
.chart-actions {
  display: flex;
  gap: 12px;
}

.chart-loading,
.chart-placeholder {
  padding: 20px;
}

.trend-chart {
  width: 100%;
  height: 400px;
}
```

## 实现效果

添加后将实现：

1. **双轴折线图**：左轴显示数据行数，右轴显示数据大小（GB）
2. **平滑曲线**：使用渐变填充的平滑曲线
3. **时间周期切换**：支持最近7天和最近30天的数据展示
4. **响应式**：图表随窗口大小自动调整
5. **交互提示**：鼠标悬停显示详细数据
6. **空状态处理**：无数据时显示友好提示

## 测试步骤

1. 打开表详情页面
2. 点击"刷新"按钮获取统计信息
3. 多次刷新累积历史数据
4. 查看数据增长趋势图表
5. 切换7天/30天视图
