<template>
  <div :class="['data-studio', { 'is-resizing': isResizing }]">
    <div class="studio-layout">
      <!-- Left: Database Tree -->
      <aside class="studio-sidebar" :style="{ width: `${sidebarWidth}px` }">
        <div class="sidebar-controls">
          <el-select
            v-model="clusterId"
            size="small"
            class="cluster-select"
            placeholder="选择集群"
            clearable
          >
            <el-option
              v-for="item in clusterOptions"
              :key="item.id"
              :label="item.clusterName"
              :value="item.id"
            />
          </el-select>
          <div class="search-row">
            <el-input
              v-model="searchKeyword"
              placeholder="搜索表名或注释"
              clearable
              size="small"
              class="search-input"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button size="small" type="primary" @click="handleCreateTable">
              <el-icon><Plus /></el-icon>
              新建表
            </el-button>
          </div>
          <div class="sort-row">
            <el-radio-group v-model="sortField" size="small" class="sort-group">
              <el-radio-button label="tableName">表名</el-radio-button>
              <el-radio-button label="createdAt">创建时间</el-radio-button>
              <el-radio-button label="rowCount">数据量</el-radio-button>
            </el-radio-group>
            <el-radio-group v-model="sortOrder" size="small" class="sort-group">
              <el-radio-button label="asc">升序</el-radio-button>
              <el-radio-button label="desc">降序</el-radio-button>
            </el-radio-group>
          </div>
        </div>

        <div class="db-tree" v-loading="dbLoading">
          <el-collapse v-model="activeDatabase" accordion @change="handleDatabaseChange">
            <el-collapse-item
              v-for="db in databases"
              :key="db"
              :name="db"
            >
              <template #title>
                <div class="db-title">
                  <el-icon class="db-toggle">
                    <ArrowDown v-if="activeDatabase === db" />
                    <ArrowRight v-else />
                  </el-icon>
                  <el-icon class="db-icon"><Coin /></el-icon>
                  <span class="db-name">{{ db }}</span>
                  <el-badge :value="getTableCount(db)" type="info" class="db-count" />
                  <el-icon v-if="databaseLoading[db]" class="is-loading loading-icon"><Loading /></el-icon>
                </div>
              </template>

              <div class="table-list">
                <div
                  v-for="table in getDisplayedTables(db)"
                  :key="getTableKey(table, db) || table.id || table.tableName"
                  class="table-item"
                  :class="{ active: selectedTableKey === getTableKey(table, db) }"
                  :ref="(el) => setTableRef(getTableKey(table, db), el, table.id)"
                  @click="openTableTab(table, db)"
                >
                  <div
                    class="table-progress-bg"
                    :style="{ width: getProgressWidth(db, table) }"
                  ></div>
                  <div class="table-content">
                    <el-icon class="table-icon"><Grid /></el-icon>
                    <div class="table-info">
                      <span class="table-name" :title="table.tableName">
                        {{ table.tableName }}
                      </span>
                      <span v-if="table.tableComment" class="table-comment" :title="table.tableComment">
                        {{ table.tableComment }}
                      </span>
                    </div>
                    <div class="table-meta-tags">
                      <span class="row-count" :title="`数据量: ${formatNumber(getTableRowCount(table))} 行`">
                        {{ formatRowCount(getTableRowCount(table)) }}
                      </span>
                      <span class="storage-size" :title="`存储大小: ${formatStorageSize(getTableStorageSize(table))}`">
                        {{ formatStorageSize(getTableStorageSize(table)) }}
                      </span>
                      <span v-if="getUpstreamCount(table.id) > 0" class="lineage-count upstream" :title="`上游表: ${getUpstreamCount(table.id)} 个`">
                        ↑{{ getUpstreamCount(table.id) }}
                      </span>
                      <span v-if="getDownstreamCount(table.id) > 0" class="lineage-count downstream" :title="`下游表: ${getDownstreamCount(table.id)} 个`">
                        ↓{{ getDownstreamCount(table.id) }}
                      </span>
                      <el-tag
                        v-if="table.layer"
                        size="small"
                        :type="getLayerType(table.layer)"
                        class="layer-tag"
                      >
                        {{ table.layer }}
                      </el-tag>
                    </div>
                  </div>
                </div>
                <el-empty v-if="!getDisplayedTables(db).length" description="暂无表" :image-size="60" />
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </aside>

      <div class="sidebar-resizer" @mousedown="startResize"></div>

      <!-- Right: Workspace -->
      <section class="studio-workspace">
        <div class="workspace-body">
          <el-tabs
            v-if="openTabs.length"
            v-model="activeTab"
            type="card"
            closable
            class="workspace-tabs"
            @tab-remove="handleTabRemove"
          >
            <el-tab-pane
              v-for="tab in openTabs"
              :key="tab.id"
              :name="String(tab.id)"
            >
              <template #label>
                <div class="tab-label">
                  <span class="tab-title">{{ tab.tableName }}</span>
                  <span class="tab-sub">{{ tab.dbName }}</span>
                </div>
              </template>

              <div class="tab-grid">
                <!-- Left 60% -->
                <div
                  class="tab-left"
                  :ref="(el) => setLeftPaneRef(tab.id, el)"
                  :style="getLeftPaneStyle(tab.id)"
                >
                  <div class="query-panel">
                    <div class="query-header">
                      <div class="query-context">
                        <el-tag size="small" type="info">{{ tab.dbName || '-' }}</el-tag>
                        <el-tag size="small" type="success">{{ tab.tableName || '-' }}</el-tag>
                      </div>
                      <div class="query-actions">
                        <span class="limit-label">Limit</span>
                        <el-input-number
                          v-model="tabStates[tab.id].query.limit"
                          :min="1"
                          :max="5000"
                          :step="100"
                          size="small"
                          controls-position="right"
                          class="limit-input"
                        />
                        <el-button
                          type="primary"
                          size="small"
                          :loading="tabStates[tab.id].queryLoading"
                          @click="executeQuery(tab.id)"
                        >
                          执行
                        </el-button>
                        <el-button size="small" @click="resetQuery(tab.id)">重置</el-button>
                        <el-button size="small" type="success" plain @click="saveAsTask(tab.id)">
                          存为任务
                        </el-button>
                      </div>
                    </div>
                    <el-input
                      v-model="tabStates[tab.id].query.sql"
                      type="textarea"
                      :rows="5"
                      resize="none"
                      class="sql-editor"
                      placeholder="-- 输入 SQL，支持 SELECT/SHOW/DESC 等只读语句"
                    />
                  </div>

                  <div class="left-resizer" @mousedown="startLeftResize(tab.id, $event)"></div>

                  <div class="result-panel">
                    <el-tabs v-model="tabStates[tab.id].resultTab" type="border-card" class="result-tabs">
                      <el-tab-pane name="table">
                        <template #label>
                          <span class="result-label"><el-icon><List /></el-icon> 结果表格</span>
                        </template>

                        <div class="table-toolbar">
                          <div class="meta-info" v-if="tabStates[tab.id].queryResult.executedAt">
                            <span class="meta-item"><el-icon><Timer /></el-icon> {{ formatDuration(tabStates[tab.id].queryResult.durationMs) }}</span>
                            <span class="meta-item"><el-icon><Files /></el-icon> {{ tabStates[tab.id].queryResult.rows.length }} 行</span>
                            <span v-if="tabStates[tab.id].queryResult.hasMore" class="meta-item truncate">
                              <el-icon><Warning /></el-icon> 结果已截断
                            </span>
                          </div>
                          <div class="export-actions">
                            <el-button
                              size="small"
                              :disabled="!tabStates[tab.id].queryResult.rows.length"
                              @click="exportResult(tab.id)"
                            >
                              导出 CSV
                            </el-button>
                          </div>
                        </div>

                        <div class="table-wrapper">
                          <el-empty
                            v-if="!tabStates[tab.id].queryResult.rows.length && !tabStates[tab.id].queryLoading"
                            description="暂无数据"
                            :image-size="80"
                          />
                          <el-table
                            v-else
                            :data="getPaginatedRows(tab.id)"
                            border
                            stripe
                            size="small"
                            height="100%"
                          >
                            <el-table-column
                              v-for="col in tabStates[tab.id].queryResult.columns"
                              :key="col"
                              :prop="col"
                              :label="col"
                              min-width="120"
                              show-overflow-tooltip
                            />
                          </el-table>
                        </div>

                        <div class="pagination-bar" v-if="tabStates[tab.id].queryResult.rows.length">
                          <el-pagination
                            v-model:current-page="tabStates[tab.id].page.current"
                            v-model:page-size="tabStates[tab.id].page.size"
                            :page-sizes="[10, 20, 50, 100, 500]"
                            layout="total, sizes, prev, pager, next"
                            :total="tabStates[tab.id].queryResult.rows.length"
                            background
                            small
                          />
                        </div>
                      </el-tab-pane>

                      <el-tab-pane name="chart">
                        <template #label>
                          <span class="result-label"><el-icon><TrendCharts /></el-icon> 可视化图表</span>
                        </template>
                        <div class="chart-grid">
                          <div class="chart-config">
                            <div class="config-title">图表类型</div>
                            <div class="chart-type">
                              <el-radio-group v-model="tabStates[tab.id].chart.type" size="small">
                                <el-radio-button label="bar">柱状图</el-radio-button>
                                <el-radio-button label="line">折线图</el-radio-button>
                                <el-radio-button label="pie">饼图</el-radio-button>
                              </el-radio-group>
                            </div>
                            <div class="config-title">
                              {{ tabStates[tab.id].chart.type === 'pie' ? '分类字段' : 'X 轴字段' }}
                            </div>
                            <el-select
                              v-model="tabStates[tab.id].chart.xAxis"
                              size="small"
                              placeholder="选择字段"
                              class="config-select"
                              :disabled="!tabStates[tab.id].queryResult.columns.length"
                            >
                              <el-option
                                v-for="col in tabStates[tab.id].queryResult.columns"
                                :key="col"
                                :label="col"
                                :value="col"
                              />
                            </el-select>
                            <div class="config-title">
                              {{ tabStates[tab.id].chart.type === 'pie' ? '数值字段' : 'Y 轴字段' }}
                            </div>
                            <el-select
                              v-model="tabStates[tab.id].chart.yAxis"
                              size="small"
                              multiple
                              collapse-tags
                              placeholder="选择数值字段"
                              class="config-select"
                              :disabled="!tabStates[tab.id].queryResult.columns.length"
                            >
                              <el-option
                                v-for="col in getNumericColumns(tab.id)"
                                :key="col"
                                :label="col"
                                :value="col"
                              />
                            </el-select>
                            <div class="hint">配置变更后自动刷新</div>
                          </div>
                          <div class="chart-canvas">
                            <div
                              class="chart-inner"
                              :ref="(el) => setChartRef(tab.id, el)"
                            ></div>
                            <div v-if="!canRenderChart(tab.id)" class="chart-empty">
                              请选择字段并执行查询
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
                </div>

                <!-- Right 40% -->
                <div class="tab-right">
                  <div class="meta-panel">
                    <el-tabs v-model="tabStates[tab.id].metaTab" class="meta-tabs">
                      <el-tab-pane name="basic" label="基本信息">
                        <div class="meta-section">
                          <div class="section-header">
                            <span>表信息</span>
                            <div class="section-actions">
                              <el-button
                                v-if="!tabStates[tab.id].metaEditing"
                                type="primary"
                                size="small"
                                @click="startMetaEdit(tab.id)"
                              >
                                编辑
                              </el-button>
                              <el-button
                                v-if="!tabStates[tab.id].metaEditing"
                                type="danger"
                                plain
                                size="small"
                                @click="handleDeleteTable"
                              >
                                删除表
                              </el-button>
                              <template v-else>
                                <el-button size="small" @click="cancelMetaEdit(tab.id)">取消</el-button>
                                <el-button
                                  type="primary"
                                  size="small"
                                  :loading="tabStates[tab.id].metaSaving"
                                  @click="saveMetaEdit(tab.id)"
                                >
                                  保存
                                </el-button>
                              </template>
                            </div>
                          </div>

                          <el-form
                            v-if="tabStates[tab.id].metaEditing"
                            :model="tabStates[tab.id].metaForm"
                            label-width="90px"
                            class="meta-form"
                          >
                            <el-form-item label="表名">
                              <el-input v-model="tabStates[tab.id].metaForm.tableName" />
                            </el-form-item>
                            <el-form-item label="表注释">
                              <el-input v-model="tabStates[tab.id].metaForm.tableComment" />
                            </el-form-item>
                            <el-form-item label="分层">
                              <el-select v-model="tabStates[tab.id].metaForm.layer" placeholder="选择分层">
                                <el-option v-for="item in layerOptions" :key="item.value" :label="item.label" :value="item.value" />
                              </el-select>
                            </el-form-item>
                            <el-form-item label="负责人">
                              <el-input v-model="tabStates[tab.id].metaForm.owner" />
                            </el-form-item>
                          </el-form>

                          <el-descriptions v-else :column="1" border size="small">
                            <el-descriptions-item label="表名">{{ tabStates[tab.id].table.tableName || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="表注释">{{ tabStates[tab.id].table.tableComment || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="分层">{{ tabStates[tab.id].table.layer || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="负责人">{{ tabStates[tab.id].table.owner || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="数据库">{{ tabStates[tab.id].table.dbName || '-' }}</el-descriptions-item>
                          </el-descriptions>

                          <div class="section-divider"></div>

                          <div class="section-header small">
                            <span>Doris 配置</span>
                          </div>
                          <el-descriptions :column="1" border size="small">
                            <el-descriptions-item label="表模型">{{ tabStates[tab.id].table.tableModel || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="主键列">{{ tabStates[tab.id].table.keyColumns || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="分区字段">{{ tabStates[tab.id].table.partitionColumn || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="分桶字段">{{ tabStates[tab.id].table.distributionColumn || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="分桶数">{{ tabStates[tab.id].table.bucketNum || '-' }}</el-descriptions-item>
                            <el-descriptions-item label="副本数">{{ tabStates[tab.id].table.replicaNum || '-' }}</el-descriptions-item>
                          </el-descriptions>
                        </div>
                      </el-tab-pane>

                      <el-tab-pane name="columns" label="列信息">
                        <div class="meta-section meta-section-fill">
                          <div v-if="tabStates[tab.id].fields.length" class="meta-table">
                            <el-table
                              :data="tabStates[tab.id].fields"
                              border
                              size="small"
                              height="100%"
                            >
                              <el-table-column label="字段名" width="140">
                                <template #default="{ row }">
                                  <el-input
                                    v-if="row._editing"
                                    v-model="row.fieldName"
                                    size="small"
                                  />
                                  <span v-else>{{ row.fieldName }}</span>
                                </template>
                              </el-table-column>
                              <el-table-column label="类型" width="120">
                                <template #default="{ row }">
                                  <el-input
                                    v-if="row._editing"
                                    v-model="row.fieldType"
                                    size="small"
                                  />
                                  <span v-else>{{ row.fieldType }}</span>
                                </template>
                              </el-table-column>
                              <el-table-column label="注释">
                                <template #default="{ row }">
                                  <el-input
                                    v-if="row._editing"
                                    v-model="row.fieldComment"
                                    size="small"
                                  />
                                  <span v-else>{{ row.fieldComment || '-' }}</span>
                                </template>
                              </el-table-column>
                              <el-table-column label="操作" width="100">
                                <template #default="{ row }">
                                  <template v-if="row._editing">
                                    <el-button link type="primary" size="small" @click="saveField(tab.id, row)">保存</el-button>
                                    <el-button link size="small" @click="cancelFieldEdit(row)">取消</el-button>
                                  </template>
                                  <el-button v-else link type="primary" size="small" @click="editField(row)">编辑</el-button>
                                </template>
                              </el-table-column>
                            </el-table>
                          </div>
                          <el-empty v-else description="暂无字段" :image-size="60" />
                        </div>
                      </el-tab-pane>

                      <el-tab-pane name="ddl" label="DDL">
                        <div class="meta-section meta-section-fill">
                          <div class="ddl-header">
                            <el-button size="small" @click="loadDdl(tab.id)" :loading="tabStates[tab.id].ddlLoading">
                              加载 DDL
                            </el-button>
                            <el-button
                              size="small"
                              :disabled="!tabStates[tab.id].ddl"
                              @click="copyDdl(tab.id)"
                            >
                              复制
                            </el-button>
                          </div>
                          <el-input
                            v-model="tabStates[tab.id].ddl"
                            type="textarea"
                            resize="none"
                            readonly
                            class="ddl-textarea"
                            placeholder="点击「加载 DDL」按钮获取建表语句"
                          />
                        </div>
                      </el-tab-pane>
                    </el-tabs>
                  </div>

                  <div class="lineage-panel">
                    <div class="lineage-header">
                      <span>数据血缘</span>
                      <el-button type="primary" link size="small" @click="goLineage(tab.id)">
                        查看完整血缘
                      </el-button>
                    </div>
                    <div class="lineage-grid">
                      <div class="lineage-card">
                        <div class="lineage-title">上游表 ({{ tabStates[tab.id].lineage.upstreamTables.length }})</div>
                        <div class="task-block">
                          <div class="task-title">写入任务 ({{ tabStates[tab.id].tasks.writeTasks.length }})</div>
                          <div v-if="tabStates[tab.id].tasks.writeTasks.length" class="task-list">
                            <div
                              v-for="task in tabStates[tab.id].tasks.writeTasks"
                              :key="task.id"
                              class="task-item"
                              @click="openTask(task.id)"
                            >
                              <div class="task-name">{{ task.taskName || '-' }}</div>
                              <div class="task-meta">{{ task.engine || '-' }}</div>
                            </div>
                          </div>
                          <el-empty v-else description="暂无写入任务" :image-size="40" />
                        </div>
                        <div class="lineage-list">
                          <div
                            v-for="item in tabStates[tab.id].lineage.upstreamTables"
                            :key="item.id"
                            class="lineage-item"
                            @click="openTableTab(item)"
                          >
                            <el-icon><Document /></el-icon>
                            <div class="lineage-info">
                              <div class="lineage-name">{{ item.tableName }}</div>
                              <div class="lineage-desc">{{ item.tableComment || '-' }}</div>
                            </div>
                            <el-tag v-if="item.layer" size="small" :type="getLayerType(item.layer)">{{ item.layer }}</el-tag>
                          </div>
                        </div>
                      </div>

                      <div class="lineage-card">
                        <div class="lineage-title">下游表 ({{ tabStates[tab.id].lineage.downstreamTables.length }})</div>
                        <div class="task-block">
                          <div class="task-title">读取任务 ({{ tabStates[tab.id].tasks.readTasks.length }})</div>
                          <div v-if="tabStates[tab.id].tasks.readTasks.length" class="task-list">
                            <div
                              v-for="task in tabStates[tab.id].tasks.readTasks"
                              :key="task.id"
                              class="task-item"
                              @click="openTask(task.id)"
                            >
                              <div class="task-name">{{ task.taskName || '-' }}</div>
                              <div class="task-meta">{{ task.engine || '-' }}</div>
                            </div>
                          </div>
                          <el-empty v-else description="暂无读取任务" :image-size="40" />
                        </div>
                        <div class="lineage-list">
                          <div
                            v-for="item in tabStates[tab.id].lineage.downstreamTables"
                            :key="item.id"
                            class="lineage-item"
                            @click="openTableTab(item)"
                          >
                            <el-icon><Document /></el-icon>
                            <div class="lineage-info">
                              <div class="lineage-name">{{ item.tableName }}</div>
                              <div class="lineage-desc">{{ item.tableComment || '-' }}</div>
                            </div>
                            <el-tag v-if="item.layer" size="small" :type="getLayerType(item.layer)">{{ item.layer }}</el-tag>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>

          <div v-else class="empty-state">
            <el-empty description="从左侧选择表以打开工作区" :image-size="120" />
          </div>
        </div>
      </section>
    </div>

    <CreateTableDrawer v-model="createDrawerVisible" @created="handleCreateSuccess" />
    <TaskEditDrawer ref="taskDrawerRef" />

  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import {
  ArrowDown,
  ArrowRight,
  Coin,
  Search,
  Clock,
  Delete,
  Plus,
  Document,
  Grid,
  Loading,
  List,
  TrendCharts,
  Timer,
  Files,
  Warning
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import { dorisClusterApi } from '@/api/doris'
import { dataQueryApi } from '@/api/query'
import CreateTableDrawer from '@/views/datastudio/CreateTableDrawer.vue'
import TaskEditDrawer from '@/views/tasks/TaskEditDrawer.vue'

const clusterOptions = ref([])
const clusterId = ref(null)
const route = useRoute()
const router = useRouter()
const sidebarWidth = ref(360)
const isResizing = ref(false)
let resizeMoveHandler = null
let resizeUpHandler = null
const leftPaneHeights = reactive({})
const leftPaneRefs = ref({})
let resizeLeftMoveHandler = null
let resizeLeftUpHandler = null
const historyData = ref([])
const historyPager = reactive({ pageNum: 1, pageSize: 15, total: 0 })
const historyLoading = ref(false)
const createDrawerVisible = ref(false)

const dbLoading = ref(false)
const databases = ref([])
const activeDatabase = ref('')
const databaseLoading = reactive({})
const tableStore = reactive({})
const lineageCache = reactive({})

const searchKeyword = ref('')
const sortField = ref('tableName')
const sortOrder = ref('asc')

const selectedTableKey = ref('')
const suppressRouteSync = ref(false)

const openTabs = ref([])
const activeTab = ref('')
const tabStates = reactive({})

const tableRefs = ref({})
const chartRefs = ref({})
const chartInstances = new Map()
const taskDrawerRef = ref(null)
const tableObserver = ref(null)

const layerOptions = [
  { label: 'ODS - 原始数据层', value: 'ODS' },
  { label: 'DWD - 明细数据层', value: 'DWD' },
  { label: 'DIM - 维度数据层', value: 'DIM' },
  { label: 'DWS - 汇总数据层', value: 'DWS' },
  { label: 'ADS - 应用数据层', value: 'ADS' }
]

const loadClusters = async () => {
  try {
    const clusters = await dorisClusterApi.list()
    clusterOptions.value = clusters
    if (!clusterId.value && clusters.length) {
      const defaultCluster = clusters.find((item) => item.isDefault === 1) || clusters[0]
      clusterId.value = defaultCluster?.id || null
    }
  } catch (error) {
    console.error('加载集群失败', error)
  }
}

const loadDatabases = async () => {
  dbLoading.value = true
  try {
    databases.value = await tableApi.listDatabases()
    if (!activeDatabase.value && databases.value.length) {
      activeDatabase.value = databases.value[0]
      await loadTables(activeDatabase.value)
    }
  } catch (error) {
    ElMessage.error('加载数据库列表失败')
  } finally {
    dbLoading.value = false
  }
}

const loadTables = async (database, force = false) => {
  if (!database) return
  if (tableStore[database] && !force) return
  databaseLoading[database] = true
  try {
    const tables = await tableApi.listByDatabase(database, sortField.value, sortOrder.value)
    tableStore[database] = Array.isArray(tables) ? tables : []
  } catch (error) {
    ElMessage.error('加载表列表失败')
  } finally {
    databaseLoading[database] = false
  }
}

const handleDatabaseChange = async (database) => {
  if (!database) return
  await loadTables(database)
}

const getFilteredTables = (database) => {
  const list = tableStore[database] || []
  if (!searchKeyword.value) return list
  const keyword = searchKeyword.value.toLowerCase()
  return list.filter((item) => {
    return (
      item.tableName?.toLowerCase().includes(keyword) ||
      item.tableComment?.toLowerCase().includes(keyword)
    )
  })
}

const getDisplayedTables = (database) => {
  const list = [...getFilteredTables(database)]
  const field = sortField.value
  const order = sortOrder.value
  list.sort((a, b) => {
    const aVal = a[field]
    const bVal = b[field]
    if (aVal == null && bVal == null) return 0
    if (aVal == null) return order === 'asc' ? -1 : 1
    if (bVal == null) return order === 'asc' ? 1 : -1
    if (typeof aVal === 'number' && typeof bVal === 'number') {
      return order === 'asc' ? aVal - bVal : bVal - aVal
    }
    return order === 'asc'
      ? String(aVal).localeCompare(String(bVal))
      : String(bVal).localeCompare(String(aVal))
  })
  return list
}

const getTableCount = (database) => getFilteredTables(database).length

const setTableRef = (key, el, tableId) => {
  if (!key || !el) return
  tableRefs.value[key] = el
  if (tableId) {
    el.dataset.tableId = String(tableId)
  }
  if (tableObserver.value) {
    tableObserver.value.observe(el)
  }
}

const setLeftPaneRef = (key, el) => {
  if (!key || !el) return
  leftPaneRefs.value[key] = el
}

const getLeftPaneStyle = (key) => {
  const height = leftPaneHeights[key]
  if (!height) return {}
  return { '--left-top': `${height}px` }
}

const getTableKey = (table, fallbackDb = '') => {
  if (!table) return ''
  const dbName = table.dbName || table.databaseName || table.database || fallbackDb || ''
  const tableName = table.tableName || ''
  if (dbName && tableName) return `${dbName}.${tableName}`
  return tableName || dbName
}

const getLayerType = (layer) => {
  const map = {
    ODS: 'info',
    DWD: 'success',
    DIM: 'warning',
    DWS: 'primary',
    ADS: 'danger'
  }
  return map[layer] || 'info'
}

const formatNumber = (num) => {
  if (num === null || num === undefined) return '-'
  const value = Number(num)
  if (Number.isNaN(value)) return num
  return value.toLocaleString('zh-CN')
}

const formatRowCount = (rowCount) => {
  if (rowCount === null || rowCount === undefined) return '-'
  if (rowCount === 0) return '0'
  if (rowCount < 1000) return rowCount.toString()
  if (rowCount < 1000000) return (rowCount / 1000).toFixed(1) + 'K'
  if (rowCount < 1000000000) return (rowCount / 1000000).toFixed(1) + 'M'
  return (rowCount / 1000000000).toFixed(1) + 'B'
}

const formatStorageSize = (size) => {
  if (size === null || size === undefined) return '-'
  if (size === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let value = size
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex++
  }
  return value >= 10 ? `${value.toFixed(0)} ${units[unitIndex]}` : `${value.toFixed(1)} ${units[unitIndex]}`
}

const formatDuration = (ms) => {
  if (!ms) return '0ms'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
}

const formatDateTime = (value) => {
  if (!value) return '-'
  return String(value).replace('T', ' ').split('.')[0]
}

const getTableRowCount = (table) => {
  if (!table || table.rowCount === null || table.rowCount === undefined) {
    return 0
  }
  return Number(table.rowCount) || 0
}

const getTableStorageSize = (table) => {
  if (!table || table.storageSize === null || table.storageSize === undefined) {
    return 0
  }
  return Number(table.storageSize) || 0
}

const getProgressWidth = (database, table) => {
  const list = tableStore[database] || []
  if (!list.length) return '0%'
  const currentRowCount = getTableRowCount(table)
  const maxRowCount = Math.max(...list.map((item) => getTableRowCount(item)))
  if (!Number.isFinite(maxRowCount) || maxRowCount <= 0) {
    return '0%'
  }
  const percentage = Math.max(10, (currentRowCount / maxRowCount) * 100)
  return percentage.toFixed(1) + '%'
}

const getUpstreamCount = (tableId) => {
  if (!tableId) return 0
  return lineageCache[tableId]?.upstreamTables?.length || 0
}

const getDownstreamCount = (tableId) => {
  if (!tableId) return 0
  return lineageCache[tableId]?.downstreamTables?.length || 0
}

const loadLineageForTable = async (tableId) => {
  if (!tableId || lineageCache[tableId]) return
  try {
    const lineageData = await tableApi.getLineage(tableId)
    lineageCache[tableId] = lineageData || { upstreamTables: [], downstreamTables: [] }
  } catch (error) {
    console.error('加载数据血缘失败', error)
  }
}

const observeExistingTableRefs = () => {
  if (!tableObserver.value) return
  Object.values(tableRefs.value).forEach((el) => {
    const tableId = el?.dataset?.tableId
    if (tableId) {
      tableObserver.value.observe(el)
    }
  })
}

const setupTableObserver = () => {
  if (tableObserver.value) {
    tableObserver.value.disconnect()
  }
  tableObserver.value = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return
        const tableId = Number(entry.target.dataset.tableId)
        if (Number.isFinite(tableId)) {
          loadLineageForTable(tableId)
        }
        tableObserver.value?.unobserve(entry.target)
      })
    },
    {
      root: null,
      rootMargin: '100px',
      threshold: 0.1
    }
  )
  observeExistingTableRefs()
}

const createTabState = (table) => {
  return reactive({
    table: { ...table },
    query: {
      sql: buildDefaultSql(table),
      limit: 200
    },
    queryLoading: false,
    queryResult: {
      columns: [],
      rows: [],
      hasMore: false,
      durationMs: 0,
      executedAt: ''
    },
    resultTab: 'table',
    page: {
      current: 1,
      size: 20
    },
    chart: {
      type: 'bar',
      xAxis: '',
      yAxis: []
    },
    metaTab: 'basic',
    metaEditing: false,
    metaSaving: false,
    metaForm: {
      tableName: table.tableName || '',
      tableComment: table.tableComment || '',
      layer: table.layer || '',
      owner: table.owner || ''
    },
    metaOriginal: {},
    fields: [],
    ddl: '',
    ddlLoading: false,
    lineage: { upstreamTables: [], downstreamTables: [] },
    tasks: { writeTasks: [], readTasks: [] }
  })
}

const openTableTab = async (table, dbFallback = '') => {
  if (!table) return
  const key = getTableKey(table, dbFallback)
  if (!key) return

  selectedTableKey.value = key

  const existing = openTabs.value.find((item) => String(item.id) === key)
  if (existing) {
    activeTab.value = String(existing.id)
    await focusTableInSidebar(table, key, dbFallback)
    return
  }

  const resolvedDb = table.dbName || table.databaseName || table.database || dbFallback || ''
  const tabItem = {
    id: key,
    tableName: table.tableName,
    dbName: resolvedDb
  }
  tabStates[key] = createTabState({ ...table, dbName: resolvedDb })
  openTabs.value.push(tabItem)
  activeTab.value = key

  await focusTableInSidebar(table, key, dbFallback)
  await loadTabData(key)
}

const focusTableInSidebar = async (table, key, dbFallback = '') => {
  if (!table) return
  const dbName = table.dbName || table.databaseName || table.database || dbFallback
  if (dbName) {
    activeDatabase.value = dbName
    await loadTables(dbName)
  }
  await nextTick()
  const ref = tableRefs.value[key]
  if (ref?.scrollIntoView) {
    ref.scrollIntoView({ block: 'nearest' })
  }
}

const updateRouteQuery = (payload) => {
  if (suppressRouteSync.value) return
  const { dbName, tableId, tableName } = payload || {}
  if (!dbName || (!tableId && !tableName)) return
  router.replace({
    path: route.path,
    query: {
      ...route.query,
      database: dbName,
      tableId: tableId ?? route.query.tableId,
      tableName: tableName ?? route.query.tableName
    }
  })
}

const clearCreateQuery = () => {
  if (!route.query.create) return
  const nextQuery = { ...route.query }
  delete nextQuery.create
  router.replace({ path: route.path, query: nextQuery })
}

const syncFromRoute = async () => {
  const { database, tableId, tableName } = route.query
  if (!database || (!tableId && !tableName)) return
  await loadTables(database, true)
  const list = tableStore[database] || []
  let target = null
  if (tableId) {
    target = list.find((item) => String(item.id) === String(tableId))
  }
  if (!target && tableName) {
    target = list.find((item) => item.tableName === tableName)
  }
  if (!target) return
  suppressRouteSync.value = true
  await openTableTab(target, database)
  suppressRouteSync.value = false
}

const loadTabData = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  try {
    const [tableInfo, fieldList, lineageData, tasksData] = await Promise.all([
      tableApi.getById(state.table.id),
      tableApi.getFields(state.table.id),
      tableApi.getLineage(state.table.id),
      tableApi.getTasks(state.table.id)
    ])
    state.table = { ...state.table, ...tableInfo }
    state.metaForm = {
      tableName: state.table.tableName || '',
      tableComment: state.table.tableComment || '',
      layer: state.table.layer || '',
      owner: state.table.owner || ''
    }
    state.metaOriginal = { ...state.metaForm }
    state.fields = Array.isArray(fieldList) ? fieldList : []
    state.lineage = {
      upstreamTables: lineageData?.upstreamTables || [],
      downstreamTables: lineageData?.downstreamTables || []
    }
    lineageCache[state.table.id] = state.lineage
    state.tasks = {
      writeTasks: Array.isArray(tasksData?.writeTasks) ? tasksData.writeTasks : [],
      readTasks: Array.isArray(tasksData?.readTasks) ? tasksData.readTasks : []
    }
    if (state.query.sql === '') {
      state.query.sql = buildDefaultSql(state.table)
    }
  } catch (error) {
    console.error('加载表详情失败', error)
  }
}

const handleTabRemove = (name) => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(name))
  if (idx === -1) return
  const removed = openTabs.value.splice(idx, 1)[0]
  if (removed) {
    disposeChart(String(removed.id))
    delete tabStates[String(removed.id)]
  }
  if (openTabs.value.length) {
    activeTab.value = String(openTabs.value[Math.max(idx - 1, 0)].id)
  } else {
    activeTab.value = ''
  }
}

const buildDefaultSql = (table) => {
  if (!table?.dbName || !table?.tableName) return ''
  return `SELECT *\nFROM \`${table.dbName}\`.\`${table.tableName}\`\nLIMIT 200;`
}

const isReadOnlySql = (sql) => {
  if (!sql) return false
  const trimmed = sql.trim().toLowerCase()
  return (
    trimmed.startsWith('select') ||
    trimmed.startsWith('with') ||
    trimmed.startsWith('show') ||
    trimmed.startsWith('desc') ||
    trimmed.startsWith('describe') ||
    trimmed.startsWith('explain')
  )
}

const executeQuery = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.query?.sql?.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  if (!isReadOnlySql(state.query.sql)) {
    ElMessage.warning('仅支持只读查询')
    return
  }
  state.queryLoading = true
  try {
    const res = await dataQueryApi.execute({
      clusterId: clusterId.value || undefined,
      database: state.table.dbName || undefined,
      sql: state.query.sql,
      limit: state.query.limit
    })
    state.queryResult = {
      columns: res.columns || [],
      rows: res.rows || [],
      hasMore: res.hasMore,
      durationMs: res.durationMs,
      executedAt: res.executedAt
    }
    state.page.current = 1
    state.resultTab = 'table'
    state.chart.xAxis = ''
    state.chart.yAxis = []
    await nextTick()
    renderChart(tabId)
    fetchHistory()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '查询失败')
  } finally {
    state.queryLoading = false
  }
}

const resetQuery = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  state.query.sql = buildDefaultSql(state.table)
}

const saveAsTask = (tabId) => {
  const state = tabStates[tabId]
  if (!state?.query?.sql?.trim()) {
    ElMessage.warning('请先输入 SQL')
    return
  }
  taskDrawerRef.value?.open(null, {
    taskSql: state.query.sql,
    taskName: `新建查询任务_${Date.now()}`,
    taskDesc: `From DataStudio\nCluster: ${clusterId.value}\nDatabase: ${state.table.dbName || ''}`
  })
}

const exportResult = (tabId) => {
  const state = tabStates[tabId]
  if (!state?.queryResult?.rows?.length) return
  const header = state.queryResult.columns.join(',')
  const body = state.queryResult.rows
    .map((row) => state.queryResult.columns.map((col) => formatCsvValue(row[col])).join(','))
    .join('\n')
  const blob = new Blob([`${header}\n${body}`], { type: 'text/csv;charset=utf-8;' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `export_${Date.now()}.csv`
  link.click()
}

const fetchHistory = async () => {
  historyLoading.value = true
  try {
    const res = await dataQueryApi.history({
      pageNum: historyPager.pageNum,
      pageSize: historyPager.pageSize
    })
    historyData.value = res.records || []
    historyPager.total = res.total || 0
  } catch (error) {
    console.error('加载历史查询失败', error)
  } finally {
    historyLoading.value = false
  }
}

const applyHistory = (row, tabId) => {
  const state = tabStates[tabId]
  if (!state || !row) return
  state.query.sql = row.sqlText || ''
  if (row.clusterId) {
    clusterId.value = row.clusterId
  }
}

const handleCreateTable = () => {
  createDrawerVisible.value = true
}

const handleDeleteTable = async () => {
  const active = activeTab.value
  const state = active ? tabStates[active] : null
  const table = state?.table
  if (!table?.id) {
    ElMessage.warning('请先选择要删除的表')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要删除表 “${table.tableName}” 吗？删除后将重命名为 deprecated_时间戳，数据不会丢失。`,
      '删除表确认',
      { type: 'warning' }
    )
    await tableApi.softDelete(table.id, clusterId.value || null)
    ElMessage.success('删除表成功')
    const dbName = table.dbName || table.databaseName || table.database
    if (dbName) {
      await loadTables(dbName, true)
    }
    handleTabRemove(active)
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除表失败')
    }
  }
}

const handleCreateSuccess = async (result) => {
  createDrawerVisible.value = false
  await loadDatabases()
  const tableId = result?.id || result?.tableId
  if (!tableId) return
  try {
    const table = await tableApi.getById(tableId)
    const dbName = table?.dbName || table?.databaseName || table?.database || ''
    if (dbName) {
      await loadTables(dbName, true)
    }
    await openTableTab(table, dbName)
  } catch (error) {
    console.error('加载新建表失败', error)
  }
}


const formatCsvValue = (value) => {
  if (value === null || value === undefined) return ''
  const str = String(value)
  return str.includes(',') ? `"${str}"` : str
}

const getPaginatedRows = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return []
  const start = (state.page.current - 1) * state.page.size
  const end = start + state.page.size
  return state.queryResult.rows.slice(start, end)
}

const getNumericColumns = (tabId) => {
  const state = tabStates[tabId]
  if (!state?.queryResult?.rows?.length) return []
  const sample = state.queryResult.rows.slice(0, 10)
  return state.queryResult.columns.filter((col) => {
    return sample.every((row) => {
      const val = row[col]
      return val === null || val === '' || !Number.isNaN(Number(val))
    })
  })
}

const canRenderChart = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return false
  return (
    state.queryResult.rows.length > 0 &&
    state.chart.xAxis &&
    state.chart.yAxis.length > 0
  )
}

const setChartRef = (tabId, el) => {
  if (!tabId || !el) return
  chartRefs.value[tabId] = el
}

const renderChart = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  const container = chartRefs.value[tabId]
  if (!container) return

  const shouldRender = canRenderChart(tabId)
  let instance = chartInstances.get(tabId)
  if (!shouldRender) {
    instance?.clear()
    return
  }
  if (!instance) {
    instance = echarts.init(container)
    chartInstances.set(tabId, instance)
  }

  if (state.chart.type === 'pie') {
    const xKey = state.chart.xAxis
    const yKey = state.chart.yAxis[0]
    if (!xKey || !yKey) {
      instance.clear()
      return
    }
    const data = state.queryResult.rows.map((row) => ({
      name: row[xKey],
      value: Number(row[yKey] || 0)
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

  const xData = state.queryResult.rows.map((row) => row[state.chart.xAxis])
  const series = state.chart.yAxis.map((key) => ({
    name: key,
    type: state.chart.type,
    data: state.queryResult.rows.map((row) => Number(row[key] || 0)),
    smooth: state.chart.type === 'line'
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

const disposeChart = (tabId) => {
  const instance = chartInstances.get(tabId)
  if (instance) {
    instance.dispose()
    chartInstances.delete(tabId)
  }
}

const handleResize = () => {
  const tabId = activeTab.value
  if (!tabId) return
  if (tabStates[tabId]?.resultTab !== 'chart') return
  chartInstances.get(tabId)?.resize()
}

const startMetaEdit = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  state.metaEditing = true
  state.metaForm = { ...state.metaForm }
}

const cancelMetaEdit = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  state.metaEditing = false
  state.metaForm = { ...state.metaOriginal }
}

const saveMetaEdit = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  state.metaSaving = true
  try {
    const payload = {
      tableName: state.metaForm.tableName,
      tableComment: state.metaForm.tableComment,
      layer: state.metaForm.layer,
      owner: state.metaForm.owner
    }
    const updated = await tableApi.update(state.table.id, payload)
    state.table = { ...state.table, ...updated }
    state.metaForm = {
      tableName: state.table.tableName || '',
      tableComment: state.table.tableComment || '',
      layer: state.table.layer || '',
      owner: state.table.owner || ''
    }
    state.metaOriginal = { ...state.metaForm }
    state.metaEditing = false
    updateTableCache(state.table)
    const tab = openTabs.value.find((item) => String(item.id) === String(tabId))
    if (tab) {
      tab.tableName = state.table.tableName
      tab.dbName = state.table.dbName
    }
    ElMessage.success('表信息已更新')
  } catch (error) {
    ElMessage.error('更新失败')
  } finally {
    state.metaSaving = false
  }
}

const updateTableCache = (updated) => {
  if (!updated?.dbName) return
  const list = tableStore[updated.dbName] || []
  const idx = list.findIndex((item) => String(item.id) === String(updated.id))
  if (idx === -1) return
  const next = [...list]
  next[idx] = { ...next[idx], ...updated }
  tableStore[updated.dbName] = next
}

const editField = (row) => {
  row._editing = true
  row._backup = { ...row }
}

const cancelFieldEdit = (row) => {
  if (!row._backup) {
    row._editing = false
    return
  }
  Object.assign(row, row._backup)
  row._editing = false
  row._backup = null
}

const saveField = async (tabId, row) => {
  const state = tabStates[tabId]
  if (!state?.table?.id || !row?.id) return
  try {
    const payload = {
      fieldName: row.fieldName,
      fieldType: row.fieldType,
      fieldComment: row.fieldComment
    }
    const updated = await tableApi.updateField(state.table.id, row.id, payload)
    Object.assign(row, updated || payload)
    row._editing = false
    row._backup = null
    ElMessage.success('字段已更新')
  } catch (error) {
    ElMessage.error('字段更新失败')
  }
}

const loadDdl = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  state.ddlLoading = true
  try {
    const ddl = await tableApi.getTableDdl(state.table.id, clusterId.value || null)
    state.ddl = ddl || ''
  } catch (error) {
    ElMessage.error('加载 DDL 失败')
  } finally {
    state.ddlLoading = false
  }
}

const copyDdl = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.ddl) return
  try {
    await navigator.clipboard.writeText(state.ddl)
    ElMessage.success('已复制')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

const openTask = (taskId) => {
  if (!taskId) return
  taskDrawerRef.value?.open(taskId)
}

const goLineage = (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  router.push({ path: '/lineage', query: { tableId: state.table.id } })
}

const startResize = (event) => {
  event.preventDefault()
  const startX = event.clientX
  const startWidth = sidebarWidth.value
  isResizing.value = true

  resizeMoveHandler = (moveEvent) => {
    const delta = moveEvent.clientX - startX
    const next = Math.max(220, Math.min(420, startWidth + delta))
    sidebarWidth.value = next
  }
  resizeUpHandler = () => {
    isResizing.value = false
    window.removeEventListener('mousemove', resizeMoveHandler)
    window.removeEventListener('mouseup', resizeUpHandler)
    resizeMoveHandler = null
    resizeUpHandler = null
  }
  window.addEventListener('mousemove', resizeMoveHandler)
  window.addEventListener('mouseup', resizeUpHandler)
}

const startLeftResize = (tabId, event) => {
  event.preventDefault()
  const container = leftPaneRefs.value[tabId]
  if (!container) return
  const queryPanel = container.querySelector('.query-panel')
  const containerRect = container.getBoundingClientRect()
  const startY = event.clientY
  const startHeight = queryPanel?.getBoundingClientRect().height || 220
  const minTop = 160
  const minBottom = 220
  const resizerHeight = 6
  isResizing.value = true

  resizeLeftMoveHandler = (moveEvent) => {
    const delta = moveEvent.clientY - startY
    let next = startHeight + delta
    const maxTop = Math.max(minTop, containerRect.height - minBottom - resizerHeight)
    next = Math.max(minTop, Math.min(maxTop, next))
    leftPaneHeights[tabId] = next
  }
  resizeLeftUpHandler = () => {
    isResizing.value = false
    window.removeEventListener('mousemove', resizeLeftMoveHandler)
    window.removeEventListener('mouseup', resizeLeftUpHandler)
    resizeLeftMoveHandler = null
    resizeLeftUpHandler = null
  }
  window.addEventListener('mousemove', resizeLeftMoveHandler)
  window.addEventListener('mouseup', resizeLeftUpHandler)
}

watch([sortField, sortOrder], async () => {
  if (!activeDatabase.value) return
  await loadTables(activeDatabase.value, true)
})

watch(
  () => [historyPager.pageNum, historyPager.pageSize],
  () => {
    fetchHistory()
  }
)

watch(
  () => [
    activeTab.value,
    tabStates[activeTab.value]?.resultTab,
    tabStates[activeTab.value]?.chart.type,
    tabStates[activeTab.value]?.chart.xAxis,
    tabStates[activeTab.value]?.chart.yAxis?.join(','),
    tabStates[activeTab.value]?.queryResult?.rows?.length
  ],
  async () => {
    const tabId = activeTab.value
    if (!tabId) return
    if (tabStates[tabId]?.resultTab !== 'chart') return
    await nextTick()
    renderChart(tabId)
  }
)

watch(
  () => activeTab.value,
  (value) => {
    if (!value) return
    const tab = openTabs.value.find((item) => String(item.id) === String(value))
    if (!tab) return
    selectedTableKey.value = String(tab.id)
    updateRouteQuery({
      dbName: tab.dbName,
      tableId: tabStates[value]?.table?.id,
      tableName: tab.tableName
    })
  }
)

watch(
  () => [route.query.database, route.query.tableId, route.query.tableName],
  async () => {
    if (suppressRouteSync.value) return
    await syncFromRoute()
  }
)

watch(
  () => route.query.create,
  (value) => {
    if (!value) return
    createDrawerVisible.value = true
    clearCreateQuery()
  }
)

onMounted(() => {
  setupTableObserver()
  loadClusters()
  loadDatabases()
  fetchHistory()
  syncFromRoute()
  if (route.query.create) {
    createDrawerVisible.value = true
    clearCreateQuery()
  }
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chartInstances.forEach((instance) => instance.dispose())
  chartInstances.clear()
  if (tableObserver.value) {
    tableObserver.value.disconnect()
  }
  if (resizeMoveHandler) {
    window.removeEventListener('mousemove', resizeMoveHandler)
    resizeMoveHandler = null
  }
  if (resizeUpHandler) {
    window.removeEventListener('mouseup', resizeUpHandler)
    resizeUpHandler = null
  }
  if (resizeLeftMoveHandler) {
    window.removeEventListener('mousemove', resizeLeftMoveHandler)
    resizeLeftMoveHandler = null
  }
  if (resizeLeftUpHandler) {
    window.removeEventListener('mouseup', resizeLeftUpHandler)
    resizeLeftUpHandler = null
  }
})
</script>

<style scoped>
.data-studio {
  height: calc(100vh - 84px);
  padding: 8px;
  background: #f3f6fb;
}

.studio-layout {
  height: 100%;
  display: flex;
  gap: 0;
}

.studio-sidebar {
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #e6e9ef;
}

.sidebar-resizer {
  width: 6px;
  cursor: col-resize;
  position: relative;
  background: transparent;
}

.sidebar-resizer::before {
  content: '';
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 50%;
  width: 1px;
  background: #e2e8f0;
}

.sidebar-resizer:hover::before {
  background: #cbd5f5;
}

.data-studio.is-resizing {
  user-select: none;
}

.cluster-select {
  width: 100%;
}

.sidebar-controls {
  padding: 12px 14px;
  border-bottom: 1px solid #eef1f6;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.search-input {
  width: 100%;
}

.search-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-row .search-input {
  flex: 1;
}

.sort-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-end;
}

.sort-group {
  display: inline-flex;
  flex-wrap: nowrap;
}

.sort-group :deep(.el-radio-button__inner) {
  padding: 4px 10px;
}

.sort-group :deep(.el-radio-button__inner) {
  text-align: center;
  border-radius: 6px;
}

.sort-group :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: 6px 0 0 6px;
}

.sort-group :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 6px 6px 0;
}



.db-tree {
  flex: 1;
  padding: 8px 8px 12px;
  overflow: auto;
}

:deep(.el-collapse-item__header) {
  padding: 0 6px;
  height: 36px;
  background: #fff;
  border-bottom: 1px solid #eef1f6;
  font-size: 13px;
}

:deep(.el-collapse-item__arrow) {
  display: none;
}

:deep(.el-collapse-item__content) {
  padding: 0;
}

.db-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.db-toggle {
  color: #94a3b8;
  font-size: 12px;
}

.db-icon {
  color: #3b82f6;
}

.db-name {
  font-weight: 600;
  color: #1f2f3d;
}

.db-count {
  margin-left: auto;
}

.loading-icon {
  margin-left: 6px;
}

.table-list {
  padding: 4px 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.table-item {
  padding: 6px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s ease;
  background-color: #fff;
  display: flex;
  align-items: center;
  gap: 8px;
  position: relative;
  overflow: hidden;
}

.table-item:hover {
  border-color: #667eea;
  background-color: #f0f4ff;
  transform: translateX(4px);
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.12);
}

.table-item.active {
  border-color: #667eea;
  background-color: #f0f4ff;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.15);
}

.table-progress-bg {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.08) 0%, rgba(102, 126, 234, 0.02) 100%);
  transition: width 0.3s ease;
  pointer-events: none;
  z-index: 0;
}

.table-item:hover .table-progress-bg {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.12) 0%, rgba(102, 126, 234, 0.04) 100%);
}

.table-item.active .table-progress-bg {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.18) 0%, rgba(102, 126, 234, 0.06) 100%);
}

.table-content {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  position: relative;
  z-index: 1;
}

.table-icon {
  color: #667eea;
  flex-shrink: 0;
}

.table-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  max-width: 240px;
}

.table-name {
  font-size: 13px;
  font-weight: 600;
  display: inline-block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 0;
  max-width: 140px;
}

.table-comment {
  color: #909399;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.table-meta-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  margin-left: auto;
  justify-content: flex-end;
}

.row-count {
  font-size: 11px;
  color: #475569;
  font-weight: 500;
  padding: 2px 6px;
  background-color: rgba(102, 126, 234, 0.1);
  border-radius: 4px;
  min-width: 35px;
  text-align: center;
}

.lineage-count {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 5px;
  border-radius: 4px;
  min-width: 28px;
  text-align: center;
}

.lineage-count.upstream {
  color: #10b981;
  background-color: rgba(16, 185, 129, 0.1);
}

.lineage-count.downstream {
  color: #f59e0b;
  background-color: rgba(245, 158, 11, 0.1);
}

.layer-tag {
  flex-shrink: 0;
}

.studio-workspace {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  border: 1px solid #e6e9ef;
  overflow: hidden;
}

.workspace-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.workspace-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

:deep(.workspace-tabs .el-tabs__content) {
  flex: 1;
  min-height: 0;
}

:deep(.workspace-tabs .el-tab-pane) {
  height: 100%;
}

.tab-label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tab-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2f3d;
}

.tab-sub {
  font-size: 11px;
  color: #94a3b8;
}

.tab-grid {
  height: 100%;
  display: grid;
  grid-template-columns: 60% 40%;
  gap: 10px;
  padding: 10px;
  box-sizing: border-box;
}

.tab-left,
.tab-right {
  min-height: 0;
}

.tab-left {
  display: grid;
  grid-template-rows: var(--left-top, 220px) 6px minmax(220px, 1fr);
  gap: 0;
  min-height: 0;
}

.left-resizer {
  cursor: row-resize;
  position: relative;
  background: transparent;
}

.left-resizer::before {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  top: 50%;
  height: 1px;
  background: #e2e8f0;
}

.left-resizer:hover::before {
  background: #cbd5f5;
}

.tab-right {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}

.query-panel {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  padding: 12px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.query-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.query-context {
  display: flex;
  gap: 6px;
}

.query-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.limit-label {
  font-size: 12px;
  color: #64748b;
}

.limit-input {
  width: 110px;
}

.sql-editor :deep(.el-textarea__inner) {
  font-family: 'Fira Code', monospace;
  font-size: 13px;
  height: 100%;
  min-height: 0;
  resize: none;
}

.sql-editor {
  flex: 1;
  min-height: 0;
}

.sql-editor :deep(.el-textarea) {
  height: 100%;
  display: flex;
}

.result-panel {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background-color: #f5f7fa;
  padding: 12px;
}

.result-tabs {
  height: 100%;
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

.table-wrapper {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: #fff;
}

.pagination-bar {
  padding: 8px;
  background: #fff;
  border-top: 1px solid #ebeef5;
  display: flex;
  justify-content: flex-end;
}

.history-panel {
  flex: 1;
  min-height: 0;
  padding: 8px;
  display: flex;
  flex-direction: column;
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

.meta-panel {
  border: 1px solid #eef1f6;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
  min-height: 0;
  flex: 1;
}

.meta-tabs {
  height: 100%;
}

:deep(.meta-tabs .el-tabs__content) {
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
}

:deep(.meta-tabs .el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.meta-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.meta-section-fill {
  flex: 1;
  min-height: 0;
}

.meta-table {
  flex: 1;
  min-height: 0;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  color: #1f2f3d;
}

.section-header.small {
  font-size: 12px;
  color: #475569;
}

.section-divider {
  height: 1px;
  background: #eef1f6;
  margin: 12px 0;
}

.meta-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.ddl-header {
  display: flex;
  gap: 8px;
}

.ddl-textarea {
  flex: 1;
  min-height: 0;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.ddl-textarea :deep(.el-textarea__inner) {
  height: 100% !important;
  min-height: 160px;
}

.lineage-panel {
  border: 1px solid #eef1f6;
  border-radius: 8px;
  background: #fff;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  flex: 1;
}

.lineage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.lineage-grid {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  min-height: 0;
}

.lineage-card {
  border: 1px solid #eef1f6;
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}

.lineage-title {
  font-weight: 600;
  font-size: 12px;
  color: #1f2f3d;
}

.task-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.task-title {
  font-size: 12px;
  color: #64748b;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.task-item {
  padding: 6px 8px;
  border-radius: 6px;
  background: #f8fafc;
  cursor: pointer;
}

.task-item:hover {
  background: #eef5ff;
}

.task-name {
  font-size: 12px;
  font-weight: 600;
  color: #1f2f3d;
}

.task-meta {
  font-size: 11px;
  color: #94a3b8;
}

.lineage-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
}

.lineage-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
}

.lineage-item:hover {
  background: #f1f5f9;
}

.lineage-info {
  flex: 1;
  min-width: 0;
}

.lineage-name {
  font-size: 12px;
  font-weight: 600;
  color: #1f2f3d;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lineage-desc {
  font-size: 11px;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f9fafb;
}

@media (max-width: 1200px) {
  .studio-layout {
    flex-direction: column;
  }

  .studio-sidebar {
    width: 100%;
    max-height: 320px;
  }

  .sidebar-resizer {
    display: none;
  }

  .left-resizer {
    display: none;
  }

  .tab-grid {
    grid-template-columns: 1fr;
  }

  .lineage-grid {
    grid-template-columns: 1fr;
  }
}
</style>
