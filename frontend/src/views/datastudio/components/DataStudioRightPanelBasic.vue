<template>
  <div ref="basicRootRef" class="meta-section meta-section-fill">
    <div class="basic-grid">
      <!-- 不再重复 tab 名「表信息」作为区块标题 -->
      <section class="section-block">
        <el-scrollbar class="meta-scroll">
          <el-descriptions :column="descColumn" border size="small" class="meta-descriptions">
            <el-descriptions-item label="表名">
              <el-input v-if="state.metaEditing" v-model="state.metaForm.tableName" size="small" class="meta-input" />
              <span v-else>{{ state.table.tableName || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="数据库">
              <span>{{ state.table.dbName || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="表注释" :span="descColumn">
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
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
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

// 表信息与「Doris信息」一样按两列排（原来是一列，Doris 配置块拆到独立 tab 后
// 整块占满面板宽度，一列会浪费右侧一半横向空间）。右栏可拖到 400px 下限，
// 两列时每个值列只剩 100px 出头，所以窄于阈值时退回一列。
const TWO_COLUMN_MIN_WIDTH = 440
const basicRootRef = ref(null)
const descColumn = ref(2)
let resizeObserver = null

const syncDescColumn = (width) => {
  if (!width) return
  descColumn.value = width >= TWO_COLUMN_MIN_WIDTH ? 2 : 1
}

onMounted(() => {
  const el = basicRootRef.value
  if (!el) return
  syncDescColumn(el.getBoundingClientRect().width)
  if (typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver((entries) => {
    syncDescColumn(entries[0]?.contentRect?.width)
  })
  resizeObserver.observe(el)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<style scoped>
/* Doris 配置块拆到独立 tab 后这里只剩表信息一块，占满面板宽度 */
.basic-grid {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 96px 刚好放下最长的「Doris创建时间」，两列时能多留 24px 给值列 */
.meta-descriptions :deep(.el-descriptions__label.is-bordered-label) {
  width: 96px;
  min-width: 96px;
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
</style>
