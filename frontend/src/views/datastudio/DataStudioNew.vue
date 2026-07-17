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
                <DataStudioCatalogNode :data="data" />
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
                  <DataStudioQueryPanel :tab="tab" />

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
  Search,
  Plus,
  Refresh,
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import PersistentTabs from '@/components/PersistentTabs.vue'
import TaskEditDrawer from '@/components/TaskEditDrawer.vue'
import DataStudioResultPanel from '@/views/datastudio/components/DataStudioResultPanel.vue'
import DataStudioQueryPanel from '@/views/datastudio/components/DataStudioQueryPanel.vue'
import DataStudioCatalogNode from '@/views/datastudio/components/DataStudioCatalogNode.vue'
import { isDemoMode } from '@/demo/runtime'
import {
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
  dataSources,
  getSourceName,
  getSchemaOptions,
  handleQuerySourceSelect,
  handleQueryDatabaseSelect,
  executeQuery,
  stopQuery,
  resetQuery,
  saveAsTask,
  getSqlCompletionContext,
  handleSqlSelectionChange,
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

// 目录树节点契约（P2-2 F16c）：DataStudioCatalogNode 消费,键集合与该组件解构保持一致
provide('dataStudioCatalogCtx', {
  dbLoading,
  schemaLoading,
  schemaCountLoading,
  tableLoading,
  setTableRef,
  getProgressWidth,
  getDatasourceIconUrl,
  isDatasourceIconInactive,
  isViewTable,
  getTableCount,
  getTableCountByType,
  getTableRowCount,
  getTableStorageSize,
  refreshDatasourceNode,
  refreshSchemaNode,
  isPlatformMetadataMissing,
  getUpstreamCount,
  getDownstreamCount,
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

:deep(.catalog-tree .el-tree-node__content:hover .table-progress-bg) {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.12) 0%, rgba(102, 126, 234, 0.04) 100%);
}

:deep(.catalog-tree .el-tree-node.is-current > .el-tree-node__content .table-progress-bg) {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.18) 0%, rgba(102, 126, 234, 0.06) 100%);
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
