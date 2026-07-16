<template>
  <div :class="['data-studio', { 'is-resizing': isResizing }]">
    <div ref="studioLayoutRef" class="studio-layout">
      <!-- Left: Database Tree -->
      <aside class="studio-sidebar" :style="sidebarPaneStyle">
        <div class="sidebar-controls">
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
            <el-button size="small" :loading="dbLoading" @click="refreshCatalog">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
            <el-button size="small" type="primary" :disabled="isDemoMode" @click="handleCreateTable">
              <el-icon><Plus /></el-icon>
              新建表
            </el-button>
          </div>
	          <div class="sort-row">
	            <el-radio-group v-model="sortField" size="small" class="sort-group">
	              <el-radio-button value="tableName">表名</el-radio-button>
	              <el-radio-button value="createdAt">创建时间</el-radio-button>
	              <el-radio-button value="rowCount">行数</el-radio-button>
	              <el-radio-button value="storageSize">数据量</el-radio-button>
	              <el-radio-button value="dorisUpdateTime">更新时间</el-radio-button>
	            </el-radio-group>
	            <el-radio-group v-model="sortOrder" size="small" class="sort-group">
	              <el-radio-button value="asc">升序</el-radio-button>
	              <el-radio-button value="desc">降序</el-radio-button>
	            </el-radio-group>
	          </div>
	        </div>

        <div class="db-tree" v-loading="dbLoading">
          <el-scrollbar class="db-tree-scroll">
            <el-tree
              ref="catalogTreeRef"
              :data="catalogRoots"
              node-key="nodeKey"
              :props="catalogTreeProps"
              lazy
              accordion
              highlight-current
              :expand-on-click-node="false"
              :current-node-key="selectedTableKey"
              :filter-node-method="filterCatalogNode"
              :load="loadCatalogNode"
              class="catalog-tree"
              @node-click="handleCatalogNodeClick"
            >
              <template #default="{ data }">
                <div
                  class="catalog-node"
                  :class="`catalog-node--${data.type}`"
                  :ref="(el) => (data.type === 'table' ? setTableRef(data.nodeKey, el, data.table?.id) : null)"
                >
                <div
                  v-if="data.type === 'table'"
                  class="table-progress-bg"
                  :style="{ width: getProgressWidth(data.sourceId, data.schemaName, data.table) }"
                ></div>

                <div class="catalog-node-row">
                  <template v-if="data.type === 'datasource'">
                    <img
                      v-if="getDatasourceIconUrl(data.sourceType)"
                      :class="['node-icon', 'datasource-logo', { 'is-inactive': isDatasourceIconInactive(data) }]"
                      :src="getDatasourceIconUrl(data.sourceType)"
                      :alt="data.sourceType || 'datasource'"
                    />
                    <el-icon v-else :class="['node-icon', 'datasource', { 'is-inactive': isDatasourceIconInactive(data) }]">
                      <Document />
                    </el-icon>
                  </template>
                  <el-icon v-else-if="data.type === 'schema'" class="node-icon schema"><Coin /></el-icon>
                  <el-icon
                    v-else-if="data.type === 'object_group'"
                    :class="['node-icon', data.objectType === 'view' ? 'view-folder' : 'table-folder']"
                  >
                    <View v-if="data.objectType === 'view'" />
                    <Grid v-else />
                  </el-icon>
                  <el-icon v-else :class="['node-icon', isViewTable(data.table) ? 'view' : 'table']">
                    <View v-if="isViewTable(data.table)" />
                    <Grid v-else />
                  </el-icon>

	                  <div v-if="data.type === 'table'" class="table-main">
	                    <div class="table-title">
	                      <span class="table-name" :title="data.table?.tableName">
	                        {{ data.table?.tableName }}
	                      </span>
                        <el-tooltip
                          v-if="isPlatformMetadataMissing(data.table)"
                          content="平台元数据不存在，请先同步"
                          placement="top"
                        >
                          <el-icon class="metadata-warning-icon"><Warning /></el-icon>
                        </el-tooltip>
	                    </div>
	                    <div v-if="data.table?.tableComment" class="table-comment" :title="data.table.tableComment">
	                      {{ data.table.tableComment }}
	                    </div>
	                  </div>
                  <span v-else class="node-name">{{ data.name }}</span>

                  <div v-if="data.type === 'datasource'" class="node-right">
                    <el-tag size="small" class="source-type" :type="data.sourceType === 'MYSQL' ? 'success' : 'warning'">
                      {{ data.sourceType || 'DORIS' }}
                    </el-tag>
                    <el-tooltip content="刷新数据源" placement="top">
                      <el-icon
                        :class="['refresh-icon', { 'is-disabled': dbLoading || schemaLoading[String(data.sourceId)] }]"
                        @click.stop="refreshDatasourceNode(data)"
                      >
                        <Refresh />
                      </el-icon>
                    </el-tooltip>
                    <el-icon v-if="schemaLoading[String(data.sourceId)]" class="is-loading loading-icon"><Loading /></el-icon>
                  </div>

                  <div v-else-if="data.type === 'schema'" class="node-right">
                    <el-badge :value="getTableCount(data.sourceId, data.schemaName)" type="info" class="db-count" />
                    <el-tooltip content="刷新数据库" placement="top">
                      <el-icon
                        :class="[
                          'refresh-icon',
                          {
                            'is-disabled':
                              dbLoading ||
                              schemaCountLoading[String(data.sourceId)] ||
                              tableLoading[`${String(data.sourceId)}::${data.schemaName}`]
                          }
                        ]"
                        @click.stop="refreshSchemaNode(data)"
                      >
                        <Refresh />
                      </el-icon>
                    </el-tooltip>
                    <el-icon
                      v-if="schemaCountLoading[String(data.sourceId)] || tableLoading[`${String(data.sourceId)}::${data.schemaName}`]"
                      class="is-loading loading-icon"
                    >
                      <Loading />
                    </el-icon>
                  </div>

                  <div v-else-if="data.type === 'object_group'" class="node-right">
                    <el-badge
                      :value="getTableCountByType(data.sourceId, data.schemaName, data.objectType)"
                      type="info"
                      class="db-count"
                    />
                  </div>

                  <div v-else-if="data.type === 'table'" class="table-meta-tags">
                    <span class="row-count" :title="`数据量: ${formatNumber(getTableRowCount(data.table))} 行`">
                      {{ formatRowCount(getTableRowCount(data.table)) }}
                    </span>
                    <span class="storage-size" :title="`存储大小: ${formatStorageSize(getTableStorageSize(data.table))}`">
                      {{ formatStorageSize(getTableStorageSize(data.table)) }}
                    </span>
                    <span
                      :class="['lineage-count', 'upstream', { 'is-zero': getUpstreamCount(data.table?.id) === 0 }]"
                      :title="`上游表: ${getUpstreamCount(data.table?.id)} 个`"
                    >
                      ↑{{ getUpstreamCount(data.table?.id) }}
                    </span>
                    <span
                      :class="['lineage-count', 'downstream', { 'is-zero': getDownstreamCount(data.table?.id) === 0 }]"
                      :title="`下游表: ${getDownstreamCount(data.table?.id)} 个`"
                    >
                      ↓{{ getDownstreamCount(data.table?.id) }}
                    </span>
                  </div>
                </div>
              </div>
            </template>
            </el-tree>
          </el-scrollbar>
        </div>
      </aside>

      <div class="sidebar-resizer" title="拖动调整宽度" @mousedown="startResize"></div>

      <!-- Right: Workspace -->
      <section class="studio-workspace">
        <div class="workspace-body">
          <PersistentTabs
            v-if="openTabs.length"
            v-model="activeTab"
            :tabs="openTabs"
            type="card"
            closable
            addable
            class="workspace-tabs"
            style="height: 100%;"
            @tab-remove="handleTabRemove"
            @tab-add="handleTabAdd"
            @close-left="handleCloseLeft"
            @close-right="handleCloseRight"
            @close-all="handleCloseAll"
          >
            <template #label="{ tab }">
              <div class="tab-label">
                <span class="tab-title">{{ tab.tableName }}</span>
                <span class="tab-sub">{{ getTabSubtitle(tab) }}</span>
              </div>
            </template>

            <template #default="{ tab }">
              <div class="tab-grid">
                <div
                  class="tab-left"
                  :ref="(el) => setLeftPaneRef(tab.id, el)"
                  :style="getLeftPaneStyle(tab.id)"
                >
                  <div class="query-panel">
                    <div class="query-topbar">
                      <div class="query-topbar__left">
                        <div class="query-context">
                          <template v-if="tab.kind === 'query'">
                            <el-select
                              v-model="tabStates[tab.id].table.sourceId"
                              size="small"
                              filterable
                              clearable
                              class="query-select query-select--source"
                              placeholder="选择数据源"
                              @change="(value) => handleQuerySourceSelect(tab.id, value)"
                            >
                              <el-option
                                v-for="source in dataSources"
                                :key="String(source.id)"
                                :label="source.clusterName || source.name || `DataSource ${source.id}`"
                                :value="String(source.id)"
                              />
                            </el-select>

                            <el-select
                              v-model="tabStates[tab.id].table.dbName"
                              size="small"
                              filterable
                              clearable
                              class="query-select query-select--db"
                              placeholder="选择数据库"
                              :disabled="!tabStates[tab.id].table.sourceId"
                              @change="(value) => handleQueryDatabaseSelect(tab.id, value)"
                            >
                              <el-option
                                v-for="db in getSchemaOptions(tabStates[tab.id].table.sourceId)"
                                :key="db"
                                :label="db"
                                :value="db"
                              />
                            </el-select>
                          </template>

                          <template v-else>
                            <el-tag size="small" type="info">{{ getSourceName(tab.sourceId) || '-' }}</el-tag>
                            <el-tag size="small" type="info">{{ tabStates[tab.id].table.dbName || '-' }}</el-tag>
                          </template>
                        </div>

                        <div class="query-divider"></div>

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
                      </div>

                      <div class="query-topbar__actions">
                        <el-button
                          type="success"
                          size="small"
                          :loading="tabStates[tab.id].queryLoading"
                          :disabled="tabStates[tab.id].queryLoading"
                          @click="executeQuery(tab.id)"
                        >
                          <el-icon><CaretRight /></el-icon>
                          {{ tabStates[tab.id].query.hasSelection ? '运行已选择' : '运行全部' }}
                        </el-button>
	                        <el-button
	                          size="small"
	                          :loading="tabStates[tab.id].queryStopping"
	                          :disabled="!tabStates[tab.id].queryCancelable || tabStates[tab.id].queryStopping"
	                          @click="stopQuery(tab.id)"
	                        >
	                          <el-icon><VideoPause /></el-icon>
	                          停止
                        </el-button>
                        <el-button size="small" :disabled="tabStates[tab.id].queryLoading" @click="resetQuery(tab.id)">
                          重置
                        </el-button>
                        <el-button
                          size="small"
                          type="success"
                          plain
                          :disabled="tabStates[tab.id].queryLoading || isDemoMode"
                          @click="saveAsTask(tab.id)"
                        >
                          存为任务
                        </el-button>
                      </div>
                    </div>
                    <SqlEditor
                      v-model="tabStates[tab.id].query.sql"
                      class="sql-editor"
                      placeholder="-- 输入 SQL，支持查询与变更语句（高风险语句需强确认）"
                      :completion-context="getSqlCompletionContext(tab.id)"
                      @selection-change="(payload) => handleSqlSelectionChange(tab.id, payload)"
                    />
                  </div>

                  <div class="left-resizer" title="拖动调整高度" @mousedown="startLeftResize(tab.id, $event)"></div>

                  <DataStudioResultPanel :tab="tab" />
                </div>

              </div>
            </template>
          </PersistentTabs>

	          <div v-else class="empty-state">
	            <el-empty description="从左侧选择表以打开工作区" :image-size="120">
	              <el-button type="primary" @click="handleTabAdd">
	                <el-icon><Plus /></el-icon>
	                新建查询
	              </el-button>
	            </el-empty>
	          </div>
	        </div>
	      </section>

      <div class="workspace-resizer" title="拖动调整宽度" @mousedown="startRightResize"></div>

      <!-- Right: Meta/Lineage -->
      <aside class="studio-right" :style="rightPaneStyle">
        <DataStudioRightPanel visual-variant="clean-slate" />
      </aside>
    </div>

    <CreateTableDrawer v-if="createDrawerVisible" v-model="createDrawerVisible" @created="handleCreateSuccess" />
    <TaskEditDrawer ref="taskDrawerRef" @success="handleTaskSuccess" />

  </div>
</template>

<script setup>
import { defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, provide, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Coin,
  Search,
  Plus,
  CaretRight,
  Document,
  Grid,
  Loading,
  Refresh,
  View,
  VideoPause,
  Warning
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import PersistentTabs from '@/components/PersistentTabs.vue'
import TaskEditDrawer from '@/components/TaskEditDrawer.vue'
import DataStudioResultPanel from '@/views/datastudio/components/DataStudioResultPanel.vue'
import { isDemoMode } from '@/demo/runtime'
import {
  formatNumber,
  formatRowCount,
  formatStorageSize,
  formatDuration,
  formatDateTime,
  isAggregateTable,
  isReplicaWarning,
} from './tableFormat'
import { useTabPersistence } from './composables/useTabPersistence'
import { useResizablePanes } from './composables/useResizablePanes'
import { useSqlCompletion } from './composables/useSqlCompletion'
import { useTabRouting } from './composables/useTabRouting'
import { useCatalogTree } from './composables/useCatalogTree'
import { useQueryExecution } from './composables/useQueryExecution'
import { useResultChart } from './composables/useResultChart'
import { useTableMetaEditing } from './composables/useTableMetaEditing'
import { useStudioTabs } from './composables/useStudioTabs'
import { useTableActions } from './composables/useTableActions'

const SqlEditor = defineAsyncComponent({
  loader: () => import('@/components/SqlEditor.vue'),
  suspensible: false
})

const CreateTableDrawer = defineAsyncComponent({
  loader: () => import('@/views/datastudio/CreateTableDrawer.vue'),
  suspensible: false
})

const DataStudioRightPanel = defineAsyncComponent({
  loader: () => import('@/views/datastudio/components/DataStudioRightPanel.vue'),
  suspensible: false
})

const clusterId = ref(null)
const route = useRoute()
const router = useRouter()
const createDrawerVisible = ref(false)

const lineageCache = reactive({})
const suppressRouteSync = ref(false)

const openTabs = ref([])
const activeTab = ref('')
const tabStates = reactive({})
const queryTabCounter = ref(1)
const tableObserver = ref(null)

// 目录树加载与缓存（P2-2 F7）：schema/table/column 缓存继续共享给 SQL 补全与路由同步。
const {
  dbLoading,
  dataSources,
  activeSource,
  schemaStore,
  schemaLoading,
  schemaCountLoading,
  activeSchema,
  tableLoading,
  tableStore,
  columnStore,
  catalogTreeRef,
  catalogTreeProps,
  catalogRoots,
  searchKeyword,
  sortField,
  sortOrder,
  selectedTableKey,
  tableRefs,
  loadClusters,
  getDatasourceById,
  activateDatasource,
  loadSchemas,
  loadTables,
  setTableRef,
  getTableKey,
  findCachedTableById,
  isDatasourceIconInactive,
  getDatasourceIconUrl,
  isViewTable,
  getTableRowCount,
  getTableStorageSize,
  getTableCount,
  getTableCountByType,
  getProgressWidth,
  filterCatalogNode,
  loadCatalogNode,
  handleCatalogNodeClick,
  refreshCatalog,
  refreshDatasourceNode,
  refreshSchemaNode,
  focusTableInSidebar,
  focusActiveTableInSidebar,
} = useCatalogTree({
  clusterId,
  tabStates,
  openTabs,
  activeTab,
  tableObserver,
  handleQuerySourceSelect: (...args) => handleQuerySourceSelect(...args),
  handleQueryDatabaseSelect: (...args) => handleQueryDatabaseSelect(...args),
  openTableTab: (...args) => openTableTab(...args),
})

// 三栏布局尺寸与拖拽（P2-2 F5）：syncResultPaneLayout 为前向引用，惰性传入
const {
  studioLayoutRef,
  sidebarPaneStyle,
  rightPaneStyle,
  normalizePaneRatios,
  isResizing,
  leftPaneHeights,
  leftPaneRefs,
  setLeftPaneRef,
  getLeftPaneStyle,
  startResize,
  startRightResize,
  startLeftResize,
} = useResizablePanes({
  activeTab,
  syncResultPaneLayout: (tabId) => syncResultPaneLayout(tabId),
})

// SQL 补全数据源（P2-2 F6）：共享目录缓存直接注入，loadTables/activateDatasource 惰性前向引用
// 仅取组件实际使用的入口；其余补全函数仅在 getSqlCompletionContext 内部使用
const {
  getSchemaOptions,
  getSqlCompletionContext,
} = useSqlCompletion({
  tabStates,
  schemaStore,
  tableStore,
  columnStore,
  loadTables: (...args) => loadTables(...args),
  activateDatasource: (...args) => activateDatasource(...args),
})

// 标签页 URL 路由同步（P2-2 F8）：catalog 加载器与 openTableTab 惰性前向引用
const {
  syncRouteWithTab,
  clearRouteTabQuery,
  clearCreateQuery,
  syncFromRoute,
} = useTabRouting({
  suppressRouteSync,
  tabStates,
  openTabs,
  activeTab,
  activeSource,
  activeSchema,
  tableStore,
  loadSchemas: (...args) => loadSchemas(...args),
  loadTables: (...args) => loadTables(...args),
  openTableTab: (...args) => openTableTab(...args),
})

// 查询执行与历史（P2-2 F9）：执行、停止、结果集标准化、导出和历史分页从主组件抽离
const {
  historyData,
  historyPager,
  historyLoading,
  buildDefaultSql,
  clearQueryTimer,
  stopNowTickerIfIdle,
  getStatementStatusTagType,
  isResultSetType,
  getResultSetCountText,
  getResultSetAlertType,
  getDisplayResultSets,
  handleSqlSelectionChange,
  handleQuerySourceSelect,
  handleQueryDatabaseSelect,
  syncAutoSelectSqlIfSchemaMismatch,
  executeQuery,
  stopQuery,
  getLiveDurationMs,
  resetQuery,
  exportResult,
  fetchHistory,
  applyHistory,
  parseResultTabIndex,
  getResultRowKeyPrefix,
  getResultSetByIndex,
} = useQueryExecution({
  clusterId,
  activeSource,
  activeSchema,
  schemaStore,
  tabStates,
  openTabs,
  activeTab,
  loadSchemas: (...args) => loadSchemas(...args),
  loadTables: (...args) => loadTables(...args),
  syncRouteWithTab: (...args) => syncRouteWithTab(...args),
  disposeChart: (...args) => disposeChart(...args),
  applyDefaultChartSelection: (...args) => applyDefaultChartSelection(...args),
  syncResultPaneLayout: (...args) => syncResultPaneLayout(...args),
})

// 结果图表（P2-2 F10）：ECharts 实例、DOM ref、默认选列、渲染和清理从主组件抽离
const {
  getNumericColumns,
  applyDefaultChartSelection,
  canRenderChart,
  setChartRef,
  syncResultPaneLayout,
  disposeChart,
} = useResultChart({
  activeTab,
  tabStates,
  parseResultTabIndex,
  getResultSetByIndex,
})

	const taskDrawerRef = ref(null)

const layerOptions = [
  { label: 'ODS - 原始数据层', value: 'ODS' },
  { label: 'DWD - 明细数据层', value: 'DWD' },
  { label: 'DIM - 维度数据层', value: 'DIM' },
  { label: 'DWS - 汇总数据层', value: 'DWS' },
  { label: 'ADS - 应用数据层', value: 'ADS' }
]

const getSourceName = (sourceId) => {
  if (!sourceId) return ''
  const source = dataSources.value.find((item) => String(item.id) === String(sourceId))
  return source?.clusterName || source?.name || ''
}

const getTabSubtitle = (tab) => {
  if (!tab) return ''
  const sourceName = getSourceName(tab.sourceId)
  const dbName = tab.dbName || ''
  if (sourceName && dbName) {
    return `${sourceName} / ${dbName}`
  }
  return sourceName || dbName || ''
}

const hasText = (value) => value !== null && value !== undefined && String(value).trim() !== ''
const hasPositiveNumber = (value) => {
  const num = Number(value)
  return Number.isFinite(num) && num > 0
}

const getTableSourceType = (table) => {
  if (!table) return ''
  const explicitType = String(table.sourceType || table.datasourceType || table.dataSourceType || '')
    .trim()
    .toUpperCase()
  if (explicitType) return explicitType
  const sourceId = table.sourceId || table.clusterId || table.datasourceId
  if (!sourceId) return ''
  return String(getDatasourceById(sourceId)?.sourceType || '')
    .trim()
    .toUpperCase()
}

const isDorisTable = (table) => {
  if (!table) return false
  const sourceType = getTableSourceType(table)
  if (sourceType === 'MYSQL') return false
  if (sourceType === 'DORIS') return true
  if (table.isSynced === 1) return true
  return (
    hasText(table.tableModel) ||
    hasPositiveNumber(table.bucketNum) ||
    hasPositiveNumber(table.replicaNum) ||
    hasText(table.distributionColumn) ||
    hasText(table.keyColumns) ||
    hasText(table.partitionColumn)
  )
}

const MISSING_PLATFORM_METADATA_MESSAGE = '该表未同步到平台，需同步后才能操作'

const isPlatformMetadataMissing = (table) => {
  if (!table) return false
  if (table.metadataMissing === true || table.metadataStatus === 'missing') return true
  return isDorisTable(table) && !table.id && hasText(table.dbName) && hasText(table.tableName)
}

const warnPlatformMetadataMissing = (table) => {
  if (!isPlatformMetadataMissing(table)) return false
  ElMessage.warning(MISSING_PLATFORM_METADATA_MESSAGE)
  return true
}

// 表元数据与字段编辑（P2-2 F10b）：保存、取消回滚、字段草稿和业务域加载从主组件抽离
const {
  businessDomainOptions,
  loadBusinessDomains,
  loadMetaDataDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
  getFieldRows,
  startMetaEdit,
  cancelMetaEdit,
  saveMetaEdit,
  startFieldsEdit,
  cancelFieldsEdit,
  saveFieldsEdit,
  addField,
  removeField,
} = useTableMetaEditing({
  clusterId,
  tabStates,
  openTabs,
  activeTab,
  selectedTableKey,
  tableRefs,
  tableStore,
  getTableKey,
  isDorisTable,
  isAggregateTable,
  warnPlatformMetadataMissing,
})

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

// Tab 工作区生命周期（P2-2 F14，完成 F8 残留）：Tab 状态创建、打开/加载/关闭
// 从主组件抽离；共享状态所有权仍在组件，openTableTab 前向引用由此闭环
const {
  createTabState,
  openTableTab,
  loadTabData,
  hydrateRestoredTableTabs,
  handleTabRemove,
  handleCloseLeft,
  handleCloseRight,
  handleCloseAll,
  handleTabAdd,
  getTabItemById,
} = useStudioTabs({
  clusterId,
  openTabs,
  activeTab,
  tabStates,
  lineageCache,
  selectedTableKey,
  findCachedTableById,
  getDatasourceById,
  getTableKey,
  focusTableInSidebar,
  loadSchemas,
  getSchemaOptions,
  buildDefaultSql,
  syncAutoSelectSqlIfSchemaMismatch,
  clearQueryTimer,
  stopNowTickerIfIdle,
  disposeChart,
  leftPaneRefs,
  leftPaneHeights,
  loadMetaDataDomainOptions,
})

// 表操作与详情加载（P2-2 F15）：建表/删表/元数据同步、DDL/访问统计、任务与血缘跳转
const {
  saveAsTask,
  handleCreateTable,
  handleDeleteTable,
  syncMissingTableMetadata,
  handleCreateSuccess,
  loadDdl,
  loadAccessStats,
  copyDdl,
  openTask,
  goCreateRelatedTask,
  handleTaskSuccess,
  goLineage,
} = useTableActions({
  clusterId,
  openTabs,
  activeTab,
  tabStates,
  createDrawerVisible,
  taskDrawerRef,
  router,
  isDorisTable,
  isPlatformMetadataMissing,
  warnPlatformMetadataMissing,
  tableStore,
  loadClusters,
  loadTables,
  openTableTab,
  loadTabData,
  handleTabRemove,
  getTabItemById,
})

watch(
  () => [activeTab.value, tabStates[activeTab.value]?.metaTab],
  ([tabId, metaTab]) => {
    if (!tabId) return
    const state = tabStates[tabId]
    if (!state) return
    if (metaTab === 'ddl') {
      if (state.ddlLoading || state.ddl) return
      loadDdl(tabId)
      return
    }
    if (metaTab === 'access') {
      if (state.accessLoading || state.accessStats) return
      loadAccessStats(tabId)
    }
  }
)

watch(
  () => activeTab.value,
  (value) => {
    if (!value) {
      if (!openTabs.value.length) {
        clearRouteTabQuery()
      }
      return
    }
    const tab = openTabs.value.find((item) => String(item.id) === String(value))
    if (!tab) return
    if (tab.sourceId) {
      clusterId.value = tab.sourceId
    }
    if (tab.kind === 'table') {
      selectedTableKey.value = String(tab.id)
      const tabId = String(tab.id)
      const state = tabStates[tabId]
      if (state && !state.dataLoaded && !state.dataLoading) {
        loadTabData(tabId)
      }
    } else {
      selectedTableKey.value = ''
    }
    syncRouteWithTab(tab, value)
  }
)

watch(
  () => [route.query.clusterId, route.query.database, route.query.tableId, route.query.tableName],
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

  provide('dataStudioCtx', {
    clusterId,
    openTabs,
    activeTab,
    tabStates,
  layerOptions,
  businessDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
  isDorisTable,
  isPlatformMetadataMissing,
  isAggregateTable,
  isReplicaWarning,
  getFieldRows,
  startMetaEdit,
  cancelMetaEdit,
  saveMetaEdit,
  handleDeleteTable,
  syncMissingTableMetadata,
  startFieldsEdit,
  cancelFieldsEdit,
  saveFieldsEdit,
  addField,
  removeField,
    copyDdl,
    loadAccessStats,
    formatDuration,
    formatDateTime,
    goLineage,
    goCreateRelatedTask,
    openTask,
    openTableTab
  })

// 查询结果面板契约（P2-2 F16a）：DataStudioResultPanel 消费,键集合与该组件解构保持一致
provide('dataStudioQueryCtx', {
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
})

// Tab 工作区持久化（P2-2 F4）：在 createTabState 之后装配，依赖共享响应式状态
const { flushPersistTabs, restoreTabsFromStorage } = useTabPersistence({
  openTabs,
  activeTab,
  tabStates,
  queryTabCounter,
  createTabState,
  storageKey: isDemoMode
    ? 'odw:datastudio:workspace-tabs:demo-v1'
    : 'odw:datastudio:workspace-tabs:v1',
})

onMounted(async () => {
  setupTableObserver()
  const restored = restoreTabsFromStorage()
  if (restored) {
    hydrateRestoredTableTabs()
  }
  loadBusinessDomains()
  await loadClusters()
  fetchHistory()
  await syncFromRoute()
  await focusActiveTableInSidebar()
  if (route.query.create) {
    createDrawerVisible.value = true
    clearCreateQuery()
  }
  await nextTick()
  normalizePaneRatios()
})

onBeforeUnmount(() => {
  flushPersistTabs()
  if (tableObserver.value) {
    tableObserver.value.disconnect()
  }
})
</script>

<style scoped>
.data-studio {
  height: calc(100vh - 84px);
  min-height: 0;
  padding: 8px;
  background: #f3f6fb;
  overflow: hidden;
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
  width: 10px;
  cursor: col-resize;
  position: relative;
  background: transparent;
}

.workspace-resizer {
  width: 10px;
  cursor: col-resize;
  position: relative;
  background: transparent;
}

.sidebar-resizer::after,
.workspace-resizer::after {
  content: '⋮⋮';
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 11px;
  line-height: 1;
  letter-spacing: -1px;
  color: #94a3b8;
  padding: 3px 4px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}

.sidebar-resizer:hover::after,
.workspace-resizer:hover::after,
.data-studio.is-resizing .sidebar-resizer::after,
.data-studio.is-resizing .workspace-resizer::after {
  opacity: 1;
  color: #64748b;
}

.data-studio.is-resizing {
  user-select: none;
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
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: nowrap;
  overflow-x: auto;
  overflow-y: hidden;
}

.sort-group {
  display: inline-flex;
  flex-wrap: nowrap;
}

.db-tree {
  flex: 1;
  min-height: 0;
  padding: 8px 8px 12px;
  overflow: hidden;
}

.db-tree-scroll {
  height: 100%;
}

.catalog-tree {
  width: 100%;
}

:deep(.catalog-tree .el-tree-node__content) {
  height: auto;
  padding: 2px 6px;
}

:deep(.catalog-tree .el-tree-node__content:hover) {
  background-color: transparent;
}

:deep(.catalog-tree .el-tree-node.is-current > .el-tree-node__content) {
  background-color: transparent;
}

.source-type {
  margin-left: auto;
  border-radius: 6px;
}

.db-count {
  display: inline-flex;
}

.loading-icon {
  margin-left: 6px;
}

.refresh-icon {
  cursor: pointer;
  color: #64748b;
  transition: color 0.15s ease;
}

.refresh-icon:hover {
  color: #3b82f6;
}

.refresh-icon.is-disabled {
  cursor: not-allowed;
  color: #cbd5e1;
  pointer-events: none;
}

.catalog-node {
  width: 100%;
  border-radius: 8px;
}

.catalog-node--datasource,
.catalog-node--schema,
.catalog-node--object_group {
  padding: 6px 8px;
  transition: background-color 0.2s ease;
}

.catalog-node--table {
  padding: 6px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background-color: #fff;
  transition: background-color 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
  position: relative;
  overflow: hidden;
}

:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--datasource),
:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--schema),
:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--object_group) {
  background-color: var(--el-fill-color-light);
}

:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--table) {
  border-color: #667eea;
  background-color: #f0f4ff;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.08);
}

:deep(.catalog-tree .el-tree-node.is-current > .el-tree-node__content .catalog-node--table) {
  border-color: #667eea;
  background-color: #f0f4ff;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.12);
}

.catalog-node-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  position: relative;
  z-index: 1;
}

.catalog-node--table .catalog-node-row {
  align-items: flex-start;
}

.node-icon {
  flex-shrink: 0;
}

.datasource-logo {
  width: 16px;
  height: 16px;
  display: block;
}

.datasource-logo.is-inactive {
  filter: grayscale(1) saturate(0) opacity(0.55);
}

.node-icon.datasource.is-inactive {
  color: #94a3b8;
}

.node-icon.datasource {
  color: #f59e0b;
}

.node-icon.schema {
  color: #3b82f6;
}

.node-icon.table {
  color: #667eea;
}

.node-icon.table-folder {
  color: #667eea;
}

.node-icon.view {
  color: #0ea5e9;
}

.node-icon.view-folder {
  color: #0ea5e9;
}

.node-name {
  font-weight: 600;
  color: #111827;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.table-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.table-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
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

:deep(.catalog-tree .el-tree-node__content:hover .table-progress-bg) {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.12) 0%, rgba(102, 126, 234, 0.04) 100%);
}

:deep(.catalog-tree .el-tree-node.is-current > .el-tree-node__content .table-progress-bg) {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.18) 0%, rgba(102, 126, 234, 0.06) 100%);
}

.table-name {
  font-size: 13px;
  font-weight: 600;
  display: inline-block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
  max-width: 200px;
}

.metadata-warning-icon {
  color: #ef4444;
  flex-shrink: 0;
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

.storage-size {
  font-size: 11px;
  color: #475569;
  font-weight: 500;
  padding: 2px 6px;
  background-color: rgba(14, 165, 233, 0.1);
  border-radius: 4px;
  min-width: 56px;
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

.lineage-count.is-zero {
  color: #94a3b8;
  background-color: rgba(148, 163, 184, 0.16);
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

.studio-right {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}

.workspace-body {
  height: 100%;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.workspace-tabs {
  height: 100%;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

:deep(.workspace-tabs > .el-tabs) {
  height: 100%;
  flex: 1;
  min-height: 0;
}

:deep(.workspace-tabs .el-tabs__header) {
  display: flex;
  align-items: center;
}

:deep(.workspace-tabs .el-tabs__nav-wrap) {
  flex: 0 1 auto;
  min-width: 0;
  max-width: calc(100% - 72px);
}

:deep(.workspace-tabs .el-tabs__new-tab) {
  width: 32px;
  height: 32px;
  padding: 0;
  margin: 4px 0 4px 6px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
}

:deep(.workspace-tabs .el-tabs__new-tab:hover) {
  background: #f8fafc;
  border-color: #c7d2fe;
}

/* remove label, keep only "+" icon */
:deep(.workspace-tabs .el-tabs__new-tab::after) {
  content: none;
}

:deep(.workspace-tabs .el-tabs__content) {
  height: 100%;
  flex: 1;
  min-height: 0;
}

:deep(.workspace-tabs .el-tab-pane) {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

:deep(.workspace-tabs .el-tabs__card) {
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
  display: flex;
  flex-direction: column;
  padding: 10px;
  box-sizing: border-box;
  min-height: 0;
}

.tab-left {
  flex: 1;
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

.left-resizer::after {
  content: '⋯';
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 14px;
  line-height: 1;
  color: #94a3b8;
  padding: 0 8px 2px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}

.left-resizer:hover::after,
.data-studio.is-resizing .left-resizer::after {
  opacity: 1;
  color: #64748b;
}

.query-panel {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  padding: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.query-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
  gap: 10px;
  flex-wrap: nowrap;
}

.query-topbar__left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
  overflow: hidden;
}

.query-topbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.query-context {
  display: flex;
  gap: 6px;
  flex-wrap: nowrap;
  align-items: center;
  min-width: 0;
}

.query-divider {
  width: 1px;
  height: 20px;
  background: #e2e8f0;
  flex-shrink: 0;
}

.query-select {
  width: 160px;
  flex: 0 0 160px;
}

.query-select--source {
  width: 180px;
  flex: 0 0 180px;
}

.query-select--db {
  width: 180px;
  flex: 0 0 180px;
}

.query-select :deep(.el-select__selected-item) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.limit-label {
  font-size: 12px;
  color: #64748b;
}

.limit-input {
  width: 110px;
}

.sql-editor {
  flex: 1;
  min-height: 0;
}

.sql-editor :deep(.cm-editor) {
  height: 100%;
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
    width: 100% !important;
    max-height: 320px;
  }

  .studio-right {
    width: 100% !important;
  }

  .sidebar-resizer,
  .workspace-resizer {
    display: none;
  }

  .left-resizer {
    display: none;
  }

}
</style>
