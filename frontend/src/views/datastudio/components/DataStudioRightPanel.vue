<template>
  <div class="right-root">
    <div v-if="hasTableTab && state" class="tab-right">
      <div class="meta-panel">
        <el-tabs v-model="state.metaTab" class="meta-tabs">
          <el-tab-pane name="basic" label="基本信息">
            <div class="meta-section meta-section-fill">
              <div class="section-header">
                <span>表信息</span>
                <div class="section-actions">
                  <el-tooltip
                    v-if="!state.metaEditing && isDorisTable(state.table) && !clusterId"
                    content="请选择 Doris 集群后再编辑"
                    placement="top"
                  >
                    <span>
                      <el-button type="primary" size="small" disabled>编辑</el-button>
                    </span>
                  </el-tooltip>
                  <el-button v-else-if="!state.metaEditing" type="primary" size="small" @click="startMetaEdit(activeTabId)">
                    编辑
                  </el-button>

                  <el-tooltip
                    v-if="!state.metaEditing && isDorisTable(state.table) && !clusterId"
                    content="请选择 Doris 集群后再删除"
                    placement="top"
                  >
                    <span>
                      <el-button type="danger" plain size="small" disabled>删除表</el-button>
                    </span>
                  </el-tooltip>
                  <el-button v-else-if="!state.metaEditing" type="danger" plain size="small" @click="handleDeleteTable">
                    删除表
                  </el-button>

                  <template v-else>
                    <el-button size="small" @click="cancelMetaEdit(activeTabId)">取消</el-button>
                    <el-button type="primary" size="small" :loading="state.metaSaving" @click="saveMetaEdit(activeTabId)">
                      保存
                    </el-button>
                  </template>
                </div>
              </div>

              <el-scrollbar class="meta-scroll">
                <el-descriptions :column="1" border size="small" class="meta-descriptions">
                  <el-descriptions-item label="表名">
                    <el-input v-if="state.metaEditing" v-model="state.metaForm.tableName" size="small" class="meta-input" />
                    <span v-else>{{ state.table.tableName || '-' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="表注释">
                    <el-input
                      v-if="state.metaEditing"
                      v-model="state.metaForm.tableComment"
                      size="small"
                      class="meta-input"
                    />
                    <span v-else>{{ state.table.tableComment || '-' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="分层">
                    <el-select
                      v-if="state.metaEditing"
                      v-model="state.metaForm.layer"
                      size="small"
                      placeholder="选择分层"
                      class="meta-input"
                    >
                      <el-option v-for="item in layerOptions" :key="item.value" :label="item.label" :value="item.value" />
                    </el-select>
                    <span v-else>{{ state.table.layer || '-' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="负责人">
                    <el-input v-if="state.metaEditing" v-model="state.metaForm.owner" size="small" class="meta-input" />
                    <span v-else>{{ state.table.owner || '-' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="数据库">
                    <span>{{ state.table.dbName || '-' }}</span>
                  </el-descriptions-item>
                </el-descriptions>

                <template v-if="isDorisTable(state.table)">
                  <div class="section-divider"></div>

                  <div class="section-header small">
                    <span>Doris 配置</span>
                  </div>
                  <el-descriptions :column="1" border size="small" class="meta-descriptions">
                    <el-descriptions-item label="表模型">{{ state.table.tableModel || '-' }}</el-descriptions-item>
                    <el-descriptions-item label="主键列">{{ state.table.keyColumns || '-' }}</el-descriptions-item>
                    <el-descriptions-item label="分区字段">{{ state.table.partitionColumn || '-' }}</el-descriptions-item>
                    <el-descriptions-item label="分桶字段">{{ state.table.distributionColumn || '-' }}</el-descriptions-item>
                    <el-descriptions-item label="分桶数">
                      <el-input-number
                        v-if="state.metaEditing"
                        v-model="state.metaForm.bucketNum"
                        :min="1"
                        size="small"
                        controls-position="right"
                        class="meta-input"
                      />
                      <span v-else>{{ state.table.bucketNum || '-' }}</span>
                    </el-descriptions-item>
                    <el-descriptions-item label="副本数">
                      <template v-if="state.metaEditing">
                        <div class="replica-edit">
                          <el-input-number
                            v-model="state.metaForm.replicaNum"
                            :min="1"
                            size="small"
                            controls-position="right"
                            class="meta-input"
                          />
                          <span v-if="isReplicaWarning(state.metaForm.replicaNum)" class="replica-warning">
                            <el-icon><Warning /></el-icon>
                            建议≥3
                          </span>
                        </div>
                      </template>
                      <span v-else :class="['replica-value', { 'replica-danger': isReplicaWarning(state.table.replicaNum) }]">
                        <el-icon v-if="isReplicaWarning(state.table.replicaNum)" class="warning-icon"><Warning /></el-icon>
                        {{ state.table.replicaNum || '-' }}
                      </span>
                    </el-descriptions-item>
                  </el-descriptions>
                </template>
              </el-scrollbar>
            </div>
          </el-tab-pane>

          <el-tab-pane name="columns" label="列信息">
            <div class="meta-section meta-section-fill">
              <div class="section-header">
                <div class="section-title">
                  <span>字段定义</span>
                  <el-tag
                    v-if="state.fieldsEditing && isAggregateTable(state.table)"
                    type="warning"
                    size="small"
                    effect="plain"
                  >
                    AGGREGATE 表仅支持修改注释
                  </el-tag>
                  <el-tag
                    v-if="state.fieldsEditing && isDorisTable(state.table)"
                    type="warning"
                    size="small"
                    effect="plain"
                  >
                    主键列不可在线修改
                  </el-tag>
                </div>
                <div class="section-actions">
                  <el-tooltip
                    v-if="!state.fieldsEditing && isDorisTable(state.table) && !clusterId"
                    content="请选择 Doris 集群后再编辑"
                    placement="top"
                  >
                    <span>
                      <el-button type="primary" size="small" disabled>编辑</el-button>
                    </span>
                  </el-tooltip>
                  <el-button v-else-if="!state.fieldsEditing" type="primary" size="small" @click="startFieldsEdit(activeTabId)">
                    编辑
                  </el-button>
                  <template v-else>
                    <el-button size="small" @click="cancelFieldsEdit(activeTabId)" :disabled="state.fieldSubmitting">
                      取消
                    </el-button>
                    <el-button
                      type="primary"
                      size="small"
                      :loading="state.fieldSubmitting"
                      @click="saveFieldsEdit(activeTabId)"
                    >
                      保存修改
                    </el-button>
                  </template>
                </div>
              </div>

              <div v-if="fieldRows.length" class="meta-table">
                <el-table :data="fieldRows" border size="small" height="100%">
                  <el-table-column label="字段名" width="260" show-overflow-tooltip>
                    <template #default="{ row }">
                      <el-input
                        v-if="state.fieldsEditing"
                        v-model="row.fieldName"
                        size="small"
                        placeholder="字段名"
                        :disabled="isAggregateTable(state.table)"
                      />
                      <span v-else>{{ row.fieldName }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="类型" width="150">
                    <template #default="{ row }">
                      <el-input
                        v-if="state.fieldsEditing"
                        v-model="row.fieldType"
                        size="small"
                        placeholder="VARCHAR(255)"
                        :disabled="isAggregateTable(state.table)"
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
                        v-if="state.fieldsEditing"
                        v-model="row.isNullable"
                        :active-value="1"
                        :inactive-value="0"
                        size="small"
                        :disabled="isAggregateTable(state.table)"
                      />
                      <el-tag v-else :type="row.isNullable ? 'success' : 'danger'" size="small">
                        {{ row.isNullable ? '是' : '否' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="主键" width="80">
                    <template #default="{ row }">
                      <template v-if="state.fieldsEditing">
                        <el-tooltip v-if="isDorisTable(state.table)" content="Doris 不支持在线修改主键列" placement="top">
                          <span>
                            <el-switch v-model="row.isPrimary" :active-value="1" :inactive-value="0" size="small" disabled />
                          </span>
                        </el-tooltip>
                        <el-switch
                          v-else
                          v-model="row.isPrimary"
                          :active-value="1"
                          :inactive-value="0"
                          size="small"
                          :disabled="isAggregateTable(state.table)"
                        />
                      </template>
                      <el-tag v-else :type="row.isPrimary ? 'success' : 'info'" size="small">
                        {{ row.isPrimary ? '是' : '否' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="默认值" width="120">
                    <template #default="{ row }">
                      <el-input
                        v-if="state.fieldsEditing"
                        v-model="row.defaultValue"
                        size="small"
                        placeholder="可选"
                        :disabled="isAggregateTable(state.table)"
                      />
                      <span v-else>{{ row.defaultValue || '-' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="注释" min-width="150">
                    <template #default="{ row }">
                      <el-input v-if="state.fieldsEditing" v-model="row.fieldComment" size="small" placeholder="字段注释" />
                      <span v-else>{{ row.fieldComment || '-' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="state.fieldsEditing" label="操作" width="150" fixed="right">
                    <template #default="{ row }">
                      <el-tooltip
                        v-if="isAggregateTable(state.table)"
                        content="AGGREGATE 表不支持新增字段"
                        placement="top"
                      >
                        <span>
                          <el-button link type="primary" size="small" disabled>新增</el-button>
                        </span>
                      </el-tooltip>
                      <el-button v-else link type="primary" size="small" @click="addField(activeTabId, row)">新增</el-button>
                      <el-popconfirm
                        width="240"
                        confirm-button-text="确定"
                        cancel-button-text="取消"
                        :title="`确定删除字段「${row.fieldName || '未命名'}」吗？`"
                        @confirm="removeField(activeTabId, row)"
                      >
                        <template #reference>
                          <el-tooltip
                            v-if="isAggregateTable(state.table)"
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
                    v-if="state.fieldsEditing"
                    type="primary"
                    size="small"
                    @click="addField(activeTabId)"
                    :disabled="isAggregateTable(state.table)"
                  >
                    新增字段
                  </el-button>
                </template>
              </el-empty>
            </div>
          </el-tab-pane>

          <el-tab-pane name="ddl" label="DDL">
            <div class="meta-section meta-section-fill" v-loading="state.ddlLoading">
              <div class="ddl-header">
                <el-button size="small" :disabled="!state.ddl" @click="copyDdl(activeTabId)">复制</el-button>
              </div>
              <el-scrollbar class="ddl-scroll">
                <pre v-if="state.ddl" class="ddl-content">{{ state.ddl }}</pre>
                <div v-else class="ddl-placeholder">加载中或暂无 DDL</div>
              </el-scrollbar>
            </div>
          </el-tab-pane>

          <el-tab-pane name="access" label="访问情况">
            <div class="meta-section meta-section-fill" v-loading="state.accessLoading">
              <div class="ddl-header">
                <el-button size="small" :disabled="!state.table?.id || state.accessLoading" @click="refreshAccess">
                  刷新
                </el-button>
              </div>
              <el-scrollbar class="meta-scroll">
                <template v-if="state.accessStats">
                  <el-alert
                    v-if="state.accessStats.note"
                    :title="state.accessStats.note"
                    type="warning"
                    show-icon
                    :closable="false"
                    class="access-note"
                  />
                  <el-descriptions :column="1" border size="small" class="meta-descriptions">
                    <el-descriptions-item label="总访问次数">
                      {{ state.accessStats.totalAccessCount ?? 0 }}
                    </el-descriptions-item>
                    <el-descriptions-item :label="`最近窗口访问(${state.accessStats.recentDays || 30}天)`">
                      {{ state.accessStats.recentAccessCount ?? 0 }}
                    </el-descriptions-item>
                    <el-descriptions-item label="近7天访问">
                      {{ state.accessStats.accessCount7d ?? 0 }}
                    </el-descriptions-item>
                    <el-descriptions-item label="近30天访问">
                      {{ state.accessStats.accessCount30d ?? 0 }}
                    </el-descriptions-item>
                    <el-descriptions-item label="最近访问时间">
                      {{ formatDateTime(state.accessStats.lastAccessTime) }}
                    </el-descriptions-item>
                    <el-descriptions-item label="访问用户数">
                      {{ state.accessStats.distinctUserCount ?? 0 }}
                    </el-descriptions-item>
                    <el-descriptions-item label="平均耗时">
                      {{ formatAccessDuration(state.accessStats.averageDurationMs) }}
                    </el-descriptions-item>
                    <el-descriptions-item label="审计来源">
                      {{ state.accessStats.dorisAuditEnabled ? (state.accessStats.dorisAuditSource || '已启用') : '未启用' }}
                    </el-descriptions-item>
                  </el-descriptions>

                  <div class="section-divider"></div>

                  <div class="section-header small">
                    <span>近{{ state.accessStats.trendDays || 14 }}天访问趋势</span>
                  </div>
                  <el-table :data="state.accessStats.trend || []" border size="small" class="access-table">
                    <el-table-column prop="date" label="日期" min-width="120" />
                    <el-table-column prop="accessCount" label="访问次数" width="120" />
                  </el-table>

                  <div class="section-divider"></div>

                  <div class="section-header small">
                    <span>活跃用户 Top{{ (state.accessStats.topUsers || []).length }}</span>
                  </div>
                  <el-table :data="state.accessStats.topUsers || []" border size="small" class="access-table">
                    <el-table-column prop="userId" label="用户" min-width="140" show-overflow-tooltip />
                    <el-table-column prop="accessCount" label="访问次数" width="100" />
                    <el-table-column label="最近访问" min-width="160">
                      <template #default="{ row }">
                        {{ formatDateTime(row.lastAccessTime) }}
                      </template>
                    </el-table-column>
                  </el-table>
                </template>
                <el-empty v-else :description="state.accessError || '暂无访问数据'" :image-size="60" />
              </el-scrollbar>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>

      <div class="lineage-panel">
        <div class="lineage-header">
          <span>数据血缘</span>
          <el-button type="primary" link size="small" @click="goLineage(activeTabId)">
            查看完整血缘
          </el-button>
        </div>
        <div class="lineage-grid">
          <div class="lineage-card">
            <div class="lineage-title">上游表 ({{ state.lineage.upstreamTables.length }})</div>
            <el-scrollbar class="lineage-scroll">
              <div class="task-block">
                <div class="task-title-row">
                  <div class="task-title">写入任务 ({{ state.tasks.writeTasks.length }})</div>
                  <el-button
                    type="primary"
                    size="small"
                    plain
                    :disabled="!state.table?.id"
                    @click.stop="goCreateRelatedTask(activeTabId, 'write')"
                  >
                    <el-icon><Plus /></el-icon>
                    新增写入任务
                  </el-button>
                </div>
                <div v-if="state.tasks.writeTasks.length" class="task-list">
                  <div v-for="task in state.tasks.writeTasks" :key="task.id" class="task-item" @click="openTask(task.id)">
                    <div class="task-name">{{ task.taskName || '-' }}</div>
                    <div class="task-meta">{{ task.engine || '-' }}</div>
                  </div>
                </div>
                <el-empty v-else description="暂无写入任务" :image-size="40" />
              </div>
              <div class="lineage-list">
                <div
                  v-for="item in state.lineage.upstreamTables"
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
            </el-scrollbar>
          </div>

          <div class="lineage-card">
            <div class="lineage-title">下游表 ({{ state.lineage.downstreamTables.length }})</div>
            <el-scrollbar class="lineage-scroll">
              <div class="task-block">
                <div class="task-title-row">
                  <div class="task-title">读取任务 ({{ state.tasks.readTasks.length }})</div>
                  <el-button
                    type="primary"
                    size="small"
                    plain
                    :disabled="!state.table?.id"
                    @click.stop="goCreateRelatedTask(activeTabId, 'read')"
                  >
                    <el-icon><Plus /></el-icon>
                    新增读取任务
                  </el-button>
                </div>
                <div v-if="state.tasks.readTasks.length" class="task-list">
                  <div v-for="task in state.tasks.readTasks" :key="task.id" class="task-item" @click="openTask(task.id)">
                    <div class="task-name">{{ task.taskName || '-' }}</div>
                    <div class="task-meta">{{ task.engine || '-' }}</div>
                  </div>
                </div>
                <el-empty v-else description="暂无读取任务" :image-size="40" />
              </div>
              <div class="lineage-list">
                <div
                  v-for="item in state.lineage.downstreamTables"
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
            </el-scrollbar>
          </div>
        </div>
      </div>
    </div>

	    <div v-else class="right-empty">
	      <el-empty :description="emptyDescription" :image-size="120" />
	    </div>
	  </div>
	</template>

<script setup>
	import { computed, inject } from 'vue'
	import { Document, Plus, Warning } from '@element-plus/icons-vue'

const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanel requires dataStudioCtx')
}

const {
  clusterId,
  openTabs,
  activeTab,
  tabStates,
  layerOptions,
  isDorisTable,
  isAggregateTable,
  isReplicaWarning,
  getLayerType,
  getFieldRows,
  getVarcharLength,
  startMetaEdit,
  cancelMetaEdit,
  saveMetaEdit,
  handleDeleteTable,
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
	} = ctx

const activeTabId = computed(() => String(activeTab.value || ''))

	const activeTabItem = computed(() => {
	  const id = activeTabId.value
	  if (!id) return null
	  return (openTabs.value || []).find((item) => String(item?.id) === id) || null
	})

	const emptyDescription = computed(() => {
	  if (activeTabItem.value?.kind === 'query') return '没有可用的对象信息'
	  return '选择表后在此查看基本信息、列信息、DDL 与数据血缘'
	})

	const hasTableTab = computed(() => {
	  return !!activeTabItem.value && activeTabItem.value.kind !== 'query'
	})

const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})

const fieldRows = computed(() => getFieldRows(activeTabId.value))

const refreshAccess = () => {
  const tabId = activeTabId.value
  if (!tabId) return
  loadAccessStats(tabId, true)
}

const formatAccessDuration = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  return formatDuration(Number(value))
}
</script>

<style scoped>
.right-root {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 10px;
  box-sizing: border-box;
}

.right-empty {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f9fafb;
  border-radius: 8px;
}

.tab-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
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
}

.meta-scroll :deep(.el-scrollbar__view) {
  padding-right: 4px;
  box-sizing: border-box;
}

.meta-table {
  flex: 1;
  min-height: 0;
}

.meta-descriptions {
  width: 100%;
}

.meta-descriptions :deep(.el-descriptions__table) {
  table-layout: fixed;
}

.meta-descriptions :deep(.el-descriptions__label.is-bordered-label) {
  width: 120px;
  min-width: 120px;
  white-space: nowrap;
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

.ddl-scroll {
  flex: 1;
  min-height: 0;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  background: #fff;
}

.ddl-content {
  margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre;
}

.ddl-placeholder {
  padding: 10px 12px;
  font-size: 12px;
  color: #94a3b8;
}

.access-note {
  margin-bottom: 10px;
}

.access-table {
  width: 100%;
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
  grid-auto-rows: minmax(0, 1fr);
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
  min-height: 0;
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

.lineage-scroll {
  flex: 1;
  min-height: 0;
}

.lineage-scroll :deep(.el-scrollbar__view) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 4px;
  box-sizing: border-box;
}

.lineage-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
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

@media (max-width: 1200px) {
  .lineage-grid {
    grid-template-columns: 1fr;
  }
}
</style>
