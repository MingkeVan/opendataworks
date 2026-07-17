<template>
  <div class="meta-section meta-section-fill">
    <div class="basic-grid" :class="{ single: !isDorisTable(state.table) }">
      <section class="section-block">
        <div class="section-header">
          <div class="section-title">表信息</div>
          <div class="section-actions">
            <el-tooltip
              v-if="!state.metaEditing && isPlatformMetadataMissing(state.table)"
              content="请先同步到平台元数据后再操作"
              placement="top"
            >
              <span>
                <el-button type="primary" size="small" disabled>编辑</el-button>
              </span>
            </el-tooltip>
            <el-tooltip
              v-else-if="!state.metaEditing && isDorisTable(state.table) && !clusterId"
              content="请选择 Doris 集群后再编辑"
              placement="top"
            >
              <span>
                <el-button type="primary" size="small" disabled>编辑</el-button>
              </span>
            </el-tooltip>
            <el-button
              v-else-if="!state.metaEditing"
              type="primary"
              size="small"
              :disabled="isDemoMode"
              @click="startMetaEdit(activeTabId)"
            >
              编辑
            </el-button>

            <el-tooltip
              v-if="!state.metaEditing && isPlatformMetadataMissing(state.table)"
              content="请先同步到平台元数据后再操作"
              placement="top"
            >
              <span>
                <el-button type="danger" plain size="small" disabled>删除表</el-button>
              </span>
            </el-tooltip>
            <el-tooltip
              v-else-if="!state.metaEditing && isDorisTable(state.table) && !clusterId"
              content="请选择 Doris 集群后再删除"
              placement="top"
            >
              <span>
                <el-button type="danger" plain size="small" disabled>删除表</el-button>
              </span>
            </el-tooltip>
            <el-button
              v-else-if="!state.metaEditing"
              type="danger"
              plain
              size="small"
              :disabled="isDemoMode"
              @click="handleDeleteTable"
            >
              删除表
            </el-button>

            <template v-else>
              <el-button size="small" @click="cancelMetaEdit(activeTabId)">取消</el-button>
              <el-button
                type="primary"
                size="small"
                :loading="state.metaSaving"
                :disabled="isDemoMode"
                @click="saveMetaEdit(activeTabId)"
              >
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
            <el-descriptions-item label="行数">
              <el-button
                link
                type="primary"
                class="metric-link"
                :disabled="!state.table?.id"
                @click="trendDialogRef?.open('rowCount')"
              >
                {{ formatRowCountDisplay(resolveTableRowCount(state.table)) }}
              </el-button>
            </el-descriptions-item>
            <el-descriptions-item label="数据量">
              <el-button
                link
                type="primary"
                class="metric-link"
                :disabled="!state.table?.id"
                @click="trendDialogRef?.open('dataSize')"
              >
                {{ formatStorageSizeDisplay(resolveTableStorageSize(state.table)) }}
              </el-button>
            </el-descriptions-item>
            <el-descriptions-item label="Doris创建时间">
              <span>{{ formatDateTime(resolveTableDorisCreateTime(state.table)) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="Doris更新时间">
              <span>{{ formatDateTime(resolveTableDorisUpdateTime(state.table)) }}</span>
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
            <el-descriptions-item label="表模型">
              <span>{{ state.table.tableModel || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="主键列">
              <span>{{ state.table.keyColumns || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="分区字段">
              <span>{{ state.table.partitionColumn || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="分桶字段">
              <span>{{ state.table.distributionColumn || '-' }}</span>
            </el-descriptions-item>
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

  <TableTrendDialog ref="trendDialogRef" :table="state?.table" />
</template>

<script setup>
import { computed, inject, ref } from 'vue'
import { Warning } from '@element-plus/icons-vue'
import { isDemoMode } from '@/demo/runtime'
import TableTrendDialog from './TableTrendDialog.vue'
import {
  resolveTableRowCount,
  resolveTableStorageSize,
  resolveTableDorisCreateTime,
  resolveTableDorisUpdateTime,
  formatRowCountDisplay,
  formatStorageSizeDisplay,
} from '../tableFormat'

// P2-2 F17d：右侧面板「基本信息」tab pane 从 DataStudioRightPanel.vue 抽出。
// 共享脚手架样式（.meta-section/.section-* 等）由父组件的 .meta-tabs :deep() 提供。
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelBasic 需要 dataStudioCtx')
}

const {
  clusterId,
  activeTab,
  tabStates,
  layerOptions,
  businessDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
  isDorisTable,
  isPlatformMetadataMissing,
  isReplicaWarning,
  startMetaEdit,
  cancelMetaEdit,
  saveMetaEdit,
  handleDeleteTable,
  formatDateTime,
} = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})
const dataDomainOptions = computed(() => getMetaDataDomainOptions(activeTabId.value))
const trendDialogRef = ref(null)
</script>

<style scoped>
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

.doris-block {
  background: var(--accent-soft);
}

.meta-descriptions :deep(.el-descriptions__label.is-bordered-label) {
  width: 108px;
  min-width: 108px;
  white-space: nowrap;
  color: var(--text-sub);
}

.meta-descriptions :deep(.el-descriptions__content.is-bordered-content) {
  white-space: normal;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.meta-input {
  width: 100%;
}

.metric-link {
  padding: 0;
  font-weight: 600;
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
</style>
