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
                </div>

                <!-- moved to DataStudioRightPanel.vue
                <Teleport to="#datastudio-right-panel">
                  <div v-if="tab.kind !== 'query' && String(activeTab) === String(tab.id)" class="tab-right">
                    <div class="meta-panel">
                    <el-tabs v-model="tabStates[tab.id].metaTab" class="meta-tabs">
                      <el-tab-pane name="basic" label="基本信息">
                        <div class="meta-section meta-section-fill">
                          <div class="section-header">
                            <span>表信息</span>
                            <div class="section-actions">
                              <el-tooltip
                                v-if="!tabStates[tab.id].metaEditing && isDorisTable(tabStates[tab.id].table) && !clusterId"
                                content="请选择 Doris 集群后再编辑"
                                placement="top"
                              >
                                <span>
                                  <el-button type="primary" size="small" disabled>编辑</el-button>
                                </span>
                              </el-tooltip>
                              <el-button
                                v-else-if="!tabStates[tab.id].metaEditing"
                                type="primary"
                                size="small"
                                @click="startMetaEdit(tab.id)"
                              >
                                编辑
                              </el-button>
                              <el-tooltip
                                v-if="!tabStates[tab.id].metaEditing && isDorisTable(tabStates[tab.id].table) && !clusterId"
                                content="请选择 Doris 集群后再删除"
                                placement="top"
                              >
                                <span>
                                  <el-button type="danger" plain size="small" disabled>删除表</el-button>
                                </span>
                              </el-tooltip>
                              <el-button
                                v-else-if="!tabStates[tab.id].metaEditing"
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

                          <div class="meta-scroll">
                            <el-descriptions :column="1" border size="small" class="meta-descriptions">
                              <el-descriptions-item label="表名">
                                <el-input
                                  v-if="tabStates[tab.id].metaEditing"
                                  v-model="tabStates[tab.id].metaForm.tableName"
                                  size="small"
                                  class="meta-input"
                                />
                                <span v-else>{{ tabStates[tab.id].table.tableName || '-' }}</span>
                              </el-descriptions-item>
                              <el-descriptions-item label="表注释">
                                <el-input
                                  v-if="tabStates[tab.id].metaEditing"
                                  v-model="tabStates[tab.id].metaForm.tableComment"
                                  size="small"
                                  class="meta-input"
                                />
                                <span v-else>{{ tabStates[tab.id].table.tableComment || '-' }}</span>
                              </el-descriptions-item>
                              <el-descriptions-item label="分层">
                                <el-select
                                  v-if="tabStates[tab.id].metaEditing"
                                  v-model="tabStates[tab.id].metaForm.layer"
                                  size="small"
                                  placeholder="选择分层"
                                  class="meta-input"
                                >
                                  <el-option v-for="item in layerOptions" :key="item.value" :label="item.label" :value="item.value" />
                                </el-select>
                                <span v-else>{{ tabStates[tab.id].table.layer || '-' }}</span>
                              </el-descriptions-item>
                              <el-descriptions-item label="负责人">
                                <el-input
                                  v-if="tabStates[tab.id].metaEditing"
                                  v-model="tabStates[tab.id].metaForm.owner"
                                  size="small"
                                  class="meta-input"
                                />
                                <span v-else>{{ tabStates[tab.id].table.owner || '-' }}</span>
                              </el-descriptions-item>
                              <el-descriptions-item label="数据库">
                                <span>{{ tabStates[tab.id].table.dbName || '-' }}</span>
                              </el-descriptions-item>
                            </el-descriptions>

                            <template v-if="isDorisTable(tabStates[tab.id].table)">
                              <div class="section-divider"></div>

                              <div class="section-header small">
                                <span>Doris 配置</span>
                              </div>
                              <el-descriptions :column="1" border size="small" class="meta-descriptions">
                                <el-descriptions-item label="表模型">{{ tabStates[tab.id].table.tableModel || '-' }}</el-descriptions-item>
                                <el-descriptions-item label="主键列">{{ tabStates[tab.id].table.keyColumns || '-' }}</el-descriptions-item>
                                <el-descriptions-item label="分区字段">{{ tabStates[tab.id].table.partitionColumn || '-' }}</el-descriptions-item>
                                <el-descriptions-item label="分桶字段">{{ tabStates[tab.id].table.distributionColumn || '-' }}</el-descriptions-item>
                                <el-descriptions-item label="分桶数">
                                  <el-input-number
                                    v-if="tabStates[tab.id].metaEditing"
                                    v-model="tabStates[tab.id].metaForm.bucketNum"
                                    :min="1"
                                    size="small"
                                    controls-position="right"
                                    class="meta-input"
                                  />
                                  <span v-else>{{ tabStates[tab.id].table.bucketNum || '-' }}</span>
                                </el-descriptions-item>
                                <el-descriptions-item label="副本数">
                                  <template v-if="tabStates[tab.id].metaEditing">
                                    <div class="replica-edit">
                                      <el-input-number
                                        v-model="tabStates[tab.id].metaForm.replicaNum"
                                        :min="1"
                                        size="small"
                                        controls-position="right"
                                        class="meta-input"
                                      />
                                      <span v-if="isReplicaWarning(tabStates[tab.id].metaForm.replicaNum)" class="replica-warning">
                                        <el-icon><Warning /></el-icon>
                                        建议≥3
                                      </span>
                                    </div>
                                  </template>
                                  <span v-else :class="['replica-value', { 'replica-danger': isReplicaWarning(tabStates[tab.id].table.replicaNum) }]">
                                    <el-icon v-if="isReplicaWarning(tabStates[tab.id].table.replicaNum)" class="warning-icon"><Warning /></el-icon>
                                    {{ tabStates[tab.id].table.replicaNum || '-' }}
                                  </span>
                                </el-descriptions-item>
                              </el-descriptions>
                            </template>
                          </div>
                        </div>
                      </el-tab-pane>

      <el-tab-pane name="columns" label="列信息">
        <div class="meta-section meta-section-fill">
          <div class="section-header">
            <div class="section-title">
              <span>字段定义</span>
              <el-tag
                v-if="tabStates[tab.id].fieldsEditing && isAggregateTable(tabStates[tab.id].table)"
                type="warning"
                size="small"
                effect="plain"
              >
                AGGREGATE 表仅支持修改注释
              </el-tag>
              <el-tag
                v-if="tabStates[tab.id].fieldsEditing && isDorisTable(tabStates[tab.id].table)"
                type="warning"
                size="small"
                effect="plain"
              >
                主键列不可在线修改
              </el-tag>
            </div>
            <div class="section-actions">
              <el-tooltip
                v-if="!tabStates[tab.id].fieldsEditing && isDorisTable(tabStates[tab.id].table) && !clusterId"
                content="请选择 Doris 集群后再编辑"
                placement="top"
              >
                <span>
                  <el-button type="primary" size="small" disabled>编辑</el-button>
                </span>
              </el-tooltip>
              <el-button
                v-else-if="!tabStates[tab.id].fieldsEditing"
                type="primary"
                size="small"
                @click="startFieldsEdit(tab.id)"
              >
                编辑
              </el-button>
              <template v-else>
                <el-button size="small" @click="cancelFieldsEdit(tab.id)" :disabled="tabStates[tab.id].fieldSubmitting">
                  取消
                </el-button>
                <el-button
                  type="primary"
                  size="small"
                  :loading="tabStates[tab.id].fieldSubmitting"
                  @click="saveFieldsEdit(tab.id)"
                >
                  保存修改
                </el-button>
              </template>
            </div>
          </div>
          <div v-if="getFieldRows(tab.id).length" class="meta-table">
            <el-table
              :data="getFieldRows(tab.id)"
              border
              size="small"
              height="100%"
            >
              <el-table-column label="字段名" width="130" show-overflow-tooltip>
                <template #default="{ row }">
                  <el-input
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.fieldName"
                    size="small"
                    placeholder="字段名"
                    :disabled="isAggregateTable(tabStates[tab.id].table)"
                  />
                  <span v-else>{{ row.fieldName }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="150">
                <template #default="{ row }">
                  <el-input
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.fieldType"
                    size="small"
                    placeholder="VARCHAR(255)"
                    :disabled="isAggregateTable(tabStates[tab.id].table)"
                  />
                  <span v-else>{{ row.fieldType }}</span>
                </template>
              </el-table-column>
              <el-table-column label="可为空" width="90">
                <template #default="{ row }">
                  <el-switch
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.isNullable"
                    :active-value="1"
                    :inactive-value="0"
                    size="small"
                    :disabled="isAggregateTable(tabStates[tab.id].table)"
                  />
                  <el-tag v-else :type="row.isNullable ? 'success' : 'danger'" size="small">
                    {{ row.isNullable ? '是' : '否' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="主键" width="80">
                <template #default="{ row }">
                  <template v-if="tabStates[tab.id].fieldsEditing">
                    <el-tooltip
                      v-if="isDorisTable(tabStates[tab.id].table)"
                      content="Doris 不支持在线修改主键列"
                      placement="top"
                    >
                      <span>
                        <el-switch
                          v-model="row.isPrimary"
                          :active-value="1"
                          :inactive-value="0"
                          size="small"
                          disabled
                        />
                      </span>
                    </el-tooltip>
                    <el-switch
                      v-else
                      v-model="row.isPrimary"
                      :active-value="1"
                      :inactive-value="0"
                      size="small"
                      :disabled="isAggregateTable(tabStates[tab.id].table)"
                    />
                  </template>
                  <template v-else>
                    <el-tag v-if="row.isPrimary" type="info" size="small">是</el-tag>
                    <span v-else>-</span>
                  </template>
                </template>
              </el-table-column>
              <el-table-column label="默认值" width="120">
                <template #default="{ row }">
                  <el-input
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.defaultValue"
                    size="small"
                    placeholder="可选"
                    :disabled="isAggregateTable(tabStates[tab.id].table)"
                  />
                  <span v-else>{{ row.defaultValue || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="注释" min-width="150">
                <template #default="{ row }">
                  <el-input
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.fieldComment"
                    size="small"
                    placeholder="字段注释"
                  />
                  <span v-else>{{ row.fieldComment || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="tabStates[tab.id].fieldsEditing" label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-tooltip
                    v-if="isAggregateTable(tabStates[tab.id].table)"
                    content="AGGREGATE 表不支持新增字段"
                    placement="top"
                  >
                    <span>
                      <el-button link type="primary" size="small" disabled>新增</el-button>
                    </span>
                  </el-tooltip>
                  <el-button
                    v-else
                    link
                    type="primary"
                    size="small"
                    @click="addField(tab.id, row)"
                  >
                    新增
                  </el-button>
                  <el-popconfirm
                    width="240"
                    confirm-button-text="确定"
                    cancel-button-text="取消"
                    :title="`确定删除字段「${row.fieldName || '未命名'}」吗？`"
                    @confirm="removeField(tab.id, row)"
                  >
                    <template #reference>
                      <el-tooltip
                        v-if="isAggregateTable(tabStates[tab.id].table)"
                        content="AGGREGATE 表不支持删除字段"
                        placement="top"
                      >
                        <span>
                          <el-button link type="danger" size="small" disabled>删除</el-button>
                        </span>
                      </el-tooltip>
                      <el-button v-else link type="danger" size="small">删除</el-button>
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-empty v-else description="暂无字段" :image-size="60">
            <template #default>
              <el-button
                v-if="tabStates[tab.id].fieldsEditing"
                type="primary"
                size="small"
                @click="addField(tab.id)"
                :disabled="isAggregateTable(tabStates[tab.id].table)"
              >
                新增字段
              </el-button>
            </template>
          </el-empty>
        </div>
      </el-tab-pane>

                      <el-tab-pane name="ddl" label="DDL">
                        <div class="meta-section meta-section-fill" v-loading="tabStates[tab.id].ddlLoading">
                          <div class="ddl-header">
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
                            placeholder="加载中或暂无 DDL"
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
	                          <div class="task-title-row">
	                            <div class="task-title">写入任务 ({{ tabStates[tab.id].tasks.writeTasks.length }})</div>
	                            <el-button
	                              type="primary"
	                              size="small"
	                              plain
	                              :disabled="!tabStates[tab.id].table?.id"
	                              @click.stop="goCreateRelatedTask(tab.id, 'write')"
	                            >
	                              <el-icon><Plus /></el-icon>
	                              新增写入任务
	                            </el-button>
	                          </div>
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
	                          <div class="task-title-row">
	                            <div class="task-title">读取任务 ({{ tabStates[tab.id].tasks.readTasks.length }})</div>
	                            <el-button
	                              type="primary"
	                              size="small"
	                              plain
	                              :disabled="!tabStates[tab.id].table?.id"
	                              @click.stop="goCreateRelatedTask(tab.id, 'read')"
	                            >
	                              <el-icon><Plus /></el-icon>
	                              新增读取任务
	                            </el-button>
	                          </div>
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
                </Teleport>
                -->
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
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Coin,
  Search,
  Clock,
  Delete,
  Plus,
  CaretRight,
  Document,
  Grid,
  Loading,
  Refresh,
  List,
  TrendCharts,
  Timer,
  Files,
  View,
  VideoPause,
  Warning
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import { lineageApi } from '@/api/lineage'
import PersistentTabs from '@/components/PersistentTabs.vue'
import TaskEditDrawer from '@/views/tasks/TaskEditDrawer.vue'
import DataStudioResultGrid from '@/views/datastudio/components/DataStudioResultGrid.vue'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import { copyText } from '@/utils/clipboard'
import {
  formatNumber,
  formatRowCount,
  formatStorageSize,
  formatDuration,
  formatDateTime,
  isAggregateTable,
} from './tableFormat'
import { useTabPersistence } from './composables/useTabPersistence'
import { useResizablePanes } from './composables/useResizablePanes'
import { useSqlCompletion } from './composables/useSqlCompletion'
import { useTabRouting } from './composables/useTabRouting'
import { useCatalogTree } from './composables/useCatalogTree'
import { useQueryExecution } from './composables/useQueryExecution'
import { useResultChart } from './composables/useResultChart'
import { useTableMetaEditing } from './composables/useTableMetaEditing'

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

const isReplicaWarning = (value) => {
  if (value === null || value === undefined || value === '') return false
  const num = Number(value)
  return Number.isFinite(num) && num > 0 && num < 3
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

	const createTabState = (table) => {
	  return reactive({
	    table: { ...table },
		    query: {
		      sql: buildDefaultSql(table),
		      limit: 200,
		      hasSelection: false,
		      selectionText: ''
		    },
		    queryLoading: false,
		    queryStopping: false,
		    queryCancelable: false,
		    queryAbortController: null,
		    queryRunId: 0,
		    queryTiming: {
		      startedAt: 0,
		      elapsedMs: 0
		    },
		    queryResult: {
	      resultSets: [],
	      columns: [],
	      rows: [],
      hasMore: false,
	      durationMs: 0,
	      executedAt: '',
	      cancelled: false,
	      statementInfos: [],
	      message: '',
	      errorMessage: ''
		    },
				    resultTab: 'result-0',
			    resultViewTabs: ['table'],
			    page: {
			      current: 1,
			      size: 20
			    },
    charts: [
      {
        type: 'bar',
        xAxis: '',
        yAxis: []
      }
    ],
    metaTab: 'basic',
    metaEditing: false,
    metaSaving: false,
    metaForm: {
      tableName: table.tableName || '',
      tableComment: table.tableComment || '',
      layer: table.layer || '',
      businessDomain: table.businessDomain || '',
      dataDomain: table.dataDomain || '',
      owner: table.owner || '',
      bucketNum: table.bucketNum ?? '',
      replicaNum: table.replicaNum ?? ''
    },
    metaOriginal: {},
    metaDataDomainOptions: [],
    metadataSyncing: false,
    fieldSubmitting: false,
    fieldsEditing: false,
    fieldsDraft: [],
    fieldsRemoved: [],
    fields: [],
    ddl: '',
    ddlLoading: false,
    accessLoading: false,
    accessStats: null,
    accessError: '',
    lineage: { upstreamTables: [], downstreamTables: [] },
    tasks: { writeTasks: [], readTasks: [] },
    dataLoading: false,
    dataLoaded: false
  })
}

const openTableTab = async (table, dbFallback = '', sourceFallback = '') => {
  if (!table) return
  let payload = table
  const tableId = payload?.id
  const hasSource = !!(payload?.sourceId || payload?.clusterId || sourceFallback)
  const hasDb = !!(payload?.dbName || payload?.databaseName || payload?.database || dbFallback)
  if (tableId && (!hasSource || !hasDb)) {
    const cached = findCachedTableById(tableId)
    if (cached) {
      payload = { ...cached, ...payload, id: tableId, dbName: cached.dbName, sourceId: cached.sourceId }
    } else {
      try {
        const tableInfo = await tableApi.getById(tableId)
        if (tableInfo) {
          const resolvedSourceId = String(
            payload?.sourceId || payload?.clusterId || tableInfo.clusterId || sourceFallback || ''
          )
          const resolvedDb =
            tableInfo.dbName ||
            payload?.dbName ||
            payload?.databaseName ||
            payload?.database ||
            dbFallback ||
            ''
          payload = {
            ...tableInfo,
            ...payload,
            id: tableId,
            dbName: resolvedDb,
            sourceId: resolvedSourceId
          }
        }
      } catch (error) {
        console.error('加载血缘表信息失败', error)
      }
    }
  }

  const sourceId = String(payload.sourceId || payload.clusterId || sourceFallback || '')
  if (sourceId) {
    clusterId.value = sourceId
  }
  const resolvedSourceType = String(payload.sourceType || getDatasourceById(sourceId)?.sourceType || '').toUpperCase()
  const resolvedDb = payload.dbName || payload.databaseName || payload.database || dbFallback || ''
  payload = { ...payload, dbName: resolvedDb, sourceId, sourceType: resolvedSourceType }
  const key = getTableKey(payload, resolvedDb, sourceId)
  if (!key) return

  selectedTableKey.value = key

  const existing = openTabs.value.find((item) => String(item.id) === String(key))
  if (existing) {
    activeTab.value = String(existing.id)
    const state = tabStates[String(existing.id)]
    if (state) {
      state.table = { ...state.table, ...payload }
      syncAutoSelectSqlIfSchemaMismatch(state)
    }
    if (existing.kind !== 'query') {
      existing.sourceId = sourceId || existing.sourceId
      existing.sourceType = resolvedSourceType || existing.sourceType
      existing.dbName = resolvedDb || existing.dbName
      existing.tableName = payload.tableName || existing.tableName
    }
    await focusTableInSidebar(payload, key, resolvedDb, sourceId)
    return
  }

  const existingById = tableId
    ? openTabs.value.find((item) => {
        if (!item || item.kind === 'query') return false
        const state = tabStates[String(item.id)]
        return state?.table?.id && String(state.table.id) === String(tableId)
      })
    : null
  if (existingById) {
    activeTab.value = String(existingById.id)
    const state = tabStates[String(existingById.id)]
    if (state) {
      state.table = { ...state.table, ...payload }
      syncAutoSelectSqlIfSchemaMismatch(state)
    }
    existingById.sourceId = sourceId || existingById.sourceId
    existingById.sourceType = resolvedSourceType || existingById.sourceType
    existingById.dbName = resolvedDb || existingById.dbName
    existingById.tableName = payload.tableName || existingById.tableName
    selectedTableKey.value = key
    await focusTableInSidebar(payload, key, resolvedDb, sourceId)
    return
  }

  const tabItem = {
    id: key,
    kind: 'table',
    tableName: payload.tableName,
    dbName: resolvedDb,
    sourceId,
    sourceType: resolvedSourceType
  }
  tabStates[key] = createTabState({ ...payload, dbName: resolvedDb, sourceId })
  openTabs.value.push(tabItem)
  activeTab.value = key

  await focusTableInSidebar(payload, key, resolvedDb, sourceId)
  await loadTabData(key)
}

const loadTabData = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table) return
  if (state.dataLoading || state.dataLoaded) return

  state.dataLoading = true
  if (!state.table.id && state.table.dbName && state.table.tableName) {
    try {
      const sourceId = state.table.sourceId || clusterId.value
      const options = await tableApi.searchOptions({
        keyword: state.table.tableName,
        limit: 20,
        dbName: state.table.dbName,
        clusterId: sourceId || undefined
      })
      const match = (options || []).find((item) => item.tableName === state.table.tableName)
      if (match?.id) {
        state.table.id = match.id
        state.table.tableComment = state.table.tableComment || match.tableComment
        state.table.layer = state.table.layer || match.layer
        state.table.metadataMissing = false
        state.table.metadataStatus = 'synced'
      }
    } catch (error) {
      console.error('解析表元数据失败', error)
    }
  }
  if (!state.table.id) {
    state.metaForm = {
      tableName: state.table.tableName || '',
      tableComment: state.table.tableComment || '',
      layer: state.table.layer || '',
      businessDomain: state.table.businessDomain || '',
      dataDomain: state.table.dataDomain || '',
      owner: state.table.owner || '',
      bucketNum: state.table.bucketNum ?? '',
      replicaNum: state.table.replicaNum ?? ''
    }
    state.metaOriginal = { ...state.metaForm }
    state.metaDataDomainOptions = []
    if (state.metaForm.businessDomain) {
      await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
    }
    state.fields = []
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
    state.lineage = {
      upstreamTables: [],
      downstreamTables: [],
      edges: []
    }
    state.tasks = {
      writeTasks: [],
      readTasks: []
    }
    state.accessLoading = false
    state.accessStats = null
    state.accessError = ''
    if (state.query.sql === '') {
      state.query.sql = buildDefaultSql(state.table)
    }
    state.dataLoaded = true
    state.dataLoading = false
    return
  }
  try {
    const [tableInfo, fieldList, lineageData, tasksData, lineageGraphData] = await Promise.all([
      tableApi.getById(state.table.id),
      tableApi.getFields(state.table.id),
      tableApi.getLineage(state.table.id),
      tableApi.getTasks(state.table.id),
      lineageApi.getLineageGraph({ tableId: state.table.id, depth: 1 }).catch(() => null)
    ])
    state.table = { ...state.table, ...tableInfo }
    state.metaForm = {
      tableName: state.table.tableName || '',
      tableComment: state.table.tableComment || '',
      layer: state.table.layer || '',
      businessDomain: state.table.businessDomain || '',
      dataDomain: state.table.dataDomain || '',
      owner: state.table.owner || '',
      bucketNum: state.table.bucketNum ?? '',
      replicaNum: state.table.replicaNum ?? ''
    }
    state.metaDataDomainOptions = []
    if (state.metaForm.businessDomain) {
      await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
    }
    state.metaOriginal = { ...state.metaForm }
    state.fields = Array.isArray(fieldList) ? fieldList : []
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
    state.lineage = {
      upstreamTables: lineageData?.upstreamTables || [],
      downstreamTables: lineageData?.downstreamTables || [],
      edges: lineageGraphData?.edges || []
    }
    lineageCache[state.table.id] = state.lineage
    state.tasks = {
      writeTasks: Array.isArray(tasksData?.writeTasks) ? tasksData.writeTasks : [],
      readTasks: Array.isArray(tasksData?.readTasks) ? tasksData.readTasks : []
    }
    state.accessLoading = false
    state.accessStats = null
    state.accessError = ''
    if (state.query.sql === '') {
      state.query.sql = buildDefaultSql(state.table)
    }
    state.dataLoaded = true
  } catch (error) {
    console.error('加载表详情失败', error)
    state.dataLoaded = false
  } finally {
    state.dataLoading = false
  }
}

const hydrateRestoredTableTabs = () => {
  const tableTabIds = openTabs.value
    .filter((tab) => tab?.kind === 'table')
    .map((tab) => String(tab.id || ''))
    .filter(Boolean)
  if (!tableTabIds.length) return
  void Promise.allSettled(tableTabIds.map((tabId) => loadTabData(tabId)))
}

	const disposeTabResources = (tabId) => {
	  const id = String(tabId || '')
	  if (!id) return
	  clearQueryTimer(id)
	  disposeChart(id)
  if (leftPaneRefs.value?.[id]) {
    delete leftPaneRefs.value[id]
  }
	  if (leftPaneHeights[id] !== undefined) {
	    delete leftPaneHeights[id]
	  }
	  delete tabStates[id]
	  stopNowTickerIfIdle()
	}

const handleTabRemove = (name) => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(name))
  if (idx === -1) return
  const removed = openTabs.value.splice(idx, 1)[0]
  if (removed) {
    disposeTabResources(removed.id)
  }
  if (openTabs.value.length) {
    activeTab.value = String(openTabs.value[Math.max(idx - 1, 0)].id)
  } else {
    activeTab.value = ''
  }
}

const handleCloseLeft = (tabKey) => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(tabKey))
  if (idx <= 0) return
  const removed = openTabs.value.splice(0, idx)
  removed.forEach((tab) => disposeTabResources(tab.id))
  const stillActive = openTabs.value.some((tab) => String(tab.id) === String(activeTab.value))
  if (!stillActive) {
    activeTab.value = String(tabKey)
  }
}

const handleCloseRight = (tabKey) => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(tabKey))
  if (idx === -1 || idx >= openTabs.value.length - 1) return
  const removed = openTabs.value.splice(idx + 1)
  removed.forEach((tab) => disposeTabResources(tab.id))
  const stillActive = openTabs.value.some((tab) => String(tab.id) === String(activeTab.value))
  if (!stillActive) {
    activeTab.value = String(tabKey)
  }
}

const handleCloseAll = () => {
  const removed = openTabs.value.splice(0)
  removed.forEach((tab) => disposeTabResources(tab.id))
  activeTab.value = ''
}

const resolveQuerySourceId = () => {
  const current = openTabs.value.find((tab) => String(tab.id) === String(activeTab.value))
  if (current?.sourceId) return String(current.sourceId)
  return ''
}

const resolveQueryDatabase = (sourceId) => {
  const sid = String(sourceId || '')
  if (!sid) return ''
  const current = openTabs.value.find((tab) => String(tab.id) === String(activeTab.value))
  if (current?.dbName) return String(current.dbName)
  return ''
}

const getTabInsertIndex = () => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(activeTab.value))
  return idx === -1 ? openTabs.value.length : idx + 1
}

const handleTabAdd = async () => {
  const sourceId = resolveQuerySourceId()
  let dbName = resolveQueryDatabase(sourceId)
  if (sourceId) {
    await loadSchemas(sourceId)
    if (dbName && !getSchemaOptions(sourceId).includes(dbName)) {
      dbName = ''
    }
  }

  const queryId = `query:${Date.now()}`
  const tabItem = {
    id: queryId,
    kind: 'query',
    tableName: '无标题 - 查询',
    dbName,
    sourceId
  }
  tabStates[queryId] = createTabState({ tableName: '', dbName, sourceId })
  tabStates[queryId].query.sql = ''
  openTabs.value.splice(getTabInsertIndex(), 0, tabItem)
  activeTab.value = queryId
}

const getTabItemById = (tabId) => {
  return openTabs.value.find((tab) => String(tab.id) === String(tabId)) || null
}

const saveAsTask = (tabId) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存查询任务')
    return
  }
  const state = tabStates[tabId]
  if (!state?.query?.sql?.trim()) {
    ElMessage.warning('请先输入 SQL')
    return
  }
  const sourceId = state?.table?.sourceId || clusterId.value || ''
  taskDrawerRef.value?.open(null, {
    taskSql: state.query.sql,
    taskName: `新建查询任务_${Date.now()}`,
    taskDesc: `From DataStudio\nCluster: ${sourceId}\nDatabase: ${state.table.dbName || ''}`
  })
}

const handleCreateTable = () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('新建表')
    return
  }
  createDrawerVisible.value = true
}

const handleDeleteTable = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('删除表')
    return
  }
  const active = activeTab.value
  const state = active ? tabStates[active] : null
  const table = state?.table
  if (warnPlatformMetadataMissing(table)) {
    return
  }
  if (!table?.id) {
    ElMessage.warning('请先选择要删除的表')
    return
  }
  const dorisTable = isDorisTable(table)
  if (dorisTable && !clusterId.value) {
    ElMessage.warning('请选择 Doris 集群')
    return
  }

  try {
    const rawName = String(table.tableName || '').trim()
    const expectedName = dorisTable ? (rawName.includes('.') ? rawName.split('.').pop() : rawName) : rawName
    const message = dorisTable
      ? `确定要删除表 “${table.tableName}” 吗？删除后将重命名为 deprecated_时间戳，数据不会丢失。`
      : `确定要删除表 “${table.tableName}” 吗？将仅删除平台元数据记录。`
    const { value } = await ElMessageBox.prompt(
      `${message}\n请输入表名以确认：${expectedName}`,
      '删除表确认',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        inputPlaceholder: expectedName,
        inputValidator: (input) => {
          if (String(input || '').trim() !== expectedName) {
            return `请输入正确表名：${expectedName}`
          }
          return true
        }
      }
    )
    const confirmTableName = String(value || '').trim()
    if (dorisTable) {
      await tableApi.softDelete(table.id, clusterId.value || null, confirmTableName)
    } else {
      await tableApi.delete(table.id, confirmTableName)
    }
    ElMessage.success('删除表成功')
    const dbName = table.dbName || table.databaseName || table.database
    if (dbName) {
      const sourceId = table.sourceId || clusterId.value
      if (sourceId) {
        await loadTables(sourceId, dbName, true)
      }
    }
    handleTabRemove(active)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('删除表失败')
    }
  }
}

const syncMissingTableMetadata = async (tabId) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('同步平台元数据')
    return
  }
  const state = tabStates[String(tabId || '')]
  const table = state?.table
  if (!isPlatformMetadataMissing(table)) return
  const sourceId = String(table.sourceId || table.clusterId || clusterId.value || '')
  const dbName = table.dbName || table.databaseName || table.database || ''
  const tableName = table.tableName || ''
  if (!sourceId || !dbName || !tableName) {
    ElMessage.warning('缺少数据源、数据库或表名，无法同步')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定将 “${dbName}.${tableName}” 同步到平台元数据吗？`,
      '同步平台元数据',
      {
        type: 'warning',
        confirmButtonText: '立即同步',
        cancelButtonText: '取消'
      }
    )
  } catch (error) {
    return
  }

  state.metadataSyncing = true
  try {
    const response = await tableApi.syncTableMetadataByName(dbName, tableName, sourceId)
    await loadTables(sourceId, dbName, true)
    const list = tableStore[String(sourceId)]?.[dbName] || []
    const synced = list.find((item) => {
      if (!item) return false
      if (response?.tableId && String(item.id) === String(response.tableId)) return true
      return item.tableName === tableName
    })
    const syncedTable = synced || {
      ...table,
      id: response?.tableId || table.id,
      metadataMissing: false,
      metadataStatus: 'synced'
    }
    state.table = {
      ...state.table,
      ...syncedTable,
      sourceId,
      dbName,
      metadataMissing: false,
      metadataStatus: 'synced'
    }
    const tab = openTabs.value.find((item) => String(item.id) === String(tabId))
    if (tab) {
      tab.sourceId = sourceId
      tab.dbName = dbName
      tab.tableName = tableName
    }
    state.dataLoaded = false
    await loadTabData(String(tabId))
    ElMessage.success('平台元数据已同步')
  } catch (error) {
    ElMessage.error('同步平台元数据失败')
  } finally {
    state.metadataSyncing = false
  }
}

const handleCreateSuccess = async (result) => {
  createDrawerVisible.value = false
  await loadClusters()
  const tableId = result?.id || result?.tableId
  if (!tableId) return
  try {
    const table = await tableApi.getById(tableId)
    const dbName = table?.dbName || table?.databaseName || table?.database || ''
    if (dbName) {
      if (table?.sourceId || clusterId.value) {
        const sourceId = table.sourceId || clusterId.value
        await loadTables(sourceId, dbName, true)
      }
    }
    await openTableTab(table, dbName, table?.sourceId || clusterId.value)
  } catch (error) {
    console.error('加载新建表失败', error)
  }
}

const loadDdl = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table) return
  const sourceId = state.table.sourceId || clusterId.value
  if (!sourceId) {
    ElMessage.warning('请选择数据源')
    return
  }
  const dbName = state.table.dbName || state.table.databaseName || state.table.database || ''
  const tableName = state.table.tableName || ''
  if (!dbName || !tableName) {
    ElMessage.warning('缺少数据库或表名')
    return
  }
  state.ddlLoading = true
  try {
    const ddl = state.table.id
      ? await tableApi.getTableDdl(state.table.id, sourceId || null)
      : await tableApi.getTableDdlByName(sourceId || null, dbName, tableName)
    state.ddl = ddl || ''
  } catch (error) {
    ElMessage.error('加载 DDL 失败')
  } finally {
    state.ddlLoading = false
  }
}

const loadAccessStats = async (tabId, force = false) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  if (state.accessLoading) return
  if (!force && state.accessStats) return
  const sourceId = state.table.sourceId || clusterId.value || state.table.clusterId
  if (!sourceId) {
    state.accessError = '缺少集群信息，无法获取访问统计'
    state.accessStats = null
    return
  }

  state.accessLoading = true
  state.accessError = ''
  try {
    const stats = await tableApi.getAccessStats(state.table.id, {
      clusterId: sourceId,
      recentDays: 30,
      trendDays: 14,
      topUsers: 5
    })
    state.accessStats = stats || null
  } catch (error) {
    state.accessStats = null
    state.accessError = error?.message || '加载访问统计失败'
  } finally {
    state.accessLoading = false
  }
}

const copyDdl = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.ddl) return
  try {
    await copyText(state.ddl)
    ElMessage.success('已复制')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

const openTask = (taskId) => {
  if (!taskId) return
  if (isDemoMode) {
    showDemoReadonlyMessage('任务详情')
    return
  }
  taskDrawerRef.value?.open(taskId)
}

const goCreateRelatedTask = (tabId, relation) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('新增关联任务')
    return
  }
  const state = tabStates[String(tabId || '')]
  if (warnPlatformMetadataMissing(state?.table)) return
  const tableId = state?.table?.id
  if (!tableId) {
    ElMessage.warning('请先选择表')
    return
  }
  taskDrawerRef.value?.open(null, { relation, tableId })
}

const handleTaskSuccess = async () => {
  const id = String(activeTab.value || '')
  const tab = getTabItemById(id)
  if (tab?.kind !== 'table') return
  if (tabStates[id]) {
    tabStates[id].dataLoaded = false
  }
  await loadTabData(id)
}

const goLineage = (tabId) => {
  const state = tabStates[tabId]
  if (warnPlatformMetadataMissing(state?.table)) return
  if (!state?.table?.id) return
  router.push({ path: '/lineage', query: { tableId: state.table.id } })
}

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
  getLayerType,
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

.tab-left,
.tab-right {
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

.tab-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
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

.meta-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
}

.meta-table {
  flex: 1;
  min-height: 0;
}

.meta-descriptions :deep(.el-descriptions__content) {
  width: 100%;
}

.meta-input {
  width: 100%;
}

.replica-edit {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.replica-warning {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #ef4444;
}

.replica-value {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.replica-danger {
  color: #ef4444;
  font-weight: 600;
}

.warning-icon {
  font-size: 12px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  color: #1f2f3d;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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

	.task-title-row {
	  display: flex;
	  align-items: center;
	  justify-content: space-between;
	  gap: 8px;
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

  .lineage-grid {
    grid-template-columns: 1fr;
  }
}
</style>
