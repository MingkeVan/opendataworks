<template>
  <div class="meta-section meta-section-fill">
    <section class="section-block section-fill">
      <div class="section-header">
        <div class="section-title">
          分区信息
          <el-tag v-if="isPartitioned" size="small" type="success" effect="plain" class="partition-tag">
            分区表
          </el-tag>
          <el-tag v-else size="small" type="info" effect="plain" class="partition-tag">非分区表</el-tag>
        </div>
      </div>

      <el-scrollbar class="meta-scroll">
        <el-descriptions :column="1" border size="small" class="meta-descriptions">
          <el-descriptions-item label="分区列">{{ state.table.partitionColumn || '-' }}</el-descriptions-item>
          <el-descriptions-item label="分桶列">{{ state.table.distributionColumn || '-' }}</el-descriptions-item>
          <el-descriptions-item label="分桶数">{{ state.table.bucketNum ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="副本数">{{ state.table.replicaNum ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="表模型">{{ state.table.tableModel || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Key 列">{{ state.table.keyColumns || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div v-loading="partitionsLoading" class="partition-list">
          <div class="partition-list-head">
            <span class="partition-list-title">分区列表</span>
            <span class="partition-list-actions">
              <span v-if="partitions.length" class="partition-counts">共 {{ partitions.length }} 个分区</span>
              <el-button link type="primary" size="small" :disabled="partitionsLoading" @click="refreshPartitions">
                刷新
              </el-button>
            </span>
          </div>

          <template v-if="partitions.length">
            <el-table :data="pagedPartitions" border size="small">
              <el-table-column prop="partitionName" label="分区名" min-width="150" show-overflow-tooltip />
              <el-table-column label="范围" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">{{ row.range || '-' }}</template>
              </el-table-column>
              <el-table-column label="大小" width="100" align="right">
                <template #default="{ row }">{{ row.dataSize || '-' }}</template>
              </el-table-column>
              <el-table-column label="行数" width="110" align="right">
                <template #default="{ row }">{{ formatRowCount(row.rowCount) }}</template>
              </el-table-column>
              <el-table-column label="分桶" width="70" align="right">
                <template #default="{ row }">{{ row.buckets ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="副本" width="70" align="right">
                <template #default="{ row }">{{ row.replicationNum ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90" show-overflow-tooltip>
                <template #default="{ row }">{{ row.state || '-' }}</template>
              </el-table-column>
            </el-table>
            <el-pagination
              v-model:current-page="currentPage"
              v-model:page-size="pageSize"
              class="partition-pager"
              size="small"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="[5, 10, 15]"
              :total="partitions.length"
            />
          </template>
          <el-empty
            v-else-if="!partitionsLoading"
            :description="partitionsError || '暂无分区'"
            :image-size="60"
          />
        </div>
      </el-scrollbar>
    </section>
  </div>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import { tableApi } from '@/api/table'
import { paginate } from '../partitionInfo'

// 「明细信息 / 分区信息」子页：
// - 分区与分桶配置：取自表详情已加载的 state
// - 分区列表：异步按需拉取 GET /v1/tables/{id}/partitions（Doris SHOW PARTITIONS），
//   结果缓存在 tab state 上，子页来回切换不重复请求
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelPartitions 需要 dataStudioCtx')
}

const { activeTab, tabStates, clusterId } = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})

const partitionsLoading = ref(false)
const partitionsError = ref('')
const currentPage = ref(1)
const pageSize = ref(5)

// 分区列表缓存在 tab state 上，跨子页切换保留
const partitions = computed(() => state.value?.partitionList || [])

const isPartitioned = computed(() => !!state.value?.table?.partitionColumn)

const pagedPartitions = computed(() => paginate(partitions.value, currentPage.value, pageSize.value))

const formatRowCount = (value) =>
  value === null || value === undefined ? '-' : Number(value).toLocaleString('en-US')

const loadPartitions = async ({ force = false } = {}) => {
  const target = state.value
  const tableId = target?.table?.id
  if (!tableId) return
  if (!force && Array.isArray(target.partitionList)) return
  if (partitionsLoading.value) return

  partitionsLoading.value = true
  partitionsError.value = ''
  try {
    // 分区查询依赖 Doris SHOW PARTITIONS；失败时就地提示，不弹全局错误
    const list = await tableApi.listPartitions(tableId, clusterId?.value ?? null, { skipErrorMessage: true })
    target.partitionList = Array.isArray(list) ? list : []
  } catch (error) {
    target.partitionList = []
    partitionsError.value = error?.message || '获取分区列表失败'
  } finally {
    partitionsLoading.value = false
  }
}

const refreshPartitions = () => loadPartitions({ force: true })

watch(
  () => state.value?.table?.id,
  () => {
    currentPage.value = 1
    partitionsError.value = ''
    loadPartitions()
  },
  { immediate: true }
)

// 页大小变化后回到第一页，避免停留在越界页
watch(pageSize, () => {
  currentPage.value = 1
})
</script>

<style scoped>
.partition-tag {
  margin-left: 8px;
  font-weight: 400;
}
.partition-counts {
  color: var(--text-muted);
  font-size: 12px;
}
.meta-descriptions :deep(.el-descriptions__label.is-bordered-label) {
  width: 96px;
  background: #f7faff;
  color: var(--text-sub);
}
.partition-list {
  margin-top: 16px;
}
.partition-list-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
}
.partition-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.partition-list-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.partition-pager {
  margin-top: 10px;
  justify-content: flex-end;
}
</style>
