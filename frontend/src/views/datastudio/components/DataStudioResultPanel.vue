<template>
  <div class="result-panel">
    <el-tabs v-model="tabStates[tab.id].resultTab" type="border-card" class="result-tabs" style="height: 100%;">
      <el-tab-pane name="info">
        <template #label>
          <span class="result-label"><el-icon><Document /></el-icon> 信息</span>
        </template>

        <div class="table-toolbar">
          <div class="meta-info">
            <span class="meta-item">
              <el-icon><Timer /></el-icon>
              {{ formatDuration(getLiveDurationMs(tab.id)) }}
            </span>
            <span class="meta-item">
              <el-tag v-if="tabStates[tab.id].queryLoading" size="small" type="info">运行中</el-tag>
              <el-tag v-else-if="tabStates[tab.id].queryResult.cancelled" size="small" type="warning">已停止</el-tag>
              <el-tag v-else size="small" type="success">已完成</el-tag>
            </span>
            <span v-if="tabStates[tab.id].queryResult.executedAt" class="meta-item">
              <el-icon><Clock /></el-icon>
              {{ formatDateTime(tabStates[tab.id].queryResult.executedAt) }}
            </span>
          </div>
        </div>

        <div class="result-view-container">
          <div class="table-wrapper">
            <el-empty
              v-if="!(tabStates[tab.id].queryResult.statementInfos || []).length"
              description="暂无执行信息"
              :image-size="80"
            />
            <el-table
              v-else
              :data="tabStates[tab.id].queryResult.statementInfos || []"
              border
              stripe
              size="small"
              height="100%"
            >
              <el-table-column prop="statementIndex" label="#" width="70" />
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="getStatementStatusTagType(row.status)">
                    {{ row.status || '-' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="110">
                <template #default="{ row }">
                  {{ formatDuration(row.durationMs || 0) }}
                </template>
              </el-table-column>
              <el-table-column prop="sqlSnippet" label="SQL 摘要" min-width="320" show-overflow-tooltip />
              <el-table-column prop="resultInfo" label="结果信息" min-width="220" show-overflow-tooltip />
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane
        v-for="(resultSet, idx) in getDisplayResultSets(tab.id)"
        :key="String(idx)"
        :name="`result-${idx}`"
      >
        <template #label>
          <span class="result-label"><el-icon><List /></el-icon> Result {{ idx + 1 }}</span>
        </template>

          <div class="table-toolbar">
            <div class="meta-info">
            <span class="meta-item">
              <el-icon><Timer /></el-icon>
                {{ formatDuration(getLiveDurationMs(tab.id)) }}
              </span>
              <span v-if="tabStates[tab.id].queryCancelable" class="meta-item">
                <template v-if="tabStates[tab.id].queryLoading">
                  <el-icon><Loading /></el-icon> 查询中
                </template>
                <template v-else>
                  <el-icon><Warning /></el-icon> 仍可停止
                </template>
              </span>
              <template v-else>
                <el-tag v-if="tabStates[tab.id].queryResult.cancelled" size="small" type="warning">
                  已停止
                </el-tag>
              <span v-if="tabStates[tab.id].queryResult.executedAt" class="meta-item">
                <el-icon><Clock /></el-icon>
                {{ formatDateTime(tabStates[tab.id].queryResult.executedAt) }}
              </span>
              <span v-if="tabStates[tab.id].queryResult.message" class="meta-item meta-message" :title="tabStates[tab.id].queryResult.message">
                {{ tabStates[tab.id].queryResult.message }}
              </span>
            </template>
            <span class="meta-item">
              <el-icon><Files /></el-icon>
              {{ getResultSetCountText(resultSet) }}
            </span>
            <span v-if="resultSet.hasMore" class="meta-item truncate">
              <el-icon><Warning /></el-icon> 结果已截断
            </span>
          </div>
          <div class="export-actions">
            <el-radio-group
              v-if="isResultSetType(resultSet)"
              v-model="tabStates[tab.id].resultViewTabs[idx]"
              size="small"
              class="result-view-switch"
            >
              <el-radio-button value="table">
                <span class="view-label"><el-icon><Grid /></el-icon> 表格</span>
              </el-radio-button>
              <el-radio-button value="chart">
                <span class="view-label"><el-icon><TrendCharts /></el-icon> 图表</span>
              </el-radio-button>
            </el-radio-group>
            <el-button
              size="small"
              :disabled="!isResultSetType(resultSet) || !(resultSet.rows || []).length"
              @click="exportResult(tab.id, idx)"
            >
              导出 CSV
            </el-button>
          </div>
          </div>

        <div v-if="tabStates[tab.id].queryResult.errorMessage" class="result-message">
          <el-alert
            type="error"
            :closable="false"
            show-icon
            :title="tabStates[tab.id].queryResult.errorMessage"
          />
        </div>
        <div
          v-else-if="tabStates[tab.id].queryResult.cancelled && tabStates[tab.id].queryResult.message"
          class="result-message"
        >
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            :title="tabStates[tab.id].queryResult.message"
          />
        </div>

          <div class="result-view-container">
          <div
            v-show="!isResultSetType(resultSet) || (tabStates[tab.id].resultViewTabs?.[idx] || 'table') === 'table'"
            class="table-wrapper"
          >
            <div v-if="!isResultSetType(resultSet)" class="statement-result-card">
              <el-alert
                :type="getResultSetAlertType(resultSet)"
                :closable="false"
                show-icon
                :title="resultSet.message || '语句执行完成'"
              />
            </div>
            <el-empty
              v-else-if="!(resultSet.rows || []).length && !tabStates[tab.id].queryLoading"
              description="暂无数据"
              :image-size="80"
            />
            <DataStudioResultGrid
              v-else
              :columns="resultSet.columns || []"
              :rows="resultSet.rows || []"
              :row-key-prefix="getResultRowKeyPrefix(tab.id, idx)"
            />
            </div>

          <div
            v-if="isResultSetType(resultSet)"
            v-show="(tabStates[tab.id].resultViewTabs?.[idx] || 'table') === 'chart'"
            class="result-chart"
          >
              <div class="chart-grid">
                <div class="chart-config">
                  <div class="config-title">图表类型</div>
                    <div class="chart-type">
                      <el-radio-group v-model="tabStates[tab.id].charts[idx].type" size="small">
                        <el-radio-button value="bar">柱状图</el-radio-button>
                        <el-radio-button value="line">折线图</el-radio-button>
                        <el-radio-button value="pie">饼图</el-radio-button>
                      </el-radio-group>
                    </div>
                  <div class="config-title">
                    {{ tabStates[tab.id].charts[idx].type === 'pie' ? '分类字段' : 'X 轴字段' }}
                  </div>
                  <el-select
                    v-model="tabStates[tab.id].charts[idx].xAxis"
                    size="small"
                    placeholder="选择字段"
                    class="config-select"
                    :disabled="!(resultSet.columns || []).length"
                  >
                    <el-option
                      v-for="col in (resultSet.columns || [])"
                      :key="col"
                      :label="col"
                      :value="col"
                    />
                  </el-select>
                  <div class="config-title">
                    {{ tabStates[tab.id].charts[idx].type === 'pie' ? '数值字段' : 'Y 轴字段' }}
                  </div>
                  <el-select
                    v-model="tabStates[tab.id].charts[idx].yAxis"
                    size="small"
                    multiple
                    collapse-tags
                    placeholder="选择数值字段"
                    class="config-select"
                    :disabled="!(resultSet.columns || []).length"
                  >
                    <el-option
                      v-for="col in getNumericColumns(tab.id, idx)"
                      :key="col"
                      :label="col"
                      :value="col"
                    />
                  </el-select>
                  <div class="hint">配置变更后自动刷新</div>
                </div>
                <div class="chart-canvas">
                  <div class="chart-inner" :ref="(el) => setChartRef(tab.id, idx, el)"></div>
                  <div v-if="!(resultSet.rows || []).length" class="chart-empty">暂无数据</div>
                  <div v-else-if="!canRenderChart(tab.id, idx)" class="chart-empty">
                    请选择字段并执行查询
                  </div>
                </div>
              </div>
            </div>
          </div>
      </el-tab-pane>

      <el-tab-pane name="history">
        <template #label>
          <span class="result-label"><el-icon><Clock /></el-icon> 历史查询</span>
        </template>
        <div class="history-panel">
          <el-table
            :data="historyData"
            border
            size="small"
            height="100%"
            v-loading="historyLoading"
          >
            <el-table-column prop="sqlText" label="SQL" min-width="220" show-overflow-tooltip />
            <el-table-column prop="databaseName" label="数据库" width="120" />
            <el-table-column prop="clusterId" label="集群" width="100" />
            <el-table-column label="执行时间" width="160">
              <template #default="{ row }">
                {{ formatDateTime(row.executedAt || row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="100">
              <template #default="{ row }">
                {{ formatDuration(row.durationMs) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="applyHistory(row, tab.id)">
                  填入
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="history-pagination">
          <el-pagination
            v-model:current-page="historyPager.pageNum"
            v-model:page-size="historyPager.pageSize"
            :page-sizes="[10, 15, 30, 50]"
            layout="total, sizes, prev, pager, next"
            :total="historyPager.total"
            background
            small
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { inject } from 'vue'
import {
  Document,
  Timer,
  Clock,
  List,
  Loading,
  Warning,
  Files,
  Grid,
  TrendCharts
} from '@element-plus/icons-vue'
import DataStudioResultGrid from './DataStudioResultGrid.vue'
import { formatDuration, formatDateTime } from '../tableFormat'

// P2-2 F16a：查询结果面板从 DataStudioNew.vue 抽出。
// 数据与行为通过 dataStudioQueryCtx 注入，键集合见 DataStudioNew.vue 的 provide。
defineProps({
  tab: {
    type: Object,
    required: true,
  },
})

const ctx = inject('dataStudioQueryCtx')
if (!ctx) {
  throw new Error('DataStudioResultPanel 需要 dataStudioQueryCtx')
}

const {
  tabStates,
  getLiveDurationMs,
  getStatementStatusTagType,
  getDisplayResultSets,
  isResultSetType,
  getResultSetCountText,
  getResultSetAlertType,
  exportResult,
  getResultRowKeyPrefix,
  applyHistory,
  historyData,
  historyPager,
  historyLoading,
  getNumericColumns,
  setChartRef,
  canRenderChart,
} = ctx
</script>

<style scoped>
.result-panel {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.result-tabs {
  flex: 1;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: none;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

:deep(.result-tabs .el-tabs__content) {
  flex: 1;
  padding: 0 !important;
  overflow: hidden;
  position: relative;
  min-height: 0;
}

:deep(.result-tabs .el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.result-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.table-toolbar {
  padding: 8px 12px;
  background-color: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.result-message {
  padding: 8px 12px;
  background-color: #fff;
  border-bottom: 1px solid #ebeef5;
}

.meta-message {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #606266;
}

.meta-info {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #606266;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.export-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.truncate {
  color: #e6a23c;
}

.result-view-container {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.result-view-switch {
  flex-shrink: 0;
}

.view-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.result-chart {
  flex: 1;
  min-height: 0;
  background: #fff;
}

.table-wrapper {
  flex: 1;
  min-height: 0;
  padding: 0;
  background: #fff;
  overflow: hidden;
}

.table-wrapper :deep(.el-table) {
  height: 100%;
}

.history-panel {
  flex: 1;
  min-height: 0;
  padding: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.history-panel :deep(.el-table) {
  height: 100%;
}

.history-pagination {
  padding: 8px 12px;
  border-top: 1px solid #eef1f6;
  display: flex;
  justify-content: flex-end;
}

.chart-grid {
  display: flex;
  height: 100%;
  min-height: 0;
}

.chart-config {
  width: 220px;
  border-right: 1px solid #eef1f6;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.config-title {
  font-size: 12px;
  font-weight: 600;
  color: #1f2f3d;
}

.config-select {
  width: 100%;
}

.hint {
  font-size: 12px;
  color: #94a3b8;
}

.chart-canvas {
  flex: 1;
  position: relative;
  min-height: 0;
}

.chart-inner {
  width: 100%;
  height: 100%;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
}

</style>
