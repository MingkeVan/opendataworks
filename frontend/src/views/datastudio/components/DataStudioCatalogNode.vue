<template>
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

<script setup>
import { inject } from 'vue'
import { Coin, Document, Grid, Loading, Refresh, View, Warning } from '@element-plus/icons-vue'
import { formatNumber, formatRowCount, formatStorageSize } from '../tableFormat'

// P2-2 F16c：目录树节点从 DataStudioNew.vue 的 el-tree scoped slot 抽出。
// 展示函数与状态通过 dataStudioCatalogCtx 注入，键集合见 DataStudioNew.vue 的 provide。
// 注意：悬停/选中态样式锚定在父组件的 .catalog-tree 上，保留在父组件的 :deep() 规则里。
defineProps({
  data: {
    type: Object,
    required: true,
  },
})

const ctx = inject('dataStudioCatalogCtx')
if (!ctx) {
  throw new Error('DataStudioCatalogNode 需要 dataStudioCatalogCtx')
}

const {
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
} = ctx
</script>

<style scoped>
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

/* .el-tree-node__content 是 flex 容器，节点占满展开图标之后的剩余宽度 */
.catalog-node {
  flex: 1;
  min-width: 0;
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

/* 不设宽度上限：侧栏可拖到 840px，表名要跟着吃满剩余宽度，放不下时才省略号 */
.table-name {
  font-size: 13px;
  font-weight: 600;
  display: inline-block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
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
</style>
