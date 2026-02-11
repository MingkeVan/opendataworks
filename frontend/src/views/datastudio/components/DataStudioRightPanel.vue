<template>
  <div :class="rootClass">
    <div v-if="hasTableTab && state" class="panel-shell">

      <section class="meta-panel" @scroll.passive>
        <el-tabs v-model="state.metaTab" class="meta-tabs detail-tabs">
          <el-tab-pane name="basic" label="基本信息">
            <div class="meta-section meta-section-fill">
              <div class="basic-grid" :class="{ single: !isDorisTable(state.table) }">
                <section class="section-block">
                  <div class="section-header">
                    <div class="section-title">表信息</div>
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
                          placeholder="选择分层（必填）"
                          class="meta-input"
                        >
                          <el-option v-for="item in layerOptions" :key="item.value" :label="item.label" :value="item.value" />
                        </el-select>
                        <span v-else>{{ state.table.layer || '-' }}</span>
                      </el-descriptions-item>
                      <el-descriptions-item label="业务域">
                        <el-select
                          v-if="state.metaEditing"
                          v-model="state.metaForm.businessDomain"
                          size="small"
                          placeholder="选择业务域"
                          class="meta-input"
                          @change="handleMetaBusinessDomainChange(activeTabId)"
                        >
                          <el-option
                            v-for="item in businessDomainOptions"
                            :key="item.domainCode"
                            :label="`${item.domainCode} - ${item.domainName}`"
                            :value="item.domainCode"
                          />
                        </el-select>
                        <span v-else>{{ state.table.businessDomain || '-' }}</span>
                      </el-descriptions-item>
                      <el-descriptions-item label="数据域">
                        <el-select
                          v-if="state.metaEditing"
                          v-model="state.metaForm.dataDomain"
                          size="small"
                          placeholder="选择数据域"
                          class="meta-input"
                          :disabled="!state.metaForm.businessDomain"
                        >
                          <el-option
                            v-for="item in dataDomainOptions"
                            :key="item.domainCode"
                            :label="`${item.domainCode} - ${item.domainName}`"
                            :value="item.domainCode"
                          />
                        </el-select>
                        <span v-else>{{ state.table.dataDomain || '-' }}</span>
                      </el-descriptions-item>
                      <el-descriptions-item label="负责人">
                        <el-input v-if="state.metaEditing" v-model="state.metaForm.owner" size="small" class="meta-input" />
                        <span v-else>{{ state.table.owner || '-' }}</span>
                      </el-descriptions-item>
                      <el-descriptions-item label="数据库">
                        <span>{{ state.table.dbName || '-' }}</span>
                      </el-descriptions-item>
                    </el-descriptions>
                  </el-scrollbar>
                </section>

                <section v-if="isDorisTable(state.table)" class="section-block doris-block">
                  <div class="section-header">
                    <div class="section-title">Doris 配置</div>
                    <el-tag size="small" type="warning" effect="plain">DORIS</el-tag>
                  </div>

                  <el-scrollbar class="meta-scroll">
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
                  </el-scrollbar>
                </section>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="columns" label="列详情">
            <div class="meta-section meta-section-fill">
              <section class="section-block section-fill">
                <div class="section-header">
                  <div class="section-title">字段定义</div>
                  <div class="section-actions">
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
                  <el-table :data="fieldRows" border size="small" height="100%" class="columns-table">
                    <el-table-column label="字段名" width="136" show-overflow-tooltip>
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
                    <el-table-column label="类型" width="136">
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
                    <el-table-column label="可为空" width="84">
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
                    <el-table-column label="主键" width="84">
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
                    <el-table-column label="注释" min-width="150" show-overflow-tooltip>
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
              </section>
            </div>
          </el-tab-pane>

          <el-tab-pane name="ddl" label="DDL">
            <div class="meta-section meta-section-fill" v-loading="state.ddlLoading">
              <section class="section-block section-fill">
                <div class="section-header">
                  <div class="section-title">建表语句</div>
                  <div class="section-actions">
                    <el-button size="small" :disabled="!state.ddl" @click="copyDdl(activeTabId)">复制</el-button>
                  </div>
                </div>
                <div class="code-shell">
                  <el-scrollbar class="ddl-scroll">
                    <pre v-if="state.ddl" class="ddl-content">{{ state.ddl }}</pre>
                    <div v-else class="ddl-placeholder">加载中或暂无 DDL</div>
                  </el-scrollbar>
                </div>
              </section>
            </div>
          </el-tab-pane>

          <el-tab-pane name="access" label="访问情况">
            <div class="meta-section meta-section-fill" v-loading="state.accessLoading">
              <section class="section-block section-fill">
                <div class="section-header">
                  <div class="section-title">访问概况</div>
                  <div class="section-actions">
                    <el-button size="small" :disabled="!state.table?.id || state.accessLoading" @click="refreshAccess">
                      刷新
                    </el-button>
                  </div>
                </div>

                <el-scrollbar class="meta-scroll access-scroll">
                  <template v-if="state.accessStats">
                    <el-alert
                      v-if="state.accessStats.note"
                      :title="state.accessStats.note"
                      type="warning"
                      show-icon
                      :closable="false"
                      class="access-note"
                    />

                    <div class="metrics-grid">
                      <div v-for="metric in accessMetrics" :key="metric.label" class="metric-card">
                        <div class="metric-label">{{ metric.label }}</div>
                        <div class="metric-value">{{ metric.value }}</div>
                      </div>
                    </div>

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
              </section>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>

      <DataStudioRightPanelLineage
        :current-table="state.table"
        :upstream-tables="state.lineage.upstreamTables"
        :downstream-tables="state.lineage.downstreamTables"
        :write-tasks="state.tasks.writeTasks"
        :read-tasks="state.tasks.readTasks"
        :edges="state.lineage.edges || []"
        @open-table="openTableTab"
        @open-task="openTask"
        @create-task="(type) => goCreateRelatedTask(activeTabId, type)"
        @go-lineage="goLineage(activeTabId)"
      />
    </div>

    <div v-else class="right-empty">
      <el-empty :description="emptyDescription" :image-size="110" />
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import { Document, Grid, Plus, Warning, Operation } from '@element-plus/icons-vue'
import DataStudioRightPanelLineage from './DataStudioRightPanelLineage.vue'

const props = defineProps({
  visualVariant: {
    type: String,
    default: 'control-deck',
    validator: (value) => ['control-deck', 'paper-blueprint', 'signal-cards', 'minimal-blue', 'navicat-blue', 'tech-grid', 'clean-slate', 'data-console'].includes(value)
  }
})

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
  businessDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
  isDorisTable,
  isAggregateTable,
  isReplicaWarning,
  getLayerType,
  getFieldRows,
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

const rootClass = computed(() => ['right-root', `variant-${props.visualVariant}`])

const emptyDescription = computed(() => {
  if (activeTabItem.value?.kind === 'query') return '没有可用的对象信息'
  return '选择表后在此查看基本信息、列详情、DDL 与数据血缘'
})

const hasTableTab = computed(() => {
  return !!activeTabItem.value && activeTabItem.value.kind !== 'query'
})

const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})

const sourceTypeLabel = computed(() => {
  const table = state.value?.table
  if (!table) return 'Data Source'
  const type = String(table.sourceType || table.datasourceType || table.dataSourceType || '').toUpperCase()
  if (type === 'MYSQL') return 'MySQL'
  if (type === 'DORIS') return 'Doris'
  return type || 'Data Source'
})

const fieldRows = computed(() => getFieldRows(activeTabId.value))
const dataDomainOptions = computed(() => getMetaDataDomainOptions(activeTabId.value))

const accessMetrics = computed(() => {
  const stats = state.value?.accessStats
  if (!stats) return []
  return [
    { label: '总访问次数', value: stats.totalAccessCount ?? 0 },
    { label: `最近${stats.recentDays || 30}天`, value: stats.recentAccessCount ?? 0 },
    { label: '访问用户数', value: stats.distinctUserCount ?? 0 },
    { label: '平均耗时', value: formatAccessDuration(stats.averageDurationMs) },
    { label: '最近访问', value: formatDateTime(stats.lastAccessTime) },
    { label: '审计来源', value: stats.dorisAuditEnabled ? (stats.dorisAuditSource || '已启用') : '未启用' }
  ]
})

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
  --bg: #f4f8ff;
  --panel: #ffffff;
  --panel-muted: #f6f9ff;
  --line: #d8e3f1;
  --line-strong: #c3d4e7;
  --text: #19314d;
  --text-sub: #5d7491;
  --text-muted: #8298b2;
  --accent: #2f6aa3;
  --accent-soft: #e9f1fb;
  --tab-bg: #eef4fc;
  --tab-active: #ffffff;
  --flow-task-bg: #f7fbff;
  --flow-table-bg: #ffffff;

  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  color: var(--text);
  font-family: 'IBM Plex Sans', 'Avenir Next', 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  overflow: hidden;
}

/* 统一滚动条样式 */
.right-root :deep(*::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.right-root :deep(*::-webkit-scrollbar-track) {
  background: transparent;
  border-radius: 3px;
}

.right-root :deep(*::-webkit-scrollbar-thumb) {
  background: var(--line);
  border-radius: 3px;
  transition: background 0.2s ease;
}

.right-root :deep(*::-webkit-scrollbar-thumb:hover) {
  background: var(--line-strong);
}

.variant-paper-blueprint {
  --bg: #f8fbff;
  --panel: #ffffff;
  --panel-muted: #f8fbff;
  --line: #d7e2f1;
  --line-strong: #bdd0e6;
  --text: #1b334f;
  --text-sub: #5a7394;
  --text-muted: #8699b1;
  --accent: #356ea8;
  --accent-soft: #ecf3fd;
  --tab-bg: #f1f6fd;
  --tab-active: #ffffff;
  --flow-task-bg: #f8fbff;
  --flow-table-bg: #ffffff;
}

.variant-signal-cards {
  --bg: #eff6ff;
  --panel: #ffffff;
  --panel-muted: #f3f8ff;
  --line: #d2dff0;
  --line-strong: #b7cae3;
  --text: #163050;
  --text-sub: #55739a;
  --text-muted: #7f97b6;
  --accent: #245f99;
  --accent-soft: #e4eefb;
  --tab-bg: #eaf2fd;
  --tab-active: #ffffff;
  --flow-task-bg: #f2f8ff;
  --flow-table-bg: #ffffff;
}

.variant-minimal-blue {
  --bg: #f9fbff;
  --panel: #ffffff;
  --panel-muted: #ffffff;
  --line: #dce6f3;
  --line-strong: #c7d7ea;
  --text: #1f3652;
  --text-sub: #637b98;
  --text-muted: #8ba0b9;
  --accent: #3c78b1;
  --accent-soft: #edf4fe;
  --tab-bg: #f3f7fd;
  --tab-active: #ffffff;
  --flow-task-bg: #ffffff;
  --flow-table-bg: #ffffff;
}

.variant-navicat-blue {
  --bg: #eef2f8;
  --panel: #ffffff;
  --panel-muted: #f5f8fc;
  --line: #cfd9e6;
  --line-strong: #bac8d9;
  --text: #24364e;
  --text-sub: #5f738c;
  --text-muted: #8697ac;
  --accent: #3f6f9e;
  --accent-soft: #e9f0f9;
  --tab-bg: #e8edf4;
  --tab-active: #ffffff;
  --flow-task-bg: #f5f8fc;
  --flow-table-bg: #ffffff;
}

.variant-tech-grid {
  --bg: #f2f5fa;
  --panel: #ffffff;
  --panel-muted: #f8fafd;
  --line: #dae1eb;
  --line-strong: #c5d0df;
  --text: #1a2f47;
  --text-sub: #5a6e87;
  --text-muted: #7d91ab;
  --accent: #2563b8;
  --accent-soft: #e6eef8;
  --tab-bg: #eff3f9;
  --tab-active: #ffffff;
  --flow-task-bg: #f8fafd;
  --flow-table-bg: #ffffff;
}

.variant-clean-slate {
  --bg: #fafbfd;
  --panel: #ffffff;
  --panel-muted: #ffffff;
  --line: #e4e8f0;
  --line-strong: #d0d7e3;
  --text: #212d3f;
  --text-sub: #657186;
  --text-muted: #8a99b0;
  --accent: #4178c1;
  --accent-soft: #eff5fd;
  --tab-bg: #f5f8fc;
  --tab-active: #ffffff;
  --flow-task-bg: #ffffff;
  --flow-table-bg: #fafbfd;
}

.variant-data-console {
  --bg: #f0f4f9;
  --panel: #ffffff;
  --panel-muted: #f6f9fc;
  --line: #d4dce8;
  --line-strong: #bfcbdb;
  --text: #1d3047;
  --text-sub: #5b6f89;
  --text-muted: #7e93ac;
  --accent: #2d66a4;
  --accent-soft: #e8f0f9;
  --tab-bg: #ebeef4;
  --tab-active: #ffffff;
  --flow-task-bg: #f6f9fc;
  --flow-table-bg: #ffffff;
}

.panel-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}


.meta-panel {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  overflow: hidden;
  min-height: 0;
  flex: 1;
}

.meta-tabs {
  height: 100%;
}

:deep(.detail-tabs > .el-tabs__header) {
  margin: 0;
  padding: 8px 10px 6px;
  border-bottom: 1px solid var(--line);
  box-sizing: border-box;
}

:deep(.detail-tabs .el-tabs__nav-wrap::after) {
  display: none;
}

:deep(.detail-tabs .el-tabs__active-bar) {
  display: none;
}

:deep(.detail-tabs .el-tabs__nav) {
  float: none;
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border-radius: 8px;
  background: var(--tab-bg);
  border: 1px solid var(--line);
}

:deep(.detail-tabs .el-tabs__item) {
  height: 28px;
  line-height: 28px;
  border-radius: 6px;
  border: 1px solid transparent;
  padding: 0 10px;
  font-weight: 600;
  color: var(--text-sub);
  transition: background-color 100ms ease, color 100ms ease, border-color 100ms ease;
}

:deep(.detail-tabs .el-tabs__item.is-active) {
  color: var(--text);
  border-color: var(--line);
  background: var(--tab-active);
}

:deep(.detail-tabs .el-tabs__content) {
  height: calc(100% - 44px);
  padding: 10px;
  box-sizing: border-box;
}

:deep(.detail-tabs .el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.meta-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-section-fill {
  flex: 1;
  min-height: 0;
}

.basic-grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.9fr);
  gap: 10px;
}

.basic-grid.single {
  grid-template-columns: 1fr;
}

.section-block {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-muted);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.section-fill {
  flex: 1;
}

.doris-block {
  background: var(--accent-soft);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.section-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
}

.section-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.section-header.small {
  font-size: 12px;
  color: var(--text-sub);
}

.section-divider {
  height: 1px;
  margin: 10px 0;
  background: var(--line);
}

.meta-scroll {
  flex: 1;
  min-height: 0;
  max-height: 100%;
  overflow: auto;
}

.meta-scroll :deep(.el-scrollbar__view) {
  padding-right: 4px;
  box-sizing: border-box;
}

.meta-descriptions :deep(.el-descriptions__label.is-bordered-label) {
  width: 108px;
  min-width: 108px;
  white-space: nowrap;
  color: var(--text-sub);
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
  color: #d14343;
}

.replica-value {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.replica-danger {
  color: #d14343;
  font-weight: 600;
}

.warning-icon {
  font-size: 12px;
}

.meta-table {
  flex: 1;
  min-height: 0;
}

:deep(.columns-table th.el-table__cell),
:deep(.access-table th.el-table__cell) {
  background: #f2f7ff;
  color: var(--text-sub);
}

.code-shell {
  flex: 1;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfdff;
}

.ddl-scroll {
  height: 100%;
  font-family: 'JetBrains Mono', 'IBM Plex Mono', 'Fira Mono', Menlo, Consolas, monospace;
}

.ddl-content {
  margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
  color: #1f3552;
}

.ddl-placeholder {
  padding: 10px 12px;
  font-size: 12px;
  color: var(--text-muted);
}

.access-note {
  margin-bottom: 10px;
}

.access-scroll {
  flex: 1;
  min-height: 0;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.metric-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  padding: 8px 9px;
}

.metric-label {
  font-size: 11px;
  color: var(--text-sub);
}

.metric-value {
  margin-top: 4px;
  font-size: 14px;
  font-weight: 700;
  color: var(--text);
  word-break: break-word;
}

.lineage-panel {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  max-height: 500px;
  overflow-y: auto;
  overflow-x: hidden;
  flex-shrink: 0;
}

.lineage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.flow-section {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-muted);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.flow-section-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
  padding-bottom: 4px;
  border-bottom: 1px solid var(--line);
}

.lineage-connections {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px minmax(0, 1fr) 32px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
}

.connection-column {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.column-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--text-sub);
  padding-bottom: 6px;
}

.connection-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 60px;
  max-height: 240px;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 2px;
}

.connection-item {
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  padding: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: all 0.2s ease;
}

.connection-item.table-item,
.connection-item.task-item {
  cursor: pointer;
}

.connection-item.table-item:hover,
.connection-item.task-item:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
  transform: translateX(2px);
}

.connection-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 700;
  color: var(--accent);
  padding-top: 32px;
}

.item-icon {
  color: var(--accent);
  flex-shrink: 0;
  font-size: 16px;
}

.item-content {
  flex: 1;
  min-width: 0;
}

.item-name {
  font-size: 12px;
  font-weight: 700;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.3;
}

.item-desc {
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.3;
}

.connection-current {
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
}

.current-table-card {
  border: 2px solid var(--accent);
  border-radius: 8px;
  background: var(--accent-soft);
  padding: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  box-shadow: 0 2px 8px rgba(47, 106, 163, 0.15);
}

.current-icon {
  color: var(--accent);
  font-size: 20px;
  flex-shrink: 0;
}

.current-content {
  flex: 1;
  min-width: 0;
}

.current-name {
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
  line-height: 1.3;
  margin-bottom: 2px;
}

.current-desc {
  font-size: 11px;
  color: var(--text-sub);
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.connection-actions {
  padding-top: 4px;
}

.empty-placeholder {
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
  padding: 16px 8px;
  border: 1px dashed var(--line);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.5);
}

.flow-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 0;
}

.flow-item {
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  padding: 6px 7px;
  display: flex;
  align-items: center;
  gap: 7px;
}

.flow-item.table-item {
  cursor: pointer;
}

.flow-item.table-item:hover,
.flow-item.task-item:hover {
  border-color: var(--line-strong);
  background: var(--accent-soft);
}

.flow-item.task-item {
  cursor: pointer;
}

.item-icon {
  color: var(--accent);
  flex-shrink: 0;
}

.flow-item-main {
  flex: 1;
  min-width: 0;
}

.item-name {
  font-size: 12px;
  font-weight: 700;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-desc {
  margin-top: 1px;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-add-row {
  padding-top: 4px;
}

.inline-empty {
  font-size: 12px;
  color: var(--text-muted);
  border: 1px dashed var(--line);
  border-radius: 6px;
  padding: 10px;
  text-align: center;
  background: #fff;
}

.current-card {
  border: 1px solid var(--line-strong);
  border-radius: 8px;
  background: var(--accent-soft);
  padding: 8px;
  display: flex;
  align-items: center;
  gap: 7px;
}

.current-icon {
  color: var(--accent);
  flex-shrink: 0;
}

.current-main {
  flex: 1;
  min-width: 0;
}

.current-name {
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
  line-height: 1.3;
}

.current-desc {
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-sub);
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.flow-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
  color: var(--accent);
}

.flow-vertical-arrow {
  text-align: center;
  color: var(--accent);
  font-size: 18px;
  line-height: 1;
  font-weight: 700;
  margin: 2px 0;
}

.current-table-center {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 6px 0;
}

.current-card-large {
  border: 2px solid var(--accent);
  border-radius: 10px;
  background: var(--accent-soft);
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 100%;
  box-shadow: 0 2px 8px rgba(47, 106, 163, 0.12);
}

.current-icon-large {
  color: var(--accent);
  font-size: 24px;
  flex-shrink: 0;
}

.current-main-large {
  flex: 1;
  min-width: 0;
}

.current-label {
  font-size: 10px;
  font-weight: 700;
  color: var(--accent);
  text-transform: uppercase;
  letter-spacing: 0.8px;
  margin-bottom: 4px;
}

.current-name-large {
  font-size: 15px;
  font-weight: 700;
  color: var(--text);
  line-height: 1.3;
  margin-bottom: 3px;
}

.current-desc-large {
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.right-empty {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--line-strong);
  border-radius: 10px;
  background: var(--panel);
}

@media (max-width: 1320px) {
  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .flow-track {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .flow-arrow {
    transform: rotate(90deg);
  }
}

@media (max-width: 1200px) {
  .basic-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .metrics-grid {
    grid-template-columns: 1fr;
  }
}
</style>
