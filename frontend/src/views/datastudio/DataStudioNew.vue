<template>
  <div :class="['data-studio', { 'is-resizing': isResizing }]">
    <div class="studio-layout">
      <!-- Left: Database Tree -->
      <aside class="studio-sidebar" :style="{ width: `${sidebarWidth}px` }">
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
                  <el-icon v-else class="node-icon table"><Grid /></el-icon>

	                  <div v-if="data.type === 'table'" class="table-main">
	                    <div class="table-title">
	                      <span class="table-name" :title="data.table?.tableName">
	                        {{ data.table?.tableName }}
	                      </span>
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
                          { 'is-disabled': dbLoading || tableLoading[`${String(data.sourceId)}::${data.schemaName}`] }
                        ]"
                        @click.stop="refreshSchemaNode(data)"
                      >
                        <Refresh />
                      </el-icon>
                    </el-tooltip>
                    <el-icon v-if="tableLoading[`${String(data.sourceId)}::${data.schemaName}`]" class="is-loading loading-icon">
                      <Loading />
                    </el-icon>
                  </div>

                  <div v-else class="table-meta-tags">
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
        </div>
      </aside>

      <div class="sidebar-resizer" @mousedown="startResize"></div>

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
              <div class="tab-grid" :class="{ 'is-single': tab.kind === 'query' }">
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
                          <div
                            class="meta-info"
                            v-if="tabStates[tab.id].queryLoading || tabStates[tab.id].queryResult.executedAt"
                          >
                            <span class="meta-item">
                              <el-icon><Timer /></el-icon>
                              {{
                                formatDuration(
                                  tabStates[tab.id].queryLoading
                                    ? tabStates[tab.id].queryTiming.elapsedMs
                                    : tabStates[tab.id].queryResult.durationMs
                                )
                              }}
                            </span>
                            <span v-if="tabStates[tab.id].queryLoading" class="meta-item">
                              <el-icon><Loading /></el-icon> 查询中
                            </span>
                            <template v-else>
                              <span class="meta-item"><el-icon><Files /></el-icon> {{ tabStates[tab.id].queryResult.rows.length }} 行</span>
                              <span v-if="tabStates[tab.id].queryResult.hasMore" class="meta-item truncate">
                                <el-icon><Warning /></el-icon> 结果已截断
                              </span>
                            </template>
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

                <!-- Right 40% -->
                <div v-if="tab.kind !== 'query'" class="tab-right">
                  <div class="meta-panel">
                    <el-tabs v-model="tabStates[tab.id].metaTab" class="meta-tabs">
                      <el-tab-pane name="basic" label="基本信息">
                        <div class="meta-section">
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
              <el-table-column label="字段名" width="170">
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
                              <el-table-column label="长度" width="70">
                                <template #default="{ row }">
                                  <span>{{ getVarcharLength(row) }}</span>
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
    </div>

    <CreateTableDrawer v-model="createDrawerVisible" @created="handleCreateSuccess" />
    <TaskEditDrawer ref="taskDrawerRef" />

  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import {
  Coin,
  Search,
  Clock,
  Delete,
  Plus,
  Document,
  Grid,
  Loading,
  Refresh,
  List,
  TrendCharts,
  Timer,
  Files,
  Warning
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import { dorisClusterApi } from '@/api/doris'
import { dataQueryApi } from '@/api/query'
import PersistentTabs from '@/components/PersistentTabs.vue'
import CreateTableDrawer from '@/views/datastudio/CreateTableDrawer.vue'
import TaskEditDrawer from '@/views/tasks/TaskEditDrawer.vue'

const clusterId = ref(null)
const route = useRoute()
const router = useRouter()
const sidebarWidth = ref(540)
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
const dataSources = ref([])
const activeSource = ref('')
const schemaStore = reactive({})
const schemaLoading = reactive({})
const activeSchema = reactive({})
const tableLoading = reactive({})
const tableStore = reactive({})
const lineageCache = reactive({})
const activatedSources = reactive({})
const datasourceActivationTasks = new Map()

const catalogTreeRef = ref(null)
const catalogTreeProps = {
  children: 'children',
  label: 'name',
  isLeaf: 'leaf'
}

const getDatasourceNodeKey = (sourceId) => `ds:${String(sourceId)}`
const getSchemaNodeKey = (sourceId, schemaName) => `schema:${String(sourceId)}::${schemaName}`

const catalogRoots = computed(() => {
  const list = Array.isArray(dataSources.value) ? dataSources.value : []
  return list.map((source) => ({
    nodeKey: getDatasourceNodeKey(source.id),
    type: 'datasource',
    name: source.clusterName || source.name || `DataSource ${source.id}`,
    sourceId: String(source.id),
    sourceType: source.sourceType,
    status: source.status,
    leaf: false
  }))
})

const searchKeyword = ref('')
const sortField = ref('tableName')
const sortOrder = ref('asc')

const selectedTableKey = ref('')
const suppressRouteSync = ref(false)

const openTabs = ref([])
const activeTab = ref('')
const tabStates = reactive({})
const queryTimerHandles = new Map()
const queryTabCounter = ref(1)

const TAB_PERSIST_KEY = 'odw:datastudio:workspace-tabs:v1'
const isRestoringTabs = ref(false)
let persistTabsTimer = null

const tabsPersistSnapshot = computed(() => {
  const tabs = (Array.isArray(openTabs.value) ? openTabs.value : []).map((tab) => {
    const id = String(tab?.id ?? '')
    const state = id ? tabStates[id] : null
    return {
      id,
      kind: tab?.kind === 'query' ? 'query' : 'table',
      tableName: tab?.tableName || '',
      dbName: tab?.dbName || state?.table?.dbName || '',
      sourceId: tab?.sourceId || state?.table?.sourceId || '',
      tableId: state?.table?.id || null,
      sql: state?.query?.sql ?? '',
      limit: Number(state?.query?.limit ?? 200)
    }
  })
  return {
    version: 1,
    activeTab: String(activeTab.value || ''),
    tabs
  }
})

const persistTabsNow = (snapshot) => {
  try {
    const tabs = snapshot?.tabs || []
    if (!Array.isArray(tabs) || tabs.length === 0) {
      localStorage.removeItem(TAB_PERSIST_KEY)
      return
    }
    localStorage.setItem(TAB_PERSIST_KEY, JSON.stringify(snapshot))
  } catch (error) {
    console.warn('保存工作区 Tab 状态失败', error)
  }
}

const schedulePersistTabs = (snapshot) => {
  if (persistTabsTimer) {
    clearTimeout(persistTabsTimer)
  }
  persistTabsTimer = setTimeout(() => {
    persistTabsTimer = null
    persistTabsNow(snapshot)
  }, 250)
}

const flushPersistTabs = () => {
  if (persistTabsTimer) {
    clearTimeout(persistTabsTimer)
    persistTabsTimer = null
  }
  persistTabsNow(tabsPersistSnapshot.value)
}

const restoreTabsFromStorage = () => {
  let parsed = null
  try {
    const raw = localStorage.getItem(TAB_PERSIST_KEY)
    if (!raw) return false
    parsed = JSON.parse(raw)
  } catch (error) {
    console.warn('读取工作区 Tab 状态失败', error)
    return false
  }

  if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.tabs)) return false

  isRestoringTabs.value = true
  try {
    const nextTabs = []
    const existingKeys = Object.keys(tabStates)
    existingKeys.forEach((key) => delete tabStates[key])

    parsed.tabs.forEach((item) => {
      const id = String(item?.id ?? '')
      if (!id) return
      const kind = item?.kind === 'query' ? 'query' : 'table'
      const tabItem = {
        id,
        kind,
        tableName: String(item?.tableName ?? ''),
        dbName: String(item?.dbName ?? ''),
        sourceId: String(item?.sourceId ?? '')
      }

      const tablePayload =
        kind === 'query'
          ? { tableName: '', dbName: tabItem.dbName, sourceId: tabItem.sourceId }
          : { id: item?.tableId || undefined, tableName: tabItem.tableName, dbName: tabItem.dbName, sourceId: tabItem.sourceId }

      tabStates[id] = createTabState(tablePayload)
      if (typeof item?.sql === 'string') {
        tabStates[id].query.sql = item.sql
      }
      if (Number.isFinite(Number(item?.limit))) {
        tabStates[id].query.limit = Number(item.limit)
      }

      nextTabs.push(tabItem)
    })

    openTabs.value = nextTabs

    const active = String(parsed?.activeTab ?? '')
    const activeExists = active && nextTabs.some((tab) => String(tab.id) === active)
    activeTab.value = activeExists ? active : (nextTabs[0] ? String(nextTabs[0].id) : '')

    const maxQueryIndex = nextTabs
      .filter((tab) => tab.kind === 'query')
      .map((tab) => {
        const match = String(tab.tableName || '').match(/(\d+)$/)
        return match ? Number(match[1]) : 0
      })
      .reduce((max, val) => (Number.isFinite(val) ? Math.max(max, val) : max), 0)
    queryTabCounter.value = maxQueryIndex ? maxQueryIndex + 1 : 1

    return true
  } finally {
    isRestoringTabs.value = false
  }
}

watch(
  tabsPersistSnapshot,
  (snapshot) => {
    if (isRestoringTabs.value) return
    schedulePersistTabs(snapshot)
  },
  { deep: true }
)

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

const clearQueryTimer = (tabId) => {
  const handle = queryTimerHandles.get(tabId)
  if (!handle) return
  clearInterval(handle)
  queryTimerHandles.delete(tabId)
}

const startQueryTimer = (tabId) => {
  clearQueryTimer(tabId)
  const state = tabStates[tabId]
  if (!state) return
  state.queryTiming.startedAt = Date.now()
  state.queryTiming.elapsedMs = 0
  const handle = setInterval(() => {
    const current = tabStates[tabId]
    if (!current?.queryLoading) {
      clearQueryTimer(tabId)
      return
    }
    current.queryTiming.elapsedMs = Date.now() - current.queryTiming.startedAt
  }, 200)
  queryTimerHandles.set(tabId, handle)
}

const loadClusters = async () => {
  dbLoading.value = true
  try {
    const clusters = await dorisClusterApi.list()
    dataSources.value = Array.isArray(clusters) ? clusters : []
    if (!clusterId.value && dataSources.value.length) {
      const defaultCluster =
        dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0]
      clusterId.value = defaultCluster?.id || null
    }
    if (!activeSource.value && dataSources.value.length) {
      const defaultSource =
        dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0]
      activeSource.value = defaultSource?.id ? String(defaultSource.id) : ''
      if (activeSource.value) {
        const ok = await loadSchemas(activeSource.value)
        if (ok) {
          await nextTick()
          await ensureCatalogPathExpanded(activeSource.value, activeSchema[String(activeSource.value)])
        }
      }
    }
  } catch (error) {
    ElMessage.error('加载数据源失败')
  } finally {
    dbLoading.value = false
  }
}

const getDatasourceById = (sourceId) => {
  const id = String(sourceId || '')
  const list = Array.isArray(dataSources.value) ? dataSources.value : []
  return list.find((item) => String(item.id) === id) || null
}

const activateDatasource = async (sourceId) => {
  if (!sourceId) return false
  const key = String(sourceId)
  const source = getDatasourceById(key)
  if (source?.status && source.status !== 'active') {
    ElMessage.warning('数据源已停用')
    return false
  }
  if (activatedSources[key]) return true
  if (datasourceActivationTasks.has(key)) {
    return datasourceActivationTasks.get(key)
  }

  const task = (async () => {
    try {
      await dorisClusterApi.testConnection(sourceId)
      activatedSources[key] = true
      return true
    } catch (error) {
      activatedSources[key] = false
      ElMessage.error('数据源连接失败')
      return false
    } finally {
      datasourceActivationTasks.delete(key)
    }
  })()

  datasourceActivationTasks.set(key, task)
  return task
}

const loadSchemas = async (sourceId, force = false) => {
  if (!sourceId) return false
  const key = String(sourceId)
  if (schemaStore[key] && !force) {
    activatedSources[key] = true
    return true
  }
  schemaLoading[key] = true
  try {
    const activated = await activateDatasource(sourceId)
    if (!activated) return false
    const schemas = await dorisClusterApi.getDatabases(sourceId)
    schemaStore[key] = Array.isArray(schemas) ? schemas : []
    activatedSources[key] = true
    refreshDatasourceChildrenInTree(sourceId)
    if (!activeSchema[key] && schemaStore[key].length) {
      activeSchema[key] = schemaStore[key][0]
      await loadTables(sourceId, activeSchema[key])
    }
    return true
  } catch (error) {
    ElMessage.error('加载数据库列表失败')
    return false
  } finally {
    schemaLoading[key] = false
  }
}

const loadTables = async (sourceId, database, force = false) => {
  if (!sourceId || !database) return false
  const sourceKey = String(sourceId)
  tableStore[sourceKey] = tableStore[sourceKey] || {}
  if (tableStore[sourceKey][database] && !force) return true
  const loadingKey = `${sourceKey}::${database}`
  tableLoading[loadingKey] = true
  try {
    const activated = await activateDatasource(sourceId)
    if (!activated) return false
    const [tables, metaTables] = await Promise.all([
      dorisClusterApi.getTables(sourceId, database),
      tableApi.listByDatabase(database, sortField.value, sortOrder.value, sourceId).catch(() => [])
    ])
    const metaList = Array.isArray(metaTables) ? metaTables : []
    const metaMap = new Map(metaList.map((item) => [item.tableName, item]))
    const list = (Array.isArray(tables) ? tables : []).map((item) => {
      const tableName = item.tableName || item.TABLE_NAME || ''
      const base = {
        ...item,
        sourceId: sourceKey,
        dbName: database,
        tableName,
        tableComment: item.tableComment || item.TABLE_COMMENT || '',
        rowCount: item.tableRows ?? item.table_rows ?? item.rowCount,
        storageSize: item.dataLength ?? item.data_length ?? item.storageSize,
        createdAt: item.createTime || item.CREATE_TIME || item.createdAt
      }
      const meta = metaMap.get(tableName)
      if (!meta) return base
      return {
        ...meta,
        ...base,
        tableComment: base.tableComment || meta.tableComment
      }
    })
    tableStore[sourceKey][database] = list
    refreshSchemaChildrenInTree(sourceId, database)
    return true
  } catch (error) {
    ElMessage.error('加载表列表失败')
    return false
  } finally {
    tableLoading[loadingKey] = false
  }
}

const handleSourceChange = async (sourceId) => {
  if (!sourceId) return
  await loadSchemas(sourceId)
}

const handleSchemaChange = async (sourceId, database) => {
  if (!sourceId || !database) return
  await loadTables(sourceId, database)
}

const getFilteredTables = (sourceId, database) => {
  const sourceKey = String(sourceId || '')
  const list = tableStore[sourceKey]?.[database] || []
  if (!searchKeyword.value) return list
  const keyword = searchKeyword.value.toLowerCase()
  return list.filter((item) => {
    return (
      item.tableName?.toLowerCase().includes(keyword) ||
      item.tableComment?.toLowerCase().includes(keyword)
    )
  })
}

const getDisplayedTables = (sourceId, database) => {
  const list = [...getFilteredTables(sourceId, database)]
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

const getTableCount = (sourceId, database) => getFilteredTables(sourceId, database).length

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

const getTableKey = (table, fallbackDb = '', fallbackSource = '') => {
  if (!table) return ''
  const sourceId = table.sourceId || table.clusterId || fallbackSource || ''
  const dbName = table.dbName || table.databaseName || table.database || fallbackDb || ''
  const tableName = table.tableName || ''
  const core = dbName && tableName ? `${dbName}.${tableName}` : tableName || dbName
  return sourceId ? `${sourceId}::${core}` : core
}

const buildSchemaNode = (sourceId, schemaName) => ({
  nodeKey: getSchemaNodeKey(sourceId, schemaName),
  type: 'schema',
  name: schemaName,
  sourceId: String(sourceId),
  schemaName,
  leaf: false
})

const isDatasourceIconInactive = (nodeData) => {
  if (!nodeData || nodeData.type !== 'datasource') return false
  if (nodeData.status && nodeData.status !== 'active') return true
  return !activatedSources[String(nodeData.sourceId)]
}

const getDatasourceIconUrl = (sourceType) => {
  const type = String(sourceType || '').toUpperCase()
  if (type === 'MYSQL') return '/datasource-icons/mysql.svg'
  if (type === 'DORIS') return '/datasource-icons/doris.svg'
  return ''
}

const buildTableNode = (table, sourceId, schemaName) => {
  const key = getTableKey(table, schemaName, sourceId)
  return {
    nodeKey: key || `table:${String(sourceId)}::${schemaName}.${table?.tableName || ''}`,
    type: 'table',
    name: table?.tableName || '',
    sourceId: String(sourceId),
    schemaName,
    table,
    leaf: true
  }
}

const parseTimeValue = (value) => {
  if (!value) return 0
  if (typeof value === 'number') return value
  const text = String(value)
  const parsed = Date.parse(text)
  if (!Number.isNaN(parsed)) return parsed
  const fallback = Date.parse(text.replace(' ', 'T'))
  return Number.isNaN(fallback) ? 0 : fallback
}

const getTableSortValue = (table) => {
  const field = sortField.value
  if (field === 'rowCount') return getTableRowCount(table)
  if (field === 'createdAt') return parseTimeValue(table?.createdAt ?? table?.createTime ?? table?.CREATE_TIME)
  return String(table?.tableName || '').toLowerCase()
}

const getSortedTablesForTree = (sourceId, database) => {
  const sourceKey = String(sourceId || '')
  const list = [...(tableStore[sourceKey]?.[database] || [])]
  const order = sortOrder.value
  list.sort((a, b) => {
    const aVal = getTableSortValue(a)
    const bVal = getTableSortValue(b)
    if (aVal === bVal) return 0
    if (order === 'asc') return aVal > bVal ? 1 : -1
    return aVal < bVal ? 1 : -1
  })
  return list
}

const buildTableChildren = (sourceId, database) => {
  return getSortedTablesForTree(sourceId, database).map((table) => buildTableNode(table, sourceId, database))
}

const refreshDatasourceChildrenInTree = (sourceId) => {
  const tree = catalogTreeRef.value
  if (!tree || !sourceId) return
  const key = getDatasourceNodeKey(sourceId)
  const node = tree.getNode(key)
  if (!node?.loaded) return
  const schemas = schemaStore[String(sourceId)] || []
  tree.updateKeyChildren(key, schemas.map((schemaName) => buildSchemaNode(sourceId, schemaName)))
  nextTick(() => tree.filter(searchKeyword.value))
}

const refreshSchemaChildrenInTree = (sourceId, database) => {
  const tree = catalogTreeRef.value
  if (!tree || !sourceId || !database) return
  const key = getSchemaNodeKey(sourceId, database)
  const node = tree.getNode(key)
  if (!node?.loaded) return
  tree.updateKeyChildren(key, buildTableChildren(sourceId, database))
  nextTick(() => tree.filter(searchKeyword.value))
}

const refreshLoadedSchemaNodesInTree = () => {
  Object.keys(tableStore).forEach((sourceId) => {
    const dbMap = tableStore[sourceId]
    if (!dbMap || typeof dbMap !== 'object') return
    Object.keys(dbMap).forEach((schemaName) => {
      refreshSchemaChildrenInTree(sourceId, schemaName)
    })
  })
}

const filterCatalogNode = (value, data) => {
  if (!value) return true
  const keyword = String(value).toLowerCase()
  if (data?.type === 'table') {
    const name = String(data.table?.tableName || data.name || '').toLowerCase()
    const comment = String(data.table?.tableComment || '').toLowerCase()
    return name.includes(keyword) || comment.includes(keyword)
  }
  return String(data?.name || '').toLowerCase().includes(keyword)
}

const loadCatalogNode = async (node, resolve, reject) => {
  const data = node?.data
  if (!data?.type) {
    resolve([])
    return
  }

  if (data.type === 'datasource') {
    const ok = await loadSchemas(data.sourceId)
    if (!ok) {
      reject?.()
      return
    }
    const schemas = schemaStore[String(data.sourceId)] || []
    resolve(schemas.map((schemaName) => buildSchemaNode(data.sourceId, schemaName)))
    nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
    return
  }

  if (data.type === 'schema') {
    const ok = await loadTables(data.sourceId, data.schemaName)
    if (!ok) {
      reject?.()
      return
    }
    resolve(buildTableChildren(data.sourceId, data.schemaName))
    nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
    return
  }

  resolve([])
}

const handleCatalogNodeClick = (data) => {
  if (!data) return
  if (data.type === 'table') {
    openTableTab(data.table, data.schemaName, data.sourceId)
    return
  }
  nextTick(() => {
    if (selectedTableKey.value) {
      catalogTreeRef.value?.setCurrentKey(selectedTableKey.value, false)
    }
  })
}

const expandCatalogNode = (key) => {
  return new Promise((resolve) => {
    const tree = catalogTreeRef.value
    if (!tree || !key) {
      resolve(false)
      return
    }
    const node = tree.getNode(key)
    if (!node) {
      resolve(false)
      return
    }
    if (node.expanded) {
      resolve(true)
      return
    }
    node.expand(() => resolve(true), true)
  })
}

const ensureCatalogPathExpanded = async (sourceId, schemaName) => {
  if (!catalogTreeRef.value || !sourceId) return
  await expandCatalogNode(getDatasourceNodeKey(sourceId))
  await nextTick()
  if (schemaName) {
    await expandCatalogNode(getSchemaNodeKey(sourceId, schemaName))
    await nextTick()
  }
}

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

const ensureClusterSelected = (table) => {
  if (isDorisTable(table) && !clusterId.value) {
    ElMessage.warning('请选择 Doris 集群')
    return false
  }
  return true
}

const getVarcharLength = (row) => {
  const fieldType = row?.fieldType || ''
  const match = String(fieldType).match(/varchar\s*\((\d+)\)/i)
  return match ? match[1] : '-'
}

const isReplicaWarning = (value) => {
  if (value === null || value === undefined || value === '') return false
  const num = Number(value)
  return Number.isFinite(num) && num > 0 && num < 3
}

const isAggregateTable = (table) => {
  if (!table?.tableModel) return false
  return String(table.tableModel).toUpperCase() === 'AGGREGATE'
}

const hasText = (value) => value !== null && value !== undefined && String(value).trim() !== ''
const hasPositiveNumber = (value) => {
  const num = Number(value)
  return Number.isFinite(num) && num > 0
}

const isDorisTable = (table) => {
  if (!table) return false
  if (table.isSynced === 1) return true
  return (
    hasText(table.tableModel) ||
    hasPositiveNumber(table.bucketNum) ||
    hasPositiveNumber(table.replicaNum) ||
    hasText(table.distributionColumn) ||
    hasText(table.keyColumns) ||
    hasText(table.partitionField) ||
    hasText(table.partitionColumn)
  )
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
  if (!table) return 0
  const value = table.rowCount ?? table.tableRows ?? table.table_rows
  if (value === null || value === undefined) return 0
  return Number(value) || 0
}

const getTableStorageSize = (table) => {
  if (!table) return 0
  const value = table.storageSize ?? table.dataLength ?? table.data_length
  if (value === null || value === undefined) return 0
  return Number(value) || 0
}

const getProgressWidth = (sourceId, database, table) => {
  const sourceKey = String(sourceId || '')
  const list = tableStore[sourceKey]?.[database] || []
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

const getFieldRows = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return []
  return state.fieldsEditing ? state.fieldsDraft : state.fields
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
    queryTiming: {
      startedAt: 0,
      elapsedMs: 0
    },
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
      owner: table.owner || '',
      bucketNum: table.bucketNum ?? '',
      replicaNum: table.replicaNum ?? ''
    },
    metaOriginal: {},
    fieldSubmitting: false,
    fieldsEditing: false,
    fieldsDraft: [],
    fieldsRemoved: [],
    fields: [],
    ddl: '',
    ddlLoading: false,
    lineage: { upstreamTables: [], downstreamTables: [] },
    tasks: { writeTasks: [], readTasks: [] }
  })
}

const openTableTab = async (table, dbFallback = '', sourceFallback = '') => {
  if (!table) return
  const sourceId = table.sourceId || table.clusterId || sourceFallback
  if (sourceId) {
    clusterId.value = sourceId
  }
  const key = getTableKey(table, dbFallback, sourceId)
  if (!key) return

  selectedTableKey.value = key

  const existing = openTabs.value.find((item) => String(item.id) === key)
  if (existing) {
    activeTab.value = String(existing.id)
    await focusTableInSidebar(table, key, dbFallback, sourceId)
    return
  }

  const resolvedDb = table.dbName || table.databaseName || table.database || dbFallback || ''
  const tabItem = {
    id: key,
    kind: 'table',
    tableName: table.tableName,
    dbName: resolvedDb,
    sourceId
  }
  tabStates[key] = createTabState({ ...table, dbName: resolvedDb, sourceId })
  openTabs.value.push(tabItem)
  activeTab.value = key

  await focusTableInSidebar(table, key, dbFallback, sourceId)
  await loadTabData(key)
}

const refreshCatalog = async () => {
  if (dbLoading.value) return
  dbLoading.value = true
  try {
    const clusters = await dorisClusterApi.list()
    dataSources.value = Array.isArray(clusters) ? clusters : []
    const ids = new Set(dataSources.value.map((item) => String(item.id)))
    if (clusterId.value && !ids.has(String(clusterId.value))) {
      const fallback =
        dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0] || null
      clusterId.value = fallback?.id || null
    }
    if (activeSource.value && !ids.has(String(activeSource.value))) {
      const fallback =
        dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0] || null
      activeSource.value = fallback?.id ? String(fallback.id) : ''
    }
    await nextTick()
    const tree = catalogTreeRef.value
    if (!tree) return
    const loadedSources = dataSources.value
      .map((item) => String(item.id))
      .filter((sourceId) => tree.getNode(getDatasourceNodeKey(sourceId))?.loaded)

    for (const sourceId of loadedSources) {
      const ok = await loadSchemas(sourceId, true)
      if (!ok) continue
      const schemas = schemaStore[String(sourceId)] || []
      for (const schemaName of schemas) {
        if (tree.getNode(getSchemaNodeKey(sourceId, schemaName))?.loaded) {
          await loadTables(sourceId, schemaName, true)
        }
      }
    }
  } catch (error) {
    ElMessage.error('刷新目录失败')
  } finally {
    dbLoading.value = false
  }
}

const refreshDatasourceNode = async (nodeData) => {
  const sourceId = nodeData?.sourceId
  if (!sourceId) return
  if (dbLoading.value || schemaLoading[String(sourceId)]) return
  await loadSchemas(sourceId, true)
}

const refreshSchemaNode = async (nodeData) => {
  const sourceId = nodeData?.sourceId
  const schemaName = nodeData?.schemaName
  if (!sourceId || !schemaName) return
  const key = `${String(sourceId)}::${schemaName}`
  if (dbLoading.value || tableLoading[key]) return
  await loadTables(sourceId, schemaName, true)
}

const focusTableInSidebar = async (table, key, dbFallback = '', sourceFallback = '') => {
  if (!table) return
  const sourceId = table.sourceId || table.clusterId || sourceFallback
  const dbName = table.dbName || table.databaseName || table.database || dbFallback
  if (sourceId) {
    activeSource.value = String(sourceId)
    await loadSchemas(sourceId)
  }
  if (sourceId && dbName) {
    activeSchema[String(sourceId)] = dbName
    await loadTables(sourceId, dbName)
  }
  await nextTick()
  await ensureCatalogPathExpanded(sourceId, dbName)
  catalogTreeRef.value?.setCurrentKey(key)
  await nextTick()
  const ref = tableRefs.value[key]
  if (ref?.scrollIntoView) {
    ref.scrollIntoView({ block: 'nearest' })
  }
}

const syncRouteWithTab = (tab, tabId) => {
  if (suppressRouteSync.value) return
  if (!tab) return
  const kind = tab.kind === 'query' ? 'query' : 'table'
  const id = String(tabId ?? tab.id ?? '')

  const nextQuery = { ...route.query }
  if (id) nextQuery.tab = id
  if (tab.sourceId) nextQuery.clusterId = String(tab.sourceId)
  if (tab.dbName) nextQuery.database = String(tab.dbName)

  if (kind === 'table') {
    const tableId = tabStates[id]?.table?.id
    if (tableId) nextQuery.tableId = String(tableId)
    else delete nextQuery.tableId
    if (tab.tableName) nextQuery.tableName = String(tab.tableName)
  } else {
    delete nextQuery.tableId
    delete nextQuery.tableName
  }

  router.replace({ path: route.path, query: nextQuery })
}

const clearRouteTabQuery = () => {
  if (suppressRouteSync.value) return
  const nextQuery = { ...route.query }
  delete nextQuery.tab
  delete nextQuery.tableId
  delete nextQuery.tableName
  router.replace({ path: route.path, query: nextQuery })
}

const clearCreateQuery = () => {
  if (!route.query.create) return
  const nextQuery = { ...route.query }
  delete nextQuery.create
  router.replace({ path: route.path, query: nextQuery })
}

const syncFromRoute = async () => {
  const { clusterId: routeClusterId, database, tableId, tableName } = route.query
  if (!routeClusterId || !database || (!tableId && !tableName)) return
  const currentTab = openTabs.value.find((item) => String(item.id) === String(activeTab.value))
  if (currentTab) {
    const sameSource = String(currentTab.sourceId || '') === String(routeClusterId)
    const sameDb = String(currentTab.dbName || '') === String(database)
    const sameName = !tableName || String(currentTab.tableName || '') === String(tableName)
    const currentId = tabStates[String(currentTab.id)]?.table?.id
    const sameId = !tableId || (currentId && String(currentId) === String(tableId))
    if (sameSource && sameDb && sameName && sameId) return
  }
  activeSource.value = String(routeClusterId)
  activeSchema[String(routeClusterId)] = database
  await loadSchemas(routeClusterId, true)
  await loadTables(routeClusterId, database, true)
  const list = tableStore[String(routeClusterId)]?.[database] || []
  let target = null
  if (tableId) {
    target = list.find((item) => String(item.id) === String(tableId))
  }
  if (!target && tableName) {
    target = list.find((item) => item.tableName === tableName)
  }
  if (!target && tableId) {
    try {
      const tableInfo = await tableApi.getById(tableId)
      if (tableInfo) {
        target = { ...tableInfo, sourceId: String(routeClusterId), dbName: database }
      }
    } catch (error) {
      console.error('路由表加载失败', error)
    }
  }
  if (!target) return
  suppressRouteSync.value = true
  await openTableTab(target, database, routeClusterId)
  suppressRouteSync.value = false
}

const loadTabData = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table) return
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
      owner: state.table.owner || '',
      bucketNum: state.table.bucketNum ?? '',
      replicaNum: state.table.replicaNum ?? ''
    }
    state.metaOriginal = { ...state.metaForm }
    state.fields = []
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
    state.lineage = {
      upstreamTables: [],
      downstreamTables: []
    }
    state.tasks = {
      writeTasks: [],
      readTasks: []
    }
    if (state.query.sql === '') {
      state.query.sql = buildDefaultSql(state.table)
    }
    return
  }
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
      owner: state.table.owner || '',
      bucketNum: state.table.bucketNum ?? '',
      replicaNum: state.table.replicaNum ?? ''
    }
    state.metaOriginal = { ...state.metaForm }
    state.fields = Array.isArray(fieldList) ? fieldList : []
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
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

const disposeTabResources = (tabId) => {
  const id = String(tabId || '')
  if (!id) return
  clearQueryTimer(id)
  disposeChart(id)
  if (leftPaneRefs.value?.[id]) {
    delete leftPaneRefs.value[id]
  }
  if (chartRefs.value?.[id]) {
    delete chartRefs.value[id]
  }
  if (leftPaneHeights[id] !== undefined) {
    delete leftPaneHeights[id]
  }
  delete tabStates[id]
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

const resolveDefaultSourceId = () => {
  if (clusterId.value) return String(clusterId.value)
  if (activeSource.value) return String(activeSource.value)
  const fallback = Array.isArray(dataSources.value) ? dataSources.value[0] : null
  return fallback?.id ? String(fallback.id) : ''
}

const resolveDefaultDatabase = (sourceId) => {
  const sid = String(sourceId || '')
  if (!sid) return ''
  return activeSchema[sid] || schemaStore[sid]?.[0] || ''
}

const handleTabAdd = async () => {
  const sourceId = resolveDefaultSourceId()
  if (!sourceId) {
    ElMessage.warning('请先选择数据源')
    return
  }
  await loadSchemas(sourceId)
  const dbName = resolveDefaultDatabase(sourceId)
  if (!dbName) {
    ElMessage.warning('请先选择数据库')
    return
  }

  const queryId = `query:${Date.now()}`
  const tabItem = {
    id: queryId,
    kind: 'query',
    tableName: `查询${queryTabCounter.value++}`,
    dbName,
    sourceId
  }
  tabStates[queryId] = createTabState({ tableName: '', dbName, sourceId })
  tabStates[queryId].query.sql = ''
  openTabs.value.push(tabItem)
  activeTab.value = queryId
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
  if (!state.table?.dbName) {
    ElMessage.warning('请先选择数据库')
    return
  }
  if (!clusterId.value) {
    ElMessage.warning('请选择数据源')
    return
  }
  if (!isReadOnlySql(state.query.sql)) {
    ElMessage.warning('仅支持只读查询')
    return
  }
  state.queryLoading = true
  startQueryTimer(tabId)
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
    clearQueryTimer(tabId)
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
    activeSource.value = String(row.clusterId)
    loadSchemas(row.clusterId)
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
  const dorisTable = isDorisTable(table)
  if (dorisTable && !clusterId.value) {
    ElMessage.warning('请选择 Doris 集群')
    return
  }

  try {
    const message = dorisTable
      ? `确定要删除表 “${table.tableName}” 吗？删除后将重命名为 deprecated_时间戳，数据不会丢失。`
      : `确定要删除表 “${table.tableName}” 吗？将仅删除平台元数据记录。`
    await ElMessageBox.confirm(
      message,
      '删除表确认',
      { type: 'warning' }
    )
    if (dorisTable) {
      await tableApi.softDelete(table.id, clusterId.value || null)
    } else {
      await tableApi.delete(table.id)
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
    if (error !== 'cancel') {
      ElMessage.error('删除表失败')
    }
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
  if (!ensureClusterSelected(state.table)) return
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
  if (!ensureClusterSelected(state.table)) return
  try {
    await ElMessageBox.confirm('确认保存表信息与 Doris 配置的修改吗？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    })
  } catch (error) {
    return
  }
  state.metaSaving = true
  try {
    const payload = {
      tableName: state.metaForm.tableName,
      tableComment: state.metaForm.tableComment,
      layer: state.metaForm.layer,
      owner: state.metaForm.owner,
      bucketNum: state.metaForm.bucketNum,
      replicaNum: state.metaForm.replicaNum
    }
    const updated = await tableApi.update(state.table.id, payload, clusterId.value || null)
    state.table = { ...state.table, ...updated }
    state.metaForm = {
      tableName: state.table.tableName || '',
      tableComment: state.table.tableComment || '',
      layer: state.table.layer || '',
      owner: state.table.owner || '',
      bucketNum: state.table.bucketNum ?? '',
      replicaNum: state.table.replicaNum ?? ''
    }
    state.metaOriginal = { ...state.metaForm }
    state.metaEditing = false
    updateTableCache(state.table)
    const newKey = syncTabKey(tabId, state.table)
    const tab = openTabs.value.find((item) => String(item.id) === String(newKey))
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
  const sourceId = updated.sourceId || clusterId.value
  if (!sourceId) return
  const sourceKey = String(sourceId)
  const list = tableStore[sourceKey]?.[updated.dbName] || []
  const idx = list.findIndex((item) => String(item.id) === String(updated.id))
  if (idx === -1) return
  const next = [...list]
  next[idx] = { ...next[idx], ...updated }
  tableStore[sourceKey][updated.dbName] = next
}

const refreshFields = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  try {
    const fieldList = await tableApi.getFields(state.table.id)
    state.fields = Array.isArray(fieldList) ? fieldList : []
  } catch (error) {
    console.error('刷新字段失败', error)
  }
}

const syncTabKey = (oldKey, updatedTable) => {
  const newKey = getTableKey(updatedTable, updatedTable?.dbName || '', updatedTable?.sourceId || clusterId.value)
  if (!newKey || newKey === oldKey) return oldKey
  const oldIndex = openTabs.value.findIndex((tab) => String(tab.id) === String(oldKey))
  const existingIndex = openTabs.value.findIndex((tab) => String(tab.id) === String(newKey))
  if (existingIndex !== -1 && existingIndex !== oldIndex) {
    if (oldIndex !== -1) {
      openTabs.value.splice(oldIndex, 1)
    }
    delete tabStates[oldKey]
    activeTab.value = String(newKey)
    selectedTableKey.value = String(newKey)
    return newKey
  }
  if (oldIndex !== -1) {
    openTabs.value[oldIndex].id = newKey
  }
  tabStates[newKey] = tabStates[oldKey]
  if (oldKey !== newKey) {
    delete tabStates[oldKey]
    delete tableRefs.value[oldKey]
  }
  if (String(activeTab.value) === String(oldKey)) {
    activeTab.value = String(newKey)
  }
  selectedTableKey.value = String(newKey)
  return newKey
}

const startFieldsEdit = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  if (!ensureClusterSelected(state.table)) return
  state.fieldsEditing = true
  state.fieldsDraft = state.fields.map((item) => ({ ...item }))
  state.fieldsRemoved = []
}

const cancelFieldsEdit = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  state.fieldsEditing = false
  state.fieldsDraft = []
  state.fieldsRemoved = []
}

const addField = (tabId, afterRow = null) => {
  const state = tabStates[tabId]
  if (!state) return
  if (isAggregateTable(state.table)) {
    ElMessage.warning('AGGREGATE 表仅支持修改注释，无法新增字段')
    return
  }
  const newRow = {
    id: null,
    fieldName: '',
    fieldType: '',
    fieldOrder: 0,
    isNullable: 1,
    isPrimary: 0,
    defaultValue: '',
    fieldComment: ''
  }
  if (!afterRow) {
    state.fieldsDraft.unshift(newRow)
    return
  }
  const index = state.fieldsDraft.indexOf(afterRow)
  if (index === -1) {
    state.fieldsDraft.unshift(newRow)
    return
  }
  state.fieldsDraft.splice(index + 1, 0, newRow)
}

const removeField = (tabId, row) => {
  const state = tabStates[tabId]
  if (!state) return
  if (isAggregateTable(state.table)) {
    ElMessage.warning('AGGREGATE 表仅支持修改注释，无法删除字段')
    return
  }
  if (row?.id) {
    state.fieldsRemoved = [...new Set([...(state.fieldsRemoved || []), row.id])]
  }
  state.fieldsDraft = state.fieldsDraft.filter((item) => item !== row)
}

const buildFieldPayload = (row) => ({
  fieldName: (row.fieldName || '').trim(),
  fieldType: (row.fieldType || '').trim(),
  fieldComment: row.fieldComment || '',
  isNullable: row.isNullable ?? 1,
  isPrimary: row.isPrimary ?? 0,
  defaultValue: row.defaultValue || '',
  fieldOrder: row.fieldOrder || 0
})

const isFieldChanged = (next, original) => {
  if (!original) return true
  const payload = buildFieldPayload(next)
  return (
    payload.fieldName !== (original.fieldName || '') ||
    payload.fieldType !== (original.fieldType || '') ||
    payload.fieldComment !== (original.fieldComment || '') ||
    Number(payload.isNullable ?? 1) !== Number(original.isNullable ?? 1) ||
    Number(payload.isPrimary ?? 0) !== Number(original.isPrimary ?? 0) ||
    payload.defaultValue !== (original.defaultValue || '') ||
    Number(payload.fieldOrder || 0) !== Number(original.fieldOrder || 0)
  )
}

const isOnlyCommentChanged = (next, original) => {
  if (!original) return false
  const payload = buildFieldPayload(next)
  return (
    payload.fieldName === (original.fieldName || '') &&
    payload.fieldType === (original.fieldType || '') &&
    Number(payload.isNullable ?? 1) === Number(original.isNullable ?? 1) &&
    Number(payload.isPrimary ?? 0) === Number(original.isPrimary ?? 0) &&
    payload.defaultValue === (original.defaultValue || '') &&
    Number(payload.fieldOrder || 0) === Number(original.fieldOrder || 0) &&
    payload.fieldComment !== (original.fieldComment || '')
  )
}

const saveFieldsEdit = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  if (!ensureClusterSelected(state.table)) return
  const draft = state.fieldsDraft || []
  const removedIds = [...new Set(state.fieldsRemoved || [])]
  for (const row of draft) {
    const payload = buildFieldPayload(row)
    if (!payload.fieldName || !payload.fieldType) {
      ElMessage.warning('请填写字段名和类型')
      return
    }
  }
  const originalMap = new Map(state.fields.map((item) => [item.id, item]))
  const createList = draft.filter((row) => !row.id)
  const updateList = draft.filter((row) => row.id && isFieldChanged(row, originalMap.get(row.id)))
  if (isAggregateTable(state.table)) {
    const invalidUpdates = updateList.filter(
      (row) => !isOnlyCommentChanged(row, originalMap.get(row.id))
    )
    if (createList.length || removedIds.length || invalidUpdates.length) {
      ElMessage.warning('AGGREGATE 表仅支持修改字段注释')
      return
    }
  }
  if (isDorisTable(state.table)) {
    const primaryChanged = updateList.some((row) => {
      const original = originalMap.get(row.id)
      return Number(row.isPrimary ?? 0) !== Number(original?.isPrimary ?? 0)
    })
    const primaryAdded = createList.some((row) => Number(row.isPrimary ?? 0) === 1)
    if (primaryChanged || primaryAdded) {
      ElMessage.warning('Doris 不支持在线修改主键列')
      return
    }
  }
  if (!createList.length && !updateList.length && !removedIds.length) {
    ElMessage.info('暂无字段变更')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认保存字段变更（新增 ${createList.length}、修改 ${updateList.length}、删除 ${removedIds.length}）吗？`,
      '提示',
      {
        type: 'warning',
        confirmButtonText: '确认',
        cancelButtonText: '取消'
      }
    )
  } catch (error) {
    return
  }
  state.fieldSubmitting = true
  try {
    for (const row of createList) {
      await tableApi.createField(state.table.id, buildFieldPayload(row), clusterId.value || null)
    }
    for (const row of updateList) {
      await tableApi.updateField(state.table.id, row.id, buildFieldPayload(row), clusterId.value || null)
    }
    for (const id of removedIds) {
      await tableApi.deleteField(state.table.id, id, clusterId.value || null)
    }
    await refreshFields(tabId)
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
    ElMessage.success('字段已保存')
  } catch (error) {
    ElMessage.error('字段保存失败')
  } finally {
    state.fieldSubmitting = false
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
    const next = Math.max(220, Math.min(840, startWidth + delta))
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
  () => [activeTab.value, tabStates[activeTab.value]?.metaTab],
  ([tabId, metaTab]) => {
    if (!tabId || metaTab !== 'ddl') return
    const state = tabStates[tabId]
    if (!state || state.ddlLoading || state.ddl) return
    loadDdl(tabId)
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

watch(searchKeyword, (value) => {
  catalogTreeRef.value?.filter(value)
})

watch([sortField, sortOrder], () => {
  refreshLoadedSchemaNodesInTree()
})

watch(selectedTableKey, (value) => {
  if (!value) return
  catalogTreeRef.value?.setCurrentKey(value, false)
})

onMounted(() => {
  setupTableObserver()
  restoreTabsFromStorage()
  loadClusters()
  fetchHistory()
  syncFromRoute()
  if (route.query.create) {
    createDrawerVisible.value = true
    clearCreateQuery()
  }
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  flushPersistTabs()
  window.removeEventListener('resize', handleResize)
  chartInstances.forEach((instance) => instance.dispose())
  chartInstances.clear()
  queryTimerHandles.forEach((handle) => clearInterval(handle))
  queryTimerHandles.clear()
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
  padding: 8px 8px 12px;
  overflow: auto;
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
.catalog-node--schema {
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
:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--schema) {
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

.tab-grid.is-single {
  grid-template-columns: 1fr;
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
