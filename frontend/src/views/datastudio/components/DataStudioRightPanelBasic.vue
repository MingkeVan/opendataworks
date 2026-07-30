<template>
  <div class="meta-section meta-section-fill">
    <div class="basic-grid single">
      <!-- 不再重复 tab 名「表信息」作为区块标题 -->
      <section class="section-block">
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
    </div>
  </div>

  <TableTrendDialog ref="trendDialogRef" :table="state?.table" />
</template>

<script setup>
import { computed, inject, ref } from 'vue'
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
  activeTab,
  tabStates,
  layerOptions,
  businessDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
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
@media (max-width: 1200px) {
  .basic-grid {
    grid-template-columns: 1fr;
  }
}
</style>
